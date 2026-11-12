class_name ScenePlayer
extends Node3D
## Проигрывание запечённой сцены: анимация "main" (персонажи, предметы, камера), дорожки fov / лиц /
## частиц / субтитров / окружения и три звуковые дорожки (Voice / SFX / Music) — от одних часов сцены.
## Часы — позиция AnimationPlayer (продвигаем вручную), звук раз в секунду сверяется с ней.

signal finished

var scene: EpisodeLoader.LoadedScene
var looped := false
var playing := false
var paused := false
var t := 0.0
var particles: CubeParticles
var env3d: Env3D
var shake_amount := 0.0
var shake_time := 0.0

var _players := {}            # voice/sfx/music → AudioStreamPlayer
var _frame := -1
var _sync_t := 0.0
var _face_state := {}         # cid → текущий индекс
var _done := false

func _init() -> void:
	name = "ScenePlayer"
	process_priority = 10
	for k in ["voice", "sfx", "music"]:
		var p := AudioStreamPlayer.new()
		p.bus = {"voice": "Voice", "sfx": "SFX", "music": "Music"}[k]
		add_child(p)
		_players[k] = p

## Начать сцену. loop — фоновая (зацикленная) сцена: без голоса, sfx+music по кругу.
func start(ls: EpisodeLoader.LoadedScene, loop: bool, start_t := 0.0) -> void:
	stop()
	scene = ls
	looped = loop
	_done = false
	_frame = -1
	_face_state.clear()
	t = clampf(start_t, 0.0, maxf(0.0, ls.duration))
	if ls.root.get_parent() != self:
		if ls.root.get_parent():
			ls.root.get_parent().remove_child(ls.root)
		add_child(ls.root)
	ls.root.visible = true
	if ls.ap:
		var anim_name := "main" if ls.ap.has_animation("main") else (ls.ap.get_animation_list()[0] if ls.ap.get_animation_list().size() > 0 else "")
		if anim_name != "":
			var anim := ls.ap.get_animation(anim_name)
			anim.loop_mode = Animation.LOOP_LINEAR if loop else Animation.LOOP_NONE
			ls.ap.play(anim_name)
			ls.ap.seek(t, true)
			_fix_hidden()
	if ls.cam:
		ls.cam.make_current()
	if env3d and ls.tracks.get("env") is Dictionary:
		env3d.apply(ls.tracks["env"])
	# звук
	for k in _players:
		var p: AudioStreamPlayer = _players[k]
		p.stop()
		p.stream = null
		if loop and k == "voice":
			continue   # у фоновых сцен голос не играет
		var s = (ls.loop_streams if loop else ls.streams).get(k)
		if s:
			p.stream = s
			p.play(t)
	playing = true
	paused = false
	_sync_t = 0.0
	_apply_frame(true)

func stop() -> void:
	playing = false
	for k in _players:
		_players[k].stop()
	if particles:
		particles.clear()
	if scene and scene.root and scene.root.get_parent() == self:
		remove_child(scene.root)
	scene = null

func set_paused(v: bool) -> void:
	paused = v
	for k in _players:
		_players[k].stream_paused = v

func _process(delta: float) -> void:
	if not playing or scene == null:
		return
	if not paused:
		var prev := t
		t += delta
		var dur := maxf(scene.duration, 0.001)
		if looped:
			if t >= dur:
				t = fmod(t, dur)
				# новый круг — звук тоже с начала
				for k in _players:
					var p: AudioStreamPlayer = _players[k]
					if p.stream:
						p.play(t)
		elif t >= dur:
			t = dur
		if scene.ap:
			scene.ap.advance(t - prev if t >= prev else t + (dur - prev))
			if absf(scene.ap.current_animation_position - t) > 0.05:
				scene.ap.seek(t, true)
			_fix_hidden()
		_sync_t += delta
		if _sync_t >= 1.0:
			_sync_t = 0.0
			_sync_audio()
	_apply_frame(false)
	_apply_shake(delta)
	if not looped and not _done and t >= scene.duration:
		_done = true
		playing = false
		finished.emit()

