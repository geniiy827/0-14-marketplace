<?php
/*
 * NovaAccounts - accounts of NOVA Client (player tag + password) for Genisys / PocketMine 0.14.
 *
 * The client talks to this plugin on the game port with encrypted UDP packets (see Crypto.php),
 * so the server needs no other ports. Data: plugin_data/NovaAccounts/accounts.db (SQLite).
 *
 * For other plugins:
 *   $acc = \pocketmine\Server::getInstance()->getPluginManager()->getPlugin("NovaAccounts");
 *   $acc->getAccount("Tag")            account row without the password hash, or null
 *   $acc->getAccountById(5)
 *   $acc->ban("Tag", $minutes, $reason) / unban("Tag") / logoutAll("Tag")
 *   $acc->getData("Tag", "key", $default) / setData("Tag", "key", $value)   free JSON per account
 *   $acc->getService()->registerOp("friends.list", function(array $req, $account, $ip){ return ["ok" => true]; });
 *   events: NovaAccounts\event\AccountRegisterEvent (cancellable), AccountCreatedEvent,
 *           AccountLoginEvent (cancellable), AccountLogoutEvent
 */
namespace NovaAccounts;

use NovaAccounts\event\AccountCreatedEvent;
use NovaAccounts\event\AccountLoginEvent;
use NovaAccounts\event\AccountLogoutEvent;
use NovaAccounts\event\AccountRegisterEvent;
use pocketmine\command\Command;
use pocketmine\command\CommandSender;
use pocketmine\plugin\PluginBase;
use pocketmine\Server;

class Main extends PluginBase{
	/** @var Crypto */
	private $crypto;
	/** @var Store */
	private $store;
	/** @var Service */
	private $service;
	/** @var Social */
	private $social;
	/** @var NovaQueryHandler|null */
	private $handler = null;
	/** sha1(request) => [time, answer] */
	private $answers = [];

	public function onEnable(){
		@mkdir($this->getDataFolder());
		$this->saveDefaultConfig();
		if(!file_exists($this->getDataFolder() . "private.pem")) $this->saveResource("private.pem");
		$this->crypto = new Crypto(file_get_contents($this->getDataFolder() . "private.pem"));
		$this->store = new Store($this->getDataFolder() . "accounts.db");
		$this->service = new Service($this->store, $this->getConfig()->getAll());
		$this->service->listener = function($what, array $account, $ip){ return $this->fire($what, $account, $ip); };
		$this->social = new Social($this->service, function($ip, $port, $data){ $this->send($ip, $port, $data); });
		$this->install();
		$this->getServer()->getScheduler()->scheduleRepeatingTask(new TickTask($this), 200);
		$this->getLogger()->info("NOVA accounts: " . $this->store->count() . " accounts, key " . substr(bin2hex($this->crypto->fingerprint()), 0, 16));
	}

	public function onDisable(){
		$this->uninstall();
		if($this->store !== null) $this->store->close();
	}

	/** puts our handler in front of the query handler (the server creates its own after the plugins start) */
	public function install(){
		try{
			$rp = new \ReflectionProperty(Server::class, "queryHandler");
			$rp->setAccessible(true);
			$cur = $rp->getValue($this->getServer());
			if($cur instanceof NovaQueryHandler) return;
			$this->handler = new NovaQueryHandler($this, $cur);
			$rp->setValue($this->getServer(), $this->handler);
		}catch(\Throwable $e){
			$this->getLogger()->error("cannot attach to the network: " . $e->getMessage());
		}
	}

	private function uninstall(){
		try{
			$rp = new \ReflectionProperty(Server::class, "queryHandler");
			$rp->setAccessible(true);
			$cur = $rp->getValue($this->getServer());
			if($cur instanceof NovaQueryHandler) $rp->setValue($this->getServer(), $cur->getInner());
		}catch(\Throwable $e){
		}
	}

	public function onNovaPacket($address, $port, $packet){
		if($packet[3] !== Crypto::VERSION) return;
		$op = $packet[4];
		if($op === "\x00" and strlen($packet) === 13){
			$this->send($address, $port, $this->crypto->hello(substr($packet, 5, 8)));
			return;
		}
		if($op === "\x02" or $op === "\x06" or $op === "\x07"){   // relay between the two ends of a link (game data, mod files)
			$this->social->relay($address, $port, $packet);
			return;
		}
		if($op !== "\x01") return;
		// a resent copy of a packet (the answer got lost): the same answer again, not a second login
		$key = sha1($packet, true);
		$now = time();
		if(isset($this->answers[$key]) and $this->answers[$key][0] > $now - 120){
			$this->send($address, $port, $this->answers[$key][1]);
			return;
		}
		$open = $this->crypto->open($packet);
		if($open === null) return;
		list($req, $enc, $mac) = $open;
		$req["_port"] = $port;                                // set here, never taken from the client
		$res = $this->service->handle($req, $address);
		$out = $this->crypto->seal($res, $enc, $mac);
		if(count($this->answers) > 2000) foreach($this->answers as $k => $v) if($v[0] < $now - 120) unset($this->answers[$k]);
		$this->answers[$key] = [$now, $out];
		$this->send($address, $port, $out);
	}

