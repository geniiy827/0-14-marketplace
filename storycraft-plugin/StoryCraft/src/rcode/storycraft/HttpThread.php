<?php
/*
 * Story Craft — отдельный поток с HTTP-сервером, чтобы не блокировать игровой сервер.
 * Общение с главным потоком только через скаляры и \Threaded-очередь логов (потокобезопасно).
 */
namespace rcode\storycraft;

class HttpThread extends \pocketmine\Thread {
	/** @var string JSON настроек */
	private $cfgJson;
	/** @var string папка с исходниками плагина (для require внутри потока) */
	private $srcDir;

	/** @var \Threaded очередь сообщений для консоли */
	public $logQueue;

	// состояние для /storycraft status|list (пишет поток, читает главный)
	public $running = true;
	public $started = false;
	public $failed = false;
	public $reloadRequested = false;
	public $connections = 0;
	public $served = 0;
	public $catalogVersion = 0;
	public $listing = "[]";

	public function __construct(array $cfg, $srcDir) {
		$this->cfgJson = json_encode($cfg);
		$this->srcDir = $srcDir;
		$this->logQueue = new \Threaded();
	}

	public function run() {
		if (method_exists($this, "registerClassLoader")) {
			$this->registerClassLoader();
		}
		// подстраховка: явно подключаем классы без зависимостей от ядра
		require_once $this->srcDir . "/Catalog.php";
		require_once $this->srcDir . "/Progress.php";
		require_once $this->srcDir . "/HttpServer.php";

		$q = $this->logQueue;
		$log = function ($m) use ($q) {
			$q[] = (string) $m;
		};
		$cfg = json_decode($this->cfgJson, true);
		$srv = new HttpServer($cfg, $log);
		if (!$srv->open()) {
			$this->failed = true;
			return;
		}
		$this->started = true;
		$lastStat = 0;
		while ($this->running) {
			if ($this->reloadRequested) {
				$this->reloadRequested = false;
				$srv->rescan(true);
				$log("[StoryCraft] Каталог пересканирован, версия " . $srv->catalog->version);
			}
			$srv->tick(50000);
			$now = microtime(true);
			if ($now - $lastStat > 0.5) {
				$lastStat = $now;
				$this->connections = $srv->connectionCount();
				$this->served = $srv->served;
				if ($this->catalogVersion !== $srv->catalog->version) {
					$this->catalogVersion = $srv->catalog->version;
					$this->listing = json_encode($srv->catalog->describe(), JSON_UNESCAPED_UNICODE);
				}
			}
		}
		$srv->close();
	}

	public function shutdown() {
		$this->running = false;
	}

	/** Вызывается ядром при остановке сервера. */
	public function quit() {
		$this->running = false;
		parent::quit();
	}

	public function getThreadName() {
		return "StoryCraft HTTP";
	}
}
