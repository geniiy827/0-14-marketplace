extends Node
## Автотест для разработки (не входит в APK): проходит экраны и сохраняет скриншоты.
## godot --path . res://tests/AutoTest.tscn -- <сценарий> <папка>

var out := "/tmp/shots"

func _ready() -> void:
	var args := OS.get_cmdline_user_args()
	var mode := args[0] if args.size() > 0 else "menu"
	if args.size() > 1:
		out = args[1]
	DirAccess.make_dir_recursive_absolute(out)
	await _run(mode)
	get_tree().quit()

func shot(name: String) -> void:
	await RenderingServer.frame_post_draw
	get_viewport().get_texture().get_image().save_png(out + "/" + name + ".png")
	print("SHOT ", name)

func wait(t: float) -> void:
	await get_tree().create_timer(t, true, false, true).timeout

func _run(mode: String) -> void:
	match mode:
		"boot":
			var b = load("res://screens/Boot.tscn").instantiate()
			get_tree().root.add_child.call_deferred(b)
			await wait(0.35); await shot("boot_0_35")
			await wait(0.5); await shot("boot_0_85")
			await wait(0.4); await shot("boot_1_25")
			await wait(2.0); await shot("boot_after")
			await wait(1.5); await shot("menu")
		"menu":
			Router.menu_bg = preload("res://screens/MenuBackground.gd").new()
			Router.go("main_menu", {}, false)
			await wait(2.0); await shot("menu")
			await wait(6.0); await shot("menu_later")
			await Content.refresh()
			Router.go("adventures")
			await wait(2.5); await shot("adventures")
			Router.go("episodes", {"adventure": "last_men"})
			await wait(2.5); await shot("episodes")
			Router.go("settings")
			await wait(1.5); await shot("settings")
		"fps":
			# замер FPS: меню, приключения, серия (без ограничения частоты)
			await wait(0.2)
			Engine.max_fps = 0
			Router.menu_bg = preload("res://screens/MenuBackground.gd").new()
			Router.go("main_menu", {}, false)
			await wait(2.0)
			print("FPS menu ", await _measure(5.0))
			Settings.set_value("shadows", false)
			await wait(0.5)
			print("FPS menu (без теней) ", await _measure(4.0))
			Settings.set_value("shadows", true)
			await Content.refresh()
			Router.go("adventures")
			await wait(2.0)
			print("FPS adventures ", await _measure(5.0))
			Router.go("player", {"adventure": "last_men", "episode": "01_escape"}, true)
			await wait(4.0)
			print("FPS episode ", await _measure(5.0))
		"shadows":
			Router.menu_bg = preload("res://screens/MenuBackground.gd").new()
			Router.go("main_menu", {}, false)
			await wait(1.0)
			var bg = Router.menu_bg
			bg.set_process(false)
			for m in bg.mobs:
				m.root.set_process(false)
			Settings.set_value("shadows", true)
			await wait(0.3)
			await shot("sh_on")
			Settings.set_value("shadows", false)
			await wait(0.3)
			await shot("sh_off")
			Settings.set_value("shadows", true)
		"sheep":
			Router.menu_bg = preload("res://screens/MenuBackground.gd").new()
			Router.go("main_menu", {}, false)
			await wait(1.0)
			var bg = Router.menu_bg
			bg.set_process(false)
			Router.current.visible = false
			for m in bg.mobs:
				if m.kind == "sheep":
					var p0: Vector3 = m.root.global_position
					bg.cam.near = 0.05
					var fwd: Vector3 = m.root.global_transform.basis.z
					var side: Vector3 = m.root.global_transform.basis.x
					bg.cam.global_position = p0 + fwd * 1.6 + side * 1.4 + Vector3(0, 1.0, 0)
					bg.cam.look_at(p0 + Vector3(0, 0.6, 0))
					await wait(0.3)
					await shot("sheep")
					bg.cam.global_position = p0 - side * 2.0 + Vector3(0, 0.5, 0)
					bg.cam.look_at(p0 + Vector3(0, 0.6, 0))
					await wait(0.3)
					await shot("sheep_side")
					break
		"orbit":
			Router.menu_bg = preload("res://screens/MenuBackground.gd").new()
			Router.go("main_menu", {}, false)
			await wait(1.0)
			for i in 6:
				Router.menu_bg._angle = i * TAU / 6.0
				await wait(0.2)
				await shot("orbit_%d" % i)
		"download":
			Router.menu_bg = preload("res://screens/MenuBackground.gd").new()
			await Content.refresh()
			await Progress.register()
			var e := Content.episode("last_men", "01_escape")
			Content.reset_session()
			var t0 := Time.get_ticks_msec()
			var dl = Net.Download.new()
			var mode2 := OS.get_cmdline_user_args()[2] if OS.get_cmdline_user_args().size() > 2 else "pack"
			var err
			if mode2 == "files":
				err = await Content._download_files("last_men", e, dl, func(d, t): pass)
			else:
				err = await Content.download_episode("last_men", e, dl, func(d, t): pass)
			print("DOWNLOAD ", mode2, " err='", err, "' cached=", Content.is_cached("last_men", e), " time=", Time.get_ticks_msec() - t0, "ms size=", Content.human_size(Content.cache_size()))
		"intro":
			Router.go("player", {"adventure": "last_men", "episode": "01_escape"}, false)
			for t in [1.0, 4.0, 6.0, 9.0]:
				await wait(t - (0 if t == 1.0 else 0))
				await shot("intro_%d" % int(t))
		"frame":
			# сцена на заданном кадре для сверки с reference_player
			var sid := OS.get_cmdline_user_args()[2]
			var fr := int(OS.get_cmdline_user_args()[3])
			var p = load("res://screens/Player.tscn").instantiate()
			p.setup({"adventure": "last_men", "episode": "01_escape"})
			p.set_meta("test_no_start", true)
			Router._screen_layer.add_child(p)
			await wait(0.5)
			var ls = await p.get_scene(sid)
			await p._show_world(ls.world)
			p.sp.start(ls, false, float(fr) / ls.fps + 0.0001)
			p.sp.set_paused(true)
			p.sp.paused = true
			await wait(0.3)
			p.subs.visible = false
			await shot("frame_%s_%d" % [sid, fr])
		"widgets":
			var p = load("res://screens/Player.tscn").instantiate()
			p.setup({"adventure": "last_men", "episode": "01_escape"})
			p.set_meta("test_no_start", true)
			Router._screen_layer.add_child(p)
			await wait(0.5)
			p.runner = LogicRunner.new(p, p.ep, {})
			p.show_background("think_loop")
			var games := [
				{"kind": "timing", "params": {"zone": [0.4, 0.6], "speed": 1.2, "rounds": 2, "title": "Тайминг"}},
				{"kind": "sequence", "params": {"items": ["А", "Б", "В", "А"], "show": true, "title": "Повтори"}},
				{"kind": "hold", "params": {"time": 1.5, "title": "Держи"}},
				{"kind": "swipe", "params": {"directions": ["left", "up"], "time_each": 2.0}},
				{"kind": "code", "params": {"length": 4, "answer": "1234", "title": "Код", "hint": "один два три четыре"}},
				{"kind": "drag", "params": {"items": [{"id": "a"}, {"id": "b"}], "targets": [{"id": "x", "rect": [0.2, 0.2, 0.2, 0.2]}, {"id": "y", "rect": [0.6, 0.2, 0.2, 0.2]}], "pairs": {"a": "x", "b": "y"}}},
			]
			for gm in games:
				var n := {"type": "minigame", "kind": gm["kind"], "params": gm["params"]}
				p.ui_minigame(n, p.runner.gen())
				await wait(1.6)
				await shot("mg_" + gm["kind"])
				p._clear_interactive()
			p.runner.vars["found"] = 1
			p.ui_overlay({"type": "overlay", "dim": 0.4, "widgets": [
				{"id": "pnl", "kind": "panel", "rect": [0.25, 0.1, 0.5, 0.7], "anim": "pop"},
				{"id": "txt", "kind": "text", "text": "Найдено: {found}", "rect": [0.3, 0.15, 0.4, 0.1], "size": 36, "color": "#3b2a12"},
				{"id": "pr", "kind": "progress", "bind": "found / 3.0", "rect": [0.3, 0.3, 0.4, 0.05]},
				{"id": "tm", "kind": "timer", "sec": 5, "rect": [0.45, 0.38, 0.1, 0.08], "on_end": [{"sfx": "fail"}]},
				{"id": "dr", "kind": "draggable", "src": "cover.png", "rect": [0.3, 0.5, 0.1, 0.1]},
				{"id": "dz", "kind": "dropzone", "accepts": ["dr"], "rect": [0.55, 0.5, 0.12, 0.12], "on_drop": [{"add": {"found": 1}}]},
				{"id": "kp", "kind": "keypad", "rect": [0.76, 0.1, 0.2, 0.5], "on_submit": [{"say": {"who": "kvas", "text": "Код {input}"}}]},
				{"id": "ok", "kind": "button", "text": "Понятно", "rect": [0.42, 0.7, 0.16, 0.08], "anim": "slide_up", "on_tap": [{"flash": "#ffffff"}]}]}, p.runner.gen())
			await wait(1.0)
			await shot("overlay")
			p.runner.run_actions([{"say": {"who": "zina", "text": "Привет!"}}, {"shake": 0.5}, {"hide": "pnl"}, {"unknown": 1}], p.runner.gen())
			await wait(0.5)
			await shot("overlay2")
		"camcheck":
			# камера по времени с шагом 1/120 с: скачки вне склеек = «чужой» план
			var p = load("res://screens/Player.tscn").instantiate()
			p.setup({"adventure": "last_men", "episode": "01_escape"})
			p.set_meta("test_no_start", true)
			Router._screen_layer.add_child(p)
			await wait(0.3)
			for sid in p.ep.data["scenes"]:
				var tl := Time.get_ticks_msec()
				var ls = await p.get_scene(sid)
				print("LOAD ", sid, " ", Time.get_ticks_msec() - tl, " ms")
				var loop := String(sid).ends_with("_loop")
				p.sp.start(ls, loop, 0.0)
				p.sp.set_paused(true)
				var cuts: Array = ls.tracks.get("cuts", [])
				var prev = null
				var bad := []
				var jumps := 0
				var steps := int(ls.duration * 120.0)
				for i in steps + 1:
					var tt := minf(i / 120.0, ls.duration)
					ls.ap.seek(tt, true)
					var pos: Vector3 = ls.cam.global_position
					if prev != null and pos.distance_to(prev) > 0.6:
						jumps += 1
						var fr := int(round(tt * ls.fps))
						var cuts_i := cuts.map(func(x): return int(x))
						if not (fr in cuts_i or (fr + 1) in cuts_i or (fr - 1) in cuts_i):
							bad.append("%.3f" % tt)
					prev = pos
				var seam := ""
				if loop:
					# как проигрыватель: на шве — склейка (seek в 0), без интерполяции конец→начало
					ls.ap.seek(ls.duration - 0.001, true)
					var a: Vector3 = ls.cam.global_position
					ls.ap.seek(0.0, true)
					seam = " шов цикла: склейка %.2f м (без промежуточных кадров)" % a.distance_to(ls.cam.global_position)
				print("CAM ", sid, " cuts=", cuts, " скачков всего: ", jumps, ", вне склеек: ", bad.size(), " ", bad.slice(0, 5), seam)
				p.sp.stop()
		"seed":
			# невидимые хотспоты, on_miss, видимый таймер, экран проигрыша с retry (синтетический узел)
			var p = load("res://screens/Player.tscn").instantiate()
			p.setup({"adventure": "last_men", "episode": "01_escape"})
			p.set_meta("test_no_start", true)
			Router._screen_layer.add_child(p)
			await wait(0.5)
			p.runner = LogicRunner.new(p, p.ep, {})
			var nodes: Dictionary = p.ep.data["nodes"]
			nodes["diff"] = {"type": "interact", "background": "lever_loop", "hint": "Найди 1 отличие", "timeout": 8, "timer_visible": true,
				"on_enter": [{"set": {"misses": 0, "found": 0, "retry": "diff"}}],
				"hotspots": [{"id": "lever", "node": "rychag__main", "radius": 90, "hidden": true, "once": true,
					"on_tap": [{"add": {"found": 1}}, {"if": "found >= 1", "then": [{"goto": "win"}]}]}],
				"on_miss": [{"add": {"misses": 1}}, {"shake": 0.3}, {"if": "misses >= 3", "then": [{"goto": "lose"}]}],
				"on_timeout": [{"goto": "lose"}]}
			nodes["lose"] = {"type": "overlay", "background": "lever_loop", "dim": 0.6, "widgets": [
				{"id": "t", "kind": "text", "text": "Ты проиграл", "rect": [0.3, 0.3, 0.4, 0.1], "size": 48},
				{"id": "b", "kind": "button", "text": "Попробовать снова", "rect": [0.38, 0.55, 0.24, 0.1],
					"on_tap": [{"if": "retry == 'diff'", "then": [{"goto": "diff"}], "else": [{"goto": "win"}]}]}]}
			nodes["win"] = {"type": "end", "title": "Нашёл", "text": "ok"}
			p.runner.start("diff")
			await wait(1.5)
			await shot("seed_diff")
			var hl: HotspotLayer = null
			for c in p.interactive.get_children():
				if c is HotspotLayer:
					hl = c
			var it = hl._items[0]
			print("SEED hidden ring visible=", it["ring"].visible, " label visible=", it["label"].visible, " on=", it["on"])
			# 3 промаха: один двойной (засчитывается один раз), потом ещё два
			for xy in [Vector2(100, 600), Vector2(102, 600)]:
				var ev := InputEventMouseButton.new(); ev.button_index = MOUSE_BUTTON_LEFT; ev.pressed = true; ev.position = xy
				hl._gui_input(ev)
			await wait(0.1)
			print("SEED после двойного тапа misses=", p.runner.vars.get("misses"))
			for i in 2:
				await wait(0.4)
				var ev2 := InputEventMouseButton.new(); ev2.button_index = MOUSE_BUTTON_LEFT; ev2.pressed = true; ev2.position = Vector2(100, 600)
				if is_instance_valid(hl):
					hl._gui_input(ev2)
			await wait(1.0)
			print("SEED после 3 промахов node=", p.runner.node_id)
			await shot("seed_lose")
			# «Попробовать снова» → goto из if внутри on_tap
			for c in p.interactive.get_children():
				if c is Overlay2D:
					c._tap("b")
			await wait(1.0)
			print("SEED retry → node=", p.runner.node_id, " misses=", p.runner.vars.get("misses"))
			# находим невидимое отличие тапом по его экранной точке
			for c in p.interactive.get_children():
				if c is HotspotLayer:
					await wait(0.3)
					var ev3 := InputEventMouseButton.new(); ev3.button_index = MOUSE_BUTTON_LEFT; ev3.pressed = true; ev3.position = c._items[0]["screen"]
					c._gui_input(ev3)
			await wait(1.0)
			print("SEED тап по отличию → node=", p.runner.node_id)
			# таймаут: снова в diff и ждём 8 с
			for c in p.get_children():
				if c is EndScreen:
					c.queue_free()
			p.runner.ended = false
			p._finished = false
			p.runner.goto("diff")
			await wait(4.5)
			await shot("seed_timer_red")
			await wait(4.5)
			print("SEED по таймеру → node=", p.runner.node_id)
		"path":
			# проходим серию по заданному пути: аргументы — действия
			Router.go("player", {"adventure": "last_men", "episode": "01_escape"}, false)
			var steps := OS.get_cmdline_user_args().slice(2)
			for st in steps:
				await _step(st)
			await wait(1.0)
			await shot("path_end")

