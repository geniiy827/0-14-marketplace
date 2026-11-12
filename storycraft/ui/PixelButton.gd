class_name PixelButton
extends Button
## Пиксельная кнопка игры: 9-slice из темы, лёгкая анимация нажатия (scale 0.96) и звук ui_click.

var click_sound := "ui_click"
var _tw: Tween

func _ready() -> void:
	focus_mode = Control.FOCUS_NONE
	button_down.connect(_on_down)
	button_up.connect(_on_up)
	pressed.connect(_on_pressed)
	resized.connect(func(): pivot_offset = size * 0.5)
	pivot_offset = size * 0.5
	mouse_filter = Control.MOUSE_FILTER_STOP

func _on_down() -> void:
	_anim(0.96, 0.06)

func _on_up() -> void:
	_anim(1.0, 0.1)

func _on_pressed() -> void:
	if click_sound != "":
		Audio.play_sfx(click_sound, -2.0)

func _anim(s: float, t: float) -> void:
	if _tw and _tw.is_valid():
		_tw.kill()
	_tw = create_tween()
	_tw.tween_property(self, "scale", Vector2(s, s), t).set_trans(Tween.TRANS_QUAD).set_ease(Tween.EASE_OUT)
