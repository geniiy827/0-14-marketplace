extends Control
## Проигрыватель серии: 3D-сцена (ScenePlayer) + мир + окружение, слой UI (субтитры, пауза, выбор,
## хотспоты, мини-игры, 2D-наложения, итог) и логика узлов (LogicRunner).
## Предзагрузка соседних сцен графа идёт в фоне (WorkerThreadPool) — переходы без чёрных пауз.

const CACHE_LIMIT := 7

var adv_id := ""
var ep_id := ""
var resume := false

var ep: EpisodeLoader
var runner: LogicRunner
var stage: Node3D
var env3d: Env3D
var sp: ScenePlayer
var particles: CubeParticles
var world_holder: Node3D

var _scenes := {}            # sid → LoadedScene
var _loading := {}           # sid → {"task": id, "result": [LoadedScene]}
var _lru: Array = []
var _worlds := {}            # wid → Node3D
var _world_loading := {}     # wid → {"task", "result"}
var _cur_world := ""
var _bg_sid := ""            # текущая фоновая (зацикленная) сцена

# UI
var subs: Subtitles
var interactive: Control      # контейнер для выбора / хотспотов / мини-игр / наложений
var hint: Label
var pause_btn: Button
var flash_rect: ColorRect
var loading_label: Label
var debug_label: Label
var pause_menu: Control = null
var _remember: Label
var _hotspots: HotspotLayer = null
var _overlay: Overlay2D = null
var _paused := false
var _finished := false
var _started_at := 0

func setup(params: Dictionary) -> void:
	adv_id = String(params.get("adventure", ""))
	ep_id = String(params.get("episode", ""))
	resume = bool(params.get("resume", false))

func _ready() -> void:
	UI.full(self)
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	Audio.stop_menu_music(1.2)
	_build_stage()
	_build_ui()
	ep = EpisodeLoader.new()
	if not ep.open(Content.episode_dir(adv_id, ep_id)):
		if ep.error == "update":
			await Router.alert("Обновите игру", "Эта серия сделана для более новой версии Story Craft.")
		else:
			await Router.alert("Серия не открывается", ep.error + "\nПопробуй очистить кэш в настройках и скачать серию снова.")
		Router.go("episodes", {"adventure": adv_id})
		return
	# переменные приключения (выборы переносятся между сериями)
	var adv_vars := Progress.adv_vars(adv_id)
	var start_node := String(ep.data.get("start", ""))
	var saved_vars := {}
	if resume:
		var r := Progress.resume_of(adv_id)
		if r.get("episode", "") == ep_id and ep.nodes().has(String(r.get("node", ""))):
			start_node = String(r["node"])
			saved_vars = r.get("vars", {}) if r.get("vars") is Dictionary else {}
			if r.get("adv_vars") is Dictionary:
				for k in r["adv_vars"]:
					adv_vars[k] = r["adv_vars"][k]
	if start_node == "" or not ep.nodes().has(start_node):
		await Router.alert("Серия повреждена", "Не найден начальный узел серии.")
		Router.go("episodes", {"adventure": adv_id})
		return
	runner = LogicRunner.new(self, ep, adv_vars)
	_started_at = Time.get_ticks_msec()
	# заранее грузим первую сцену и её соседей
	_preload_for(ep.nodes()[start_node])
	if has_meta("test_no_start"):
		return   # автотест сам управляет сценой
	runner.start(start_node, saved_vars)

func _exit_tree() -> void:
	get_tree().paused = false
	# дожидаемся фоновых загрузок, чтобы потоки не работали с удалёнными объектами
	for d in [_loading, _world_loading]:
		for k in d:
			WorkerThreadPool.wait_for_task_completion(d[k]["task"])
			var r = d[k]["result"][0]
			if r is EpisodeLoader.LoadedScene and r.root:
				r.root.free()
			elif r is Node:
				r.free()
	if stage and is_instance_valid(stage):
		stage.queue_free()
	for wid in _worlds:
		if is_instance_valid(_worlds[wid]) and _worlds[wid].get_parent() == null:
			_worlds[wid].free()
	for sid in _scenes:
		var ls: EpisodeLoader.LoadedScene = _scenes[sid]
		if ls.root and is_instance_valid(ls.root) and ls.root.get_parent() == null:
			ls.root.free()

