<?php
require __DIR__ . "/stubs/pm.php";
spl_autoload_register(function($c){ $f = __DIR__ . "/../NovaAccounts/src/" . str_replace("\\", "/", $c) . ".php"; if(file_exists($f)) require $f; });
$GLOBALS["CFG"] = [];
$dir = "/tmp/claude-0/-home-claude/35a8a87f-967e-5dfe-920c-0842806e132a/scratchpad/pmdata/";
@mkdir($dir); @unlink($dir . "accounts.db"); @unlink($dir . "private.pem");
$srv = new pocketmine\Server();
$m = new NovaAccounts\Main(); $m->dir = $dir; $m->res = "/home/claude/work/keys/";
// saveResource("private.pem") -> copy from keys
copy("/home/claude/work/keys/nova_accounts_private.pem", $dir . "private.pem");
$m->onEnable();
$srv->start();                       // the real query handler replaces ours...
$srv->sched->tasks[0]->onRun(0);     // ...and the tick task puts ours back
$sock = socket_create(AF_INET, SOCK_DGRAM, SOL_UDP); socket_bind($sock, "127.0.0.1", 19999); $srv->net->sock = $sock;
while(true){
	socket_recvfrom($sock, $buf, 4096, 0, $ip, $port);
	if($buf === "quit") break;
	$srv->handlePacket($ip, $port, $buf);
}
$m->onDisable();
