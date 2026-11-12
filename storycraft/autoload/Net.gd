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
	req.use_threads = true
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
	var downloaded := 0      # байт текущего файла
	var req: HTTPRequest = null

	func abort() -> void:
		cancel = true
		if req != null and is_instance_valid(req):
			req.cancel_request()

## Скачать файл в dest (через .part, с докачкой). Возвращает "" при успехе или текст ошибки.
func download(path: String, dest: String, dl: Download, attempts := 3) -> String:
	DirAccess.make_dir_recursive_absolute(dest.get_base_dir())
	var part := dest + ".part"
	var last_err := ""
	for attempt in attempts:
		if dl.cancel:
			return "cancel"
		var have := 0
		if FileAccess.file_exists(part):
			var f := FileAccess.open(part, FileAccess.READ)
			if f:
				have = int(f.get_length())
				f.close()
		var tmp := dest + ".chunk"
		var req := HTTPRequest.new()
		req.use_threads = true
		req.timeout = 30.0
		req.download_chunk_size = 65536
		req.download_file = tmp
		add_child(req)
		dl.req = req
		var hs := PackedStringArray()
		if have > 0:
			hs.append("Range: bytes=%d-" % have)
		var err := req.request(url(path), hs)
		if err != OK:
			req.queue_free()
			last_err = "request %d" % err
			continue
		# прогресс
		var done := [false]
		var poll := func():
			while not done[0] and is_instance_valid(req):
				dl.downloaded = have + req.get_downloaded_bytes()
				await get_tree().process_frame
		poll.call()
		var res: Array = await req.request_completed
		done[0] = true
		dl.downloaded = 0
		req.queue_free()
		dl.req = null
		if dl.cancel:
			DirAccess.remove_absolute(tmp)
			return "cancel"
		var result: int = res[0]
		var code: int = res[1]
		if result != HTTPRequest.RESULT_SUCCESS or not (code == 200 or code == 206):
			DirAccess.remove_absolute(tmp)
			if code == 416:
				DirAccess.remove_absolute(part)   # .part битый — качаем заново
			last_err = "HTTP %d / %d" % [code, result]
			await get_tree().create_timer(0.5 * (attempt + 1)).timeout
			continue
		if code == 206 and have > 0:
			_append_file(part, tmp)
			DirAccess.remove_absolute(tmp)
		else:
			DirAccess.remove_absolute(part)
			DirAccess.rename_absolute(tmp, part)
		if FileAccess.file_exists(dest):
			DirAccess.remove_absolute(dest)
		DirAccess.rename_absolute(part, dest)
		return ""
	return last_err if last_err != "" else "error"

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
