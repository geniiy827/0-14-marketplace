extends Minigame
## swipe: свайпы в нужную сторону (QTE). directions — список, time_each — время на каждый.

const ARROWS := {"left": "←", "right": "→", "up": "↑", "down": "↓"}

var _dirs: Array = []
var _i := 0
var _each := 1.5
var _left := 0.0
var _arrow: Label
var _bar: ProgressBar
var _start := Vector2.ZERO
var _down := false

func _ready() -> void:
	make_panel(520)
	for d in p("directions", ["left"]):
		if ARROWS.has(String(d)):
			_dirs.append(String(d))
	if _dirs.is_empty():
		finish(true)
		return
	_each = maxf(0.3, float(p("time_each", 1.5)))
	_arrow = UI.label("", 96, true, Color(1, 0.85, 0.4))
	_arrow.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(_arrow)
	_bar = bar()
	box.add_child(_bar)
	set_var("score", 0)
	_next()

func _next() -> void:
	_left = _each
	_arrow.text = ARROWS[_dirs[_i]]
	_arrow.pivot_offset = _arrow.size * 0.5
	_arrow.scale = Vector2(1.4, 1.4)
	create_tween().tween_property(_arrow, "scale", Vector2.ONE, 0.15)

func _process(delta: float) -> void:
	if finished:
		return
	_left -= delta
	_bar.value = clampf(_left / _each, 0.0, 1.0)
	set_var("time_left", maxf(0.0, _left))
	if _left <= 0.0:
		finish(false)

func _gui_input(e: InputEvent) -> void:
	if finished:
		return
	if e is InputEventMouseButton and e.button_index == MOUSE_BUTTON_LEFT:
		accept_event()
		if e.pressed:
			_down = true
			_start = e.position
		elif _down:
			_down = false
			_swiped(e.position - _start)

func _swiped(d: Vector2) -> void:
	if d.length() < 50.0:
		return
	var dir := ("right" if d.x > 0 else "left") if absf(d.x) > absf(d.y) else ("down" if d.y > 0 else "up")
	if dir == _dirs[_i]:
		_i += 1
		set_var("score", _i)
		Audio.play_sfx("pop", -4.0)
		player.shake(0.2)
		if _i >= _dirs.size():
			finish(true)
		else:
			_next()
	else:
		player.shake(0.5)
		finish(false)
