<?php
namespace NovaAccounts\event;

use pocketmine\event\Cancellable;

/** Before an account is created (getAccount() has only "tag"). Cancel it to refuse; setReason() is shown to the player. */
class AccountRegisterEvent extends AccountEvent implements Cancellable{
	public static $handlerList = null;
	private $reason = "";
	public function setReason($r){ $this->reason = (string) $r; }
	public function getReason(){ return $this->reason; }
}
