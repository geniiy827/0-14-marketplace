extends Node
## Контент: каталог приключений с сервера, скачивание серий «пакетом» в 5 параллельных потоков,
## обложки. Всё скачанное живёт только одну сессию: user://session/ удаляется при запуске и выходе.

const CACHE := "user://session"
const LEGACY_CACHE := "user://cache"     # кэш версии 1.0 — удаляем
const STREAMS := 5
const CHUNK := 524288           # кусок пакета для одного запроса (512 КБ)
const SUPPORTED_EPISODE_VERSION := 1

signal catalog_changed

var catalog := {"version": 0, "adventures": []}
var _manifest := {}            # путь в кэше → sha1 (что лежит на диске)
var _cover_cache := {}         # sha1 → Texture2D

func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS

## Новая сессия: стираем всё скачанное в прошлый раз (вызывается из Boot).
func reset_session() -> void:
	_remove_tree(LEGACY_CACHE)
	clear_cache()

func _read_json(path: String):
	if not FileAccess.file_exists(path):
		return null
	return JSON.parse_string(FileAccess.get_file_as_string(path))

func _save_manifest() -> void:
	var f := FileAccess.open(CACHE + "/manifest.json.tmp", FileAccess.WRITE)
	if f:
		f.store_string(JSON.stringify(_manifest))
		f.close()
		DirAccess.rename_absolute(CACHE + "/manifest.json.tmp", CACHE + "/manifest.json")

## Обновить каталог. Возвращает {"ok": bool, "changed": bool}.
func refresh() -> Dictionary:
	var hs := PackedStringArray()
	if int(catalog.get("version", 0)) > 0:
		hs.append('If-None-Match: "%d"' % int(catalog["version"]))
	var r := await Net.get_json("/api/catalog", hs)
	if r["code"] == 304:
		return {"ok": true, "changed": false}
	if not r["ok"] or not r["json"] is Dictionary:
		return {"ok": false, "changed": false}
	catalog = r["json"]
	if not catalog.get("adventures") is Array:
		catalog["adventures"] = []
	catalog_changed.emit()
	preload_covers()
	return {"ok": true, "changed": true}

func adventures() -> Array:
	return catalog.get("adventures", [])

func adventure(id: String) -> Dictionary:
	for a in adventures():
		if a.get("id", "") == id:
			return a
	return {}

func episodes(adv_id: String) -> Array:
	return adventure(adv_id).get("episodes", [])

func episode(adv_id: String, ep_id: String) -> Dictionary:
	for e in episodes(adv_id):
		if e.get("id", "") == ep_id:
			return e
	return {}

# ---------------------------------------------------------------- кэш серий

func local_path(adv_id: String, rel: String) -> String:
	return "%s/%s/%s" % [CACHE, adv_id, rel]

func episode_dir(adv_id: String, ep_id: String) -> String:
	return "%s/%s/episodes/%s" % [CACHE, adv_id, ep_id]

## Файлы серии, которых нет в кэше или которые изменились (по sha1).
func missing_files(adv_id: String, ep: Dictionary) -> Array:
	var out := []
	for f in ep.get("files", []):
		var lp := local_path(adv_id, f["path"])
		if _manifest.get(lp, "") != f.get("sha1", "") or not FileAccess.file_exists(lp):
			out.append(f)
	return out

func download_size(adv_id: String, ep: Dictionary) -> int:
	if is_cached(adv_id, ep):
		return 0
	if int(ep.get("pack_size", 0)) > 0:
		return int(ep["pack_size"])
	var n := 0
	for f in missing_files(adv_id, ep):
		n += int(f.get("size", 0))
	return n

func is_cached(adv_id: String, ep: Dictionary) -> bool:
	return missing_files(adv_id, ep).is_empty()

# ---------------------------------------------------------------- общие загрузки (фон + экран серий)

var _jobs := {}               # "adv/ep" → {"dl", "done", "total", "finished", "err"}

func _job_key(adv_id: String, ep_id: String) -> String:
	return adv_id + "/" + ep_id

## Состояние загрузки серии (пусто — не качается).
func job(adv_id: String, ep_id: String) -> Dictionary:
	return _jobs.get(_job_key(adv_id, ep_id), {})

## Начать загрузку серии в фоне (если ещё не скачана и не качается).
func start_background(adv_id: String, ep: Dictionary) -> void:
	if is_cached(adv_id, ep) or not job(adv_id, String(ep.get("id", ""))).is_empty():
		return
	_run_job(adv_id, ep)

func _run_job(adv_id: String, ep: Dictionary) -> Dictionary:
	var key := _job_key(adv_id, String(ep.get("id", "")))
	var j := {"dl": Net.Download.new(), "done": 0, "total": 1, "finished": false, "err": ""}
	_jobs[key] = j
	_job_body(key, adv_id, ep, j)
	return j

