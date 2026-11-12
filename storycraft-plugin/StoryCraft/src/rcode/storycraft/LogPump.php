<?php
namespace rcode\storycraft;

use pocketmine\scheduler\PluginTask;

/** Периодически выводит логи HTTP-потока в консоль. */
class LogPump extends PluginTask {
	public function onRun($currentTick) {
		$this->getOwner()->pumpLogs();
	}
}
