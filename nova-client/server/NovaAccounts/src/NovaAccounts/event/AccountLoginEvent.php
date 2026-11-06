<?php
namespace NovaAccounts\event;

use pocketmine\event\Cancellable;

/** Correct tag + password, before the session is made. Cancel it to refuse; setReason() is shown to the player. */
class AccountLoginEvent extends AccountEvent implements Cancellable{
	public static $handlerList = null;
	private $reason = "";
	public function setReason($r){ $this->reason = (string) $r; }
	public function getReason(){ return $this->reason; }
}
