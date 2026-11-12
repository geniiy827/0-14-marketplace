class_name Overlay2D
extends Control
## Свободная 2D-композиция узла overlay: виджеты image, text, button, panel, progress, timer,
## draggable, dropzone, keypad. rect — доли экрана [x, y, w, h]; anim — fade|pop|slide_left|slide_up|shake.

var player              # Player
var gen := 0
var node := {}

var _widgets := {}      # id → {w: Dictionary, c: Control, ...}
var _dim: ColorRect
var _drag_id := ""
var _grab := Vector2.ZERO
var _refresh_t := 0.0

func _ready() -> void:
	UI.full(self)
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	_dim = ColorRect.new()
	_dim.color = Color(0, 0, 0, clampf(float(node.get("dim", 0.0)), 0.0, 1.0))
	UI.full(_dim)
	_dim.mouse_filter = Control.MOUSE_FILTER_STOP if float(node.get("dim", 0.0)) > 0.0 else Control.MOUSE_FILTER_IGNORE
	add_child(_dim)
	var i := 0
	for w in node.get("widgets", []):
		if w is Dictionary:
			_make(w, i)
			i += 1

func _rect(w: Dictionary) -> Rect2:
	var vs := get_viewport_rect().size
	var r = w.get("rect", [0.4, 0.4, 0.2, 0.1])
	if not r is Array or r.size() < 4:
		r = [0.4, 0.4, 0.2, 0.1]
	return Rect2(float(r[0]) * vs.x, float(r[1]) * vs.y, float(r[2]) * vs.x, float(r[3]) * vs.y)

func _make(w: Dictionary, order: int) -> void:
	var id := String(w.get("id", "w%d" % order))
	var kind := String(w.get("kind", "image"))
	var r := _rect(w)
	var c: Control = null
	var info := {"w": w, "kind": kind, "home": r.position}
	match kind:
		"image", "draggable":
			var t := TextureRect.new()
			t.texture = _tex(String(w.get("src", w.get("image", ""))))
			t.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
			t.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
			t.texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST
			c = t
		"text":
			var l := UI.label("", int(w.get("size", UI.FS_SMALL)), bool(w.get("bold", false)))
			l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
			if w.has("color"):
				l.add_theme_color_override("font_color", UI.hex(String(w["color"])))
				# тёмный текст на записке — без обводки
				if UI.hex(String(w["color"])).get_luminance() < 0.4:
					l.add_theme_constant_override("outline_size", 0)
			l.horizontal_alignment = {"left": HORIZONTAL_ALIGNMENT_LEFT, "right": HORIZONTAL_ALIGNMENT_RIGHT}.get(String(w.get("align", "center")), HORIZONTAL_ALIGNMENT_CENTER)
			l.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
			c = l
		"button":
			var b := UI.button("")
			b.pressed.connect(_tap.bind(id))
			c = b
		"panel":
			c = PanelContainer.new()
		"progress":
			var pb := ProgressBar.new()
			pb.show_percentage = false
			pb.max_value = 1.0
			pb.step = 0.0
			c = pb
		"timer":
			var tl := UI.label("", int(w.get("size", UI.FS_MID)), true)
			tl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
			tl.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
			info["left"] = float(w.get("sec", w.get("time", 10.0)))
			c = tl
		"dropzone":
			var z := Panel.new()
			var sb := StyleBoxFlat.new()
			sb.bg_color = Color(1, 1, 1, 0.1)
			sb.border_color = Color(1, 0.85, 0.4, 0.8)
			sb.set_border_width_all(3)
			z.add_theme_stylebox_override("panel", sb)
			c = z
		"keypad":
			c = _keypad(id, w)
		_:
			player.logic_error("Неизвестный виджет «%s»" % kind)
			return
	c.position = r.position
	c.size = r.size
	if kind != "keypad":
		c.custom_minimum_size = Vector2.ZERO
	c.mouse_filter = Control.MOUSE_FILTER_IGNORE
	if kind == "button" or kind == "keypad":
		c.mouse_filter = Control.MOUSE_FILTER_STOP
	elif w.has("on_tap") or kind == "draggable":
		c.mouse_filter = Control.MOUSE_FILTER_STOP
		c.gui_input.connect(_widget_input.bind(id))
	add_child(c)
	info["c"] = c
	_widgets[id] = info
	if w.get("hidden", false):
		c.visible = false
	_update_text(id)
	_anim(c, String(w.get("anim", "")), order)

func _tex(src: String) -> Texture2D:
	if src == "":
		return null
	if src.begins_with("res://"):
		return load(src) if ResourceLoader.exists(src) else null
	return Content.load_texture(player.ep.file(src))

func _keypad(id: String, w: Dictionary) -> Control:
	var vb := VBoxContainer.new()
	var disp := UI.label("", UI.FS_MID, true, Color(1, 0.9, 0.5))
	disp.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	vb.add_child(disp)
	var grid := GridContainer.new()
	grid.columns = 3
	vb.add_child(grid)
	var var_name := String(w.get("var", "input"))
	var maxlen := int(w.get("length", 6))
	var state := {"s": ""}
	for k in ["1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK"]:
		var b := UI.button(k)
		b.custom_minimum_size = Vector2(80, 56)
		b.size_flags_horizontal = Control.SIZE_EXPAND_FILL
		b.pressed.connect(func():
			if k == "⌫":
				state["s"] = String(state["s"]).substr(0, maxi(0, String(state["s"]).length() - 1))
			elif k == "OK":
				player.runner.set_var(var_name, state["s"])
				_run(w.get("on_submit", w.get("on_tap", [])))
				return
			elif String(state["s"]).length() < maxlen:
				state["s"] = String(state["s"]) + k
			disp.text = state["s"]
			player.runner.set_var(var_name, state["s"]))
		grid.add_child(b)
	return vb

