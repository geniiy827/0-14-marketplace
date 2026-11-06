<?php
// test harness: Crypto + Service + Store + Social on 127.0.0.1:19999 (like Main::onNovaPacket)
$src = __DIR__ . "/../NovaAccounts/src/NovaAccounts/";
foreach(["Crypto", "Store", "Service", "Social"] as $c) require $src . "$c.php";
$cfg = ["messages" => []];
foreach(file(__DIR__ . "/../NovaAccounts/resources/config.yml") as $l) if(preg_match('/^  ([a-z_]+): "(.*)"$/', rtrim($l), $m)) $cfg["messages"][$m[1]] = $m[2];
$db = "/tmp/claude-0/-home-claude/35a8a87f-967e-5dfe-920c-0842806e132a/scratchpad/acc2.db"; @unlink($db);
$crypto = new NovaAccounts\Crypto(file_get_contents("/home/claude/work/keys/nova_accounts_private.pem"));
$svc = new NovaAccounts\Service(new NovaAccounts\Store($db), $cfg);
$sock = socket_create(AF_INET, SOCK_DGRAM, SOL_UDP);
socket_bind($sock, "127.0.0.1", 19999);
$send = function($ip, $port, $d) use ($sock){ socket_sendto($sock, $d, strlen($d), 0, $ip, $port); };
$social = new NovaAccounts\Social($svc, $send);
$relayed = 0;
while(true){
	socket_recvfrom($sock, $buf, 4096, 0, $ip, $port);
	if($buf === "quit") break;
	if(strlen($buf) < 5 || substr($buf, 0, 3) !== "\xfe\xfd\x4e") continue;
	if($buf[4] === "\x00") { $send($ip, $port, $crypto->hello(substr($buf, 5, 8))); continue; }
	if($buf[4] === "\x02") { $relayed++; $social->relay($ip, $port, $buf); continue; }
	$open = $crypto->open($buf);
	if($open === null) { echo "bad packet\n"; continue; }
	list($req, $e, $m) = $open;
	$req["_port"] = $port;
	$res = $svc->handle($req, $ip);
	if($req["op"] !== "presence") echo $req["op"], " -> ", json_encode($res, JSON_UNESCAPED_UNICODE), "\n";
	$send($ip, $port, $crypto->seal($res, $e, $m));
}
echo "relayed $relayed\n";
