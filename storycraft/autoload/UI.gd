extends Node
## Общий стиль интерфейса: шрифты Minecraft (без сглаживания), пиксельные 9-slice текстуры,
## тема для всех экранов и небольшие фабрики виджетов.

## Во сколько раз увеличиваем пиксельные UI-текстуры (1 пиксель текстуры = 3 px при 720p).
const PX := 3
## Размеры шрифта кратны пикселю шрифта Minecraft (12 px на «em» = 1 пиксель шрифта ×12).
const FS_SMALL := 24
const FS_MID := 36
const FS_BIG := 48

const C_TEXT := Color(1, 1, 1)
const C_OUTLINE := Color(0.16, 0.09, 0.03)
const C_DIM := Color(0.86, 0.80, 0.70)

var font_regular: FontFile
var font_bold: FontFile
var font_italic: FontFile
var theme: Theme
var _tex_cache := {}

func _ready() -> void:
	font_regular = _font("res://assets/fonts/minecraft_regular.ttf")
	font_bold = _font("res://assets/fonts/minecraft_bold.ttf")
	font_italic = _font("res://assets/fonts/minecraft_italic.ttf")
	theme = _build_theme()
	# тема на всё приложение: вливаем в тему по умолчанию (её видят все Control, в т.ч. под CanvasLayer)
	ThemeDB.get_default_theme().merge_with(theme)
	ThemeDB.fallback_font = font_regular
	ThemeDB.fallback_font_size = FS_SMALL
	get_tree().root.theme = theme

func _font(path: String) -> FontFile:
	var f: FontFile = load(path)
	# пиксельный шрифт: без сглаживания, без хинтинга, без субпиксельного позиционирования
	f.antialiasing = TextServer.FONT_ANTIALIASING_NONE
	f.hinting = TextServer.HINTING_NONE
	f.subpixel_positioning = TextServer.SUBPIXEL_POSITIONING_DISABLED
	f.generate_mipmaps = false
	f.oversampling = 1.0
	return f

## Пиксельная текстура, увеличенная в scale раз фильтром Nearest (для чётких 9-slice).
func px_tex(path: String, scale := PX) -> Texture2D:
	var key := "%s@%d" % [path, scale]
	if _tex_cache.has(key):
		return _tex_cache[key]
	var src: Texture2D = load(path)
	var img := src.get_image()
	if img.is_compressed():
		img.decompress()
	img.resize(img.get_width() * scale, img.get_height() * scale, Image.INTERPOLATE_NEAREST)
	var t := ImageTexture.create_from_image(img)
	_tex_cache[key] = t
	return t

func _sbt(path: String, margin: int, content: Vector4 = Vector4(-1, -1, -1, -1)) -> StyleBoxTexture:
	var s := StyleBoxTexture.new()
	s.texture = px_tex(path)
	var m := margin * PX
	s.texture_margin_left = m
	s.texture_margin_right = m
	s.texture_margin_top = m
	s.texture_margin_bottom = m
	s.axis_stretch_horizontal = StyleBoxTexture.AXIS_STRETCH_MODE_TILE_FIT
	s.axis_stretch_vertical = StyleBoxTexture.AXIS_STRETCH_MODE_STRETCH
	if content.x >= 0:
		s.content_margin_left = content.x
		s.content_margin_top = content.y
		s.content_margin_right = content.z
		s.content_margin_bottom = content.w
	return s

