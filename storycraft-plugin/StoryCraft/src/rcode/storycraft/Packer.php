<?php
/*
 * Story Craft — «пакет серии»: все файлы серии одним файлом, каждый сжат gzencode(…, 6).
 * Игра качает пакет в несколько потоков (Range) и распаковывает у себя — это в разы быстрее,
 * чем 40+ отдельных запросов, и в 4–5 раз меньше трафика.
 *
 * Формат:
 *   [4 байта]  uint32 LE  N = длина индекса
 *   [N байт]   JSON {"files":[{"path","offset","zsize","size","sha1"}…], "total": длина блока данных}
 *   [данные]   gzencode(файл, 6) подряд; offset — от начала блока данных
 * Нужен только zlib. Без зависимостей от ядра. Синтаксис PHP 7.0.
 */
namespace rcode\storycraft;

class Packer {
	private $dir;
	private $log;

	public function __construct($packsDir, $log) {
		$this->dir = rtrim($packsDir, "/\\");
		$this->log = $log;
		if (!is_dir($this->dir)) {
			@mkdir($this->dir, 0777, true);
		}
	}

	private function log($m) {
		$f = $this->log;
		if ($f !== null) {
			$f($m);
		}
	}

	public function path($advId, $epId) {
		return $this->dir . "/" . $advId . "/" . $epId . ".pack";
	}

	/**
	 * Пакет актуален? Если нет — пересобрать. $files: [["rel" => путь в серии, "abs", "size", "sha1"]].
	 * Возвращает ["path", "size", "index", "data_offset"] или null.
	 */
	public function ensure($advId, $epId, array $files) {
		if (!function_exists("gzencode")) {
			return null;
		}
		$p = $this->path($advId, $epId);
		$sig = $this->signature($files);
		$sigFile = $p . ".sig";
		if (is_file($p) && is_file($sigFile) && trim((string) @file_get_contents($sigFile)) === $sig) {
			return $this->info($p);
		}
		$t0 = microtime(true);
		if (!$this->build($p, $files)) {
			return null;
		}
		@file_put_contents($sigFile, $sig);
		$info = $this->info($p);
		if ($info !== null) {
			$this->log(sprintf("[StoryCraft] Пакет %s/%s: %d файлов, %.1f МБ → %.1f МБ за %.1f с",
				$advId, $epId, count($files), $this->rawSize($files) / 1048576, $info["size"] / 1048576, microtime(true) - $t0));
		}
		return $info;
	}

	private function rawSize(array $files) {
		$n = 0;
		foreach ($files as $f) {
			$n += $f["size"];
		}
		return $n;
	}

	/** Пересборка, если у любого файла изменились mtime или размер (или сам список). */
	private function signature(array $files) {
		$s = "v1\n";
		foreach ($files as $f) {
			$s .= $f["rel"] . " " . $f["size"] . " " . @filemtime($f["abs"]) . "\n";
		}
		return md5($s);
	}

	private function build($p, array $files) {
		@mkdir(dirname($p), 0777, true);
		$tmpData = $p . ".data.tmp";
		$tmp = $p . ".tmp";
		$d = @fopen($tmpData, "wb");
		if ($d === false) {
			$this->log("[StoryCraft] Не удалось создать пакет " . $p);
			return false;
		}
		$index = [];
		$off = 0;
		foreach ($files as $f) {
			$raw = @file_get_contents($f["abs"]);
			if ($raw === false) {
				fclose($d);
				@unlink($tmpData);
				return false;
			}
			$gz = gzencode($raw, 6);
			fwrite($d, $gz);
			$index[] = ["path" => $f["rel"], "offset" => $off, "zsize" => strlen($gz), "size" => strlen($raw), "sha1" => $f["sha1"]];
			$off += strlen($gz);
			unset($raw, $gz);
		}
		fclose($d);
		$json = json_encode(["files" => $index, "total" => $off], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
		$o = @fopen($tmp, "wb");
		$in = @fopen($tmpData, "rb");
		if ($o === false || $in === false) {
			return false;
		}
		fwrite($o, pack("V", strlen($json)));
		fwrite($o, $json);
		stream_copy_to_stream($in, $o);
		fclose($in);
		fclose($o);
		@unlink($tmpData);
		@rename($tmp, $p);
		return true;
	}

	/** Прочитать индекс пакета. */
	public function info($p) {
		$h = @fopen($p, "rb");
		if ($h === false) {
			return null;
		}
		$hdr = fread($h, 4);
		if (strlen($hdr) !== 4) {
			fclose($h);
			return null;
		}
		$n = unpack("V", $hdr)[1];
		$json = $n > 0 ? fread($h, $n) : "";
		fclose($h);
		$idx = json_decode($json, true);
		if (!is_array($idx)) {
			return null;
		}
		clearstatcache(true, $p);
		return ["path" => $p, "size" => (int) filesize($p), "index" => $idx, "data_offset" => 4 + $n];
	}

	/** Удалить пакеты серий, которых больше нет. $keep: [путь пакета => true]. */
	public function cleanup(array $keep) {
		if (!is_dir($this->dir)) {
			return;
		}
		foreach ((array) @scandir($this->dir) as $adv) {
			if ($adv === "." || $adv === ".." || !is_dir($this->dir . "/" . $adv)) {
				continue;
			}
			foreach ((array) @scandir($this->dir . "/" . $adv) as $f) {
				$p = $this->dir . "/" . $adv . "/" . $f;
				if (substr($f, -5) === ".pack" && !isset($keep[$p])) {
					@unlink($p);
					@unlink($p . ".sig");
				}
			}
		}
	}
}
