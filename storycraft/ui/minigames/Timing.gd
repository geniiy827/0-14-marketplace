extends Minigame
## timing: бегунок ходит туда-сюда, тапни, когда он в зелёной зоне. rounds попаданий подряд.

var _zone := Vector2(0.4, 0.6)
var _speed := 1.0
var _rounds := 1
var _hits := 0
var _x := 0.0
var _dir := 1.0
var _track: Control
var _zone_rect: ColorRect
var _cursor: ColorRect
var _info: Label
var _cool := 0.0

func _ready() -> void:
	make_panel(720)
	var z = p("zone", [0.4, 0.6])
	if z is Array and z.size() >= 2:
		_zone = Vector2(clampf(float(z[0]), 0.0, 1.0), clampf(float(z[1]), 0.0, 1.0))
	_speed = maxf(0.1, float(p("speed", 1.0)))
	_rounds = maxi(1, int(p("rounds", 1)))
	_track = Control.new()
	_track.custom_minimum_size = Vector2(640, 48)
	box.add_child(_track)
	var bg := ColorRect.new()
	bg.color = Color(0.15, 0.1, 0.06)
	bg.size = Vector2(640, 48)
	_track.add_child(bg)
	_zone_rect = ColorRect.new()
	_zone_rect.color = Color(0.3, 0.85, 0.3)
	_zone_rect.position = Vector2(_zone.x * 640, 4)
	_zone_rect.size = Vector2((_zone.y - _zone.x) * 640, 40)
	_track.add_child(_zone_rect)
	_cursor = ColorRect.new()
	_cursor.color = Color(1, 0.95, 0.7)
	_cursor.size = Vector2(10, 60)
	_cursor.position = Vector2(0, -6)
	_track.add_child(_cursor)
	_info = UI.label("Тапни в зелёной зоне!", UI.FS_SMALL, true)
	_info.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	box.add_child(_info)
	set_var("score", 0)

func _process(delta: float) -> void:
	if finished:
		return
	_cool = maxf(0.0, _cool - delta)
	_x += _dir * _speed * delta
	if _x >= 1.0:
		_x = 1.0
		_dir = -1.0
	elif _x <= 0.0:
		_x = 0.0
		_dir = 1.0
	_cursor.position.x = _x * 640 - 5

func _gui_input(e: InputEvent) -> void:
	if finished or _cool > 0.0:
		return
	if e is InputEventMouseButton and e.pressed and e.button_index == MOUSE_BUTTON_LEFT:
		accept_event()
		if _x >= _zone.x and _x <= _zone.y:
			_hits += 1
			set_var("score", _hits)
			Audio.play_sfx("pop", -4.0)
			_info.text = "Есть! %d / %d" % [_hits, _rounds]
			_cool = 0.25
			_speed *= 1.15
			if _hits >= _rounds:
				finish(true)
		else:
			_info.text = "Мимо!"
			player.shake(0.4)
			finish(false)