# ---------------------------------------------------------------- 3D

func _build_stage() -> void:
	stage = Node3D.new()
	stage.name = "EpisodeStage"
	env3d = Env3D.new()
	stage.add_child(env3d)
	world_holder = Node3D.new()
	world_holder.name = "Worlds"
	stage.add_child(world_holder)
	sp = ScenePlayer.new()
	sp.env3d = env3d
	stage.add_child(sp)
	particles = CubeParticles.new()
	stage.add_child(particles)
	sp.particles = particles
	# камера по умолчанию, пока первая сцена грузится
	var cam := Camera3D.new()
	cam.name = "WaitCamera"
	stage.add_child(cam)
	Router.world_root().add_child(stage)
	cam.make_current()

## Сцена из кэша или загрузка (в потоке). Ждём кадрами, чтобы UI не замирал.
func get_scene(sid: String) -> EpisodeLoader.LoadedScene:
	if _scenes.has(sid):
		_touch(sid)
		return _scenes[sid]
	if not _loading.has(sid):
		_start_load(sid)
	var shown := false
	var t0 := Time.get_ticks_msec()
	while _loading.has(sid):
		var job: Dictionary = _loading[sid]
		if WorkerThreadPool.is_task_completed(job["task"]):
			WorkerThreadPool.wait_for_task_completion(job["task"])
			_loading.erase(sid)
			var ls: EpisodeLoader.LoadedScene = job["result"][0]
			if ls == null:
				ls = EpisodeLoader.LoadedScene.new()
				ls.error = "Не удалось загрузить сцену «%s»" % sid
			if ls.error == "":
				_scenes[sid] = ls
				_touch(sid)
			if shown:
				loading_label.visible = false
			return ls
		if not shown and Time.get_ticks_msec() - t0 > 300:
			shown = true
			loading_label.visible = true
		await get_tree().process_frame
	return _scenes.get(sid)

func _start_load(sid: String) -> void:
	if sid == "" or _scenes.has(sid) or _loading.has(sid):
		return
	var holder := [null]
	var e := ep
	var task := WorkerThreadPool.add_task(func(): holder[0] = e.load_scene(sid), false, "scene " + sid)
	_loading[sid] = {"task": task, "result": holder}

func _touch(sid: String) -> void:
	_lru.erase(sid)
	_lru.append(sid)
	# выгружаем давно неиспользуемые сцены (кроме текущей и фоновой)
	var i := 0
	while _scenes.size() > CACHE_LIMIT and i < _lru.size():
		var old: String = _lru[i]
		if (sp.scene != null and sp.scene.sid == old) or old == _bg_sid or old == sid:
			i += 1
			continue
		_lru.remove_at(i)
		var ls: EpisodeLoader.LoadedScene = _scenes.get(old)
		_scenes.erase(old)
		if ls and ls.root and is_instance_valid(ls.root) and ls.root.get_parent() == null:
			ls.root.queue_free()

## Предзагрузка сцен, соседних с узлом.
func _preload_for(n: Dictionary) -> void:
	if runner == null and ep == null:
		return
	var tmp := runner if runner else LogicRunner.new(self, ep, {})
	var list: Array = tmp.neighbor_scenes(n)
	for key in ["scene", "background"]:
		if n.has(key):
			list.push_front(String(n[key]))
	for sid in list:
		_start_load(sid)
		# мир соседней сцены тоже готовим заранее
		_start_world(String(ep.scene_info(sid).get("world", "")))

