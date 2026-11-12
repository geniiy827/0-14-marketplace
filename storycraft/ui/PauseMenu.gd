class_name PauseMenu
extends Control
## Меню паузы: «Продолжить», «Начать серию заново», «Настройки», «Выйти в меню».
## Работает при остановленном дереве (анимация, звук и таймеры мини-игр стоят).

var player   # Player

func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	UI.full(self)
	mouse_filter = Control.MOUSE_FILTER_STOP
	var dim := ColorRect.new()
	dim.color = Color(0, 0, 0, 0.55)
	UI.full(dim)
	add_child(dim)
	var center := CenterContainer.new()
	UI.full(center)
	add_child(center)
	var panel := PanelContainer.new()
	panel.custom_minimum_size = Vector2(480, 0)
	center.add_child(panel)
	var vb := VBoxContainer.new()
	vb.add_theme_constant_override("separation", 14)
	panel.add_child(vb)
	var t := UI.label("Пауза", UI.FS_BIG, true)
	t.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	vb.add_child(t)
	for pair in [["Продолжить", "continue"], ["Начать серию заново", "restart"], ["Настройки", "settings"], ["Выйти в меню", "exit"]]:
		var b := UI.button(pair[0], func(): player.pause_action(pair[1]))
		b.custom_minimum_size = Vector2(432, 72)
		vb.add_child(b)
	modulate.a = 0.0
	create_tween().tween_property(self, "modulate:a", 1.0, 0.15)

func close() -> void:
	queue_free()