func _anim(c: Control, a: String, order: int) -> void:
	if a == "":
		return
	var delay := 0.05 * order
	var end_pos := c.position
	c.pivot_offset = c.size * 0.5
	var tw := c.create_tween().set_parallel(true)
	match a:
		"fade":
			c.modulate.a = 0.0
			tw.tween_property(c, "modulate:a", 1.0, 0.3).set_delay(delay)
		"pop":
			c.scale = Vector2(0.6, 0.6)
			c.modulate.a = 0.0
			tw.tween_property(c, "scale", Vector2.ONE, 0.3).set_delay(delay).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)
			tw.tween_property(c, "modulate:a", 1.0, 0.15).set_delay(delay)
		"slide_left":
			c.position.x += get_viewport_rect().size.x * 0.5
			c.modulate.a = 0.0
			tw.tween_property(c, "position", end_pos, 0.35).set_delay(delay).set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_OUT)
			tw.tween_property(c, "modulate:a", 1.0, 0.2).set_delay(delay)
		"slide_up":
			c.position.y += get_viewport_rect().size.y * 0.4
			c.modulate.a = 0.0
			tw.tween_property(c, "position", end_pos, 0.35).set_delay(delay).set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_OUT)
			tw.tween_property(c, "modulate:a", 1.0, 0.2).set_delay(delay)
		"shake":
			tw.set_parallel(false)
			for i in 4:
				tw.tween_property(c, "position:x", end_pos.x + 10, 0.04)
				tw.tween_property(c, "position:x", end_pos.x - 10, 0.04)
			tw.tween_property(c, "position:x", end_pos.x, 0.04)

func _update_text(id: String) -> void:
	var info: Dictionary = _widgets[id]
	var w: Dictionary = info["w"]
	var c: Control = info["c"]
	match info["kind"]:
		"text":
			(c as Label).text = player.runner.text(w.get("text", ""))
		"button":
			(c as Button).text = player.runner.text(w.get("text", ""))
		"progress":
			(c as ProgressBar).value = clampf(float(player.runner.eval(w.get("bind", "0"), 0.0)), 0.0, 1.0)

func _process(delta: float) -> void:
	_refresh_t += delta
	for id in _widgets:
		var info: Dictionary = _widgets[id]
		if info["kind"] == "timer" and info.has("left") and info["c"].visible:
			info["left"] = float(info["left"]) - delta
			var left: float = maxf(0.0, info["left"])
			player.runner.vars["time_left"] = left
			(info["c"] as Label).text = "%d" % ceili(left)
			if info["left"] <= 0.0:
				info.erase("left")
				_run(info["w"].get("on_end", []))
	if _refresh_t >= 0.15:
		_refresh_t = 0.0
		for id in _widgets:
			if _widgets[id]["kind"] in ["text", "button", "progress"]:
				_update_text(id)

func set_widget_visible(id: String, v: bool) -> bool:
	if not _widgets.has(id):
		return false
	var c: Control = _widgets[id]["c"]
	if v and not c.visible:
		UI.fade_in(c, 0.2)
	c.visible = v
	return true

func _tap(id: String) -> void:
	_run(_widgets[id]["w"].get("on_tap", []))

func _run(actions) -> void:
	await player.runner.run_actions(actions, gen)

func _widget_input(e: InputEvent, id: String) -> void:
	var info: Dictionary = _widgets[id]
	var c: Control = info["c"]
	if not (e is InputEventMouseButton or e is InputEventMouseMotion):
		return
	if info["kind"] == "draggable":
		if e is InputEventMouseButton and e.button_index == MOUSE_BUTTON_LEFT:
			if e.pressed:
				_drag_id = id
				_grab = c.get_global_mouse_position() - c.global_position
				c.move_to_front()
			elif _drag_id == id:
				_drag_id = ""
				_drop(id, c.get_global_mouse_position())
		elif e is InputEventMouseMotion and _drag_id == id:
			c.global_position = c.get_global_mouse_position() - _grab
		return
	if e is InputEventMouseButton and not e.pressed and e.button_index == MOUSE_BUTTON_LEFT:
		_tap(id)

func _drop(id: String, at: Vector2) -> void:
	var info: Dictionary = _widgets[id]
	var c: Control = info["c"]
	for zid in _widgets:
		var z: Dictionary = _widgets[zid]
		if z["kind"] != "dropzone" or not z["c"].visible:
			continue
		var zr := Rect2(z["c"].global_position, z["c"].size)
		if not zr.has_point(at):
			continue
		var acc = z["w"].get("accepts", [])
		if (acc is Array and (acc.is_empty() or acc.has(id))) or (acc is String and acc == id):
			var tw := c.create_tween()
			tw.tween_property(c, "global_position", zr.get_center() - c.size * 0.5, 0.12)
			c.mouse_filter = Control.MOUSE_FILTER_IGNORE
			player.runner.vars["dropped"] = id
			_run(z["w"].get("on_drop", []))
			return
	var tw2 := c.create_tween()
	tw2.tween_property(c, "position", info["home"], 0.2).set_trans(Tween.TRANS_BACK)
