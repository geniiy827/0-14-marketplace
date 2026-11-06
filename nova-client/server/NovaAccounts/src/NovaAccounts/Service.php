<?php
/*
 * NovaAccounts - the account logic (no PocketMine code here: also runs in tests).
 *
 * Requests are JSON objects inside the encrypted packet:
 *   {"op": "register", "tag": "", "pass": "", "pass2": "", "ts": unix, "n": "random hex", "dev": "phone model"}
 *   {"op": "login", "tag": "", "pass": "", ...}         -> {"ok": true, "tag": "Real_Tag", "token": "64 hex", "id": 5}
 *   {"op": "resume", "token": ""}                       -> {"ok": true, "tag": ..., "id": ...}
 *   {"op": "logout", "token": ""}                       -> {"ok": true}
 *   ops added by other plugins: Service::registerOp(); "token" in the request gives them the account.
 * Answers always carry "n" (the request nonce) and on failure {"ok": false, "err": "code", "msg": "text"}.
 */
namespace NovaAccounts;

class Service{
	/** @var Store */
	public $store;
	private $cfg;
	/** @var callable[] op => fn(array $req, array|null $account, string $ip): array */
	private $ops = [];
	/** @var callable|null fn(string $event, array $account, string $ip): string|null (a text cancels) */
	public $listener = null;

	private $hits = [];          // ip => [times]
	private $fails = [];         // "ip:..." / "acc:..." => [times]
	private $regs = [];          // ip => [times]
	private $nonces = [];        // nonce => time

	public function __construct(Store $store, array $cfg){
		$this->store = $store;
		$this->cfg = $cfg;
	}

	private function c($path, $def){
		$v = $this->cfg;
		foreach(explode(".", $path) as $p){
			if(!is_array($v) or !array_key_exists($p, $v)) return $def;
			$v = $v[$p];
		}
		return $v;
	}

	public function msg($key, array $vars = []){
		$t = (string) $this->c("messages." . $key, $key);
		foreach($vars as $k => $v) $t = str_replace("{" . $k . "}", (string) $v, $t);
		return $t;
	}

	private function fail($code, array $vars = []){
		return ["ok" => false, "err" => $code, "msg" => $this->msg($code, $vars)];
	}

	public function registerOp($op, callable $fn){
		if(in_array($op, ["register", "login", "resume", "logout"], true)) throw new \InvalidArgumentException("op $op is built in");
		$this->ops[$op] = $fn;
	}

	// ------------------------------------------------------------------ rate limits
	private static function count(array &$list, $key, $window){
		$now = time();
		if(!isset($list[$key])) return 0;
		$list[$key] = array_values(array_filter($list[$key], function($t) use ($now, $window){ return $t > $now - $window; }));
		if(count($list[$key]) === 0){ unset($list[$key]); return 0; }
		return count($list[$key]);
	}
	private static function add(array &$list, $key){ $list[$key][] = time(); }

	/** called every few minutes: forget old entries */
	public function cleanup(){
		$now = time();
		foreach([&$this->hits, &$this->fails, &$this->regs] as &$list){
			foreach($list as $k => $times){
				$list[$k] = array_values(array_filter($times, function($t) use ($now){ return $t > $now - 3600; }));
				if(count($list[$k]) === 0) unset($list[$k]);
			}
		}
		foreach($this->nonces as $n => $t) if($t < $now - 900) unset($this->nonces[$n]);
		$this->store->purgeExpired();
	}

	// ------------------------------------------------------------------ requests
	public function handle(array $req, $ip){
		$n = isset($req["n"]) && is_string($req["n"]) ? substr($req["n"], 0, 32) : "";
		$r = $this->dispatch($req, $ip, $n);
		$r["n"] = $n;
		return $r;
	}

