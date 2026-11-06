<?php
/*
 * NovaAccounts - packet crypto (PHP 7.0+).
 *
 * Request  (UDP, game port): FE FD 4E | ver 01 | op 01 | RSA-OAEP(server key, encKey16 . macKey16) 256 B | iv 16 | AES-128-CBC(json) | HMAC-SHA256(macKey, op . rsa . iv . ct) 32
 * Response:                  FE FD 4E | ver 01 | 81    | iv 16 | AES-128-CBC(json) | HMAC-SHA256(macKey, 81 . iv . ct) 32
 * Hello:    FE FD 4E 01 00 | nonce 8   ->   FE FD 4E 01 80 | nonce 8 | server time (8, BE) | sha256(public key DER) 32
 * The public key is built into the client, so nobody in the middle can read passwords or tokens.
 */
namespace NovaAccounts;

class Crypto{
	const MAGIC = "\xfe\xfd\x4e";
	const VERSION = "\x01";

	/** @var resource */
	private $key;
	private $fingerprint;

	public function __construct($pem){
		$this->key = openssl_pkey_get_private($pem);
		if($this->key === false){
			throw new \RuntimeException("NovaAccounts: bad private key");
		}
		$det = openssl_pkey_get_details($this->key);
		$der = base64_decode(preg_replace('/-----[^-]+-----|\s+/', '', $det["key"]));
		$this->fingerprint = hash("sha256", $der, true);
	}

	public function fingerprint(){
		return $this->fingerprint;
	}

	public function hello($nonce){
		$t = time();
		return self::MAGIC . self::VERSION . "\x80" . $nonce . pack("NN", ($t >> 32) & 0xffffffff, $t & 0xffffffff) . $this->fingerprint;
	}

	/**
	 * @return array [json array, encKey, macKey] or null
	 */
	public function open($packet){
		// FE FD 4E 01 01 + 256 + 16 + ct(>=16) + 32
		if(strlen($packet) < 5 + 256 + 16 + 16 + 32) return null;
		$op = $packet[4];
		$rsa = substr($packet, 5, 256);
		$iv = substr($packet, 261, 16);
		$ct = substr($packet, 277, strlen($packet) - 277 - 32);
		$mac = substr($packet, -32);
		if(strlen($ct) === 0 or strlen($ct) % 16 !== 0) return null;
		$keys = "";
		if(!openssl_private_decrypt($rsa, $keys, $this->key, OPENSSL_PKCS1_OAEP_PADDING) or strlen($keys) !== 32) return null;
		$enc = substr($keys, 0, 16);
		$macKey = substr($keys, 16, 16);
		if(!hash_equals(hash_hmac("sha256", $op . $rsa . $iv . $ct, $macKey, true), $mac)) return null;
		$plain = openssl_decrypt($ct, "aes-128-cbc", $enc, OPENSSL_RAW_DATA, $iv);
		if($plain === false) return null;
		$json = json_decode($plain, true);
		if(!is_array($json)) return null;
		return [$json, $enc, $macKey];
	}

	public function seal(array $json, $enc, $macKey){
		$iv = random_bytes(16);
		$ct = openssl_encrypt(json_encode($json, JSON_UNESCAPED_UNICODE), "aes-128-cbc", $enc, OPENSSL_RAW_DATA, $iv);
		$mac = hash_hmac("sha256", "\x81" . $iv . $ct, $macKey, true);
		return self::MAGIC . self::VERSION . "\x81" . $iv . $ct . $mac;
	}
}
