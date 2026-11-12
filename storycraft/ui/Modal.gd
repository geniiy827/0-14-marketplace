class_name Modal
extends Control
## Модальное окно: затемнение + деревянная панель с заголовком, текстом и кнопками.
## chosen(index) — номер нажатой кнопки. «Назад» = последняя кнопка (если окно закрываемое).

signal chosen(index: int)

var closable := true
var _title := ""
var _text := ""
var _buttons: Array = []
var _icon: Texture2D = null
var _panel: PanelContainer
var _box: VBoxContainer
var _btn_row: HBoxContainer
var _done := false

func configure(title: String, text: String, buttons: Array, can_close := true, icon: Texture2D = null) -> void:
	_title = title
	_text = text
	_buttons = buttons
	closable = can_close
	_icon = icon

func _ready() -> void:
	UI.full(self)
	mouse_filter = Control.MOUSE_FILTER_STOP
	var dim := ColorRect.new()
	dim.color = Color(0, 0, 0, 0.55)
	UI.full(dim)
	add_child(dim)
	var center := CenterContainer.new()
	UI.full(center)
	add_child(center)
	_panel = PanelContainer.new()
	_panel.custom_minimum_size = Vector2(560, 0)
	center.add_child(_panel)
	_box = VBoxContainer.new()
	_box.add_theme_constant_override("separation", 18)
	_panel.add_child(_box)
	if _icon != null:
		var ic := TextureRect.new()
		ic.texture = _icon
		ic.stretch_mode = TextureRect.STRETCH_KEEP_CENTERED
		_box.add_child(ic)
	if _title != "":
		var t := UI.label(_title, UI.FS_MID, true)
		t.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		_box.add_child(t)
	if _text != "":
		var l := UI.label(_text, UI.FS_SMALL, false, UI.C_DIM)
		l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		l.custom_minimum_size = Vector2(500, 0)
		_box.add_child(l)
	_btn_row = HBoxContainer.new()
	_btn_row.alignment = BoxContainer.ALIGNMENT_CENTER
	_btn_row.add_theme_constant_override("separation", 18)
	_box.add_child(_btn_row)
	for i in _buttons.size():
		var b := UI.button(String(_buttons[i]), _choose.bind(i))
		b.custom_minimum_size = Vector2(200, 0)
		_btn_row.add_child(b)
	# появление: pop
	_panel.pivot_offset = _panel.size * 0.5
	_panel.scale = Vector2(0.9, 0.9)
	modulate.a = 0.0
	await get_tree().process_frame
	_panel.pivot_offset = _panel.size * 0.5
	var tw := create_tween().set_parallel(true)
	tw.tween_property(self, "modulate:a", 1.0, 0.15)
	tw.tween_property(_panel, "scale", Vector2.ONE, 0.18).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)

func content_box() -> VBoxContainer:
	return _box

func button_row() -> HBoxContainer:
	return _btn_row

func _choose(i: int) -> void:
	if _done:
		return
	_done = true
	chosen.emit(i)
	close()

func on_back() -> void:
	if closable and not _buttons.is_empty():
		_choose(_buttons.size() - 1)

func close() -> void:
	_done = true
	var tw := create_tween()
	tw.tween_property(self, "modulate:a", 0.0, 0.12)
	tw.tween_callback(queue_free)
