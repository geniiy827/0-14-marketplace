<?php
// minimal PocketMine/Genisys stand-ins to run NovaAccounts\Main the way Genisys does
namespace pocketmine\event { abstract class Event { private $c = false; public function isCancelled(){ return $this->c; } public function setCancelled($v = true){ $this->c = $v; } } interface Cancellable {} }
namespace pocketmine\event\plugin { abstract class PluginEvent extends \pocketmine\event\Event { private $plugin; public function __construct($p){ $this->plugin = $p; } public function getPlugin(){ return $this->plugin; } } }
namespace pocketmine\scheduler { abstract class Task { abstract public function onRun($t); } abstract class PluginTask extends Task { protected $owner; public function __construct($o){ $this->owner = $o; } } }
namespace pocketmine\command { class Command {} interface CommandSender { public function sendMessage($m); } }
namespace pocketmine\network\query { class QueryHandler { public $real = true; public function __construct(){} public function handle($a, $p, $pk){ echo "real query handler got type ", ord($pk[2]), "\n"; } public function regenerateInfo(){} public function regenerateToken(){} } }
namespace pocketmine\plugin {
	class Logger { public function info($m){ echo "[info] $m\n"; } public function error($m){ echo "[err] $m\n"; } public function debug($m){ echo "[debug] $m\n"; } }
	class Config { private $d; public function __construct($d){ $this->d = $d; } public function getAll(){ return $this->d; } }
	abstract class PluginBase {
		public $dir; public $res;
		public function getDataFolder(){ return $this->dir; }
		public function saveDefaultConfig(){}
		public function saveResource($f){ copy($this->res . $f, $this->dir . $f); }
		public function getConfig(){ return new Config($GLOBALS["CFG"]); }
		public function getLogger(){ return new Logger; }
		public function getServer(){ return \pocketmine\Server::getInstance(); }
	}
}
namespace pocketmine {
	class Net { public $sock; public function sendPacket($a, $p, $d){ socket_sendto($this->sock, $d, strlen($d), 0, $a, $p); } }
	class Sched { public $tasks = []; public function scheduleRepeatingTask($t, $p){ $this->tasks[] = $t; } }
	class PM { public $events = []; public function callEvent($e){ $this->events[] = get_class($e); echo "event ", get_class($e), "\n"; } }
	class Server {
		private static $i; private $queryHandler; public $net, $sched, $pm;
		public static function getInstance(){ return self::$i; }
		public function __construct(){ self::$i = $this; $this->net = new Net; $this->sched = new Sched; $this->pm = new PM; }
		public function getNetwork(){ return $this->net; }
		public function getScheduler(){ return $this->sched; }
		public function getPluginManager(){ return $this->pm; }
		public function start(){ $this->queryHandler = new network\query\QueryHandler(); }   // Genisys creates it after plugins
		// copied from Genisys Server::handlePacket
		public function handlePacket($address, $port, $payload){
			try{
				if(strlen($payload) > 2 and substr($payload, 0, 2) === "\xfe\xfd" and $this->queryHandler instanceof network\query\QueryHandler){
					$this->queryHandler->handle($address, $port, $payload);
				}
			}catch(\Throwable $e){ echo "BLOCKED $address\n"; }
		}
	}
}
