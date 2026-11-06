<?php
/*
 * NovaAccounts - friends, who is online, worlds of friends, invites and connections between two NOVA clients.
 *
 * Playing together: the host's world runs on the host's phone. Both phones keep one UDP "tunnel" socket open to this
 * server (presence every few seconds), so the server knows the public address of each socket. On "join" the server
 * tells the joiner the host's address and pokes the host (FE FD 4E 01 03), the host learns the joiner's address from
 * its next presence answer. Both then send packets straight to each other (hole punching). When that does not work
 * (some mobile networks), the packets go through this server: FE FD 4E 01 02 | link id (4) | game packet.
 *
 * Ops (encrypted, need "token"):
 *   friends.list                          -> friends [{tag, online, world: {name, players, max, mode} | null, joinable}],
 *                                            incoming [tag], outgoing [tag]
 *   friends.request / accept / decline / remove {tag}
 *   presence {world: null | {name, mode: "friends" | "invite", max, players}}   (sent from the tunnel socket)
 *                                         -> invites [{from, world, players, max}], links [{id, peer, role}]
 *   invite {tag}  invite.decline {from}
 *   join {tag}                            -> {link, peer: "ip:port", name}  (sent from the tunnel socket)
 *   leave {link}
 */
namespace NovaAccounts;

class Social{
	const ONLINE_SECONDS = 40;
	const INVITE_SECONDS = 120;
	const LINK_SECONDS = 120;
	const MAX_FRIENDS = 200;
	const MAX_PENDING = 50;

	/** @var Service */
	private $svc;
	/** @var Store */
	private $store;
	/** @var callable fn(ip, port, data) */
	private $send;

	/** account id => [t, ip, port, world|null, invites: [fromId => [t, world]]] */
	private $online = [];
	/** link id => [host, join, hostAddr, joinAddr, t, name] */
	private $links = [];
	/** "ip:port" => link id (for relay) */
	private $byAddr = [];

	public function __construct(Service $svc, callable $send){
		$this->svc = $svc;
		$this->store = $svc->store;
		$this->send = $send;
		foreach(["friends.list", "friends.request", "friends.accept", "friends.decline", "friends.remove",
			"presence", "invite", "invite.decline", "join", "leave"] as $op){
			$svc->registerOp($op, function(array $req, $account, $ip) use ($op){
				if($account === null) return ["ok" => false, "err" => "session", "msg" => $this->svc->msg("session")];
				return $this->op($op, $req, $account, $ip);
			});
		}
	}

	private function err($code, $msg){
		return ["ok" => false, "err" => $code, "msg" => $msg];
	}

	private function other(array $req){
		$tag = isset($req["tag"]) && is_string($req["tag"]) ? $req["tag"] : (isset($req["from"]) && is_string($req["from"]) ? $req["from"] : "");
		if($tag === "" or strlen($tag) > 32) return null;
		return $this->store->byTag($tag);
	}

	public function isOnline($id){
		return isset($this->online[$id]) and $this->online[$id][0] > time() - self::ONLINE_SECONDS;
	}

	/** what a friend may see of $id's world (null = nothing) */
	private function worldOf($id, $viewer){
		if(!$this->isOnline($id) or $this->online[$id][3] === null) return null;
		$w = $this->online[$id][3];
		$invited = isset($this->online[$viewer]) && isset($this->online[$viewer][4][$id]) && $this->online[$viewer][4][$id][0] > time() - self::INVITE_SECONDS;
		$w["joinable"] = ($w["mode"] === "friends" || $invited) && $w["players"] < $w["max"];
		return $w;
	}