	private function dispatch(array $req, $ip, $n){
		if(self::count($this->hits, $ip, 60) >= (int) $this->c("limits.requests-per-minute-ip", 600)) return $this->fail("rate");
		self::add($this->hits, $ip);
		$op = isset($req["op"]) && is_string($req["op"]) ? $req["op"] : "";
		// replay protection: a fresh time and a nonce never seen in the last 15 minutes
		$ts = isset($req["ts"]) ? (int) $req["ts"] : 0;
		if(abs(time() - $ts) > 300) { $f = $this->fail("time"); $f["time"] = time(); return $f; }
		if(strlen($n) < 16 or isset($this->nonces[$n])) return $this->fail("bad_request");
		$this->nonces[$n] = time();

		switch($op){
			case "register": return $this->register($req, $ip);
			case "login": return $this->login($req, $ip);
			case "resume":
				$a = $this->bySession($req);
				if($a === null) return $this->fail("session");
				if(($b = $this->banText($a)) !== null) return ["ok" => false, "err" => "banned", "msg" => $b];
				return ["ok" => true, "tag" => $a["tag"], "id" => (int) $a["id"]];
			case "logout":
				$a = $this->bySession($req);
				$this->store->dropSession(self::tokenHash(isset($req["token"]) ? (string) $req["token"] : ""));
				if($a !== null && $this->listener) call_user_func($this->listener, "logout", $a, $ip);
				return ["ok" => true, "msg" => $this->msg("logout")];
			default:
				if(isset($this->ops[$op])){
					$a = $this->bySession($req);
					if($a !== null && $this->banText($a) !== null) $a = null;
					$r = call_user_func($this->ops[$op], $req, $a, $ip);
					return is_array($r) ? $r : ["ok" => false, "err" => "bad_request", "msg" => $this->msg("bad_request")];
				}
				return $this->fail("bad_request");
		}
	}

	public static function tokenHash($token){
		return hash("sha256", "nova-session:" . $token);
	}

	private function bySession(array $req){
		$t = isset($req["token"]) && is_string($req["token"]) ? $req["token"] : "";
		if(!preg_match('/^[0-9a-f]{64}$/', $t)) return null;
		return $this->store->session(self::tokenHash($t));
	}

	public function checkTag($tag){
		$min = (int) $this->c("tag.min", 3);
		$max = (int) $this->c("tag.max", 16);
		if(!is_string($tag) or !preg_match('/^[A-Za-z0-9_]{' . $min . ',' . $max . '}$/', $tag)) return $this->fail("tag_format", ["min" => $min, "max" => $max]);
		$res = $this->c("tag.reserved", []);
		if(is_array($res) and in_array(strtolower($tag), array_map("strtolower", $res), true)) return $this->fail("tag_reserved");
		return null;
	}

	private function register(array $req, $ip){
		$tag = isset($req["tag"]) ? $req["tag"] : "";
		$pass = isset($req["pass"]) && is_string($req["pass"]) ? $req["pass"] : "";
		$pass2 = isset($req["pass2"]) && is_string($req["pass2"]) ? $req["pass2"] : "";
		if(($e = $this->checkTag($tag)) !== null) return $e;
		$min = (int) $this->c("password.min", 6);
		if(mb_strlen($pass, "UTF-8") < $min) return $this->fail("pass_short", ["min" => $min]);
		if(mb_strlen($pass, "UTF-8") > (int) $this->c("password.max", 64)) return $this->fail("pass_long");
		if($pass !== $pass2) return $this->fail("pass_mismatch");
		if(strtolower($pass) === strtolower($tag)) return $this->fail("pass_is_tag");
		if(self::count($this->regs, $ip, 3600) >= (int) $this->c("limits.registrations-per-hour-ip", 3)) return $this->fail("rate");
		if($this->store->byTag($tag) !== null) return $this->fail("tag_taken");
		if($this->listener){
			$deny = call_user_func($this->listener, "register", ["tag" => $tag], $ip);
			if(is_string($deny)) return ["ok" => false, "err" => "denied", "msg" => $deny];
		}
		$hash = password_hash($pass, PASSWORD_BCRYPT, ["cost" => (int) $this->c("password.bcrypt-cost", 10)]);
		$id = $this->store->create($tag, $hash, $ip);
		if($id === null) return $this->fail("tag_taken");
		self::add($this->regs, $ip);
		$a = $this->store->byId($id);
		$r = $this->newSession($a, $req, $ip);
		$r["msg"] = $this->msg("registered", ["tag" => $a["tag"]]);
		if($this->listener) call_user_func($this->listener, "registered", $a, $ip);
		return $r;
	}

