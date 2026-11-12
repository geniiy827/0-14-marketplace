class_name EpisodeRow
extends Card
## Карточка серии: мини-обложка, «Серия N», название, описание, длительность, размер загрузки.
## Состояния: закрыта (замок, серая), доступна, пройдена (галочка + концовка).

enum State { LOCKED, AVAILABLE, COMPLETED }

var number := 1
var duration := 0.0
var meta_text := ""
var state := State.AVAILABLE
var ending_text := ""
var row_width := 900.0
var _cover_rect: TextureRect

func _ready() -> void:
	mouse_filter = Control.MOUSE_FILTER_PASS
	var h := 150.0
	custom_minimum_size = Vector2(row_width, h)
	size = custom_minimum_size
	pivot_offset = size * 0.5
	var bg := ColorRect.new()
	bg.color = Color(0, 0, 0, 0.28)
	bg.size = size
	bg.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(bg)
	var bar := ColorRect.new()
	bar.color = accent
	bar.size = Vector2(6, h)
	bar.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(bar)
	var cw := (h - 24) * 16.0 / 9.0
	var cbg := ColorRect.new()
	cbg.color = UI.C_OUTLINE
	cbg.position = Vector2(20, 10)
	cbg.size = Vector2(cw + 4, h - 20)
	cbg.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(cbg)
	_cover_rect = TextureRect.new()
	_cover_rect.position = Vector2(22, 12)
	_cover_rect.size = Vector2(cw, h - 24)
	_cover_rect.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	_cover_rect.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_COVERED
	_cover_rect.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(_cover_rect)
	var x := 22 + cw + 24
	var right_w := 200.0
	var vb := VBoxContainer.new()
	vb.position = Vector2(x, 10)
	vb.size = Vector2(row_width - x - right_w, h - 20)
	vb.add_theme_constant_override("separation", 0)
	vb.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(vb)
	vb.add_child(UI.label("Серия %d" % number, UI.FS_SMALL, false, accent.lightened(0.25)))
	var t := UI.label(title, UI.FS_SMALL, true)
	t.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	t.clip_text = true
	t.custom_minimum_size.x = vb.size.x
	vb.add_child(t)
	var d := UI.label(description, UI.FS_SMALL, false, UI.C_DIM)
	d.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	d.max_lines_visible = 1
	d.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	d.custom_minimum_size.x = vb.size.x
	d.add_theme_constant_override("outline_size", 4)
	vb.add_child(d)
	var m := UI.label(meta_text, UI.FS_SMALL, false, Color(1, 1, 1, 0.7))
	m.add_theme_constant_override("outline_size", 4)
	vb.add_child(m)
	# справа — состояние
	var rv := VBoxContainer.new()
	rv.position = Vector2(row_width - right_w, 0)
	rv.size = Vector2(right_w - 20, h)
	rv.alignment = BoxContainer.ALIGNMENT_CENTER
	rv.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(rv)
	match state:
		State.LOCKED:
			var ic := TextureRect.new()
			ic.texture = UI.px_tex("res://assets/ui/icon_lock.png", 3)
			ic.stretch_mode = TextureRect.STRETCH_KEEP_CENTERED
			rv.add_child(ic)
			var l := UI.label("Закрыта", UI.FS_SMALL, false, UI.C_DIM)
			l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
			rv.add_child(l)
			modulate = Color(0.55, 0.55, 0.55)
		State.COMPLETED:
			var ok := UI.label("✔ Пройдена", UI.FS_SMALL, true, Color(0.55, 1.0, 0.45))
			ok.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
			rv.add_child(ok)
			if ending_text != "":
				var e := UI.label(ending_text, UI.FS_SMALL, false, UI.C_DIM)
				e.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
				e.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
				e.clip_text = true
				e.custom_minimum_size.x = right_w - 20
				rv.add_child(e)
		_:
			var ic2 := TextureRect.new()
			ic2.texture = UI.px_tex("res://assets/ui/icon_play.png", 4)
			ic2.stretch_mode = TextureRect.STRETCH_KEEP_CENTERED
			rv.add_child(ic2)
			var l2 := UI.label("Играть", UI.FS_SMALL, true)
			l2.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
			rv.add_child(l2)

func set_cover(tex: Texture2D) -> void:
	if tex == null or _cover_rect == null:
		return
	_cover_rect.texture = tex
	_cover_rect.modulate.a = 0.0
	create_tween().tween_property(_cover_rect, "modulate:a", 1.0, 0.25)