func _job_body(key: String, adv_id: String, ep: Dictionary, j: Dictionary) -> void:
	var err: String = await download_episode(adv_id, ep, j["dl"], func(d, t):
		j["done"] = d
		j["total"] = maxi(1, t))
	j["err"] = err
	j["finished"] = true
	_jobs.erase(key)

## Скачать серию с прогрессом; если она уже качается в фоне — присоединиться к этой загрузке.
## cancel_dl — внешний объект отмены (кнопка «Отмена» в окне). Возвращает "" или текст ошибки.
func download_shared(adv_id: String, ep: Dictionary, cancel_dl, on_progress: Callable) -> String:
	if is_cached(adv_id, ep):
		on_progress.call(1, 1)
		return ""
	var j := job(adv_id, String(ep.get("id", "")))
	if j.is_empty():
		j = _run_job(adv_id, ep)
	while not j["finished"]:
		if cancel_dl.cancel:
			j["dl"].abort()
		on_progress.call(j["done"], j["total"])
		await get_tree().process_frame
	return j["err"]

## Следующая серия приключения после ep_id (или пусто).
func next_episode(adv_id: String, ep_id: String) -> Dictionary:
	var eps := episodes(adv_id)
	for i in eps.size():
		if eps[i].get("id", "") == ep_id and i + 1 < eps.size():
			return eps[i + 1]
	return {}

## Скачать серию. on_progress(done_bytes, total_bytes). Возвращает "" или текст ошибки.
## Сначала — пакет серии в 5 параллельных потоков (Range); если сервер старый (нет /api/pack) —
## отдельные файлы, тоже по 5 одновременно.
func download_episode(adv_id: String, ep: Dictionary, dl, on_progress: Callable) -> String:
	if is_cached(adv_id, ep):
		on_progress.call(1, 1)
		return ""
	var ep_id := String(ep.get("id", ""))
	var r := await Net.get_json("/api/pack/%s/%s/index" % [adv_id.uri_encode(), ep_id.uri_encode()], PackedStringArray(), 10.0)
	if r["ok"] and r["json"] is Dictionary and int(r["json"].get("pack_size", 0)) > 0:
		return await _download_pack(adv_id, ep, r["json"], dl, on_progress)
	if r["code"] != 404 and r["code"] != 0 and not r["ok"]:
		return "HTTP %d" % r["code"]
	if r["code"] == 0:
		return "нет связи"
	return await _download_files(adv_id, ep, dl, on_progress)

func _download_pack(adv_id: String, ep: Dictionary, idx: Dictionary, dl, on_progress: Callable) -> String:
	var ep_id := String(ep.get("id", ""))
	var size := int(idx["pack_size"])
	var url := "/api/pack/%s/%s" % [adv_id.uri_encode(), ep_id.uri_encode()]
	var tmp_dir := "%s/_dl/%s_%s" % [CACHE, adv_id, ep_id]
	DirAccess.make_dir_recursive_absolute(tmp_dir)
	# пакет режем на куски по CHUNK и качаем 5 потоками из общей очереди: медленный/оборванный кусок
	# маленький и перезапрашивается сам, остальные потоки тем временем берут следующие куски
	var chunks := int(ceil(float(size) / CHUNK))
	var st := {"next": 0, "err": "", "running": 0, "done_bytes": 0}
	for slot in mini(STREAMS, chunks):
		dl.slot_bytes[slot] = 0
		_chunk_worker(url, tmp_dir, size, chunks, st, dl, slot)
	while st["running"] > 0:
		on_progress.call(st["done_bytes"] + dl.total_slots(), size)
		await get_tree().process_frame
	if dl.cancel:
		return "cancel"
	if st["err"] != "":
		return st["err"]
	on_progress.call(size, size)
	# склейка частей
	var pack_path := tmp_dir + "/pack.bin"
	var out := FileAccess.open(pack_path, FileAccess.WRITE)
	if out == null:
		return "не удалось записать пакет"
	for i in chunks:
		var pf := FileAccess.open("%s/part%d" % [tmp_dir, i], FileAccess.READ)
		if pf:
			while pf.get_position() < pf.get_length():
				out.store_buffer(pf.get_buffer(1 << 20))
			pf.close()
	out.close()
	# распаковка в потоке: gzip каждого файла + сверка sha1
	var dir := episode_dir(adv_id, ep_id)
	var holder := [""]
	var task := WorkerThreadPool.add_task(func(): holder[0] = _unpack(pack_path, idx, dir), false, "unpack " + ep_id)
	while not WorkerThreadPool.is_task_completed(task):
		await get_tree().process_frame
	WorkerThreadPool.wait_for_task_completion(task)
	_remove_tree(tmp_dir)
	if holder[0] != "":
		return holder[0]
	for f in idx.get("files", []):
		_manifest[local_path(adv_id, "episodes/%s/%s" % [ep_id, f["path"]])] = String(f.get("sha1", ""))
	# файлы из каталога, которых нет в пакете (на всякий случай) — докачиваем по одному
	_save_manifest()
	if not is_cached(adv_id, ep):
		return await _download_files(adv_id, ep, dl, on_progress)
	return ""

