class_name ChoicePanel
extends Control
## Панель выбора: вопрос, 2–4 варианта кнопками в стиле игры, полоса таймера.
## По истечении таймера выбирается вариант timeout_index. chosen(index) — индекс в options узла.

signal chosen(index: int)

var prompt := ""
var speaker_name := ""
var speaker_color := Color.WHITE
var options: Array = []        # [{index, text, locked, locked_text}]
var timer := 0.0
var timeout_index := -1

var _panel: PanelContainer
var _bar: ProgressBar
var _left := 0.0
var _done := false

func _ready() -> void:
	UI.full(self)
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	var sm := UI.safe_margins()
	_panel = PanelContainer.new()
	_panel.custom_minimum_size = Vector2(520, 0)
	UI.pin(_panel, 1.0, 0.5, -40 - sm.z, 40)
	add_child(_panel)
	var vb := VBoxContainer.new()
	vb.add_theme_constant_override("separation", 12)
	_panel.add_child(vb)
	if speaker_name != "":
		vb.add_child(UI.label(speaker_name, UI.FS_SMALL, true, speaker_color))
	if prompt != "":
		var p := UI.label(prompt, UI.FS_SMALL, true)
		p.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		p.custom_minimum_size = Vector2(472, 0)
		vb.add_child(p)
	for o in options:
		var b := UI.button(String(o["text"]))
		b.custom_minimum_size = Vector2(472, 64)
		b.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
		if o.get("locked", false):
			b.disabled = true
			b.icon = UI.px_tex("res://assets/ui/icon_lock.png", 2)
			b.text = String(o["text"]) + "\n" + String(o.get("locked_text", ""))
		else:
			b.pressed.connect(_pick.bind(int(o["index"]), b))
		vb.add_child(b)
	if timer > 0.0:
		_left = timer
		_bar = ProgressBar.new()
		_bar.show_percentage = false
		_bar.max_value = 1.0
		_bar.step = 0.0
		_bar.value = 1.0
		_bar.custom_minimum_size = Vector2(472, 24)
		vb.add_child(_bar)
	# выезд справа
	await get_tree().process_frame
	var target := _panel.position
	_panel.position = target + Vector2(_panel.size.x + 80, 0)
	var tw := create_tween()
	tw.tween_property(_panel, "position", target, 0.3).set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_OUT)
	Audio.play_sfx("pop", -6.0)

func _process(delta: float) -> void:
	if _done or timer <= 0.0 or _bar == null:
		return
	_left -= delta
	_bar.value = clampf(_left / timer, 0.0, 1.0)
	# последние секунды — полоса краснеет
	_bar.modulate = Color(1, 0.45, 0.4) if _left < 3.0 else Color.WHITE
	if _left <= 0.0:
		_finish(timeout_index)

func _pick(idx: int, b: Button) -> void:
	if _done:
		return
	# короткая вспышка выбранной кнопки
	var tw := b.create_tween()
	tw.tween_property(b, "modulate", Color(1.5, 1.4, 1.1), 0.08)
	tw.tween_property(b, "modulate", Color.WHITE, 0.12)
	_finish(idx)

func _finish(idx: int) -> void:
	if _done:
		return
	_done = true
	chosen.emit(idx)

func close() -> void:
	var tw := create_tween()
	tw.tween_property(self, "modulate:a", 0.0, 0.2)
	tw.tween_callback(queue_free)
