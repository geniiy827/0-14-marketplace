extends Node
## Сеть: базовый URL сервера (в одном месте), HTTP-запросы с таймаутами и повторами, проверка связи,
## скачивание файлов с докачкой (Range) и прогрессом.

## Адрес сервера по умолчанию. Обычный HTTP без TLS — в Android-экспорте разрешён cleartext-трафик.
const DEFAULT_URL := "http://nova-host.mcbe.in:12055"
const PING_TIMEOUT := 5.0
const PING_ATTEMPTS := 2

signal online_changed(online: bool)

var online := false
var _checking := false

func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS

func base_url() -> String:
	var u := String(Settings.get_value("server_url", "")).strip_edges()
	if u == "":
		u = DEFAULT_URL
	if not u.begins_with("http://") and not u.begins_with("https://"):
		u = "http://" + u
	return u.trim_suffix("/")

func url(path: String) -> String:
	if path.begins_with("http://") or path.begins_with("https://"):
		return path
	return base_url() + ("" if path.begins_with("/") else "/") + path

func _set_online(v: bool) -> void:
	if v != online:
		online = v
		online_changed.emit(v)

## Один HTTP-запрос. Возвращает {"ok", "code", "json", "body", "headers", "error"}.
func request(method: int, path: String, body = null, headers: PackedStringArray = PackedStringArray(), timeout := 8.0) -> Dictionary:
	var req := HTTPRequest.new()
	req.timeout = timeout
	req.use_threads = false   # таймаут потокового запроса подвешивал главный поток (см. _fetch)
	add_child(req)
	var data := ""
	var hs := PackedStringArray(headers)
	if body != null:
		data = body if body is String else JSON.stringify(body)
		hs.append("Content-Type: application/json")
	var err := req.request(url(path), hs, method, data)
	if err != OK:
		req.queue_free()
		return {"ok": false, "code": 0, "error": "request %d" % err, "json": null, "body": PackedByteArray(), "headers": PackedStringArray()}
	var res: Array = await req.request_completed
	req.queue_free()
	var result: int = res[0]
	var code: int = res[1]
	var raw: PackedByteArray = res[3]
	var out := {"ok": result == HTTPRequest.RESULT_SUCCESS and code >= 200 and code < 300,
		"code": code, "result": result, "body": raw, "headers": res[2], "json": null, "error": ""}
	if result != HTTPRequest.RESULT_SUCCESS:
		out["error"] = "network %d" % result
	elif raw.size() > 0:
		var j = JSON.parse_string(raw.get_string_from_utf8())
		out["json"] = j
	return out

func get_json(path: String, headers := PackedStringArray(), timeout := 8.0) -> Dictionary:
	return await request(HTTPClient.METHOD_GET, path, null, headers, timeout)

func post_json(path: String, body, headers := PackedStringArray(), timeout := 8.0) -> Dictionary:
	return await request(HTTPClient.METHOD_POST, path, body, headers, timeout)

## Проверка связи: GET /api/ping, таймаут 5 с, 2 попытки.
func ping() -> bool:
	for i in PING_ATTEMPTS:
		var r := await get_json("/api/ping", PackedStringArray(), PING_TIMEOUT)
		if r["ok"] and r["json"] is Dictionary and r["json"].get("ok", false):
			_set_online(true)
			return true
	_set_online(false)
	return false

## Гарантировать связь: если ping не прошёл — неснимаемое окно «Нет подключения» до успешной проверки.
func ensure_online() -> void:
	if await ping():
		return
	while true:
		await Router.show_no_internet()
		if await ping():
			Router.hide_no_internet()
			return

# ---------------------------------------------------------------- скачивание