func _chunk_worker(url: String, tmp_dir: String, size: int, chunks: int, st: Dictionary, dl, slot: int) -> void:
	st["running"] += 1
	while st["next"] < chunks and st["err"] == "" and not dl.cancel:
		var i: int = st["next"]
		st["next"] += 1
		var a := i * CHUNK
		var b := mini(size, a + CHUNK) - 1
		var err: String = await Net.download_range(url, "%s/part%d" % [tmp_dir, i], a, b, dl, slot)
		dl.slot_bytes[slot] = 0
		if err != "":
			if st["err"] == "":
				st["err"] = err
			break
		st["done_bytes"] += b - a + 1
	st["running"] -= 1

## Распаковать пакет (вызывается из потока). Возвращает "" или текст ошибки.
static func _unpack(pack_path: String, idx: Dictionary, dir: String) -> String:
	var f := FileAccess.open(pack_path, FileAccess.READ)
	if f == null:
		return "пакет не открывается"
	var base := int(idx.get("data_offset", 0))
	for e in idx.get("files", []):
		var rel := String(e.get("path", ""))
		if rel == "" or rel.begins_with("/") or rel.contains(".."):
			continue
		f.seek(base + int(e["offset"]))
		var z := f.get_buffer(int(e["zsize"]))
		var raw := z.decompress_dynamic(-1, FileAccess.COMPRESSION_GZIP)
		if raw.size() != int(e.get("size", raw.size())):
			f.close()
			return "файл повреждён: " + rel
		var ctx := HashingContext.new()
		ctx.start(HashingContext.HASH_SHA1)
		ctx.update(raw)
		if e.has("sha1") and ctx.finish().hex_encode() != String(e["sha1"]):
			f.close()
			return "контрольная сумма не совпала: " + rel
		var out_path := dir + "/" + rel
		DirAccess.make_dir_recursive_absolute(out_path.get_base_dir())
		var o := FileAccess.open(out_path, FileAccess.WRITE)
		if o == null:
			f.close()
			return "не удалось записать: " + rel
		o.store_buffer(raw)
		o.close()
	f.close()
	return ""

## Старый сервер без пакетов: отдельные файлы, по 5 одновременно.
func _download_files(adv_id: String, ep: Dictionary, dl, on_progress: Callable) -> String:
	var need := missing_files(adv_id, ep)
	var total := 0
	for f in need:
		total += int(f.get("size", 0))
	var st := {"done": 0, "next": 0, "err": "", "running": 0}
	for slot in mini(STREAMS, need.size()):
		dl.slot_bytes[slot] = 0
		_file_worker(adv_id, need, st, dl, slot)
	while st["running"] > 0 or (st["next"] < need.size() and st["err"] == "" and not dl.cancel):
		on_progress.call(st["done"] + dl.total_slots(), total)
		await get_tree().process_frame
	_save_manifest()
	if dl.cancel:
		return "cancel"
	if st["err"] != "":
		return st["err"]
	on_progress.call(total, total)
	return ""

func _file_worker(adv_id: String, need: Array, st: Dictionary, dl, slot: int) -> void:
	st["running"] += 1
	var one = Net.Download.new()
	while st["next"] < need.size() and st["err"] == "" and not dl.cancel:
		var f: Dictionary = need[st["next"]]
		st["next"] += 1
		var lp := local_path(adv_id, f["path"])
		var parts := PackedStringArray()
		for seg in String(f["path"]).split("/"):
			parts.append(seg.uri_encode())
		one.downloaded = 0
		var watch := [true]
		var mirror := func():
			while watch[0]:
				dl.slot_bytes[slot] = one.downloaded
				if dl.cancel:
					one.abort()
				await get_tree().process_frame
		mirror.call()
		var err: String = await Net.download("/files/%s/%s" % [adv_id.uri_encode(), "/".join(parts)], lp, one)
		watch[0] = false
		dl.slot_bytes[slot] = 0
		if err == "" and f.get("sha1", "") != "" and sha1_file(lp) != f["sha1"]:
			err = "контрольная сумма не совпала: " + String(f["path"])
			DirAccess.remove_absolute(lp)
		if err != "":
			if st["err"] == "":
				st["err"] = err
			break
		_manifest[lp] = f.get("sha1", "")
		st["done"] += int(f.get("size", 0))
	st["running"] -= 1

