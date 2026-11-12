class_name EpisodeLoader
extends RefCounted
## Серия из кэша: чтение episode.json, проверка версии, загрузка сцен (glb + дорожки + звук + лица)
## и миров. Загрузка сцен потокобезопасна — вызывается из WorkerThreadPool для предзагрузки.

const SUPPORTED_VERSION := 1

var dir := ""                 # user://cache/<adv>/episodes/<ep>
var data := {}
var error := ""
var _face_tex := {}           # cid → Array[Texture2D]
var _mutex := Mutex.new()

## Загруженная сцена, готовая к проигрыванию.
class LoadedScene:
	extends RefCounted
	var sid := ""
	var root: Node3D
	var ap: AnimationPlayer
	var cam: Camera3D
	var tracks := {}
	var fps := 15.0
	var frames := 1
	var duration := 0.0
	var world := ""
	var streams := {}         # voice/sfx/music → AudioStream
	var loop_streams := {}    # те же, но зацикленные (для фоновых сцен)
	var face_mats := {}       # cid → Array[BaseMaterial3D]
	var face_tex := {}        # cid → Array[Texture2D]
	var parts: Array[Node3D] = []
	var error := ""

func open(path_dir: String) -> bool:
	dir = path_dir
	var p := dir + "/episode.json"
	if not FileAccess.file_exists(p):
		error = "Нет файла серии (episode.json)"
		return false
	var j = JSON.parse_string(FileAccess.get_file_as_string(p))
	if not j is Dictionary:
		error = "Файл серии повреждён"
		return false
	data = j
	if int(data.get("version", 1)) > SUPPORTED_VERSION:
		error = "update"
		return false
	if not data.get("nodes") is Dictionary or not data.get("scenes") is Dictionary:
		error = "В серии нет сцен или узлов"
		return false
	return true

func nodes() -> Dictionary:
	return data.get("nodes", {})

func scene_info(sid: String) -> Dictionary:
	var s = data.get("scenes", {}).get(sid)
	return s if s is Dictionary else {}

func cast() -> Dictionary:
	var c = data.get("cast", {})
	return c if c is Dictionary else {}

func cast_name(cid: String) -> String:
	var c = cast().get(cid)
	return String(c.get("name", cid)) if c is Dictionary else cid

func cast_color(cid: String) -> Color:
	var c = cast().get(cid)
	return UI.hex(String(c.get("color", "#ffffff"))) if c is Dictionary else Color.WHITE

func file(rel: String) -> String:
	return dir + "/" + rel

## Текстуры лиц персонажа (6 вариантов), кэшируются на всю серию.
func faces(cid: String) -> Array:
	_mutex.lock()
	if _face_tex.has(cid):
		var r: Array = _face_tex[cid]
		_mutex.unlock()
		return r
	_mutex.unlock()
	var list := []
	var c = cast().get(cid)
	var paths: Array = c.get("faces", []) if c is Dictionary else []
	if paths.is_empty():
		for k in 6:
			paths.append("textures/%s_face%d.png" % [cid, k])
	for p in paths:
		var img := Image.load_from_file(file(String(p))) if FileAccess.file_exists(file(String(p))) else null
		list.append(ImageTexture.create_from_image(img) if img != null and not img.is_empty() else null)
	_mutex.lock()
	_face_tex[cid] = list
	_mutex.unlock()
	return list