## Объект загрузки: можно отменить (cancel = true), прогресс — в downloaded.
class Download:
	extends RefCounted
	var cancel := false
	var downloaded := 0      # байт текущего файла (одиночная загрузка)
	var req: HTTPRequest = null
	var reqs := {}           # слот → HTTPRequest (параллельные потоки)
	var slot_bytes := {}     # слот → скачано байт этим потоком

	func total_slots() -> int:
		var n := 0
		for k in slot_bytes:
			n += int(slot_bytes[k])
		return n

	func abort() -> void:
		cancel = true
		if req != null and is_instance_valid(req):
			req.cancel_request()
		for k in reqs:
			if is_instance_valid(reqs[k]):
				reqs[k].cancel_request()

## Сколько секунд без единого байта считаем «зависшим» соединением (мобильная сеть) — рвём и докачиваем.
const STALL_SEC := 6.0

## Один HTTP-запрос с записью тела в tmp. Сторожевой таймер: нет данных STALL_SEC — запрос рвём.
## on_bytes(n) — сколько байт тела получено. Возвращает [result, code].
func _fetch(path: String, tmp: String, hs: PackedStringArray, dl: Download, slot: int, on_bytes: Callable) -> Array:
	var req := HTTPRequest.new()
	# БЕЗ потока: отмена «зависшего» запроса в потоковом HTTPRequest ждёт поток, застрявший
	# в чтении сокета, — главный поток замирал на 10–30 с («игра намертво висит на середине загрузки»).
	# Без потока отмена мгновенная, а чтение неблокирующее, по куску за кадр.
	req.use_threads = false
	req.timeout = 0.0              # общий таймаут не нужен — есть сторож по «тишине»
	req.download_chunk_size = 262144
	req.download_file = tmp
	add_child(req)
	if slot < 0:
		dl.req = req
	else:
		dl.reqs[slot] = req
	if req.request(url(path), hs) != OK:
		req.queue_free()
		return [HTTPRequest.RESULT_CANT_CONNECT, 0]
	var st := {"done": false, "res": []}
	req.request_completed.connect(func(r, c, _h, _b):
		if not st["done"]:
			st["done"] = true
			st["res"] = [r, c])
	var last_n := -1
	var last_t := Time.get_ticks_msec()
	while not st["done"]:
		await get_tree().process_frame
		if dl.cancel:
			req.cancel_request()
			st["done"] = true
			st["res"] = [HTTPRequest.RESULT_CANT_CONNECT, 0]
			break
		var n := req.get_downloaded_bytes()
		on_bytes.call(n)
		var now := Time.get_ticks_msec()
		if n != last_n:
			last_n = n
			last_t = now
		elif now - last_t > STALL_SEC * 1000.0:
			# связь «замерла» — не ждём, рвём и продолжаем с того же места
			req.cancel_request()
			st["done"] = true
			st["res"] = [HTTPRequest.RESULT_TIMEOUT, 0]
	if slot < 0:
		dl.req = null
	else:
		dl.reqs.erase(slot)
	req.queue_free()
	return st["res"]

## Скачать файл в dest (через .part, с докачкой). Возвращает "" при успехе или текст ошибки.
func download(path: String, dest: String, dl: Download, attempts := 6) -> String:
	DirAccess.make_dir_recursive_absolute(dest.get_base_dir())
	var part := dest + ".part"
	var tmp := dest + ".chunk"
	var last_err := ""
	for attempt in attempts:
		if dl.cancel:
			return "cancel"
		var have := _file_size(part)
		var hs := PackedStringArray()
		if have > 0:
			hs.append("Range: bytes=%d-" % have)
		DirAccess.remove_absolute(tmp)
		var r: Array = await _fetch(path, tmp, hs, dl, -1, func(n): dl.downloaded = have + n)
		dl.downloaded = 0
		if dl.cancel:
			DirAccess.remove_absolute(tmp)
			return "cancel"
		var result: int = r[0]
		var code: int = r[1]
		if code == 416:
			DirAccess.remove_absolute(part)   # .part битый — качаем заново
		# что успели получить — сохраняем (докачка со следующей попытки)
		_keep_chunk(part, tmp, have, code, result)
		if result == HTTPRequest.RESULT_SUCCESS and (code == 200 or code == 206):
			if FileAccess.file_exists(dest):
				DirAccess.remove_absolute(dest)
			DirAccess.rename_absolute(part, dest)
			return ""
		last_err = "HTTP %d / %d" % [code, result]
		await get_tree().create_timer(minf(0.4 * (attempt + 1), 2.0)).timeout
	return last_err if last_err != "" else "error"