func _measure(sec: float) -> String:
	var frames := Engine.get_process_frames()
	var t0 := Time.get_ticks_usec()
	await wait(sec)
	var dt := (Time.get_ticks_usec() - t0) / 1000000.0
	var fps := (Engine.get_process_frames() - frames) / dt
	return "%.1f fps, draw calls %d, primitives %d" % [fps, Performance.get_monitor(Performance.RENDER_TOTAL_DRAW_CALLS_IN_FRAME), Performance.get_monitor(Performance.RENDER_TOTAL_PRIMITIVES_IN_FRAME)]

func _player():
	return Router.current

## Шаг пути: wait:<сек>, choose:<i>, tap:<hotspot>, taps:<n>, shot:<имя>, skip (перемотка сцены)
func _step(st: String) -> void:
	var p = _player()
	var a := st.split(":")
	match a[0]:
		"wait":
			await wait(float(a[1]))
		"skip":
			# ждём, пока идёт сцена, ускоряя время
			Engine.time_scale = 8.0
			var w0 := Time.get_ticks_msec()
			while not (p.sp.playing and not p.sp.looped) and Time.get_ticks_msec() - w0 < 4000:
				await get_tree().process_frame
			while p.sp.playing and not p.sp.looped:
				await get_tree().process_frame
			Engine.time_scale = 1.0
			await wait(0.6)
		"shot":
			await shot(a[1])
		"choose":
			for c in p.interactive.get_children():
				if c is ChoicePanel:
					c._finish(int(a[1]))
			await wait(0.5)
		"tap":
			for c in p.interactive.get_children():
				if c is HotspotLayer:
					for it in c._items:
						if it["h"].get("id") == a[1]:
							print("hotspot ", a[1], " on=", it["on"], " screen=", it["screen"])
							c._trigger(it)
			await wait(0.5)
		"taps":
			for c in p.interactive.get_children():
				if c is Minigame:
					for i in int(a[1]):
						if not is_instance_valid(c) or c.finished:
							break
						var ev := InputEventMouseButton.new()
						ev.button_index = MOUSE_BUTTON_LEFT
						ev.pressed = true
						c._gui_input(ev)
						await wait(0.05)
			await wait(0.5)
		"state":
			print("STATE node=", p.runner.node_id, " vars=", p.runner.vars, " adv=", p.runner.adv)
