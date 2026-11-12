<?php
/*
 * Story Craft — прогресс игроков по устройствам (progress/<device_id>.json).
 * Запись атомарно: flock на lock-файле + tmp + rename. Без зависимостей от ядра.
 */
namespace rcode\storycraft;

class Progress {
	private $dir;

	public function __construct($dir) {
		$this->dir = rtrim($dir, "/\\");
		if (!is_dir($this->dir)) {
			@mkdir($this->dir, 0777, true);
		}
	}

	public static function validId($id) {
		return is_string($id) && preg_match('/^[A-Za-z0-9_\-]{8,64}$/', $id) === 1;
	}

	private function path($id) {
		return $this->dir . "/" . $id . ".json";
	}

	public function load($id) {
		if (!self::validId($id)) {
			return null;
		}
		$raw = @file_get_contents($this->path($id));
		if ($raw === false) {
			return null;
		}
		$j = json_decode($raw, true);
		return is_array($j) ? $j : null;
	}

	/** Выполнить изменение под блокировкой: $fn(array|null $cur) → array. */
	private function mutate($id, $fn) {
		$lock = @fopen($this->path($id) . ".lock", "c");
		if ($lock !== false) {
			flock($lock, LOCK_EX);
		}
		$cur = $this->load($id);
		$new = $fn($cur);
		if (is_array($new)) {
			$tmp = $this->path($id) . "." . getmypid() . ".tmp";
			file_put_contents($tmp, json_encode($new, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT));
			@rename($tmp, $this->path($id));
		}
		if ($lock !== false) {
			flock($lock, LOCK_UN);
			fclose($lock);
		}
		return $new;
	}

	/** Регистрация: повторно для того же устройства — тот же токен. */
	public function register($id, $model, $appVersion) {
		$res = $this->mutate($id, function ($cur) use ($id, $model, $appVersion) {
			$now = time();
			if ($cur === null) {
				$cur = [
					"device_id" => $id,
					"token" => bin2hex(random_bytes(16)),
					"created" => $now,
					"adventures" => new \stdClass(),
				];
			}
			$cur["seen"] = $now;
			if ((string) $model !== "" || !isset($cur["model"])) {
				$cur["model"] = substr((string) $model, 0, 80);
			}
			if ((string) $appVersion !== "" || !isset($cur["app_version"])) {
				$cur["app_version"] = substr((string) $appVersion, 0, 20);
			}
			return $cur;
		});
		return $res["token"];
	}

	public function auth($id, $token) {
		$p = $this->load($id);
		if ($p === null || !is_string($token) || $token === "") {
			return null;
		}
		return hash_equals((string) $p["token"], $token) ? $p : null;
	}

	/** Публичное представление прогресса (без токена). */
	public static function view($p) {
		unset($p["token"]);
		if (empty($p["adventures"])) {
			$p["adventures"] = new \stdClass();
		}
		return $p;
	}

	/** Применить событие. Возвращает [ok, progress|ошибка]. */
	public function event($id, $ev) {
		if (!is_array($ev) || !isset($ev["type"])) {
			return [false, "bad event"];
		}
		$type = (string) $ev["type"];
		$adv = isset($ev["adventure"]) ? (string) $ev["adventure"] : "";
		if (!preg_match('/^[A-Za-z0-9_\-]+$/', $adv)) {
			return [false, "bad adventure"];
		}
		if (!in_array($type, ["checkpoint", "complete", "reset"], true)) {
			return [false, "unknown event type"];
		}
		$res = $this->mutate($id, function ($cur) use ($type, $adv, $ev) {
			if ($cur === null) {
				return null;
			}
			$advs = isset($cur["adventures"]) && is_array($cur["adventures"]) ? $cur["adventures"] : [];
			$a = isset($advs[$adv]) && is_array($advs[$adv]) ? $advs[$adv] : [];
			if (!isset($a["completed"]) || !is_array($a["completed"])) {
				$a["completed"] = [];
			}
			if (!isset($a["vars"]) || !is_array($a["vars"])) {
				$a["vars"] = [];
			}
			$ep = isset($ev["episode"]) ? (string) $ev["episode"] : "";
			if ($type === "checkpoint") {
				$a["resume"] = [
					"episode" => $ep,
					"node" => isset($ev["node"]) ? (string) $ev["node"] : "",
					"vars" => isset($ev["vars"]) && is_array($ev["vars"]) ? $ev["vars"] : new \stdClass(),
					"adv_vars" => isset($ev["adv_vars"]) && is_array($ev["adv_vars"]) ? $ev["adv_vars"] : new \stdClass(),
					"at" => time(),
				];
			} elseif ($type === "complete") {
				$a["completed"][$ep] = [
					"ending" => isset($ev["ending"]) ? (string) $ev["ending"] : "",
					"at" => time(),
				];
				if (isset($ev["adv_vars"]) && is_array($ev["adv_vars"])) {
					foreach ($ev["adv_vars"] as $k => $v) {
						$a["vars"][(string) $k] = $v;
					}
				}
				if (isset($a["resume"]["episode"]) && $a["resume"]["episode"] === $ep) {
					unset($a["resume"]);
				}
			} elseif ($type === "reset") {
				$a = ["completed" => [], "vars" => []];
			}
			if (empty($a["completed"])) {
				$a["completed"] = new \stdClass();
			}
			if (empty($a["vars"])) {
				$a["vars"] = new \stdClass();
			}
			$advs[$adv] = $a;
			$cur["adventures"] = $advs;
			$cur["seen"] = time();
			return $cur;
		});
		if ($res === null) {
			return [false, "unknown device"];
		}
		return [true, self::view($res)];
	}
}
