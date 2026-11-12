class_name EndScreen
extends Control
## Итог серии: название концовки, текст, кнопки «Дальше» (к списку серий) и «Пройти заново».

signal chosen(index: int)

var title := ""
var text := ""
var _done := false

func _ready() -> void:
	UI.full(self)
	mouse_filter = Control.MOUSE_FILTER_STOP
	var dim := ColorRect.new()
	dim.color = Color(0, 0, 0, 0.0)
	UI.full(dim)
	add_child(dim)
	create_tween().tween_property(dim, "color:a", 0.6, 0.8)
	var center := CenterContainer.new()
	UI.full(center)
	add_child(center)
	var panel := PanelContainer.new()
	panel.custom_minimum_size = Vector2(640, 0)
	center.add_child(panel)
	var vb := VBoxContainer.new()
	vb.add_theme_constant_override("separation", 18)
	panel.add_child(vb)
	var small := UI.label("Серия пройдена", UI.FS_SMALL, false, UI.C_DIM)
	small.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	vb.add_child(small)
	var t := UI.label(title, UI.FS_BIG, true, Color(1, 0.85, 0.4))
	t.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	t.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	t.custom_minimum_size = Vector2(592, 0)
	vb.add_child(t)
	if text != "":
		var l := UI.label(text, UI.FS_SMALL)
		l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		l.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		l.custom_minimum_size = Vector2(592, 0)
		vb.add_child(l)
	var row := HBoxContainer.new()
	row.alignment = BoxContainer.ALIGNMENT_CENTER
	row.add_theme_constant_override("separation", 18)
	vb.add_child(row)
	var b1 := UI.button("Дальше", func(): _pick(0))
	b1.custom_minimum_size = Vector2(260, 72)
	row.add_child(b1)
	var b2 := UI.button("Пройти заново", func(): _pick(1))
	b2.custom_minimum_size = Vector2(260, 72)
	row.add_child(b2)
	panel.modulate.a = 0.0
	panel.pivot_offset = Vector2(320, 150)
	panel.scale = Vector2(0.85, 0.85)
	var tw := create_tween().set_parallel(true)
	tw.tween_property(panel, "modulate:a", 1.0, 0.4).set_delay(0.4)
	tw.tween_property(panel, "scale", Vector2.ONE, 0.45).set_delay(0.4).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)

func _pick(i: int) -> void:
	if _done:
		return
	_done = true
	chosen.emit(i)

func on_back() -> void:
	_pick(0)