	private function op($op, array $req, array $me, $ip){
		$id = (int) $me["id"];
		$port = isset($req["_port"]) ? (int) $req["_port"] : 0;
		switch($op){
			case "friends.list":
				$fr = [];
				foreach($this->store->friendList($id, "friends") as $f){
					$w = $this->worldOf((int) $f["id"], $id);
					$fr[] = ["tag" => $f["tag"], "online" => $this->isOnline((int) $f["id"]), "world" => $w];
				}
				return ["ok" => true, "friends" => $fr,
					"incoming" => array_map(function($r){ return $r["tag"]; }, $this->store->friendList($id, "in")),
					"outgoing" => array_map(function($r){ return $r["tag"]; }, $this->store->friendList($id, "out"))];

			case "friends.request":
				$o = $this->other($req);
				if($o === null) return $this->err("no_such", "Игрок с таким тегом не найден");
				$oid = (int) $o["id"];
				if($oid === $id) return $this->err("self", "Это ваш тег");
				if($this->store->relation($id, $oid) === 1) return $this->err("already", "Вы уже друзья");
				if($this->store->relation($oid, $id) === 0){            // they asked first: now friends
					$this->befriend($id, $oid);
					return ["ok" => true, "msg" => $o["tag"] . " теперь ваш друг", "friends" => true];
				}
				if($this->store->relation($id, $oid) === 0) return $this->err("already", "Запрос уже отправлен");
				if($this->store->countRel($id, 0, true) >= self::MAX_PENDING) return $this->err("limit", "Слишком много запросов");
				if($this->store->countRel($id, 1, true) >= self::MAX_FRIENDS) return $this->err("limit", "Слишком много друзей");
				$this->store->setRel($id, $oid, 0);
				$this->poke($oid);
				return ["ok" => true, "msg" => "Запрос отправлен: " . $o["tag"]];

			case "friends.accept":
				$o = $this->other($req);
				if($o === null or $this->store->relation((int) $o["id"], $id) !== 0) return $this->err("no_request", "Запроса нет");
				$this->befriend($id, (int) $o["id"]);
				return ["ok" => true, "msg" => $o["tag"] . " теперь ваш друг"];

			case "friends.decline":
			case "friends.remove":
				$o = $this->other($req);
				if($o === null) return $this->err("no_such", "Игрок не найден");
				$this->store->dropRel($id, (int) $o["id"]);
				return ["ok" => true, "msg" => $op === "friends.remove" ? $o["tag"] . " удалён из друзей" : "Запрос отклонён"];

			case "presence":
				$w = null;
				if(isset($req["world"]) and is_array($req["world"])){
					$x = $req["world"];
					$max = max(2, min(10, (int) (isset($x["max"]) ? $x["max"] : 5)));
					$w = [
						"name" => mb_substr(isset($x["name"]) && is_string($x["name"]) ? $x["name"] : "", 0, 32, "UTF-8"),
						"mode" => (isset($x["mode"]) && $x["mode"] === "invite") ? "invite" : "friends",
						"max" => $max,
						"players" => max(1, min($max, (int) (isset($x["players"]) ? $x["players"] : 1)))
					];
				}
				$inv = isset($this->online[$id]) ? $this->online[$id][4] : [];
				if(isset($req["keep"]) and is_array($req["keep"])){          // links still in use (also when direct)
					foreach($req["keep"] as $k){
						$k = (int) $k;
						if(isset($this->links[$k]) and ($this->links[$k][0] === $id or $this->links[$k][1] === $id)){
							$this->links[$k][4] = time();
							if($this->links[$k][1] === $id){                 // the joiner's tunnel address may change too
								$this->links[$k][3] = [$ip, $port];
								$this->byAddr[$ip . ":" . $port] = $k;
							}
						}
					}
				}
				$this->online[$id] = [time(), $ip, $port, $w, $inv];
				$invites = [];
				foreach($inv as $from => $v){
					if($v[0] <= time() - self::INVITE_SECONDS or ($wf = $this->worldOf($from, $id)) === null){ unset($this->online[$id][4][$from]); continue; }
					$a = $this->store->byId($from);
					if($a !== null) $invites[] = ["from" => $a["tag"], "world" => $wf["name"], "players" => $wf["players"], "max" => $wf["max"]];
				}
				$links = [];
				foreach($this->links as $lid => $l){
					if($l[4] <= time() - self::LINK_SECONDS){ $this->dropLink($lid); continue; }
					if($l[0] === $id){
						$this->links[$lid][2] = [$ip, $port];              // the host's tunnel address may change
						$this->byAddr[$ip . ":" . $port] = $lid;
						$j = $this->store->byId($l[1]);
						$links[] = ["id" => $lid, "peer" => $l[3][0] . ":" . $l[3][1], "role" => "host", "tag" => $j ? $j["tag"] : ""];
					}
				}
				return ["ok" => true, "invites" => $invites, "links" => $links, "incoming" => $this->store->countRel($id, 0, false)];

			case "invite":
				$o = $this->other($req);
				if($o === null or $this->store->relation($id, (int) $o["id"]) !== 1) return $this->err("not_friend", "Это не ваш друг");
				if(!$this->isOnline($id) or $this->online[$id][3] === null) return $this->err("no_world", "Сначала откройте мир для друзей");
				$oid = (int) $o["id"];
				if(!$this->isOnline($oid)) return $this->err("offline", $o["tag"] . " не в сети");
				$this->online[$oid][4][$id] = [time()];
				$this->poke($oid);
				return ["ok" => true, "msg" => "Приглашение отправлено: " . $o["tag"]];

			case "invite.decline":
				$o = $this->other($req);
				if($o !== null and isset($this->online[$id])) unset($this->online[$id][4][(int) $o["id"]]);
				return ["ok" => true];

			case "join":
				$o = $this->other($req);
				if($o === null or $this->store->relation($id, (int) $o["id"]) !== 1) return $this->err("not_friend", "Это не ваш друг");
				$hid = (int) $o["id"];
				$w = $this->worldOf($hid, $id);
				if($w === null) return $this->err("no_world", $o["tag"] . " сейчас не в открытом мире");
				if($w["players"] >= $w["max"]) return $this->err("full", "Мир заполнен");
				if(!$w["joinable"]) return $this->err("invite_only", "Вход только по приглашению");
				if(isset($this->online[$id])) unset($this->online[$id][4][$hid]);
				do{ $lid = mt_rand(1, 0x7fffffff); }while(isset($this->links[$lid]));
				$h = $this->online[$hid];
				$this->links[$lid] = [$hid, $id, [$h[1], $h[2]], [$ip, $port], time(), $w["name"]];
				$this->byAddr[$h[1] . ":" . $h[2]] = $lid;
				$this->byAddr[$ip . ":" . $port] = $lid;
				$this->poke($hid);
				return ["ok" => true, "link" => $lid, "peer" => $h[1] . ":" . $h[2], "name" => $w["name"], "host" => $o["tag"]];

			case "leave":
				$lid = isset($req["link"]) ? (int) $req["link"] : 0;
				if(isset($this->links[$lid]) and ($this->links[$lid][0] === $id or $this->links[$lid][1] === $id)) $this->dropLink($lid);
				return ["ok" => true];
		}
		return $this->err("bad_request", "?");
	}

