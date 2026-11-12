extends Node
## Контент: каталог приключений с сервера, кэш файлов в user://cache/…, скачивание серий
## (только изменившиеся файлы — по sha1) с прогрессом, обложки.

const CACHE := "user://cache"
const SUPPORTED_EPISODE_VERSION := 1

signal catalog_changed

var catalog := {"version": 0, "adventures": []}
var _manifest := {}            # путь в кэше → sha1 (что лежит на диске)
var _cover_cache := {}         # sha1 → Texture2D

func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	DirAccess.make_dir_recursive_absolute(CACHE)
	var j = _read_json(CACHE + "/manifest.json")
	if j is Dictionary:
		_manifest = j

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
	var n := 0
	for f in missing_files(adv_id, ep):
		n += int(f.get("size", 0))
	return n

func is_cached(adv_id: String, ep: Dictionary) -> bool:
	return missing_files(adv_id, ep).is_empty()

## Скачать изменившиеся файлы серии. on_progress(done_bytes, total_bytes). Возвращает "" или ошибку.
func download_episode(adv_id: String, ep: Dictionary, dl, on_progress: Callable) -> String:
	var need := missing_files(adv_id, ep)
	var total := 0
	for f in need:
		total += int(f.get("size", 0))
	var st := {"done": 0}
	var fin := [false]
	# фоновое обновление прогресса
	var ticker := func():
		while not fin[0]:
			on_progress.call(st["done"] + dl.downloaded, total)
			await get_tree().process_frame
	ticker.call()
	for f in need:
		if dl.cancel:
			fin[0] = true
			return "cancel"
		var lp := local_path(adv_id, f["path"])
		var parts := PackedStringArray()
		for seg in String(f["path"]).split("/"):
			parts.append(seg.uri_encode())
		var err: String = await Net.download("/files/%s/%s" % [adv_id.uri_encode(), "/".join(parts)], lp, dl)
		if err == "" and f.get("sha1", "") != "":
			var got := sha1_file(lp)
			if got != f["sha1"]:
				err = "контрольная сумма не совпала: " + String(f["path"])
				DirAccess.remove_absolute(lp)
		if err != "":
			fin[0] = true
			_save_manifest()
			return err
		_manifest[lp] = f.get("sha1", "")
		st["done"] += int(f.get("size", 0))
	fin[0] = true
	_save_manifest()
	on_progress.call(total, total)
	return ""

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

## Обложка по URL каталога; кэшируется на диске по sha1. Может вернуть null.
func get_cover(url: String, sha1: String = "") -> Texture2D:
	if url == "":
		return null
	var key := sha1 if sha1 != "" else url.md5_text()
	if _cover_cache.has(key):
		return _cover_cache[key]
	var lp := "%s/covers/%s.%s" % [CACHE, key, url.get_extension().to_lower()]
	if not FileAccess.file_exists(lp):
		var dl = Net.Download.new()
		var err: String = await Net.download(url, lp, dl, 2)
		if err != "":
			return null
	var tex := load_texture(lp)
	if tex:
		_cover_cache[key] = tex
	return tex

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
