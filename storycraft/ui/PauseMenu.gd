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
	_status = UI.label("", UI.FS_SMALL, false, UI.C_DIM)
	_status.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	vb.add_child(_status)
	modulate.a = 0.0
	create_tween().tween_property(self, "modulate:a", 1.0, 0.15)

var _status: Label

## Строка «Следующая серия: 63%», если она качается в фоне.
func _process(_d: float) -> void:
	if _status == null or player == null:
		return
	var nxt: Dictionary = Content.next_episode(player.adv_id, player.ep_id)
	if nxt.is_empty() or not bool(Settings.get_value("prefetch_next", false)):
		_status.text = ""
		return
	var j: Dictionary = Content.job(player.adv_id, String(nxt.get("id", "")))
	if not j.is_empty():
		_status.text = "Следующая серия: %d%%" % int(100.0 * j["done"] / maxi(1, j["total"]))
	elif Content.is_cached(player.adv_id, nxt):
		_status.text = "Следующая серия скачана ✔"
	else:
		_status.text = ""

func close() -> void:
	queue_free()
