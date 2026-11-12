<?php
/*
 * Story Craft — главный класс плагина для Genisys (MCPE 0.14.3, PHP 7).
 * Запускает HTTP-поток, выводит его логи в консоль, обрабатывает команды /storycraft.
 */
namespace rcode\storycraft;

use pocketmine\command\Command;
use pocketmine\command\CommandSender;
use pocketmine\plugin\PluginBase;
use pocketmine\utils\Config;

class Main extends PluginBase {
	/** @var HttpThread|null */
	private $thread = null;
	/** @var array */
	private $cfg = [];

	public function onEnable() {
		$data = rtrim($this->getDataFolder(), "/\\");
		@mkdir($data, 0777, true);
		@mkdir($data . "/content/adventures", 0777, true);
		@mkdir($data . "/progress", 0777, true);

		$conf = new Config($data . "/config.yml", Config::YAML, [
			"http_port" => 12055,
			"bind" => "0.0.0.0",
			"scan_interval" => 5,
			"max_connections" => 64,
			"public_url" => "",
			"admin_key" => bin2hex(random_bytes(12)),
		]);
		$conf->save();
		$this->cfg = [
			"http_port" => (int) $conf->get("http_port", 12055),
			"bind" => (string) $conf->get("bind", "0.0.0.0"),
			"scan_interval" => max(1, (int) $conf->get("scan_interval", 5)),
			"max_connections" => max(4, (int) $conf->get("max_connections", 64)),
			"public_url" => (string) $conf->get("public_url", ""),
			"data_dir" => $data,
		];
		$this->writeReadme($data . "/content/README.txt");

		$this->thread = new HttpThread($this->cfg, __DIR__);
		$this->thread->start();
		$this->getServer()->getScheduler()->scheduleRepeatingTask(new LogPump($this), 5);
		$this->getLogger()->info("Story Craft: HTTP на порту " . $this->cfg["http_port"] . " (TCP). Проверка: http://<адрес>:" . $this->cfg["http_port"] . "/api/ping");
	}

	public function onDisable() {
		if ($this->thread !== null) {
			$this->thread->shutdown();
			$this->thread->join();
			$this->pumpLogs();
			$this->thread = null;
		}
	}

	/** Перенести сообщения из потока в консоль сервера (главный поток). */
	public function pumpLogs() {
		if ($this->thread === null) {
			return;
		}
		$n = 0;
		while ($n++ < 50 && ($m = $this->thread->logQueue->shift()) !== null) {
			$this->getLogger()->info($m);
		}
	}

	private function writeReadme($path) {
		if (is_file($path)) {
			return;
		}
		@file_put_contents($path, "Story Craft — папка контента\r\n" .
			"=============================\r\n\r\n" .
			"Каждое приключение — отдельная папка в adventures/:\r\n\r\n" .
			"adventures/<id_приключения>/            (латиница, цифры, _ и -)\r\n" .
			"    adventure.json   {\"id\",\"title\",\"description\",\"order\":1,\"accent\":\"#ff8a3a\",\"cover\":\"cover.png\",\"hidden\":false}\r\n" .
			"    cover.png        превью 16:9\r\n" .
			"    episodes/<NN_id>/                    например 01_escape\r\n" .
			"        episode.json, cover.png, scenes/, worlds/, textures/, audio/   (экспорт mcanim --export-game)\r\n\r\n" .
			"Положили/удалили/изменили папку — через несколько секунд это видно в игре.\r\n" .
			"\"hidden\": true в adventure.json или episode.json скрывает приключение/серию.\r\n" .
			"Команды: /storycraft status | reload | list | progress <device_id>\r\n");
	}

	public function onCommand(CommandSender $sender, Command $command, $label, array $args) {
		if (strtolower($command->getName()) !== "storycraft") {
			return false;
		}
		$sub = isset($args[0]) ? strtolower($args[0]) : "status";
		$t = $this->thread;
		switch ($sub) {
			case "status":
				if ($t === null) {
					$sender->sendMessage("§cStory Craft: HTTP-поток не запущен");
					return true;
				}
				$state = $t->failed ? "§cОШИБКА (порт занят?)" : ($t->started ? "§aработает" : "§eзапускается");
				$sender->sendMessage("§6[Story Craft]§r HTTP: " . $state . "§r, порт TCP " . $this->cfg["http_port"]);
				$sender->sendMessage("Подключений сейчас: " . $t->connections . ", запросов обслужено: " . $t->served);
				$sender->sendMessage("Версия каталога: " . $t->catalogVersion);
				return true;
			case "reload":
				if ($t !== null) {
					$t->reloadRequested = true;
				}
				$sender->sendMessage("§6[Story Craft]§r Пересканирую папку контента…");
				return true;
			case "list":
				$lines = $t !== null ? json_decode($t->listing, true) : [];
				$sender->sendMessage("§6[Story Craft]§r Каталог v" . ($t !== null ? $t->catalogVersion : 0) . ":");
				foreach ((array) $lines as $l) {
					$sender->sendMessage($l);
				}
				return true;
			case "progress":
				if (!isset($args[1])) {
					$sender->sendMessage("Использование: /storycraft progress <device_id>");
					return true;
				}
				$p = (new Progress($this->cfg["data_dir"] . "/progress"))->load($args[1]);
				if ($p === null) {
					$sender->sendMessage("§cУстройство не найдено");
					return true;
				}
				$sender->sendMessage(json_encode(Progress::view($p), JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT));
				return true;
		}
		$sender->sendMessage("Использование: /storycraft <status|reload|list|progress <device_id>>");
		return true;
	}
}
