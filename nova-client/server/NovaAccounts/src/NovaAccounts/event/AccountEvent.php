<?php
namespace NovaAccounts\event;

use NovaAccounts\Main;
use pocketmine\event\plugin\PluginEvent;

/** Base of the NovaAccounts events. getAccount(): the account row (id, tag, created, last_login, banned, data...). */
abstract class AccountEvent extends PluginEvent{
	/** @var array */
	protected $account;
	/** @var string */
	protected $ip;

	public function __construct(Main $plugin, array $account, $ip){
		parent::__construct($plugin);
		unset($account["pass"]);
		$this->account = $account;
		$this->ip = $ip;
	}

	public function getAccount(){ return $this->account; }
	public function getTag(){ return $this->account["tag"]; }
	public function getIp(){ return $this->ip; }
}
