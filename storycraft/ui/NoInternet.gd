extends Control
## Неснимаемое окно «Нет подключения к серверу» с одной кнопкой «Попробовать снова».

signal retry

var _btn: Button
var _status: Label

func _ready() -> void:
	UI.full(self)
	mouse_filter = Control.MOUSE_FILTER_STOP
	var dim := ColorRect.new()
	dim.color = Color(0, 0, 0, 0.7)
	UI.full(dim)
	add_child(dim)
	var center := CenterContainer.new()
	UI.full(center)
	add_child(center)
	var panel := PanelContainer.new()
	panel.custom_minimum_size = Vector2(600, 0)
	center.add_child(panel)
	var box := VBoxContainer.new()
	box.add_theme_constant_override("separation", 18)
	panel.add_child(box)
	var ic := TextureRect.new()
	ic.texture = UI.px_tex("res://assets/ui/icon_wifi_off.png", 5)
	ic.stretch_mode = TextureRect.STRETCH_KEEP_CENTERED
	box.add_child(ic)
	var t := UI.label("Нет подключения к серверу", UI.FS_MID, true)
	t.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(t)
	var l := UI.label("Проверь интернет и попробуй снова", UI.FS_SMALL, false, UI.C_DIM)
	l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(l)
	_status = UI.label("", UI.FS_SMALL, false, UI.C_DIM)
	_status.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(_status)
	var row := HBoxContainer.new()
	row.alignment = BoxContainer.ALIGNMENT_CENTER
	box.add_child(row)
	_btn = UI.button("Попробовать снова", func(): retry.emit())
	_btn.custom_minimum_size = Vector2(360, 0)
	row.add_child(_btn)
	UI.fade_in(self, 0.2)

func set_busy(b: bool) -> void:
	if _btn == null:
		return
	_btn.disabled = b
	_status.text = "Проверяю связь…" if b else ""

func close() -> void:
	var tw := create_tween()
	tw.tween_property(self, "modulate:a", 0.0, 0.15)
	tw.tween_callback(queue_free)