## Мир грузится в потоке (статичный, кэшируется на всю серию).
func _start_world(wid: String) -> void:
	if wid == "" or _worlds.has(wid) or _world_loading.has(wid):
		return
	var holder := [null]
	var e := ep
	var task := WorkerThreadPool.add_task(func(): holder[0] = e.load_world(wid), false, "world " + wid)
	_world_loading[wid] = {"task": task, "result": holder}

func _get_world(wid: String) -> Node3D:
	if wid == "":
		return null
	if _worlds.has(wid):
		return _worlds[wid]
	_start_world(wid)
	while _world_loading.has(wid):
		var job: Dictionary = _world_loading[wid]
		if WorkerThreadPool.is_task_completed(job["task"]):
			WorkerThreadPool.wait_for_task_completion(job["task"])
			_world_loading.erase(wid)
			_worlds[wid] = job["result"][0]
			break
		loading_label.visible = true
		await get_tree().process_frame
	loading_label.visible = false
	return _worlds.get(wid)

func _show_world(wid: String) -> void:
	if wid == _cur_world:
		return
	var w := await _get_world(wid)
	for c in world_holder.get_children():
		world_holder.remove_child(c)
	if w:
		world_holder.add_child(w)
	_cur_world = wid

## Проиграть сцену целиком (для узла scene и действия play). false — сцена не загрузилась.
func play_scene(sid: String, g: int) -> bool:
	_clear_interactive()
	_bg_sid = ""
	var ls := await get_scene(sid)
	if not runner.alive(g):
		return false
	if ls == null or ls.error != "":
		logic_error(ls.error if ls else "Нет сцены " + sid)
		await wait(0.5)
		return runner.alive(g)  # битая сцена — пропускаем, история продолжается
	await _show_world(ls.world)
	if not runner.alive(g):
		return false
	sp.start(ls, false)
	_preload_for(runner.node)
	await sp.finished
	return runner.alive(g)

## Фоновая зацикленная сцена для интерактивного узла.
func show_background(sid: String) -> void:
	if sid == "":
		return
	if _bg_sid == sid and sp.playing and sp.looped:
		return
	_bg_sid = sid
	var ls := await get_scene(sid)
	if ls == null or ls.error != "" or _bg_sid != sid:
		return
	await _show_world(ls.world)
	if _bg_sid != sid:
		return
	sp.start(ls, true)
	_preload_for(runner.node)

# ---------------------------------------------------------------- UI

func _build_ui() -> void:
	var sm := UI.safe_margins()
	interactive = Control.new()
	UI.full(interactive)
	interactive.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(interactive)

	subs = Subtitles.new()
	add_child(subs)

	hint = UI.label("", UI.FS_SMALL, true)
	hint.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	UI.pin(hint, 0.5, 0.0, 0, 28 + sm.y)
	hint.custom_minimum_size = Vector2(800, 0)
	hint.visible = false
	add_child(hint)

	_remember = UI.label("", UI.FS_SMALL, true, Color(1.0, 0.85, 0.45))
	UI.pin(_remember, 0.0, 0.0, 32 + sm.x, 32 + sm.y)
	_remember.modulate.a = 0.0
	add_child(_remember)

	loading_label = UI.label("Загрузка…", UI.FS_SMALL, false, UI.C_DIM)
	UI.pin(loading_label, 0.0, 1.0, 32 + sm.x, -24 - sm.w)
	loading_label.visible = false
	add_child(loading_label)

	debug_label = UI.label("", UI.FS_SMALL, false, Color(1, 0.5, 0.4))
	debug_label.position = Vector2(32 + sm.x, 100)
	debug_label.visible = false
	debug_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	debug_label.custom_minimum_size = Vector2(800, 0)
	add_child(debug_label)

	flash_rect = ColorRect.new()
	UI.full(flash_rect)
	flash_rect.color = Color(1, 1, 1, 0)
	flash_rect.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(flash_rect)

	pause_btn = UI.icon_button("res://assets/ui/icon_pause.png", toggle_pause)
	UI.pin(pause_btn, 1.0, 0.0, -20 - sm.z, 16 + sm.y)
	pause_btn.process_mode = Node.PROCESS_MODE_ALWAYS
	add_child(pause_btn)

