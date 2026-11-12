extends Minigame
## drag: перетащи предметы в нужные места (2D). items [{id,image}], targets [{id,rect}], pairs {item: target}.

var _items := {}         # id → {node, home, placed}
var _targets := {}       # id → Rect2 (экран)
var _pairs := {}
var _drag_id := ""
var _grab := Vector2.ZERO
var _info: Label

func _ready() -> void:
	UI.full(self)
	mouse_filter = Control.MOUSE_FILTER_STOP
	var vs := get_viewport_rect().size
	_pairs = p("pairs", {}) if p("pairs") is Dictionary else {}
	for t in p("targets", []):
		if not t is Dictionary:
			continue
		var r: Array = t.get("rect", [0.4, 0.3, 0.2, 0.2])
		var rect := Rect2(float(r[0]) * vs.x, float(r[1]) * vs.y, float(r[2]) * vs.x, float(r[3]) * vs.y)
		_targets[String(t.get("id", ""))] = rect
		var zone := ColorRect.new()
		zone.color = Color(1, 1, 1, 0.12)
		zone.position = rect.position
		zone.size = rect.size
		zone.mouse_filter = Control.MOUSE_FILTER_IGNORE
		add_child(zone)
		if t.has("image"):
			var img := TextureRect.new()
			img.texture = Content.load_texture(player.ep.file(String(t["image"])))
			img.position = rect.position
			img.size = rect.size
			img.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
			img.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
			img.mouse_filter = Control.MOUSE_FILTER_IGNORE
			add_child(img)
	var items: Array = p("items", [])
	var n := items.size()
	var w := minf(160.0, vs.x * 0.8 / maxi(1, n))
	for i in n:
		var it = items[i]
		if not it is Dictionary:
			continue
		var id := String(it.get("id", str(i)))
		var node := TextureRect.new()
		var tex: Texture2D = null
		if it.has("image"):
			tex = Content.load_texture(player.ep.file(String(it["image"])))
		if tex:
			node.texture = tex
		else:
			node.texture = UI.px_tex("res://assets/ui/panel.png", 2)
		node.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
		node.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
		node.size = Vector2(w, w)
		var home := Vector2(vs.x * 0.1 + (vs.x * 0.8 - n * w) * 0.5 + i * w, vs.y - w - 40)
		node.position = home
		node.mouse_filter = Control.MOUSE_FILTER_IGNORE
		add_child(node)
		if not tex:
			var l := UI.label(id, UI.FS_SMALL, true)
			l.position = Vector2(8, w * 0.4)
			node.add_child(l)
		_items[id] = {"node": node, "home": home, "placed": false}
	_info = UI.label(String(p("title", "Перетащи предметы на места")), UI.FS_SMALL, true)
	UI.pin(_info, 0.5, 0.0, 0, 90)
	add_child(_info)
	set_var("score", 0)

func _gui_input(e: InputEvent) -> void:
	if finished:
		return
	if e is InputEventMouseButton and e.button_index == MOUSE_BUTTON_LEFT:
		accept_event()
		if e.pressed:
			for id in _items:
				var it: Dictionary = _items[id]
				var node: TextureRect = it["node"]
				if not it["placed"] and Rect2(node.position, node.size).has_point(e.position):
					_drag_id = id
					_grab = e.position - node.position
					node.move_to_front()
					node.scale = Vector2(1.1, 1.1)
					Audio.play_sfx("pop", -8.0)
					break
		elif _drag_id != "":
			_drop(_drag_id, e.position)
			_drag_id = ""
	elif e is InputEventMouseMotion and _drag_id != "":
		_items[_drag_id]["node"].position = e.position - _grab

func _drop(id: String, at: Vector2) -> void:
	var it: Dictionary = _items[id]
	var node: TextureRect = it["node"]
	node.scale = Vector2.ONE
	var want := String(_pairs.get(id, ""))
	var hit := ""
	for tid in _targets:
		if (_targets[tid] as Rect2).has_point(at):
			hit = tid
			break
	if hit != "" and hit == want:
		it["placed"] = true
		var r: Rect2 = _targets[hit]
		var tw := node.create_tween()
		tw.tween_property(node, "position", r.get_center() - node.size * 0.5, 0.12)
		Audio.play_sfx("success", -10.0)
		var placed := 0
		for k in _items:
			if _items[k]["placed"]:
				placed += 1
		set_var("score", placed)
		if placed >= _pairs.size() or placed >= _items.size():
			finish(true)
	else:
		if hit != "":
			player.shake(0.2)
			Audio.play_sfx("fail", -10.0)
		var tw2 := node.create_tween()
		tw2.tween_property(node, "position", it["home"], 0.2).set_trans(Tween.TRANS_BACK)