## Звук сверяется с часами анимации; расхождение > 0.1 с — seek.
func _sync_audio() -> void:
	for k in _players:
		var p: AudioStreamPlayer = _players[k]
		if not p.playing or p.stream == null:
			continue
		var len := p.stream.get_length()
		if len <= 0.0:
			continue
		var want := t
		if looped:
			want = fmod(t, len)
		elif t >= len:
			continue
		var pos := p.get_playback_position() + AudioServer.get_time_since_last_mix()
		if absf(pos - want) > 0.1:
			p.seek(want)

## Скрытые объекты (масштаб 0 на кадре) прячем, чтобы не было вырожденных матриц.
func _fix_hidden() -> void:
	for n in scene.parts:
		var s: Vector3 = n.scale
		if absf(s.x) < 1e-5 or absf(s.y) < 1e-5 or absf(s.z) < 1e-5:
			n.visible = false
			n.scale = Vector3(1e-4, 1e-4, 1e-4)
		elif not n.visible:
			n.visible = true

func current_frame() -> int:
	return clampi(int(floor(t * scene.fps + 0.0001)), 0, maxi(0, scene.frames - 1))

func _apply_frame(force: bool) -> void:
	var tr := scene.tracks
	var f := current_frame()
	# FOV — лерп между кадрами
	if scene.cam and tr.get("fov") is Array and not tr["fov"].is_empty():
		var fa: Array = tr["fov"]
		var ft := t * scene.fps
		var i0 := clampi(int(floor(ft)), 0, fa.size() - 1)
		var i1 := mini(i0 + 1, fa.size() - 1)
		scene.cam.fov = clampf(lerpf(float(fa[i0]), float(fa[i1]), clampf(ft - i0, 0.0, 1.0)), 1.0, 170.0)
	if f == _frame and not force:
		return
	_frame = f
	# лица
	if tr.get("face") is Dictionary:
		for cid in tr["face"]:
			var arr = tr["face"][cid]
			if not arr is Array or arr.is_empty():
				continue
			var k := int(arr[mini(f, arr.size() - 1)])
			if _face_state.get(cid, -1) == k:
				continue
			_face_state[cid] = k
			var texs: Array = scene.face_tex.get(cid, [])
			if k >= 0 and k < texs.size() and texs[k] != null:
				for m in scene.face_mats.get(cid, []):
					m.albedo_texture = texs[k]
	# частицы (только кадры, где они есть)
	if particles:
		var pt = tr.get("particles", {})
		var key := str(f)
		if pt is Dictionary and pt.has(key):
			particles.show_set(pt[key], scene.sid + ":" + key)
		else:
			particles.show_set(null, "")

## Текущий субтитр (по времени сцены) или пустой словарь.
func current_subtitle() -> Dictionary:
	if scene == null or looped:
		return {}
	var subs = scene.tracks.get("subtitles", [])
	if not subs is Array:
		return {}
	for s in subs:
		if s is Dictionary and t >= float(s.get("t0", 0)) and t < float(s.get("t1", 0)) + 0.25:
			return s
	return {}

## Тряска камеры (сила, время).
func shake(amount: float, time := 0.3) -> void:
	shake_amount = maxf(shake_amount, amount)
	shake_time = maxf(shake_time, time)

func _apply_shake(delta: float) -> void:
	if scene == null or scene.cam == null:
		return
	if shake_time > 0.0 and not paused:
		shake_time -= delta
		var a := shake_amount * clampf(shake_time / 0.3, 0.0, 1.0)
		scene.cam.h_offset = randf_range(-a, a) * 0.3
		scene.cam.v_offset = randf_range(-a, a) * 0.3
		if shake_time <= 0.0:
			shake_amount = 0.0
	else:
		scene.cam.h_offset = 0.0
		scene.cam.v_offset = 0.0

## Мировая позиция узла сцены по имени (для хотспотов).
func node_position(node_name: String):
	if scene == null or scene.root == null:
		return null
	var n := scene.root.find_child(node_name, true, false)
	if n is Node3D:
		if not n.visible:
			return null
		# центр меша, а не точка опоры
		if n is MeshInstance3D and n.mesh:
			return n.global_transform * n.mesh.get_aabb().get_center()
		return n.global_position
	return null

func camera() -> Camera3D:
	return scene.cam if scene else null