func _process(_delta: float) -> void:
	if sp == null:
		return
	subs.show_line(sp.current_subtitle(), ep)

func _clear_interactive() -> void:
	for c in interactive.get_children():
		c.queue_free()
	_hotspots = null
	_overlay = null
	hint.visible = false

func clear_interactive() -> void:
	_clear_interactive()

func on_node_enter(id: String, n: Dictionary) -> void:
	_clear_interactive()
	_preload_for(n)
	var type := String(n.get("type", ""))
	# точка продолжения — на интерактивных узлах
	if type in ["choice", "interact", "minigame", "overlay"]:
		_send_checkpoint(id)

func checkpoint() -> void:
	_send_checkpoint(runner.node_id)

func _send_checkpoint(node: String) -> void:
	if _finished or runner == null:
		return
	var v := runner.vars.duplicate(true)
	for k in ["taps", "time_left", "score"]:
		v.erase(k)
	Progress.send_event({"type": "checkpoint", "adventure": adv_id, "episode": ep_id, "node": node,
		"vars": v, "adv_vars": runner.adv.duplicate(true)})

# ---------------------------------------------------------------- интерактив

func ui_choice(n: Dictionary, shown: Array, timer: float, timeout_idx: int, g: int) -> int:
	var cp := ChoicePanel.new()
	cp.prompt = runner.text(n.get("prompt", ""))
	cp.speaker_name = ep.cast_name(String(n.get("speaker", ""))) if n.get("speaker", "") != "" else ""
	cp.speaker_color = ep.cast_color(String(n.get("speaker", "")))
	cp.options = shown
	cp.timer = timer
	cp.timeout_index = timeout_idx
	interactive.add_child(cp)
	var idx: int = await cp.chosen
	if is_instance_valid(cp):
		cp.close()
	return idx

func remember(text: String) -> void:
	_remember.text = text
	var tw := create_tween()
	tw.tween_property(_remember, "modulate:a", 1.0, 0.25)
	tw.tween_interval(2.4)
	tw.tween_property(_remember, "modulate:a", 0.0, 0.5)

func ui_interact(n: Dictionary, g: int) -> void:
	if n.has("hint"):
		hint.text = runner.text(n["hint"])
		UI.fade_in(hint)
	_hotspots = HotspotLayer.new()
	_hotspots.player = self
	_hotspots.gen = g
	_hotspots.hotspots = n.get("hotspots", [])
	interactive.add_child(_hotspots)

## Тап по хотспоту — действия по очереди.
func hotspot_tapped(h: Dictionary, g: int) -> void:
	await runner.run_actions(h.get("on_tap", []), g)

func restore_interactive(n: Dictionary, g: int) -> void:
	# после вставной сцены (play без then) — вернуть фон и интерактив узла
	var type := String(n.get("type", ""))
	if n.has("background"):
		show_background(String(n["background"]))
	if type == "interact" and _hotspots == null:
		ui_interact(n, g)
	elif type == "overlay" and _overlay == null:
		ui_overlay(n, g)

func ui_minigame(n: Dictionary, g: int) -> bool:
	var kind := String(n.get("kind", ""))
	var mg: Minigame = Minigame.create(kind)
	if mg == null:
		logic_error("Неизвестная мини-игра «%s»" % kind)
		return true
	mg.params = n.get("params", {}) if n.get("params") is Dictionary else {}
	mg.runner = runner
	mg.player = self
	interactive.add_child(mg)
	var ok: bool = await mg.done
	if is_instance_valid(mg):
		mg.close()
	return ok