	private function send($address, $port, $data){
		$this->getServer()->getNetwork()->sendPacket($address, $port, $data);
	}

	/** Service -> PocketMine events. A string = refused with that text. */
	private function fire($what, array $account, $ip){
		$pm = $this->getServer()->getPluginManager();
		switch($what){
			case "register":
				$ev = new AccountRegisterEvent($this, $account, $ip);
				$pm->callEvent($ev);
				return $ev->isCancelled() ? ($ev->getReason() !== "" ? $ev->getReason() : "Регистрация запрещена") : null;
			case "registered":
				$pm->callEvent(new AccountCreatedEvent($this, $account, $ip));
				$this->getLogger()->info("new account " . $account["tag"] . " from " . $ip);
				return null;
			case "login":
				$ev = new AccountLoginEvent($this, $account, $ip);
				$pm->callEvent($ev);
				return $ev->isCancelled() ? ($ev->getReason() !== "" ? $ev->getReason() : "Вход запрещён") : null;
			case "logout":
				$pm->callEvent(new AccountLogoutEvent($this, $account, $ip));
				return null;
		}
		return null;
	}

	// ------------------------------------------------------------------ API
	public function getService(){ return $this->service; }
	public function getSocial(){ return $this->social; }

	private static function clean($a){
		if($a === null) return null;
		unset($a["pass"]);
		$a["data"] = json_decode($a["data"], true);
		if(!is_array($a["data"])) $a["data"] = [];
		return $a;
	}

	public function getAccount($tag){ return self::clean($this->store->byTag((string) $tag)); }
	public function getAccountById($id){ return self::clean($this->store->byId((int) $id)); }

	/** @param int $minutes 0 = forever */
	public function ban($tag, $minutes = 0, $reason = ""){
		$a = $this->store->byTag($tag);
		if($a === null) return false;
		$this->store->update($a["id"], ["banned" => 1, "ban_until" => $minutes > 0 ? time() + $minutes * 60 : 0, "ban_reason" => (string) $reason]);
		$this->store->dropSessions($a["id"]);
		return true;
	}

	public function unban($tag){
		$a = $this->store->byTag($tag);
		if($a === null) return false;
		$this->store->update($a["id"], ["banned" => 0, "ban_until" => 0, "ban_reason" => ""]);
		return true;
	}

	public function logoutAll($tag){
		$a = $this->store->byTag($tag);
		return $a === null ? false : $this->store->dropSessions($a["id"]);
	}

	public function getData($tag, $key, $default = null){
		$a = $this->getAccount($tag);
		return $a !== null && array_key_exists($key, $a["data"]) ? $a["data"][$key] : $default;
	}

	public function setData($tag, $key, $value){
		$a = $this->getAccount($tag);
		if($a === null) return false;
		$a["data"][$key] = $value;
		$this->store->update($a["id"], ["data" => json_encode($a["data"], JSON_UNESCAPED_UNICODE)]);
		return true;
	}

	// ------------------------------------------------------------------ /novaacc
	public function onCommand(CommandSender $sender, Command $command, $label, array $args){
		$sub = strtolower(isset($args[0]) ? $args[0] : "");
		$tag = isset($args[1]) ? $args[1] : "";
		switch($sub){
			case "info":
				$a = $this->getAccount($tag);
				if($a === null){ $sender->sendMessage("Нет аккаунта $tag"); return true; }
				$sender->sendMessage("#" . $a["id"] . " " . $a["tag"] . " | создан " . date("d.m.Y H:i", $a["created"])
					. " | вход " . ($a["last_login"] ? date("d.m.Y H:i", $a["last_login"]) : "-") . " " . $a["last_ip"]
					. ($a["banned"] ? " | БАН" . ($a["ban_until"] ? " до " . date("d.m.Y H:i", $a["ban_until"]) : "") . " " . $a["ban_reason"] : ""));
				return true;
			case "ban":
				$min = isset($args[2]) ? (int) $args[2] : 0;
				$reason = implode(" ", array_slice($args, 3));
				$sender->sendMessage($this->ban($tag, $min, $reason) ? "Аккаунт $tag заблокирован" . ($min ? " на $min мин." : "") : "Нет аккаунта $tag");
				return true;
			case "unban":
				$sender->sendMessage($this->unban($tag) ? "Аккаунт $tag разблокирован" : "Нет аккаунта $tag");
				return true;
			case "logoutall":
				$n = $this->logoutAll($tag);
				$sender->sendMessage($n === false ? "Нет аккаунта $tag" : "Закрыто сессий: $n");
				return true;
			case "list":
				foreach($this->store->latest(15) as $a) $sender->sendMessage("#" . $a["id"] . " " . $a["tag"] . ($a["banned"] ? " (бан)" : ""));
				return true;
			case "stats":
				$st = $this->social->stats();
				$sender->sendMessage("Аккаунтов: " . $this->store->count() . ", в сети: " . $st["online"] . ", игр вместе: " . $st["links"]);
				return true;
		}
		return false;
	}
}
