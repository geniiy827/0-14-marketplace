<?php
/*
 * Тестовый запуск HTTP-части плагина без ядра Genisys:
 *   php tools/standalone.php <data_dir> [port]
 * data_dir — папка как plugins/StoryCraft (внутри content/adventures, progress).
 */
$src = __DIR__ . "/../StoryCraft/src/rcode/storycraft";
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
if (!$srv->open()) {
	exit(1);
}
while (true) {
	$srv->tick(50000);
}