func ui_overlay(n: Dictionary, g: int) -> void:
	_overlay = Overlay2D.new()
	_overlay.player = self
	_overlay.gen = g
	_overlay.node = n
	interactive.add_child(_overlay)

func set_visible_item(id: String, v: bool) -> void:
	if _overlay and _overlay.set_widget_visible(id, v):
		return
	if _hotspots:
		_hotspots.set_hotspot_visible(id, v)

# ---------------------------------------------------------------- эффекты

func sfx(name: String) -> void:
	if name.contains("/"):
		Audio.play_sfx(ep.file(name))
	else:
		Audio.play_sfx(name)

func say(who: String, text: String, voice: String, time: float) -> void:
	var dur := time if time > 0.0 else clampf(1.2 + text.length() * 0.06, 1.5, 6.0)
	subs.say(ep.cast_name(who) if who != "" else "", ep.cast_color(who), text, dur)
	if voice != "":
		var p := AudioStreamPlayer.new()
		p.bus = "Voice"
		p.stream = Audio.load_stream(ep.file(voice))
		add_child(p)
		p.play()
		p.finished.connect(p.queue_free)

func shake(v: float) -> void:
	sp.shake(clampf(v, 0.0, 2.0), maxf(0.2, v))

func flash(color: String) -> void:
	var c := UI.hex(color, Color.WHITE)
	c.a = 0.85
	flash_rect.color = c
	var tw := create_tween()
	tw.tween_property(flash_rect, "color:a", 0.0, 0.45)

func wait(sec: float) -> void:
	await get_tree().create_timer(maxf(0.0, sec), false).timeout

func logic_error(msg: String) -> void:
	push_warning("[Серия] " + msg)
	if OS.is_debug_build():
		debug_label.text = msg
		debug_label.visible = true
		var tw := create_tween()
		tw.tween_interval(4.0)
		tw.tween_callback(func(): debug_label.visible = false)

# ---------------------------------------------------------------- конец серии

func ui_end(title: String, text: String, ending: String, unlock_next: bool) -> void:
	_finished = true
	_clear_interactive()
	pause_btn.visible = false
	Progress.send_event({"type": "complete", "adventure": adv_id, "episode": ep_id, "ending": ending,
		"adv_vars": runner.adv.duplicate(true)})
	Audio.play_sfx("success", -4.0)
	var es := EndScreen.new()
	es.title = title
	es.text = text
	add_child(es)
	var idx: int = await es.chosen
	if idx == 0:
		Router.go("episodes", {"adventure": adv_id})
	else:
		Router.go("player", {"adventure": adv_id, "episode": ep_id, "resume": false})

# ---------------------------------------------------------------- пауза

func on_back() -> void:
	if _finished:
		Router.go("episodes", {"adventure": adv_id})
		return
	toggle_pause()

func toggle_pause() -> void:
	if _finished:
		return
	if _paused:
		_resume()
	else:
		_pause()

func _pause() -> void:
	_paused = true
	get_tree().paused = true
	pause_menu = PauseMenu.new()
	pause_menu.player = self
	add_child(pause_menu)

func _resume() -> void:
	_paused = false
	if pause_menu and is_instance_valid(pause_menu):
		pause_menu.close()
	pause_menu = null
	get_tree().paused = false

func pause_action(what: String) -> void:
	match what:
		"continue":
			_resume()
		"restart":
			if await Router.confirm("Начать серию заново?", "Прогресс в этой серии сбросится.", "Заново", "Отмена"):
				get_tree().paused = false
				Router.go("player", {"adventure": adv_id, "episode": ep_id, "resume": false})
		"settings":
			var s: Control = load("res://screens/Settings.tscn").instantiate()
			s.setup({"overlay": true})
			Router.push_modal(s)
		"exit":
			if runner and not _finished:
				_send_checkpoint(runner.node_id)
			get_tree().paused = false
			Router.go("episodes", {"adventure": adv_id})
