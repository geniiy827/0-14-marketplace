<?php
/*
 * Story Craft — минимальный неблокирующий HTTP/1.1 сервер на stream_select.
 * Без зависимостей от ядра: работает и внутри потока Genisys (HttpThread), и из php-cli (tools/standalone.php).
 * GET / HEAD / POST, Content-Length, Range, ETag, If-None-Match, Connection: keep-alive/close.
 */
namespace rcode\storycraft;

class HttpServer {
	const MAX_HEADER = 16384;
	const MAX_BODY = 65536;
	const CHUNK = 262144;
	/** сколько максимум пишем в один сокет за один тик цикла */
	const WRITE_PER_TICK = 524288;
	const IDLE_TIMEOUT = 20;
	const KEEPALIVE_MAX = 200;

	/** @var resource */
	private $server;
	/** @var array id => состояние соединения */
	private $conns = [];
	private $nextId = 1;

	public $catalog;
	public $progress;
	private $cfg;
	private $log;
	private $lastScan = 0;

	/** @var int счётчик обслуженных запросов */
	public $served = 0;

	public function __construct(array $cfg, $log) {
		$this->cfg = $cfg;
		$this->log = $log;
		$data = $cfg["data_dir"];
		$this->catalog = new Catalog($data . "/content/adventures", $data, isset($cfg["public_url"]) ? $cfg["public_url"] : "", $log);
		$this->progress = new Progress($data . "/progress");
	}

	public function log($m) {
		$f = $this->log;
		if ($f !== null) {
			$f($m);
		}
	}

	public function open() {
		$addr = "tcp://" . $this->cfg["bind"] . ":" . (int) $this->cfg["http_port"];
		$ctx = stream_context_create(["socket" => ["so_reuseport" => false, "backlog" => 128]]);
		$errno = 0;
		$errstr = "";
		$this->server = @stream_socket_server($addr, $errno, $errstr, STREAM_SERVER_BIND | STREAM_SERVER_LISTEN, $ctx);
		if ($this->server === false) {
			$this->log("[StoryCraft] Не удалось открыть HTTP " . $addr . ": " . $errstr . " (" . $errno . ")");
			return false;
		}
		stream_set_blocking($this->server, false);
		$this->catalog->scan(true);
		$this->lastScan = time();
		$this->log("[StoryCraft] HTTP слушает " . $addr . ", каталог v" . $this->catalog->version);
		return true;
	}

	public function connectionCount() {
		return count($this->conns);
	}

	public function close() {
		foreach (array_keys($this->conns) as $id) {
			$this->drop($id);
		}
		if (is_resource($this->server)) {
			fclose($this->server);
		}
	}

	/** Пересканировать каталог (по таймеру или принудительно). */
	public function rescan($force = false) {
		$this->lastScan = time();
		if ($this->catalog->scan($force)) {
			$this->log("[StoryCraft] Каталог обновлён → v" . $this->catalog->version);
		}
	}

	/** Один шаг цикла: ждём события до $timeoutUs микросекунд. */
	public function tick($timeoutUs = 50000) {
		$interval = max(1, (int) $this->cfg["scan_interval"]);
		if (time() - $this->lastScan >= $interval) {
			$this->rescan(false);
		}
		$read = [$this->server];
		$write = [];
		$now = time();
		foreach ($this->conns as $id => $c) {
			if ($now - $c["last"] > self::IDLE_TIMEOUT) {
				$this->drop($id);
				continue;
			}
			if ($c["out"] !== "" || $c["file"] !== null) {
				$write[] = $c["sock"];
			} else {
				$read[] = $c["sock"];
			}
		}
		$except = null;
		$n = @stream_select($read, $write, $except, 0, $timeoutUs);
		if ($n === false || $n === 0) {
			return;
		}
		foreach ($read as $s) {
			if ($s === $this->server) {
				$this->accept();
				continue;
			}
			$this->onReadable((int) $s);
		}
		foreach ($write as $s) {
			$this->onWritable((int) $s);
		}
	}

	private function accept() {
		for ($i = 0; $i < 16; $i++) {
			$s = @stream_socket_accept($this->server, 0, $peer);
			if ($s === false) {
				return;
			}
			stream_set_blocking($s, false);
			// большой буфер отправки — быстрее отдаём файлы по медленным мобильным каналам
			if (function_exists("socket_import_stream") && defined("SO_SNDBUF")) {
				$sock = @socket_import_stream($s);
				if ($sock) {
					@socket_set_option($sock, SOL_SOCKET, SO_SNDBUF, 1048576);
				}
			}
			$id = (int) $s;
			$this->conns[$id] = ["sock" => $s, "in" => "", "out" => "", "file" => null, "fileLeft" => 0,
				"keep" => false, "count" => 0, "last" => time(), "peer" => $peer];
			if (count($this->conns) > (int) $this->cfg["max_connections"]) {
				$this->conns[$id]["out"] = $this->rawResponse(503, ["Content-Type" => "application/json; charset=utf-8"], '{"ok":false,"error":"busy"}', false);
				$this->conns[$id]["keep"] = false;
			}
		}
	}

