extends Node
## Прогресс игрока: device_id, регистрация на сервере (token), синхронизация прогресса.
## Локальная копия в user://progress.json; события, не дошедшие до сервера, копятся в очереди
## и уходят, когда появится связь.

const DEVICE_PATH := "user://device.cfg"
const DATA_PATH := "user://progress.json"
const QUEUE_PATH := "user://progress_queue.json"

signal updated

var device_id := ""
var aux_id := ""          # OS.get_unique_id() — вспомогательный
var token := ""
var data := {"adventures": {}}
var _queue: Array = []
var _flushing := false

func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	_load_device()
	_load_local()
	# раз в 15 с пробуем отправить очередь
	var t := Timer.new()
	t.wait_time = 15.0
	t.autostart = true
	t.timeout.connect(flush)
	add_child(t)

func _load_device() -> void:
	var cf := ConfigFile.new()
	cf.load(DEVICE_PATH)
	device_id = cf.get_value("device", "id", "")
	token = cf.get_value("device", "token", "")
	if device_id == "":
		device_id = _uuid4()
		cf.set_value("device", "id", device_id)
		cf.save(DEVICE_PATH)
	aux_id = OS.get_unique_id()

func _save_device() -> void:
	var cf := ConfigFile.new()
	cf.set_value("device", "id", device_id)
	cf.set_value("device", "token", token)
	cf.save(DEVICE_PATH)

static func _uuid4() -> String:
	var b := PackedByteArray()
	var rng := RandomNumberGenerator.new()
	rng.randomize()
	var c := Crypto.new()
	b = c.generate_random_bytes(16)
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	var h := b.hex_encode()
	return "%s-%s-%s-%s-%s" % [h.substr(0, 8), h.substr(8, 4), h.substr(12, 4), h.substr(16, 4), h.substr(20, 12)]

func _load_local() -> void:
	var j = _read_json(DATA_PATH)
	if j is Dictionary:
		data = j
	if not data.get("adventures") is Dictionary:
		data["adventures"] = {}
	var q = _read_json(QUEUE_PATH)
	if q is Array:
		_queue = q

func _read_json(path: String):
	if not FileAccess.file_exists(path):
		return null
	return JSON.parse_string(FileAccess.get_file_as_string(path))

func _write_json(path: String, v) -> void:
	var f := FileAccess.open(path + ".tmp", FileAccess.WRITE)
	if f == null:
		return
	f.store_string(JSON.stringify(v))
	f.close()
	DirAccess.rename_absolute(path + ".tmp", path)

func _auth_headers() -> PackedStringArray:
	return PackedStringArray(["X-Device: " + device_id, "X-Token: " + token])

## Регистрация устройства: POST /api/register → token (повторно сервер выдаёт тот же).
func register() -> bool:
	var r := await Net.post_json("/api/register", {
		"device_id": device_id,
		"model": OS.get_model_name(),
		"app_version": ProjectSettings.get_setting("application/config/version", "1.0.0"),
	})
	if r["ok"] and r["json"] is Dictionary and r["json"].get("token", "") != "":
		token = r["json"]["token"]
		_save_device()
		return true
	return false

## Загрузить прогресс с сервера (сначала отправив очередь).
func fetch() -> bool:
	if token == "" and not await register():
		return false
	await flush()
	var r := await Net.get_json("/api/progress", _auth_headers())
	if r["code"] == 401:
		# сервер потерял устройство — регистрируемся заново
		if await register():
			r = await Net.get_json("/api/progress", _auth_headers())
	if r["ok"] and r["json"] is Dictionary and r["json"].get("progress") is Dictionary:
		# события в очереди ещё не дошли — применяем их поверх
		data = r["json"]["progress"]
		if not data.get("adventures") is Dictionary:
			data["adventures"] = {}
		for ev in _queue:
			_apply_local(ev)
		_write_json(DATA_PATH, data)
		updated.emit()
		return true
	return false

## Отправить событие (checkpoint / complete / reset): локально сразу, на сервер — через очередь.
func send_event(ev: Dictionary) -> void:
	_apply_local(ev)
	_write_json(DATA_PATH, data)
	_queue.append(ev)
	_write_json(QUEUE_PATH, _queue)
	updated.emit()
	flush()

func flush() -> void:
	if _flushing or _queue.is_empty():
		return
	_flushing = true
	if token == "" and not await register():
		_flushing = false
		return
	while not _queue.is_empty():
		var ev = _queue[0]
		var r := await Net.post_json("/api/progress", ev, _auth_headers(), 6.0)
		if r["code"] == 401:
			if not await register():
				break
			continue
		if r["ok"] or r["code"] == 400:
			# 400 — событие отвергнуто сервером, повторять бессмысленно
			_queue.pop_front()
			_write_json(QUEUE_PATH, _queue)
		else:
			break
	_flushing = false

## Та же логика, что на сервере (Progress.php), чтобы игра работала без ожидания ответа.
func _apply_local(ev: Dictionary) -> void:
	var adv := String(ev.get("adventure", ""))
	if adv == "":
		return
	var advs: Dictionary = data["adventures"]
	var a: Dictionary = advs.get(adv, {}) if advs.get(adv) is Dictionary else {}
	if not a.get("completed") is Dictionary:
		a["completed"] = {}
	if not a.get("vars") is Dictionary:
		a["vars"] = {}
	var ep := String(ev.get("episode", ""))
	match String(ev.get("type", "")):
		"checkpoint":
			a["resume"] = {"episode": ep, "node": ev.get("node", ""), "vars": ev.get("vars", {}),
				"adv_vars": ev.get("adv_vars", {}), "at": int(Time.get_unix_time_from_system())}
		"complete":
			a["completed"][ep] = {"ending": ev.get("ending", ""), "at": int(Time.get_unix_time_from_system())}
			var av = ev.get("adv_vars", {})
			if av is Dictionary:
				for k in av:
					a["vars"][k] = av[k]
			if a.get("resume") is Dictionary and a["resume"].get("episode", "") == ep:
				a.erase("resume")
		"reset":
			a = {"completed": {}, "vars": {}}
	advs[adv] = a

# ---------------------------------------------------------------- запросы

func adventure(adv: String) -> Dictionary:
	var a = data["adventures"].get(adv)
	return a if a is Dictionary else {}

func is_completed(adv: String, ep: String) -> bool:
	var c = adventure(adv).get("completed", {})
	return c is Dictionary and c.has(ep)

func ending_of(adv: String, ep: String) -> String:
	var c = adventure(adv).get("completed", {})
	if c is Dictionary and c.get(ep) is Dictionary:
		return String(c[ep].get("ending", ""))
	return ""

func adv_vars(adv: String) -> Dictionary:
	var v = adventure(adv).get("vars", {})
	return v.duplicate(true) if v is Dictionary else {}

func resume_of(adv: String) -> Dictionary:
	var r = adventure(adv).get("resume")
	return r if r is Dictionary else {}

## Серия N доступна, если она первая или пройдена предыдущая (episodes — отсортированы по number).
func is_unlocked(adv: String, episodes: Array, index: int) -> bool:
	if index <= 0:
		return true
	return is_completed(adv, String(episodes[index - 1].get("id", "")))