	private function befriend($a, $b){
		$this->store->dropRel($a, $b);
		$this->store->setRel($a, $b, 1);
		$this->store->setRel($b, $a, 1);
		$this->poke($b);
	}

	/** "ask the server now": plaintext, carries nothing secret */
	private function poke($id){
		if($this->isOnline($id) and $this->online[$id][2] > 0) call_user_func($this->send, $this->online[$id][1], $this->online[$id][2], "\xfe\xfd\x4e\x01\x03");
	}

	private function dropLink($lid){
		if(!isset($this->links[$lid])) return;
		$l = $this->links[$lid];
		foreach([$l[2], $l[3]] as $a){
			$k = $a[0] . ":" . $a[1];
			if(isset($this->byAddr[$k]) and $this->byAddr[$k] === $lid) unset($this->byAddr[$k]);
		}
		unset($this->links[$lid]);
	}

	/** FE FD 4E 01 02 | link (4) | data from one end of a link -> the other end */
	public function relay($ip, $port, $packet){
		if(strlen($packet) < 9) return;
		$lid = unpack("N", substr($packet, 5, 4))[1];
		if(!isset($this->links[$lid])) return;
		$l = &$this->links[$lid];
		$src = $ip . ":" . $port;
		if($l[2][0] . ":" . $l[2][1] === $src) $to = $l[3];
		elseif($l[3][0] . ":" . $l[3][1] === $src) $to = $l[2];
		else return;
		$l[4] = time();
		call_user_func($this->send, $to[0], $to[1], $packet);
	}

	public function cleanup(){
		$now = time();
		foreach($this->online as $id => $o) if($o[0] < $now - 600) unset($this->online[$id]);
		foreach($this->links as $lid => $l) if($l[4] < $now - self::LINK_SECONDS) $this->dropLink($lid);
	}

	public function stats(){
		$n = 0;
		foreach($this->online as $id => $o) if($o[0] > time() - self::ONLINE_SECONDS) $n++;
		return ["online" => $n, "links" => count($this->links)];
	}
}