	private function drop($id) {
		if (!isset($this->conns[$id])) {
			return;
		}
		$c = $this->conns[$id];
		if ($c["file"] !== null) {
			@fclose($c["file"]);
		}
		@fclose($c["sock"]);
		unset($this->conns[$id]);
	}

	private function onReadable($id) {
		if (!isset($this->conns[$id])) {
			return;
		}
		$c = &$this->conns[$id];
		$data = @fread($c["sock"], 32768);
		if ($data === false || ($data === "" && feof($c["sock"]))) {
			$this->drop($id);
			return;
		}
		$c["last"] = time();
		$c["in"] .= $data;
		$this->tryParse($id);
	}

	private function tryParse($id) {
		$c = &$this->conns[$id];
		$p = strpos($c["in"], "\r\n\r\n");
		if ($p === false) {
			if (strlen($c["in"]) > self::MAX_HEADER) {
				$this->respondJson($id, 431, ["ok" => false, "error" => "headers too large"], false);
			}
			return;
		}
		$head = substr($c["in"], 0, $p);
		$lines = explode("\r\n", $head);
		$first = explode(" ", array_shift($lines));
		if (count($first) < 3) {
			$this->respondJson($id, 400, ["ok" => false, "error" => "bad request"], false);
			return;
		}
		$method = strtoupper($first[0]);
		$target = $first[1];
		$proto = strtoupper($first[2]);
		$h = [];
		foreach ($lines as $l) {
			$k = strpos($l, ":");
			if ($k !== false) {
				$h[strtolower(trim(substr($l, 0, $k)))] = trim(substr($l, $k + 1));
			}
		}
		$len = isset($h["content-length"]) ? (int) $h["content-length"] : 0;
		if ($len < 0 || $len > self::MAX_BODY) {
			$this->respondJson($id, 413, ["ok" => false, "error" => "body too large"], false);
			return;
		}
		if (strlen($c["in"]) < $p + 4 + $len) {
			return; // ждём тело
		}
		$body = substr($c["in"], $p + 4, $len);
		$c["in"] = (string) substr($c["in"], $p + 4 + $len);
		$conn = isset($h["connection"]) ? strtolower($h["connection"]) : "";
		$keep = ($proto === "HTTP/1.1") ? ($conn !== "close") : ($conn === "keep-alive");
		$c["count"]++;
		if ($c["count"] >= self::KEEPALIVE_MAX) {
			$keep = false;
		}
		$c["keep"] = $keep;
		$this->served++;
		try {
			$this->route($id, $method, $target, $h, $body);
		} catch (\Throwable $e) {
			$this->log("[StoryCraft] Ошибка обработки " . $target . ": " . $e->getMessage());
			$this->respondJson($id, 500, ["ok" => false, "error" => "internal"], false);
		}
	}

	private static $STATUS = [200 => "OK", 206 => "Partial Content", 304 => "Not Modified", 400 => "Bad Request",
		401 => "Unauthorized", 404 => "Not Found", 405 => "Method Not Allowed", 413 => "Payload Too Large",
		416 => "Range Not Satisfiable", 431 => "Request Header Fields Too Large", 500 => "Internal Server Error",
		503 => "Service Unavailable"];

	private function rawResponse($code, array $headers, $body, $keep, $bodyLen = null) {
		$s = "HTTP/1.1 " . $code . " " . (isset(self::$STATUS[$code]) ? self::$STATUS[$code] : "OK") . "\r\n";
		$headers["Server"] = "StoryCraft";
		$headers["Access-Control-Allow-Origin"] = "*";
		$headers["Connection"] = $keep ? "keep-alive" : "close";
		if (!isset($headers["Content-Length"])) {
			$headers["Content-Length"] = $bodyLen !== null ? $bodyLen : strlen($body);
		}
		foreach ($headers as $k => $v) {
			$s .= $k . ": " . $v . "\r\n";
		}
		return $s . "\r\n" . $body;
	}

	private function respond($id, $code, array $headers, $body, $head = false) {
		$c = &$this->conns[$id];
		$c["out"] .= $this->rawResponse($code, $headers, $head ? "" : $body, $c["keep"], strlen($body));
	}

