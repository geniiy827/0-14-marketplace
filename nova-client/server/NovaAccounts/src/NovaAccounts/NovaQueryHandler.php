<?php
/*
 * NovaAccounts - receives NOVA packets on the game port.
 *
 * RakLib hands every unknown UDP packet to Server::handlePacket(), which passes packets starting with FE FD to
 * the query handler. NOVA packets start with FE FD 4E, so this subclass takes them and gives everything else to
 * the real query handler (if query is enabled). No extra port is needed.
 */
namespace NovaAccounts;

use pocketmine\network\query\QueryHandler;

class NovaQueryHandler extends QueryHandler{
	/** @var Main */
	private $plugin;
	/** @var QueryHandler|null */
	private $inner;

	/** the parent constructor is not called on purpose: it would start a second query handler */
	public function __construct(Main $plugin, $inner){
		$this->plugin = $plugin;
		$this->inner = $inner;
	}

	public function getInner(){
		return $this->inner;
	}

	public function regenerateInfo(){
		if($this->inner !== null) $this->inner->regenerateInfo();
	}

	public function regenerateToken(){
		if($this->inner !== null) $this->inner->regenerateToken();
	}

	public function handle($address, $port, $packet){
		if(strlen($packet) >= 5 and $packet[2] === "\x4e"){
			try{
				$this->plugin->onNovaPacket($address, $port, $packet);
			}catch(\Throwable $e){
				$this->plugin->getLogger()->debug("NOVA packet from $address: " . $e->getMessage());
			}
			return;
		}
		if($this->inner !== null) $this->inner->handle($address, $port, $packet);
	}
}
