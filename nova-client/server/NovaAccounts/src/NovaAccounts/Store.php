<?php
/*
 * NovaAccounts - storage (SQLite3, plugin_data/NovaAccounts/accounts.db).
 * Accounts are rows, sessions keep only sha256(token). `data` is free JSON for other plugins.
 */
namespace NovaAccounts;

class Store{
	const SCHEMA = 1;

	/** @var \SQLite3 */
	private $db;

	public function __construct($file){
		$this->db = new \SQLite3($file);
		$this->db->busyTimeout(3000);
		$this->db->exec("PRAGMA journal_mode = WAL");
		$this->db->exec("PRAGMA foreign_keys = ON");
		$this->db->exec("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)");
		$this->db->exec("CREATE TABLE IF NOT EXISTS accounts (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			tag TEXT NOT NULL,
			tag_lc TEXT NOT NULL UNIQUE,
			pass TEXT NOT NULL,
			created INTEGER NOT NULL,
			last_login INTEGER NOT NULL DEFAULT 0,
			last_ip TEXT NOT NULL DEFAULT '',
			reg_ip TEXT NOT NULL DEFAULT '',
			banned INTEGER NOT NULL DEFAULT 0,
			ban_until INTEGER NOT NULL DEFAULT 0,
			ban_reason TEXT NOT NULL DEFAULT '',
			data TEXT NOT NULL DEFAULT '{}'
		)");
		$this->db->exec("CREATE TABLE IF NOT EXISTS sessions (
			token TEXT PRIMARY KEY,
			account INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
			created INTEGER NOT NULL,
			expires INTEGER NOT NULL,
			last_seen INTEGER NOT NULL,
			ip TEXT NOT NULL DEFAULT '',
			device TEXT NOT NULL DEFAULT ''
		)");
		$this->db->exec("CREATE INDEX IF NOT EXISTS sessions_account ON sessions(account)");
		$this->set("schema", (string) self::SCHEMA);
	}

	public function close(){
		$this->db->close();
	}

	private function set($k, $v){
		$st = $this->db->prepare("INSERT OR REPLACE INTO meta (k, v) VALUES (:k, :v)");
		$st->bindValue(":k", $k, SQLITE3_TEXT);
		$st->bindValue(":v", $v, SQLITE3_TEXT);
		$st->execute();
	}

	private function row(\SQLite3Stmt $st){
		$r = $st->execute();
		$a = $r ? $r->fetchArray(SQLITE3_ASSOC) : false;
		return $a === false ? null : $a;
	}

	public function byTag($tag){
		$st = $this->db->prepare("SELECT * FROM accounts WHERE tag_lc = :t");
		$st->bindValue(":t", strtolower($tag), SQLITE3_TEXT);
		return $this->row($st);
	}

	public function byId($id){
		$st = $this->db->prepare("SELECT * FROM accounts WHERE id = :i");
		$st->bindValue(":i", (int) $id, SQLITE3_INTEGER);
		return $this->row($st);
	}

	/** @return int|null new id, null when the tag is taken */
	public function create($tag, $hash, $ip){
		$st = $this->db->prepare("INSERT OR IGNORE INTO accounts (tag, tag_lc, pass, created, reg_ip) VALUES (:t, :l, :p, :c, :ip)");
		$st->bindValue(":t", $tag, SQLITE3_TEXT);
		$st->bindValue(":l", strtolower($tag), SQLITE3_TEXT);
		$st->bindValue(":p", $hash, SQLITE3_TEXT);
		$st->bindValue(":c", time(), SQLITE3_INTEGER);
		$st->bindValue(":ip", $ip, SQLITE3_TEXT);
		$st->execute();
		if($this->db->changes() !== 1) return null;
		return (int) $this->db->lastInsertRowID();
	}

	public function update($id, array $fields){
		if(count($fields) === 0) return;
		$set = [];
		foreach($fields as $k => $v){
			if(!preg_match('/^[a-z_]+$/', $k)) throw new \InvalidArgumentException("field $k");
			$set[] = "$k = :$k";
		}
		$st = $this->db->prepare("UPDATE accounts SET " . implode(", ", $set) . " WHERE id = :id");
		foreach($fields as $k => $v){
			$st->bindValue(":$k", $v, is_int($v) ? SQLITE3_INTEGER : SQLITE3_TEXT);
		}
		$st->bindValue(":id", (int) $id, SQLITE3_INTEGER);
		$st->execute();
	}

	public function addSession($account, $tokenHash, $days, $ip, $device, $max){
		$now = time();
		$st = $this->db->prepare("INSERT INTO sessions (token, account, created, expires, last_seen, ip, device) VALUES (:t, :a, :c, :e, :c, :ip, :d)");
		$st->bindValue(":t", $tokenHash, SQLITE3_TEXT);
		$st->bindValue(":a", (int) $account, SQLITE3_INTEGER);
		$st->bindValue(":c", $now, SQLITE3_INTEGER);
		$st->bindValue(":e", $now + $days * 86400, SQLITE3_INTEGER);
		$st->bindValue(":ip", $ip, SQLITE3_TEXT);
		$st->bindValue(":d", substr($device, 0, 64), SQLITE3_TEXT);
		$st->execute();
		// keep the newest $max
		$st = $this->db->prepare("DELETE FROM sessions WHERE account = :a AND token NOT IN (SELECT token FROM sessions WHERE account = :a ORDER BY created DESC LIMIT :m)");
		$st->bindValue(":a", (int) $account, SQLITE3_INTEGER);
		$st->bindValue(":m", (int) $max, SQLITE3_INTEGER);
		$st->execute();
	}

	/** @return array|null account row of a live session (and refreshes it) */
	public function session($tokenHash){
		$st = $this->db->prepare("SELECT account, expires FROM sessions WHERE token = :t");
		$st->bindValue(":t", $tokenHash, SQLITE3_TEXT);
		$s = $this->row($st);
		if($s === null) return null;
		if($s["expires"] < time()){
			$this->dropSession($tokenHash);
			return null;
		}
		$st = $this->db->prepare("UPDATE sessions SET last_seen = :n WHERE token = :t");
		$st->bindValue(":n", time(), SQLITE3_INTEGER);
		$st->bindValue(":t", $tokenHash, SQLITE3_TEXT);
		$st->execute();
		return $this->byId($s["account"]);
	}

	public function dropSession($tokenHash){
		$st = $this->db->prepare("DELETE FROM sessions WHERE token = :t");
		$st->bindValue(":t", $tokenHash, SQLITE3_TEXT);
		$st->execute();
	}

	public function dropSessions($account){
		$st = $this->db->prepare("DELETE FROM sessions WHERE account = :a");
		$st->bindValue(":a", (int) $account, SQLITE3_INTEGER);
		$st->execute();
		return $this->db->changes();
	}

	public function purgeExpired(){
		$st = $this->db->prepare("DELETE FROM sessions WHERE expires < :n");
		$st->bindValue(":n", time(), SQLITE3_INTEGER);
		$st->execute();
	}

	public function count(){
		return (int) $this->db->querySingle("SELECT COUNT(*) FROM accounts");
	}

	public function latest($n){
		$st = $this->db->prepare("SELECT id, tag, created, last_login, banned FROM accounts ORDER BY id DESC LIMIT :n");
		$st->bindValue(":n", (int) $n, SQLITE3_INTEGER);
		$r = $st->execute();
		$out = [];
		while($r and ($a = $r->fetchArray(SQLITE3_ASSOC)) !== false) $out[] = $a;
		return $out;
	}
}