	private function respondJson($id, $code, $obj, $keep = null) {
		if ($keep === false && isset($this->conns[$id])) {
			$this->conns[$id]["keep"] = false;
			$this->conns[$id]["in"] = "";
		}
		$json = is_string($obj) ? $obj : json_encode($obj, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
		$this->respond($id, $code, ["Content-Type" => "application/json; charset=utf-8", "Cache-Control" => "no-cache"], $json);
	}

	private function route($id, $method, $target, array $h, $body) {
		$q = strpos($target, "?");
		$path = rawurldecode($q === false ? $target : substr($target, 0, $q));
		$isHead = ($method === "HEAD");
		if ($path === "/api/ping" || $path === "/" ) {
			if ($method !== "GET" && !$isHead) {
				$this->respondJson($id, 405, ["ok" => false, "error" => "method"]);
				return;
			}
			$this->respondJson($id, 200, ["ok" => true, "name" => "StoryCraft", "api" => 1, "time" => time(),
				"catalog" => $this->catalog->version]);
			return;
		}
		if ($path === "/api/catalog") {
			$etag = '"' . $this->catalog->version . '"';
			$inm = isset($h["if-none-match"]) ? trim($h["if-none-match"]) : "";
			if ($inm !== "" && ($inm === $etag || trim($inm, '"') === (string) $this->catalog->version)) {
				$this->respond($id, 304, ["ETag" => $etag], "", true);
				return;
			}
			$this->respond($id, 200, ["Content-Type" => "application/json; charset=utf-8", "ETag" => $etag,
				"Cache-Control" => "no-cache"], $this->catalog->json, $isHead);
			return;
		}
		if (strpos($path, "/api/pack/") === 0) {
			if ($method !== "GET" && !$isHead) {
				$this->respondJson($id, 405, ["ok" => false, "error" => "method"]);
				return;
			}
			$parts = explode("/", substr($path, 10));
			$pack = count($parts) >= 2 ? $this->catalog->pack($parts[0], $parts[1]) : null;
			if ($pack === null || !is_file($pack["path"])) {
				$this->respondJson($id, 404, ["ok" => false, "error" => "no pack"]);
				return;
			}
			if (count($parts) === 3 && $parts[2] === "index") {
				$idx = $pack["index"];
				$idx["data_offset"] = $pack["data_offset"];
				$idx["pack_size"] = $pack["size"];
				$idx["ok"] = true;
				$this->respondJson($id, 200, $idx);
				return;
			}
			if (count($parts) === 2) {
				$this->serveAbs($id, $pack["path"], $h, $isHead);
				return;
			}
			$this->respondJson($id, 404, ["ok" => false, "error" => "not found"]);
			return;
		}
		if (strpos($path, "/files/") === 0) {
			if ($method !== "GET" && !$isHead) {
				$this->respondJson($id, 405, ["ok" => false, "error" => "method"]);
				return;
			}
			$this->serveFile($id, substr($path, 7), $h, $isHead);
			return;
		}
		if ($path === "/api/register") {
			if ($method !== "POST") {
				$this->respondJson($id, 405, ["ok" => false, "error" => "method"]);
				return;
			}
			$j = json_decode($body, true);
			$dev = is_array($j) && isset($j["device_id"]) ? $j["device_id"] : "";
			if (!Progress::validId($dev)) {
				$this->respondJson($id, 400, ["ok" => false, "error" => "bad device_id"]);
				return;
			}
			$tok = $this->progress->register($dev, isset($j["model"]) ? $j["model"] : "", isset($j["app_version"]) ? $j["app_version"] : "");
			$this->respondJson($id, 200, ["ok" => true, "token" => $tok]);
			return;
		}
		if ($path === "/api/progress") {
			$dev = isset($h["x-device"]) ? $h["x-device"] : "";
			$tok = isset($h["x-token"]) ? $h["x-token"] : "";
			$p = $this->progress->auth($dev, $tok);
			if ($p === null) {
				$this->respondJson($id, 401, ["ok" => false, "error" => "unauthorized"]);
				return;
			}
			if ($method === "GET") {
				$this->respondJson($id, 200, ["ok" => true, "progress" => Progress::view($p)]);
				return;
			}
			if ($method === "POST") {
				$r = $this->progress->event($dev, json_decode($body, true));
				if (!$r[0]) {
					$this->respondJson($id, 400, ["ok" => false, "error" => $r[1]]);
					return;
				}
				$this->respondJson($id, 200, ["ok" => true, "progress" => $r[1]]);
				return;
			}
			$this->respondJson($id, 405, ["ok" => false, "error" => "method"]);
			return;
		}
		$this->respondJson($id, 404, ["ok" => false, "error" => "not found"]);
	}

	private static function mime($p) {
		$ext = strtolower(pathinfo($p, PATHINFO_EXTENSION));
		$m = ["json" => "application/json; charset=utf-8", "png" => "image/png", "jpg" => "image/jpeg",
			"jpeg" => "image/jpeg", "ogg" => "audio/ogg", "glb" => "model/gltf-binary", "txt" => "text/plain; charset=utf-8",
			"wav" => "audio/wav", "mp3" => "audio/mpeg"];
		return isset($m[$ext]) ? $m[$ext] : "application/octet-stream";
	}

	private function serveFile($id, $rest, array $h, $isHead) {
		$slash = strpos($rest, "/");
		if ($slash === false) {
			$this->respondJson($id, 404, ["ok" => false, "error" => "not found"]);
			return;
		}
		$abs = $this->catalog->resolve(substr($rest, 0, $slash), substr($rest, $slash + 1));
		if ($abs === null) {
			$this->respondJson($id, 404, ["ok" => false, "error" => "not found"]);
			return;
		}
		$this->serveAbs($id, $abs, $h, $isHead);
	}

	/** Отдать файл с диска: ETag, If-None-Match, Range (докачка и параллельные части). */
	private function serveAbs($id, $abs, array $h, $isHead) {
		$size = (int) filesize($abs);
		$sha = $this->catalog->sha1Of($abs);
		$etag = '"' . $sha . '"';
		$base = ["Content-Type" => self::mime($abs), "ETag" => $etag, "Accept-Ranges" => "bytes",
			"Cache-Control" => "no-cache"];
		$inm = isset($h["if-none-match"]) ? $h["if-none-match"] : "";
		if ($inm !== "" && ($inm === $etag || trim($inm, '"') === $sha) && !isset($h["range"])) {
			$this->respond($id, 304, ["ETag" => $etag], "", true);
			return;
		}
		$start = 0;
		$end = $size - 1;
		$code = 200;
		if (isset($h["range"])) {
			// If-Range: если ETag не совпал — отдаём файл целиком
			$ifRange = isset($h["if-range"]) ? $h["if-range"] : "";
			if ($ifRange === "" || $ifRange === $etag) {
				if (!preg_match('/^bytes=(\d*)-(\d*)$/', trim($h["range"]), $m) || ($m[1] === "" && $m[2] === "")) {
					$this->respond($id, 416, ["Content-Range" => "bytes */" . $size], "", true);
					return;
				}
				if ($m[1] === "") {
					$n = (int) $m[2];
					$start = max(0, $size - $n);
				} else {
					$start = (int) $m[1];
					if ($m[2] !== "") {
						$end = min($end, (int) $m[2]);
					}
				}
				if ($start >= $size || $start > $end) {
					$this->respond($id, 416, ["Content-Range" => "bytes */" . $size], "", true);
					return;
				}
				$code = 206;
				$base["Content-Range"] = "bytes " . $start . "-" . $end . "/" . $size;
			}
		}
		$len = $size > 0 ? $end - $start + 1 : 0;
		$base["Content-Length"] = $len;
		$c = &$this->conns[$id];
		$c["out"] .= $this->rawResponse($code, $base, "", $c["keep"]);
		if ($isHead || $len === 0) {
			return;
		}
		$f = @fopen($abs, "rb");
		if ($f === false) {
			$c["keep"] = false;
			return;
		}
		if ($start > 0) {
			fseek($f, $start);
		}
		$c["file"] = $f;
		$c["fileLeft"] = $len;
	}

	private function onWritable($id) {
		if (!isset($this->conns[$id])) {
			return;
		}
		$c = &$this->conns[$id];
		// пишем, пока сокет принимает данные (до WRITE_PER_TICK за тик), а не один кусок
		$budget = self::WRITE_PER_TICK;
		while ($budget > 0) {
			if ($c["out"] === "" && $c["file"] !== null) {
				$chunk = fread($c["file"], min(self::CHUNK, $c["fileLeft"]));
				if ($chunk === false || $chunk === "") {
					fclose($c["file"]);
					$c["file"] = null;
					$c["keep"] = false;
				} else {
					$c["out"] = $chunk;
					$c["fileLeft"] -= strlen($chunk);
					if ($c["fileLeft"] <= 0) {
						fclose($c["file"]);
						$c["file"] = null;
					}
				}
			}
			if ($c["out"] === "") {
				break;
			}
			$w = @fwrite($c["sock"], $c["out"]);
			if ($w === false) {
				$this->drop($id);
				return;
			}
			if ($w === 0) {
				break;   // буфер сокета полон — продолжим на следующем тике
			}
			$c["out"] = (string) substr($c["out"], $w);
			$c["last"] = time();
			$budget -= $w;
		}
		if ($c["out"] === "" && $c["file"] === null) {
			if (!$c["keep"]) {
				$this->drop($id);
				return;
			}
			if ($c["in"] !== "") {
				$this->tryParse($id);
			}
		}
	}
}