func _build_theme() -> Theme:
	var t := Theme.new()
	t.default_font = font_regular
	t.default_font_size = FS_SMALL

	# Кнопки — оранжево-деревянные
	var pad := Vector4(24, 12, 24, 16)
	t.set_stylebox("normal", "Button", _sbt("res://assets/ui/button_normal.png", 2, pad))
	t.set_stylebox("hover", "Button", _sbt("res://assets/ui/button_hover.png", 2, pad))
	t.set_stylebox("pressed", "Button", _sbt("res://assets/ui/button_pressed.png", 2, Vector4(24, 15, 24, 13)))
	t.set_stylebox("disabled", "Button", _sbt("res://assets/ui/button_disabled.png", 2, pad))
	t.set_stylebox("hover_pressed", "Button", _sbt("res://assets/ui/button_pressed.png", 2, Vector4(24, 15, 24, 13)))
	t.set_stylebox("focus", "Button", StyleBoxEmpty.new())
	t.set_font("font", "Button", font_bold)
	t.set_font_size("font_size", "Button", FS_SMALL)
	for c in ["font_color", "font_hover_color", "font_pressed_color", "font_focus_color", "font_hover_pressed_color"]:
		t.set_color(c, "Button", C_TEXT)
	t.set_color("font_disabled_color", "Button", Color(0.85, 0.85, 0.85))
	t.set_color("font_outline_color", "Button", C_OUTLINE)
	t.set_constant("outline_size", "Button", 6)
	t.set_constant("h_separation", "Button", 12)
	t.set_constant("icon_max_width", "Button", 48)

	# Надписи — белые с тёмной обводкой
	t.set_color("font_color", "Label", C_TEXT)
	t.set_color("font_outline_color", "Label", C_OUTLINE)
	t.set_constant("outline_size", "Label", 6)
	t.set_constant("line_spacing", "Label", 4)
	t.set_color("default_color", "RichTextLabel", C_TEXT)
	t.set_color("font_outline_color", "RichTextLabel", C_OUTLINE)
	t.set_constant("outline_size", "RichTextLabel", 6)
	t.set_font("normal_font", "RichTextLabel", font_regular)
	t.set_font("bold_font", "RichTextLabel", font_bold)
	t.set_font("italics_font", "RichTextLabel", font_italic)
	t.set_font_size("normal_font_size", "RichTextLabel", FS_SMALL)
	t.set_font_size("bold_font_size", "RichTextLabel", FS_SMALL)

	# Панель
	var panel := _sbt("res://assets/ui/panel.png", 4, Vector4(24, 24, 24, 24))
	panel.axis_stretch_vertical = StyleBoxTexture.AXIS_STRETCH_MODE_TILE_FIT
	t.set_stylebox("panel", "PanelContainer", panel)
	t.set_stylebox("panel", "Panel", panel)

	# Слайдер
	var track := _sbt("res://assets/ui/slider_track.png", 2)
	track.content_margin_top = 9
	track.content_margin_bottom = 9
	t.set_stylebox("slider", "HSlider", track)
	var fill := _sbt("res://assets/ui/progress_fill.png", 1)
	fill.content_margin_top = 9
	fill.content_margin_bottom = 9
	t.set_stylebox("grabber_area", "HSlider", fill)
	t.set_stylebox("grabber_area_highlight", "HSlider", fill)
	t.set_icon("grabber", "HSlider", px_tex("res://assets/ui/slider_knob.png"))
	t.set_icon("grabber_highlight", "HSlider", px_tex("res://assets/ui/slider_knob.png"))
	t.set_stylebox("focus", "HSlider", StyleBoxEmpty.new())

	# Прогресс-бар
	t.set_stylebox("background", "ProgressBar", _sbt("res://assets/ui/progress_bg.png", 1))
	var pf := _sbt("res://assets/ui/progress_fill.png", 1)
	t.set_stylebox("fill", "ProgressBar", pf)
	t.set_constant("outline_size", "ProgressBar", 0)
	t.set_color("font_color", "ProgressBar", Color(0, 0, 0, 0))

	# Чекбокс
	# чекбоксы 24×24 (вдвое детальнее прежних 12×12). Раньше было 12×PX = 36 px; ровно 36 из 24 не получить
	# целым масштабом (×1.5 ломает пиксели галочки), поэтому ближайший целый: round(PX / 2) = 2 → 48 px.
	var cb_scale := maxi(1, roundi(PX / 2.0))
	for cls in ["CheckBox", "CheckButton"]:
		t.set_icon("checked", cls, px_tex("res://assets/ui/checkbox_on.png", cb_scale))
		t.set_icon("unchecked", cls, px_tex("res://assets/ui/checkbox_off.png", cb_scale))
		t.set_icon("checked_disabled", cls, px_tex("res://assets/ui/checkbox_on.png", cb_scale))
		t.set_icon("unchecked_disabled", cls, px_tex("res://assets/ui/checkbox_off.png", cb_scale))
		for s in ["normal", "hover", "pressed", "hover_pressed", "focus", "disabled"]:
			t.set_stylebox(s, cls, StyleBoxEmpty.new())
		t.set_color("font_color", cls, C_TEXT)
		t.set_color("font_hover_color", cls, C_TEXT)
		t.set_color("font_pressed_color", cls, C_TEXT)
		t.set_color("font_hover_pressed_color", cls, C_TEXT)
		t.set_color("font_outline_color", cls, C_OUTLINE)
		t.set_constant("outline_size", cls, 6)
		t.set_constant("h_separation", cls, 16)

	# Скролл без видимых полос
	var empty := StyleBoxEmpty.new()
	t.set_stylebox("panel", "ScrollContainer", empty)
	t.set_stylebox("scroll", "HScrollBar", empty)
	t.set_stylebox("scroll", "VScrollBar", empty)
	t.set_stylebox("grabber", "VScrollBar", _flat(Color(1, 0.8, 0.4, 0.5)))
	t.set_stylebox("grabber_highlight", "VScrollBar", _flat(Color(1, 0.8, 0.4, 0.7)))
	t.set_stylebox("grabber_pressed", "VScrollBar", _flat(Color(1, 0.8, 0.4, 0.9)))
	return t