## Принятый кусок дописать к .part. 206 — продолжение; 200 — файл целиком с начала.
func _keep_chunk(part: String, tmp: String, have: int, code: int, result: int) -> void:
	if not FileAccess.file_exists(tmp):
		return
	if code == 206 and have > 0:
		_append_file(part, tmp)
		DirAccess.remove_absolute(tmp)
	elif code == 200 or (code == 206 and have == 0):
		DirAccess.remove_absolute(part)
		DirAccess.rename_absolute(tmp, part)
	elif code == 0 and have >= 0 and result != HTTPRequest.RESULT_SUCCESS:
		# соединение оборвано/заморожено: заголовки уже были, тело частично записано —
		# для запроса с Range это продолжение, без Range — начало файла
		if have > 0:
			_append_file(part, tmp)
		else:
			DirAccess.rename_absolute(tmp, part)
		DirAccess.remove_absolute(tmp)
	else:
		DirAccess.remove_absolute(tmp)

## Скачать диапазон [start, end] файла path в dest (докачка: продолжает с уже скачанного в dest).
## Прогресс — в dl.slot_bytes[slot]. Возвращает "" или текст ошибки.
func download_range(path: String, dest: String, start: int, end: int, dl: Download, slot: int, attempts := 8) -> String:
	DirAccess.make_dir_recursive_absolute(dest.get_base_dir())
	var need := end - start + 1
	var tmp := dest + ".chunk"
	var last_err := ""
	for attempt in attempts:
		if dl.cancel:
			return "cancel"
		var have := _file_size(dest)
		if have >= need:
			dl.slot_bytes[slot] = need
			return ""
		DirAccess.remove_absolute(tmp)
		var hs := PackedStringArray(["Range: bytes=%d-%d" % [start + have, end]])
		var r: Array = await _fetch(path, tmp, hs, dl, slot, func(n): dl.slot_bytes[slot] = have + n)
		if dl.cancel:
			DirAccess.remove_absolute(tmp)
			return "cancel"
		var code: int = r[1]
		# 206 или обрыв посреди тела 206 — дописываем принятое; 200 (сервер не понял Range) — нельзя
		if code == 206 or (code == 0 and _file_size(tmp) > 0):
			var got := _file_size(tmp)
			if got > need - have:
				DirAccess.remove_absolute(tmp)   # лишнее — не наш диапазон
			elif have > 0:
				_append_file(dest, tmp)
			else:
				DirAccess.rename_absolute(tmp, dest)
		DirAccess.remove_absolute(tmp)
		have = _file_size(dest)
		dl.slot_bytes[slot] = have
		if have >= need:
			return ""
		last_err = "HTTP %d / %d" % [code, r[0]]
		await get_tree().create_timer(minf(0.3 * (attempt + 1), 1.5)).timeout
	return last_err if last_err != "" else "error"

static func _file_size(p: String) -> int:
	if not FileAccess.file_exists(p):
		return 0
	var f := FileAccess.open(p, FileAccess.READ)
	if f == null:
		return 0
	var n := int(f.get_length())
	f.close()
	return n

func _append_file(dst: String, src: String) -> void:
	var a := FileAccess.open(dst, FileAccess.READ_WRITE)
	var b := FileAccess.open(src, FileAccess.READ)
	if a == null or b == null:
		return
	a.seek_end()
	while b.get_position() < b.get_length():
		a.store_buffer(b.get_buffer(1 << 20))
	a.close()
	b.close()
