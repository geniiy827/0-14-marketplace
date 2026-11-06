<?php
// test harness: the plugin's Crypto + Service + Store without PocketMine, on 127.0.0.1:19999
$src = __DIR__ . "/../NovaAccounts/src/NovaAccounts/";
require $src . "Crypto.php"; require $src . "Store.php"; require $src . "Service.php";
$cfg = ["messages" => [], "limits" => ["registrations-per-hour-ip" => 3, "login-fails-account" => 5]];
foreach(file(__DIR__ . "/../NovaAccounts/resources/config.yml") as $l) if(preg_match('/^  ([a-z_]+): "(.*)"$/', rtrim($l), $m)) $cfg["messages"][$m[1]] = $m[2];
@unlink("/tmp/claude-0/-home-claude/35a8a87f-967e-5dfe-920c-0842806e132a/scratchpad/acc.db");
$crypto = new NovaAccounts\Crypto(file_get_contents(__DIR__ . "/../NovaAccounts/resources/private.pem"));
$svc = new NovaAccounts\Service(new NovaAccounts\Store("/tmp/claude-0/-home-claude/35a8a87f-967e-5dfe-920c-0842806e132a/scratchpad/acc.db"), $cfg);
$sock = socket_create(AF_INET, SOCK_DGRAM, SOL_UDP);
socket_bind($sock, "127.0.0.1", 19999);
$answers = [];
while(true){
	$n = socket_recvfrom($sock, $buf, 4096, 0, $ip, $port);
	if($buf === "quit") break;
	if(strlen($buf) < 5 || substr($buf, 0, 3) !== "\xfe\xfd\x4e") continue;
	if($buf[4] === "\x00") { $o = $crypto->hello(substr($buf, 5, 8)); socket_sendto($sock, $o, strlen($o), 0, $ip, $port); continue; }
	$k = sha1($buf);
	if(isset($answers[$k])) { $o = $answers[$k]; socket_sendto($sock, $o, strlen($o), 0, $ip, $port); continue; }
	$open = $crypto->open($buf);
	if($open === null) { echo "bad packet\n"; continue; }
	list($req, $e, $m) = $open;
	$res = $svc->handle($req, $ip);
	echo json_encode(["req" => array_diff_key($req, ["pass" => 1, "pass2" => 1]), "res" => $res], JSON_UNESCAPED_UNICODE), "\n";
	$o = $crypto->seal($res, $e, $m);
	$answers[$k] = $o;
	socket_sendto($sock, $o, strlen($o), 0, $ip, $port);
}
