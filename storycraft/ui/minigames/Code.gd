extends Minigame
## code: кодовый замок с пиксельной цифровой клавиатурой. length, answer, title, hint, attempts (опц.).

var _len := 4
var _answer := ""
var _typed := ""
var _tries := 0
var _display: Label
var _hint: Label

func _ready() -> void:
	make_panel(520)
	_answer = String(runner.text(str(p("answer", "")))) if runner else str(p("answer", ""))
	_len = maxi(1, int(p("length", _answer.length() if _answer != "" else 4)))
	_display = UI.label("", UI.FS_BIG, true, Color(1, 0.9, 0.5))
	_display.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(_display)
	if p("hint", "") != "":
		_hint = UI.label(runner.text(p("hint")) if runner else String(p("hint")), UI.FS_SMALL, false, UI.C_DIM)
		_hint.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		_hint.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		_hint.custom_minimum_size = Vector2(460, 0)
		box.add_child(_hint)
	var grid := GridContainer.new()
	grid.columns = 3
	grid.add_theme_constant_override("h_separation", 10)
	grid.add_theme_constant_override("v_separation", 10)
	var center := CenterContainer.new()
	center.add_child(grid)
	box.add_child(center)
	for k in ["1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK"]:
		var b := UI.button(k)
		b.custom_minimum_size = Vector2(110, 64)
		b.pressed.connect(_key.bind(k))
		grid.add_child(b)
	_refresh()

func _refresh() -> void:
	var s := ""
	for i in _len:
		s += (_typed[i] if i < _typed.length() else "_") + " "
	_display.text = s.strip_edges()

func _key(k: String) -> void:
	if finished:
		return
	if k == "⌫":
		_typed = _typed.substr(0, maxi(0, _typed.length() - 1))
	elif k == "OK":
		_check()
		return
	elif _typed.length() < _len:
		_typed += k
	_refresh()
	if _typed.length() == _len and p("auto_submit", true):
		_check()

func _check() -> void:
	set_var("score", _typed.to_int())
	if _typed == _answer:
		_display.add_theme_color_override("font_color", Color(0.5, 1, 0.5))
		finish(true)
		return
	_tries += 1
	player.shake(0.4)
	Audio.play_sfx("fail", -6.0)
	var tw := _display.create_tween()
	_display.add_theme_color_override("font_color", Color(1, 0.4, 0.4))
	tw.tween_interval(0.4)
	tw.tween_callback(func(): _display.add_theme_color_override("font_color", Color(1, 0.9, 0.5)))
	_typed = ""
	_refresh()
	var limit := int(p("attempts", 0))
	if limit > 0 and _tries >= limit:
		finish(false)
