class_name InteractTimer
extends Control
## Полоса времени узла interact (timer_visible): сверху по центру, как у выбора.
## В последние 5 секунд краснеет и тикает звуком ui_click. Останавливается вместе с паузой.

var duration := 10.0
var _left := 10.0
var _bar: ProgressBar
var _last_tick := -1

func _ready() -> void:
	_left = duration
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	custom_minimum_size = Vector2(520, 24)
	UI.pin(self, 0.5, 0.0, 0, 76 + UI.safe_margins().y)
	_bar = ProgressBar.new()
	_bar.show_percentage = false
	_bar.max_value = 1.0
	_bar.step = 0.0
	_bar.value = 1.0
	_bar.custom_minimum_size = Vector2(520, 24)
	_bar.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(_bar)

func _process(delta: float) -> void:
	_left = maxf(0.0, _left - delta)
	_bar.value = _left / maxf(duration, 0.001)
	if _left <= 5.0 and _left > 0.0:
		_bar.modulate = Color(1, 0.4, 0.35)
		var s := ceili(_left)
		if s != _last_tick:
			_last_tick = s
			Audio.play_sfx("ui_click", -4.0)
	else:
		_bar.modulate = Color.WHITE
