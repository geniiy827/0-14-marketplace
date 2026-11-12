<?php
/*
 * Story Craft — каталог контента.
 * Без зависимостей от ядра (pocketmine): можно запускать из php-cli для тестов.
 * Синтаксис совместим с PHP 7.0.
 */
namespace rcode\storycraft;

class Catalog {
	/** @var string папка content/adventures */
	private $root;
	/** @var string файл состояния (версия каталога + подпись содержимого) */
	private $stateFile;
	/** @var string файл кэша sha1 */
	private $shaFile;
	/** @var string префикс URL (public_url) */
	private $publicUrl;
	/** @var callable логгер */
	private $log;

	/** @var array кэш sha1: path => [size, mtime, sha1] */
	private $sha = [];
	private $shaDirty = false;
	/** @var string подпись текущего содержимого */
	private $signature = "";
	/** @var int */
	public $version = 0;
	/** @var string готовый JSON каталога */
	public $json = "{}";
	/** @var array каталог как массив */
	public $data = [];

	public function __construct($root, $dataDir, $publicUrl, $log) {
		$this->root = rtrim($root, "/\\");
		$this->stateFile = $dataDir . "/catalog_state.json";
		$this->shaFile = $dataDir . "/sha1_cache.json";
		$this->publicUrl = rtrim((string) $publicUrl, "/");
		$this->log = $log;
		if (!is_dir($this->root)) {
			@mkdir($this->root, 0777, true);
		}
		$st = @json_decode(@file_get_contents($this->stateFile), true);
		if (is_array($st)) {
			$this->version = (int) (isset($st["version"]) ? $st["version"] : 0);
			$this->signature = (string) (isset($st["signature"]) ? $st["signature"] : "");
		}
		$sc = @json_decode(@file_get_contents($this->shaFile), true);
		if (is_array($sc)) {
			$this->sha = $sc;
		}
	}

	private function log($msg) {
		$f = $this->log;
		if ($f !== null) {
			$f($msg);
		}
	}

	public function root() {
		return $this->root;
	}

