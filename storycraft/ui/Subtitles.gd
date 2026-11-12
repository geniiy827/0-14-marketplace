class_name Subtitles
extends Control
## Субтитры снизу: имя цветом персонажа + текст, шрифт Minecraft с обводкой.
## Размер — из настроек, можно выключить. Реплики действия say показываются поверх сцены.

var _bg: PanelContainer
var _rt: RichTextLabel
var _cur_key := ""
var _say_until := 0.0
var _say_line := {}

func _ready() -> void:
	UI.full(self)
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	_bg = PanelContainer.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0, 0, 0, 0.38)
	sb.content_margin_left = 20
	sb.content_margin_right = 20
	sb.content_margin_top = 8
	sb.content_margin_bottom = 10
	_bg.add_theme_stylebox_override("panel", sb)
	_bg.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(_bg)
	_rt = RichTextLabel.new()
	_rt.bbcode_enabled = true
	_rt.fit_content = true
	_rt.scroll_active = false
	_rt.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	_rt.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_bg.add_child(_rt)
	_bg.visible = false

## Реплика поверх (действие say) на dur секунд.
func say(name: String, color: Color, text: String, dur: float) -> void:
	_say_line = {"name": name, "color": "#" + color.to_html(false), "text": text}
	_say_until = Time.get_ticks_msec() / 1000.0 + dur

## Вызывается каждый кадр: строка субтитра сцены (или пусто).
func show_line(sub: Dictionary, ep) -> void:
	var line := sub
	if Time.get_ticks_msec() / 1000.0 < _say_until:
		line = _say_line
	if not bool(Settings.get_value("subtitles", true)) or line.is_empty():
		_set_text("", "", "")
		return
	var who := String(line.get("who", ""))
	var name := String(line.get("name", ""))
	if name == "" and who != "" and ep != null:
		name = ep.cast_name(who)
	var color := String(line.get("color", ""))
	if color == "" and who != "" and ep != null:
		color = "#" + ep.cast_color(who).to_html(false)
	_set_text(name, color, String(line.get("text", "")))

func _set_text(name: String, color: String, text: String) -> void:
	var size := Settings.subtitle_font_size()
	var key := "%s|%s|%s|%d|%s" % [name, color, text, size, str(get_viewport_rect().size)]
	if key == _cur_key:
		return
	_cur_key = key
	if text == "":
		_bg.visible = false
		return
	var t := text.replace("[", "[lb]")
	var bb := ""
	if name != "":
		bb = "[color=%s][b]%s:[/b][/color] " % [color if color != "" else "#ffffff", name.replace("[", "[lb]")]
	bb += t
	_rt.add_theme_font_size_override("normal_font_size", size)
	_rt.add_theme_font_size_override("bold_font_size", size)
	_rt.add_theme_constant_override("outline_size", 6 if size <= 24 else 8)
	var vw := get_viewport_rect().size.x
	var w := minf(vw * 0.8, 1100.0)
	_rt.custom_minimum_size = Vector2(w - 40, 0)
	_rt.text = "[center]" + bb + "[/center]"
	_bg.visible = true
	_bg.reset_size()
	await get_tree().process_frame
	var sm := UI.safe_margins()
	_bg.size = Vector2(w, 0)
	_bg.reset_size()
	_bg.position = Vector2((vw - _bg.size.x) * 0.5, get_viewport_rect().size.y - _bg.size.y - 28 - sm.w)
