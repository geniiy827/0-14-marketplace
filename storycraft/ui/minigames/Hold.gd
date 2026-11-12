extends Minigame
## hold: удерживай палец, не отпуская — полоса заполняется за time секунд. Отпустил — сначала.

var _time := 2.0
var _t := 0.0
var _holding := false
var _bar: ProgressBar
var _info: Label

func _ready() -> void:
	make_panel(640)
	_time = maxf(0.2, float(p("time", 2.0)))
	_bar = bar()
	box.add_child(_bar)
	_info = UI.label("Удерживай палец на экране", UI.FS_SMALL, true)
	_info.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(_info)

func _gui_input(e: InputEvent) -> void:
	if finished:
		return
	if e is InputEventMouseButton and e.button_index == MOUSE_BUTTON_LEFT:
		accept_event()
		_holding = e.pressed
		if not e.pressed and _t < _time:
			_info.text = "Не отпускай! Ещё раз"
			_t = 0.0

func _process(delta: float) -> void:
	if finished:
		return
	if _holding:
		_t += delta
		player.shake(0.08)
	else:
		_t = maxf(0.0, _t - delta * 2.0)
	_bar.value = clampf(_t / _time, 0.0, 1.0)
	set_var("score", _bar.value)
	if _t >= _time:
		finish(true)