func _flat(c: Color) -> StyleBoxFlat:
	var s := StyleBoxFlat.new()
	s.bg_color = c
	return s

# ---------------------------------------------------------------- фабрики

func label(text: String, size := FS_SMALL, bold := false, color := C_TEXT) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", size)
	if bold:
		l.add_theme_font_override("font", font_bold)
	if color != C_TEXT:
		l.add_theme_color_override("font_color", color)
	return l

func button(text: String, on_press: Callable = Callable(), size := FS_SMALL) -> Button:
	var b := PixelButton.new()
	b.text = text
	if size != FS_SMALL:
		b.add_theme_font_size_override("font_size", size)
	if on_press.is_valid():
		b.pressed.connect(on_press)
	return b

func icon_button(icon_path: String, on_press: Callable = Callable()) -> Button:
	var b := PixelButton.new()
	b.icon = px_tex(icon_path, 3)
	b.custom_minimum_size = Vector2(84, 72)
	b.icon_alignment = HORIZONTAL_ALIGNMENT_CENTER
	b.expand_icon = false
	for s in ["normal", "hover", "pressed", "disabled", "hover_pressed"]:
		var sb: StyleBoxTexture = theme.get_stylebox(s, "Button").duplicate()
		sb.content_margin_left = 12
		sb.content_margin_right = 12
		b.add_theme_stylebox_override(s, sb)
	if on_press.is_valid():
		b.pressed.connect(on_press)
	return b

func panel() -> PanelContainer:
	return PanelContainer.new()

## Растянуть контрол на весь родитель.
static func full(c: Control) -> Control:
	c.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	return c

## Прикрепить контрол к точке-якорю (ax, ay ∈ {0, 0.5, 1}) со смещением (ox, oy).
## Контрол растёт от якоря внутрь экрана (справа — влево, по центру — в обе стороны).
static func pin(c: Control, ax: float, ay: float, ox: float, oy: float) -> Control:
	c.anchor_left = ax
	c.anchor_right = ax
	c.anchor_top = ay
	c.anchor_bottom = ay
	c.offset_left = ox
	c.offset_right = ox
	c.offset_top = oy
	c.offset_bottom = oy
	c.grow_horizontal = Control.GROW_DIRECTION_END if ax < 0.25 else (Control.GROW_DIRECTION_BOTH if ax < 0.75 else Control.GROW_DIRECTION_BEGIN)
	c.grow_vertical = Control.GROW_DIRECTION_END if ay < 0.25 else (Control.GROW_DIRECTION_BOTH if ay < 0.75 else Control.GROW_DIRECTION_BEGIN)
	return c

## Безопасная зона экрана (вырезы) в координатах UI.
func safe_margins() -> Vector4:
	var win := DisplayServer.window_get_size()
	var safe := DisplayServer.get_display_safe_area()
	if win.x <= 0 or safe.size.x <= 0:
		return Vector4.ZERO
	var vp := get_viewport().get_visible_rect().size
	var k := vp.x / float(win.x)
	var scr := DisplayServer.screen_get_position()
	var l := maxf(0.0, (safe.position.x - scr.x) * k)
	var t := maxf(0.0, (safe.position.y - scr.y) * k)
	var r := maxf(0.0, (win.x - (safe.end.x - scr.x)) * k)
	var b := maxf(0.0, (win.y - (safe.end.y - scr.y)) * k)
	if not OS.has_feature("mobile"):
		return Vector4.ZERO
	return Vector4(l, t, r, b)

## Плавное появление/исчезновение.
func fade_in(c: CanvasItem, t := 0.25) -> Tween:
	c.modulate.a = 0.0
	c.visible = true
	var tw := c.create_tween()
	tw.tween_property(c, "modulate:a", 1.0, t)
	return tw

static func hex(s: String, def := Color(1, 0.54, 0.23)) -> Color:
	if s.is_valid_html_color():
		return Color.html(s)
	return def
