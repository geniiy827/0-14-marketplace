extends Minigame
## sequence: повтори последовательность (как «Саймон»). items — подписи кнопок; show — сначала показать.

var _items: Array = []
var _btns: Array = []
var _pos := 0
var _input := false
var _info: Label

func _ready() -> void:
	make_panel(760)
	for it in p("items", []):
		_items.append(String(it))
	if _items.is_empty():
		finish(true)
		return
	var uniq: Array = []
	for it in _items:
		if not uniq.has(it):
			uniq.append(it)
	var row := HFlowContainer.new()
	row.alignment = FlowContainer.ALIGNMENT_CENTER
	row.add_theme_constant_override("h_separation", 12)
	row.add_theme_constant_override("v_separation", 12)
	box.add_child(row)
	for u in uniq:
		var b := UI.button(u)
		b.custom_minimum_size = Vector2(160, 72)
		b.pressed.connect(_press.bind(u, b))
		row.add_child(b)
		_btns.append({"id": u, "b": b})
	_info = UI.label("Запоминай…", UI.FS_SMALL, true)
	_info.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(_info)
	set_var("score", 0)
	if p("show", true):
		await _show_sequence()
	_input = true
	_info.text = "Повтори!"

func _btn(id: String) -> Button:
	for x in _btns:
		if x["id"] == id:
			return x["b"]
	return null

func _show_sequence() -> void:
	for b in _btns:
		b["b"].disabled = true
	await get_tree().create_timer(0.6, false).timeout
	for it in _items:
		var b := _btn(it)
		if b:
			b.disabled = false
			b.modulate = Color(1.6, 1.5, 1.1)
			Audio.play_sfx("tick", -4.0)
			await get_tree().create_timer(0.45, false).timeout
			b.modulate = Color.WHITE
			b.disabled = true
			await get_tree().create_timer(0.2, false).timeout
	for b in _btns:
		b["b"].disabled = false

func _press(id: String, b: Button) -> void:
	if not _input or finished:
		return
	if _items[_pos] == id:
		_pos += 1
		set_var("score", _pos)
		if _pos >= _items.size():
			_info.text = "Верно!"
			finish(true)
	else:
		_info.text = "Ошибка!"
		player.shake(0.4)
		finish(false)