## Загрузить сцену (можно из потока).
func load_scene(sid: String) -> LoadedScene:
	var ls := LoadedScene.new()
	ls.sid = sid
	var info := scene_info(sid)
	if info.is_empty():
		ls.error = "Нет сцены «%s»" % sid
		return ls
	ls.fps = float(info.get("fps", 15))
	ls.frames = int(info.get("frames", 1))
	ls.duration = float(info.get("duration", 0.0))
	ls.world = String(info.get("world", ""))
	# дорожки
	var tp := file(String(info.get("tracks", "scenes/%s.json" % sid)))
	if FileAccess.file_exists(tp):
		var j = JSON.parse_string(FileAccess.get_file_as_string(tp))
		if j is Dictionary:
			ls.tracks = j
			ls.fps = float(j.get("fps", ls.fps))
			ls.frames = int(j.get("frames", ls.frames))
			ls.duration = float(j.get("duration", ls.duration))
			if ls.world == "":
				ls.world = String(j.get("world", ""))
	# glb
	# keep_keys: исходные ключи анимации (с дублями на склейках), без пересэмплирования
	var root := GlbLoader.load_glb(file(String(info.get("glb", "scenes/%s.glb" % sid))), true)
	if root == null:
		ls.error = "Не удалось загрузить сцену «%s»" % sid
		return ls
	GlbLoader.fix_materials(root, false)
	ls.root = root
	ls.ap = GlbLoader.anim_player(root)
	if ls.ap:
		ls.ap.callback_mode_process = AnimationMixer.ANIMATION_CALLBACK_MODE_PROCESS_MANUAL
		ls.ap.playback_default_blend_time = 0.0
		# кватернионы нормализуем: LINEAR-интерполяция по ключам каждого кадра
		var anim := ls.ap.get_animation("main")
		if anim == null and ls.ap.get_animation_list().size() > 0:
			anim = ls.ap.get_animation(ls.ap.get_animation_list()[0])
		if anim:
			for ti in anim.get_track_count():
				anim.track_set_interpolation_type(ti, Animation.INTERPOLATION_LINEAR)
			# сегменты между ключами не «срезаем» на шве цикла
			anim.loop_mode = Animation.LOOP_NONE
	for c in root.find_children("*", "Camera3D", true, false):
		ls.cam = c
		break
	if ls.cam:
		ls.cam.current = false
		ls.cam.keep_aspect = Camera3D.KEEP_HEIGHT
	# части персонажей и предметы (узлы верхнего уровня)
	for n in root.find_children("*", "Node3D", true, false):
		if n is Node3D and String(n.name).contains("__"):
			ls.parts.append(n)
	# материалы лиц: у всех частей персонажа один материал скина
	var face_track: Dictionary = ls.tracks.get("face", {}) if ls.tracks.get("face") is Dictionary else {}
	for cid in face_track.keys():
		var mats := []
		for n in ls.parts:
			if String(n.name).begins_with(String(cid) + "__") and n is MeshInstance3D and n.mesh:
				for si in n.mesh.get_surface_count():
					var m = n.get_surface_override_material(si)
					if m == null:
						m = n.mesh.surface_get_material(si)
					if m is BaseMaterial3D and not mats.has(m):
						mats.append(m)
		ls.face_mats[cid] = mats
		ls.face_tex[cid] = faces(String(cid))
	# звук (три дорожки)
	var au: Dictionary = info.get("audio", {}) if info.get("audio") is Dictionary else {}
	for k in ["voice", "sfx", "music"]:
		var rel := String(au.get(k, "audio/%s_%s.ogg" % [sid, k]))
		var apath := file(rel)
		if FileAccess.file_exists(apath):
			var s := AudioStreamOggVorbis.load_from_file(apath)
			if s:
				ls.streams[k] = s
				var s2: AudioStreamOggVorbis = s.duplicate()
				s2.loop = true
				ls.loop_streams[k] = s2
	return ls

## Загрузить мир (статичный, кэшируется в Player на всю серию).
func load_world(wid: String) -> Node3D:
	var w = data.get("worlds", {}).get(wid)
	if not w is Dictionary:
		return null
	var root := GlbLoader.load_glb(file(String(w.get("glb", "worlds/%s.glb" % wid))))
	if root:
		GlbLoader.fix_materials(root, true)
		root.name = "World_" + wid
	return root

func world_marks(wid: String) -> Dictionary:
	var w = data.get("worlds", {}).get(wid)
	if not w is Dictionary:
		return {}
	var p := file(String(w.get("meta", "worlds/%s.json" % wid)))
	if not FileAccess.file_exists(p):
		return {}
	var j = JSON.parse_string(FileAccess.get_file_as_string(p))
	return j.get("marks", {}) if j is Dictionary else {}