	/**
	 * Пересканировать папку. Возвращает true, если каталог изменился (версия выросла).
	 * Дёшево, если ничего не менялось: сравниваем подпись (пути + размеры + mtime).
	 */
	public function scan($force = false) {
		clearstatcache();
		$sig = $this->computeSignature();
		if (!$force && $sig === $this->signature && $this->data !== []) {
			return false;
		}
		$changed = ($sig !== $this->signature);
		$this->build();
		if ($changed) {
			$this->version++;
			$this->signature = $sig;
			$this->saveState();
		} elseif ($this->version === 0) {
			$this->version = 1;
			$this->saveState();
		}
		$this->data["version"] = $this->version;
		$this->json = json_encode($this->data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
		if ($this->shaDirty) {
			@file_put_contents($this->shaFile, json_encode($this->sha, JSON_UNESCAPED_SLASHES));
			$this->shaDirty = false;
		}
		return $changed;
	}

	private function saveState() {
		$tmp = $this->stateFile . ".tmp";
		@file_put_contents($tmp, json_encode(["version" => $this->version, "signature" => $this->signature]));
		@rename($tmp, $this->stateFile);
	}

	/** Подпись: md5 списка всех файлов с размерами и mtime. */
	private function computeSignature() {
		$list = [];
		$this->walk($this->root, "", $list);
		sort($list);
		return md5(implode("\n", $list));
	}

	private function walk($abs, $rel, &$out) {
		$h = @opendir($abs);
		if ($h === false) {
			return;
		}
		while (($e = readdir($h)) !== false) {
			if ($e === "." || $e === "..") {
				continue;
			}
			$p = $abs . "/" . $e;
			$r = $rel === "" ? $e : $rel . "/" . $e;
			if (is_dir($p)) {
				$out[] = "D " . $r;
				$this->walk($p, $r, $out);
			} else {
				$out[] = "F " . $r . " " . @filesize($p) . " " . @filemtime($p);
			}
		}
		closedir($h);
	}

	/** Список файлов (относительно $abs), рекурсивно. */
	private function files($abs, $rel = "") {
		$res = [];
		$h = @opendir($abs);
		if ($h === false) {
			return $res;
		}
		$names = [];
		while (($e = readdir($h)) !== false) {
			if ($e !== "." && $e !== "..") {
				$names[] = $e;
			}
		}
		closedir($h);
		sort($names);
		foreach ($names as $e) {
			$p = $abs . "/" . $e;
			$r = $rel === "" ? $e : $rel . "/" . $e;
			if (is_dir($p)) {
				foreach ($this->files($p, $r) as $x) {
					$res[] = $x;
				}
			} else {
				$res[] = $r;
			}
		}
		return $res;
	}

	/** sha1 файла с кэшем по (size, mtime). */
	public function sha1Of($abs) {
		$size = @filesize($abs);
		$mtime = @filemtime($abs);
		if ($size === false) {
			return "";
		}
		if (isset($this->sha[$abs])) {
			$c = $this->sha[$abs];
			if ($c[0] === $size && $c[1] === $mtime) {
				return $c[2];
			}
		}
		$h = @sha1_file($abs);
		if ($h === false) {
			return "";
		}
		$this->sha[$abs] = [$size, $mtime, $h];
		$this->shaDirty = true;
		return $h;
	}

	private function url($advId, $rel) {
		$parts = array_map("rawurlencode", explode("/", $rel));
		return $this->publicUrl . "/files/" . rawurlencode($advId) . "/" . implode("/", $parts);
	}

	private function readJson($path) {
		$raw = @file_get_contents($path);
		if ($raw === false) {
			return null;
		}
		// убираем BOM, если файл сохранён в «Блокноте»
		if (substr($raw, 0, 3) === "\xEF\xBB\xBF") {
			$raw = substr($raw, 3);
		}
		$j = json_decode($raw, true);
		if (!is_array($j)) {
			$this->log("[StoryCraft] Ошибка JSON: " . $path . " (" . json_last_error_msg() . ")");
			return null;
		}
		return $j;
	}

	private function build() {
		$advs = [];
		$known = [];
		$dirs = @scandir($this->root);
		if ($dirs === false) {
			$dirs = [];
		}
		foreach ($dirs as $advId) {
			if ($advId === "." || $advId === ".." || !is_dir($this->root . "/" . $advId)) {
				continue;
			}
			if (!preg_match('/^[A-Za-z0-9_\-]+$/', $advId)) {
				$this->log("[StoryCraft] Пропуск папки с недопустимым именем: " . $advId);
				continue;
			}
			$advDir = $this->root . "/" . $advId;
			$a = $this->readJson($advDir . "/adventure.json");
			if ($a === null || !empty($a["hidden"])) {
				continue;
			}
			$cover = isset($a["cover"]) ? (string) $a["cover"] : "cover.png";
			$adv = [
				"id" => $advId,
				"title" => isset($a["title"]) ? (string) $a["title"] : $advId,
				"description" => isset($a["description"]) ? (string) $a["description"] : "",
				"accent" => isset($a["accent"]) ? (string) $a["accent"] : "#ff8a3a",
				"order" => isset($a["order"]) ? (int) $a["order"] : 1000,
				"cover" => is_file($advDir . "/" . $cover) ? $this->url($advId, $cover) : "",
				"cover_sha1" => is_file($advDir . "/" . $cover) ? $this->sha1Of($advDir . "/" . $cover) : "",
				"episodes" => [],
			];
			$epRoot = $advDir . "/episodes";
			$eps = is_dir($epRoot) ? @scandir($epRoot) : [];
			if ($eps === false) {
				$eps = [];
			}
			foreach ($eps as $epId) {
				if ($epId === "." || $epId === ".." || !is_dir($epRoot . "/" . $epId)) {
					continue;
				}
				$epDir = $epRoot . "/" . $epId;
				$e = $this->readJson($epDir . "/episode.json");
				if ($e === null || !empty($e["hidden"])) {
					continue;
				}
				$num = isset($e["number"]) ? (int) $e["number"] : (int) $epId;
				$files = [];
				$size = 0;
				foreach ($this->files($epDir) as $rel) {
					$abs = $epDir . "/" . $rel;
					$fs = (int) @filesize($abs);
					$size += $fs;
					$files[] = ["path" => "episodes/" . $epId . "/" . $rel, "size" => $fs, "sha1" => $this->sha1Of($abs)];
				}
				$epCover = isset($e["cover"]) ? (string) $e["cover"] : "cover.png";
				$adv["episodes"][] = [
					"id" => $epId,
					"number" => $num,
					"title" => isset($e["title"]) ? (string) $e["title"] : $epId,
					"description" => isset($e["description"]) ? (string) $e["description"] : "",
					"duration" => isset($e["duration"]) ? (float) $e["duration"] : 0.0,
					"version" => isset($e["version"]) ? (int) $e["version"] : 1,
					"cover" => is_file($epDir . "/" . $epCover) ? $this->url($advId, "episodes/" . $epId . "/" . $epCover) : "",
					"size" => $size,
					"sha1" => $this->sha1Of($epDir . "/episode.json"),
					"files" => $files,
				];
				$known[$epDir] = true;
			}
			usort($adv["episodes"], function ($x, $y) {
				if ($x["number"] === $y["number"]) {
					return strcmp($x["id"], $y["id"]);
				}
				return $x["number"] < $y["number"] ? -1 : 1;
			});
			$advs[] = $adv;
		}
		usort($advs, function ($x, $y) {
			if ($x["order"] === $y["order"]) {
				return strcmp($x["id"], $y["id"]);
			}
			return $x["order"] < $y["order"] ? -1 : 1;
		});
		// чистим кэш sha1 от удалённых файлов
		foreach (array_keys($this->sha) as $p) {
			if (!is_file($p)) {
				unset($this->sha[$p]);
				$this->shaDirty = true;
			}
		}
		$this->data = ["version" => $this->version, "generated" => time(), "adventures" => $advs];
	}

	/** Человекочитаемый список для /storycraft list. */
	public function describe() {
		$lines = [];
		if (empty($this->data["adventures"])) {
			return ["(пусто) — положите приключения в " . $this->root];
		}
		foreach ($this->data["adventures"] as $a) {
			$lines[] = "§6" . $a["id"] . "§r — " . $a["title"] . " (" . count($a["episodes"]) . " сер.)";
			foreach ($a["episodes"] as $e) {
				$lines[] = "   #" . $e["number"] . " " . $e["id"] . " — " . $e["title"] . " — " . round($e["size"] / 1048576, 1) . " МБ, " . count($e["files"]) . " файлов";
			}
		}
		return $lines;
	}

	/**
	 * Безопасно получить абсолютный путь файла /files/<adv>/<path>. null — нельзя/нет.
	 */
	public function resolve($advId, $rel) {
		if (!preg_match('/^[A-Za-z0-9_\-]+$/', $advId)) {
			return null;
		}
		if ($rel === "" || strpos($rel, "\0") !== false || strpos($rel, "\\") !== false || $rel[0] === "/") {
			return null;
		}
		foreach (explode("/", $rel) as $seg) {
			if ($seg === "" || $seg === "." || $seg === "..") {
				return null;
			}
		}
		$base = realpath($this->root . "/" . $advId);
		if ($base === false) {
			return null;
		}
		$p = realpath($base . "/" . $rel);
		if ($p === false || !is_file($p)) {
			return null;
		}
		if (strpos($p, $base . DIRECTORY_SEPARATOR) !== 0) {
			return null;
		}
		return $p;
	}
}
