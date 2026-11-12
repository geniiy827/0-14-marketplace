<?php
/*
 * Тестовый запуск HTTP-части плагина без ядра Genisys:
 *   php tools/standalone.php <data_dir> [port]
 * data_dir — папка как plugins/StoryCraft (внутри content/adventures, progress).
 */
$src = __DIR__ . "/../StoryCraft/src/rcode/storycraft";
require $src . "/Packer.php";
require $src . "/Catalog.php";
require $src . "/Progress.php";
require $src . "/HttpServer.php";

$data = isset($argv[1]) ? rtrim($argv[1], "/") : __DIR__ . "/../test_data";
@mkdir($data . "/content/adventures", 0777, true);
$cfg = ["http_port" => isset($argv[2]) ? (int) $argv[2] : 12055, "bind" => "0.0.0.0", "scan_interval" => 2,
	"max_connections" => 64, "public_url" => "", "data_dir" => $data];
$srv = new rcode\storycraft\HttpServer($cfg, function ($m) {
	fwrite(STDERR, date("H:i:s ") . $m . "\n");
});
// SC_THROTTLE=<байт/с на соединение> — имитация медленной мобильной сети для тестов
if (getenv("SC_THROTTLE")) {
	$srv->writePerTick = max(1024, (int) (getenv("SC_THROTTLE") / 20));
	fwrite(STDERR, "throttle " . getenv("SC_THROTTLE") . " B/s per connection\n");
}
// SC_STALL=<байт> — одно соединение «зависнет» после стольких байт (проверка сторожа в игре)
if (getenv("SC_STALL")) {
	$srv->stallOnceAfter = (int) getenv("SC_STALL");
}
if (!$srv->open()) {
	exit(1);
}
while (true) {
	$srv->tick(getenv("SC_THROTTLE") ? 0 : 50000); if (getenv("SC_THROTTLE")) { usleep(50000); }
}