static func sha1_file(path: String) -> String:
	var f := FileAccess.open(path, FileAccess.READ)
	if f == null:
		return ""
	var ctx := HashingContext.new()
	ctx.start(HashingContext.HASH_SHA1)
	while f.get_position() < f.get_length():
		ctx.update(f.get_buffer(1 << 20))
	f.close()
	return ctx.finish().hex_encode()

# ---------------------------------------------------------------- обложки

var _cover_jobs := {}         # key → true, пока обложка грузится

static func _cover_key(url: String, sha1: String) -> String:
	return sha1 if sha1 != "" else url.md5_text()

## Обложка уже в памяти? (без ожидания — чтобы карточка сразу показывала картинку)
func cover_now(url: String, sha1: String = "") -> Texture2D:
	return _cover_cache.get(_cover_key(url, sha1))

## Обложка по URL каталога: скачивание + декодирование PNG в фоновом потоке. Может вернуть null.
func get_cover(url: String, sha1: String = "") -> Texture2D:
	if url == "":
		return null
	var key := _cover_key(url, sha1)
	if _cover_cache.has(key):
		return _cover_cache[key]
	# уже грузится (например, предзагрузкой) — ждём её
	if _cover_jobs.has(key):
		while _cover_jobs.has(key):
			await get_tree().process_frame
		return _cover_cache.get(key)
	_cover_jobs[key] = true
	var lp := "%s/covers/%s.%s" % [CACHE, key, url.get_extension().to_lower()]
	if not FileAccess.file_exists(lp):
		var dl = Net.Download.new()
		var err: String = await Net.download(url, lp, dl, 3)
		if err != "":
			_cover_jobs.erase(key)
			return null
	# PNG 1280×720 декодируется в потоке — без подвисаний интерфейса
	var holder := [null]
	var task := WorkerThreadPool.add_task(func(): holder[0] = Image.load_from_file(lp), false, "cover")
	while not WorkerThreadPool.is_task_completed(task):
		await get_tree().process_frame
	WorkerThreadPool.wait_for_task_completion(task)
	var img: Image = holder[0]
	var tex: Texture2D = null
	if img != null and not img.is_empty():
		tex = ImageTexture.create_from_image(img)
		_cover_cache[key] = tex
	_cover_jobs.erase(key)
	return tex

## Сразу после загрузки каталога — все обложки приключений и серий, по 4 одновременно.
func preload_covers() -> void:
	var list := []
	for a in adventures():
		list.append([String(a.get("cover", "")), String(a.get("cover_sha1", ""))])
		for e in a.get("episodes", []):
			list.append([String(e.get("cover", "")), String(e.get("cover_sha1", ""))])
	var st := {"i": 0}
	var worker := func():
		while st["i"] < list.size():
			var it: Array = list[st["i"]]
			st["i"] += 1
			if it[0] != "":
				await get_cover(it[0], it[1])
	for k in 4:
		worker.call()

## Загрузить картинку с диска как текстуру (без импорта).
static func load_texture(path: String) -> ImageTexture:
	var img := Image.load_from_file(path)
	if img == null or img.is_empty():
		return null
	return ImageTexture.create_from_image(img)

# ---------------------------------------------------------------- размер / очистка

func cache_size() -> int:
	return _dir_size(CACHE)

func _dir_size(path: String) -> int:
	var n := 0
	var d := DirAccess.open(path)
	if d == null:
		return 0
	d.include_hidden = true
	for f in d.get_files():
		var fa := FileAccess.open(path + "/" + f, FileAccess.READ)
		if fa:
			n += fa.get_length()
			fa.close()
	for sub in d.get_directories():
		n += _dir_size(path + "/" + sub)
	return n

func clear_cache() -> void:
	_remove_tree(CACHE)
	DirAccess.make_dir_recursive_absolute(CACHE)
	_manifest = {}
	_cover_cache = {}
	Audio.clear_cache_user()
	_save_manifest()

func _remove_tree(path: String) -> void:
	var d := DirAccess.open(path)
	if d == null:
		return
	d.include_hidden = true
	for f in d.get_files():
		DirAccess.remove_absolute(path + "/" + f)
	for sub in d.get_directories():
		_remove_tree(path + "/" + sub)
	DirAccess.remove_absolute(path)

static func human_size(bytes: int) -> String:
	if bytes < 1024 * 1024:
		return "%d КБ" % ceili(bytes / 1024.0)
	return "%.1f МБ" % (bytes / 1048576.0)
