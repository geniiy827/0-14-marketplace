class_name HotspotLayer
extends Control
## Хотспоты 3D-взаимодействия: позиция узла фоновой сцены (каждый кадр) или точки pos →
## экран через Camera3D.unproject_position. Пульсирующее кольцо + подпись. Тап в радиусе (px при 720p)
## или удержание (hold, с круговым прогрессом) → действия on_tap.

var player              # Player
var gen := 0
var hotspots: Array = []

var _items: Array = []   # {h, ring, label, hidden_by_action, used, busy}
var _ring_tex: Texture2D
var _time := 0.0
var _hold_item = null
var _hold_t := 0.0
var _cond_t := 0.0

func _ready() -> void:
	UI.full(self)
	mouse_filter = Control.MOUSE_FILTER_STOP
	_ring_tex = UI.px_tex("res://assets/ui/hotspot_ring.png", 1)
	for h in hotspots:
		if not h is Dictionary:
			continue
		var ring := TextureRect.new()
		ring.texture = _ring_tex
		ring.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
		ring.stretch_mode = TextureRect.STRETCH_SCALE
		ring.texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST
		ring.mouse_filter = Control.MOUSE_FILTER_IGNORE
		add_child(ring)
		var lbl := UI.label(String(h.get("label", "")), UI.FS_SMALL, true)
		lbl.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		lbl.mouse_filter = Control.MOUSE_FILTER_IGNORE
		add_child(lbl)
		_items.append({"h": h, "ring": ring, "label": lbl, "hidden": false, "used": false, "busy": false,
			"cond": true, "screen": Vector2.ZERO, "on": false})
	_eval_conditions()

func set_hotspot_visible(id: String, v: bool) -> void:
	for it in _items:
		if String(it["h"].get("id", "")) == id:
			it["hidden"] = not v

func _eval_conditions() -> void:
	for it in _items:
		var h: Dictionary = it["h"]
		it["cond"] = player.runner.test(h["if"]) if h.has("if") else true

func _world_pos(h: Dictionary):
	if h.has("node"):
		return player.sp.node_position(String(h["node"]))
	var p = h.get("pos")
	if p is Array and p.size() >= 3:
		return Vector3(float(p[0]), float(p[1]), float(p[2]))
	return null

func _scale_k() -> float:
	return get_viewport_rect().size.y / 720.0

func _process(delta: float) -> void:
	_time += delta
	_cond_t += delta
	if _cond_t > 0.25:
		_cond_t = 0.0
		_eval_conditions()
	var cam: Camera3D = player.sp.camera()
	var k := _scale_k()
	for it in _items:
		var h: Dictionary = it["h"]
		var ring: TextureRect = it["ring"]
		var lbl: Label = it["label"]
		var wp = _world_pos(h)
		var on: bool = not it["hidden"] and not it["used"] and it["cond"] and wp != null and cam != null
		if on and cam.is_position_behind(wp):
			on = false
		it["on"] = on
		ring.visible = on
		lbl.visible = on and lbl.text != ""
		if not on:
			continue
		var sp: Vector2 = cam.unproject_position(wp)
		it["screen"] = sp
		var base := clampf(float(h.get("radius", 80)) * 1.1, 56.0, 140.0) * k
		var pulse := 1.0 + 0.08 * sin(_time * 4.0 + float(_items.find(it)))
		var sz := base * pulse
		ring.size = Vector2(sz, sz)
		ring.position = sp - ring.size * 0.5
		ring.modulate.a = 0.75 + 0.25 * sin(_time * 4.0)
		lbl.reset_size()
		lbl.position = sp + Vector2(-lbl.size.x * 0.5, sz * 0.5 + 4)
	# удержание
	if _hold_item != null:
		var need := float(_hold_item["h"].get("hold", 1.0))
		_hold_t += delta
		if not _hold_item["on"]:
			_hold_item = null
		elif _hold_t >= need:
			var it = _hold_item
			_hold_item = null
			_trigger(it)
	queue_redraw()

func _draw() -> void:
	if _hold_item == null:
		return
	var need := maxf(0.05, float(_hold_item["h"].get("hold", 1.0)))
	var r := clampf(float(_hold_item["h"].get("radius", 80)) * 0.6, 30.0, 80.0) * _scale_k()
	var c: Vector2 = _hold_item["screen"]
	draw_arc(c, r, -PI * 0.5, -PI * 0.5 + TAU * clampf(_hold_t / need, 0.0, 1.0), 48, Color(1, 0.85, 0.4), 8.0)

func _gui_input(e: InputEvent) -> void:
	if not (e is InputEventMouseButton and e.button_index == MOUSE_BUTTON_LEFT):
		return
	if not e.pressed:
		_hold_item = null
		return
	var k := _scale_k()
	var best = null
	var best_d := INF
	for it in _items:
		if not it["on"] or it["busy"]:
			continue
		var d: float = (it["screen"] as Vector2).distance_to(e.position)
		if d <= float(it["h"].get("radius", 80)) * k and d < best_d:
			best = it
			best_d = d
	if best == null:
		return
	accept_event()
	if best["h"].has("hold"):
		_hold_item = best
		_hold_t = 0.0
		Audio.play_sfx("tick", -8.0)
	else:
		_trigger(best)

func _trigger(it: Dictionary) -> void:
	var h: Dictionary = it["h"]
	if h.get("once", false):
		it["used"] = true
	it["busy"] = true
	var ring: TextureRect = it["ring"]
	var tw := ring.create_tween()
	tw.tween_property(ring, "modulate", Color(2, 2, 2, 1), 0.06)
	tw.tween_property(ring, "modulate", Color.WHITE, 0.15)
	await player.hotspot_tapped(h, gen)
	if is_instance_valid(self):
		it["busy"] = false
