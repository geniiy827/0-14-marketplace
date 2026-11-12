extends Minigame
## tap_fast: натапать taps раз за time секунд (полоса силы, тряска камеры на тапах).

var _need := 18
var _time := 5.0
var _left := 5.0
var _taps := 0
var _bar: ProgressBar
var _info: Label
var _started := false

func _ready() -> void:
	make_panel(680)
	_need = maxi(1, int(p("taps", 18)))
	_time = maxf(0.5, float(p("time", 5.0)))
	_left = _time
	_bar = bar(UI.hex(String(p("bar_color", "#ff8a3a"))))
	box.add_child(_bar)
	_info = UI.label("Жми!", UI.FS_MID, true)
	_info.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(_info)
	set_var("taps", 0)
	set_var("time_left", _time)
	_update()

func _gui_input(e: InputEvent) -> void:
	if finished:
		return
	if e is InputEventMouseButton and e.pressed and e.button_index == MOUSE_BUTTON_LEFT:
		accept_event()
		_started = true
		_taps += 1
		set_var("taps", _taps)
		set_var("score", _taps)
		player.shake(0.25)
		Audio.play_sfx("hit", -8.0, randf_range(0.9, 1.2))
		Settings.vibrate(15)
		var tw := _bar.create_tween()
		_bar.scale = Vector2(1.0, 1.15)
		tw.tween_property(_bar, "scale", Vector2.ONE, 0.08)
		_update()
		if _taps >= _need:
			_info.text = "Есть!"
			finish(true)

func _process(delta: float) -> void:
	if finished or not _started:
		return
	_left -= delta
	set_var("time_left", maxf(0.0, _left))
	_update()
	if _left <= 0.0:
		_info.text = "Не хватило сил…"
		finish(false)

func _update() -> void:
	_bar.value = clampf(float(_taps) / _need, 0.0, 1.0)
	if not finished:
		_info.text = "%d / %d   ·   %.1f с" % [_taps, _need, maxf(0.0, _left)]
