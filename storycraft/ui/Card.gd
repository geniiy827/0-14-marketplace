class_name Card
extends Control
## Карточка приключения: превью 16:9 + полоса снизу с названием и кратким описанием на цвете accent.
## Нажатие (с анимацией) → tapped; долгое удержание → long_pressed.

signal tapped
signal long_pressed

const LONG_PRESS := 0.6

var card_id := ""
var title := ""
var description := ""
var accent := Color(1, 0.54, 0.23)
var card_width := 420.0
var strip_height := 104.0

var _cover: TextureRect
var _pressing := false
var _press_time := 0.0
var _long_fired := false
var _tw: Tween

func _ready() -> void:
	mouse_filter = Control.MOUSE_FILTER_PASS
	size_flags_vertical = Control.SIZE_SHRINK_CENTER
	var cover_h := card_width * 9.0 / 16.0
	custom_minimum_size = Vector2(card_width, cover_h + strip_height)
	size = custom_minimum_size
	pivot_offset = size * 0.5
	# рамка
	var frame := ColorRect.new()
	frame.color = UI.C_OUTLINE
	frame.position = Vector2(-4, -4)
	frame.size = size + Vector2(8, 8)
	frame.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(frame)
	var bg := ColorRect.new()
	bg.color = Color(0.12, 0.10, 0.08)
	bg.size = Vector2(card_width, cover_h)
	bg.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(bg)
	_cover = TextureRect.new()
	_cover.size = Vector2(card_width, cover_h)
	_cover.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	_cover.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_COVERED
	_cover.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(_cover)
	var strip := ColorRect.new()
	strip.color = accent
	strip.position = Vector2(0, cover_h)
	strip.size = Vector2(card_width, strip_height)
	strip.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(strip)
	var vb := VBoxContainer.new()
	vb.position = Vector2(16, cover_h + 8)
	vb.size = Vector2(card_width - 32, strip_height - 16)
	vb.add_theme_constant_override("separation", 2)
	vb.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(vb)
	var t := UI.label(title, UI.FS_SMALL, true)
	t.clip_text = true
	t.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	t.custom_minimum_size.x = card_width - 32
	vb.add_child(t)
	var d := UI.label(description, UI.FS_SMALL, false, Color(1, 1, 1, 0.92))
	d.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	d.max_lines_visible = 2
	d.text_overrun_behavior = TextServer.OVERRUN_TRIM_ELLIPSIS
	d.custom_minimum_size = Vector2(card_width - 32, 0)
	d.add_theme_font_size_override("font_size", UI.FS_SMALL)
	d.add_theme_constant_override("outline_size", 4)
	vb.add_child(d)

func set_cover(tex: Texture2D) -> void:
	if tex == null or _cover == null:
		return
	_cover.texture = tex
	_cover.modulate.a = 0.0
	create_tween().tween_property(_cover, "modulate:a", 1.0, 0.25)

func _carousel() -> Carousel:
	var p := get_parent()
	while p != null and not p is Carousel:
		p = p.get_parent()
	return p as Carousel

func _gui_input(e: InputEvent) -> void:
	if e is InputEventMouseButton and e.button_index == MOUSE_BUTTON_LEFT:
		if e.pressed:
			_pressing = true
			_press_time = 0.0
			_long_fired = false
			_anim(0.96)
		else:
			var was := _pressing
			_pressing = false
			_anim(1.0)
			var c := _carousel()
			if was and not _long_fired and (c == null or not c.dragged):
				Audio.click()
				tapped.emit()

func _process(delta: float) -> void:
	if not _pressing:
		return
	var c := _carousel()
	if c != null and c.dragged:
		_pressing = false
		_anim(1.0)
		return
	_press_time += delta
	if _press_time >= LONG_PRESS and not _long_fired:
		_long_fired = true
		_anim(1.0)
		Settings.vibrate(30)
		long_pressed.emit()

func _anim(s: float) -> void:
	if _tw and _tw.is_valid():
		_tw.kill()
	_tw = create_tween()
	_tw.tween_property(self, "scale", Vector2(s, s), 0.08).set_trans(Tween.TRANS_QUAD).set_ease(Tween.EASE_OUT)

## Анимированное появление / исчезновение (при обновлении каталога).
func appear(delay := 0.0) -> void:
	modulate.a = 0.0
	scale = Vector2(0.85, 0.85)
	var tw := create_tween().set_parallel(true)
	tw.tween_property(self, "modulate:a", 1.0, 0.3).set_delay(delay)
	tw.tween_property(self, "scale", Vector2.ONE, 0.35).set_delay(delay).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)

func disappear() -> void:
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	var tw := create_tween().set_parallel(true)
	tw.tween_property(self, "modulate:a", 0.0, 0.25)
	tw.tween_property(self, "scale", Vector2(0.8, 0.8), 0.25)
	tw.chain().tween_callback(queue_free)
