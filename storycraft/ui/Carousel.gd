class_name Carousel
extends Control
## Прокручиваемый список со свайпом и инерцией (горизонтальный или вертикальный).
## Дети добавляются в content (HBox/VBox). Карточки должны иметь mouse_filter = PASS.

var vertical := false
var content: BoxContainer
var spacing := 32
var padding := 32.0

var _offset := 0.0
var _vel := 0.0
var _dragging := false
var _press_pos := Vector2.ZERO
var _last_pos := 0.0
var _last_t := 0
var dragged := false          # был ли настоящий свайп (карточки не срабатывают)

func _init(is_vertical := false) -> void:
	vertical = is_vertical

func _ready() -> void:
	clip_contents = true
	mouse_filter = Control.MOUSE_FILTER_STOP
	content = VBoxContainer.new() if vertical else HBoxContainer.new()
	content.add_theme_constant_override("separation", spacing)
	content.mouse_filter = Control.MOUSE_FILTER_PASS
	if not vertical:
		content.alignment = BoxContainer.ALIGNMENT_BEGIN
	add_child(content)
	resized.connect(_layout)
	content.resized.connect(_layout)
	_layout()

func _axis(v: Vector2) -> float:
	return v.y if vertical else v.x

func _content_len() -> float:
	return _axis(content.get_combined_minimum_size())

func _view_len() -> float:
	return _axis(size)

func _max_offset() -> float:
	return maxf(0.0, _content_len() + padding * 2.0 - _view_len())

func _layout() -> void:
	if content == null:
		return
	var cm := content.get_combined_minimum_size()
	if vertical:
		content.size = Vector2(size.x, cm.y)
		content.position = Vector2(0, padding - _offset)
	else:
		content.size = Vector2(cm.x, size.y)
		# если всё помещается — по центру
		var free := _view_len() - cm.x
		var x := (free * 0.5) if free > padding * 2.0 else (padding - _offset)
		content.position = Vector2(x, 0)

func _gui_input(e: InputEvent) -> void:
	_handle(e)

## Карточки пробрасывают события сюда (PASS) — обрабатываем свайп.
func _handle(e: InputEvent) -> void:
	# только мышь: на Android касания эмулируются в мышь (emulate_mouse_from_touch)
	if e is InputEventMouseButton and e.button_index == MOUSE_BUTTON_LEFT:
		if e.pressed:
			_dragging = true
			dragged = false
			_press_pos = e.global_position
			_last_pos = _axis(_press_pos)
			_last_t = Time.get_ticks_msec()
			_vel = 0.0
		else:
			_dragging = false
	elif e is InputEventMouseMotion and _dragging:
		var gp: Vector2 = e.global_position
		if not dragged and _axis(gp - _press_pos) != 0.0 and absf(_axis(gp - _press_pos)) > 14.0:
			dragged = true
		if dragged:
			var p := _axis(gp)
			var d := p - _last_pos
			var now := Time.get_ticks_msec()
			var dt := maxf(0.001, (now - _last_t) / 1000.0)
			_vel = lerpf(_vel, -d / dt, 0.5)
			_last_pos = p
			_last_t = now
			var mo := _max_offset()
			# за краями — с сопротивлением
			if _offset < 0.0 or _offset > mo:
				d *= 0.4
			_offset -= d
			_layout()
	elif e is InputEventMouseButton and e.pressed:
		if e.button_index in [MOUSE_BUTTON_WHEEL_DOWN, MOUSE_BUTTON_WHEEL_RIGHT]:
			_vel = 1400.0
		elif e.button_index in [MOUSE_BUTTON_WHEEL_UP, MOUSE_BUTTON_WHEEL_LEFT]:
			_vel = -1400.0

func _process(delta: float) -> void:
	if _dragging:
		return
	var mo := _max_offset()
	if absf(_vel) > 5.0:
		_offset += _vel * delta
		_vel *= pow(0.04, delta)   # трение
	else:
		_vel = 0.0
	# возврат от краёв
	if _offset < 0.0:
		_offset = lerpf(_offset, 0.0, minf(1.0, delta * 12.0))
		if _vel < 0.0:
			_vel *= 0.5
	elif _offset > mo:
		_offset = lerpf(_offset, mo, minf(1.0, delta * 12.0))
		if _vel > 0.0:
			_vel *= 0.5
	_layout()

func scroll_to_start() -> void:
	_offset = 0.0
	_vel = 0.0
	_layout()
