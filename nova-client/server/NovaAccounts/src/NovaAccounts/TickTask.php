<?php
namespace NovaAccounts;

use pocketmine\scheduler\PluginTask;

/** every 10 seconds: keep the packet handler installed, forget old rate-limit entries */
class TickTask extends PluginTask{
	private $n = 0;
	public function onRun($currentTick){
		/** @var Main $p */
		$p = $this->owner;
		$p->install();
		if(++$this->n % 30 === 0){ $p->getService()->cleanup(); $p->getSocial()->cleanup(); }
	}
}