	private function login(array $req, $ip){
		$tag = isset($req["tag"]) && is_string($req["tag"]) ? $req["tag"] : "";
		$pass = isset($req["pass"]) && is_string($req["pass"]) ? $req["pass"] : "";
		$lock = (int) $this->c("limits.lock-minutes", 5);
		if(self::count($this->fails, "ip:" . $ip, 600) >= (int) $this->c("limits.login-fails-ip", 10)) return $this->fail("rate");
		$akey = "acc:" . strtolower($tag);
		if(self::count($this->fails, $akey, $lock * 60) >= (int) $this->c("limits.login-fails-account", 5)) return $this->fail("locked", ["min" => $lock]);
		$a = strlen($tag) > 0 && strlen($tag) <= 32 ? $this->store->byTag($tag) : null;
		// the same work with or without the account: no timing hint which tags exist
		$hash = $a !== null ? $a["pass"] : '$2y$10$SF8AvJQFaT5KIJt58zDYZOQeW909BZLvYOonz2og9/VIhxNcipVaC';
		$ok = password_verify($pass, $hash) && $a !== null;
		if(!$ok){
			self::add($this->fails, "ip:" . $ip);
			self::add($this->fails, $akey);
			return $this->fail("wrong");
		}
		unset($this->fails[$akey]);
		if(($b = $this->banText($a)) !== null) return ["ok" => false, "err" => "banned", "msg" => $b];
		if($this->listener){
			$deny = call_user_func($this->listener, "login", $a, $ip);
			if(is_string($deny)) return ["ok" => false, "err" => "denied", "msg" => $deny];
		}
		if(password_needs_rehash($a["pass"], PASSWORD_BCRYPT, ["cost" => (int) $this->c("password.bcrypt-cost", 10)])){
			$this->store->update($a["id"], ["pass" => password_hash($pass, PASSWORD_BCRYPT, ["cost" => (int) $this->c("password.bcrypt-cost", 10)])]);
		}
		$r = $this->newSession($a, $req, $ip);
		$r["msg"] = $this->msg("welcome", ["tag" => $a["tag"]]);
		return $r;
	}

	private function newSession(array $a, array $req, $ip){
		$token = bin2hex(random_bytes(32));
		$dev = isset($req["dev"]) && is_string($req["dev"]) ? $req["dev"] : "";
		$this->store->addSession($a["id"], self::tokenHash($token), (int) $this->c("session.days", 30), $ip, $dev, (int) $this->c("session.max-per-account", 10));
		$this->store->update($a["id"], ["last_login" => time(), "last_ip" => $ip]);
		return ["ok" => true, "tag" => $a["tag"], "id" => (int) $a["id"], "token" => $token];
	}

	/** @return string|null the ban text, null when not banned (an expired ban is lifted here) */
	public function banText(array $a){
		if((int) $a["banned"] === 0) return null;
		$until = (int) $a["ban_until"];
		if($until > 0 && $until <= time()){
			$this->store->update($a["id"], ["banned" => 0, "ban_until" => 0, "ban_reason" => ""]);
			return null;
		}
		return $this->msg("banned", [
			"until" => $until > 0 ? " до " . date("d.m.Y H:i", $until) : "",
			"reason" => $a["ban_reason"] !== "" ? ": " . $a["ban_reason"] : ""
		]);
	}
}
