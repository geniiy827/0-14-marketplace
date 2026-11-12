class_name Minigame
extends Control
## Основа мини-игр: параметры узла, доступ к переменным (taps / time_left / score), сигнал done(success).
## Пауза серии останавливает мини-игру (process_mode наследуется от проигрывателя).

signal done(success: bool)

const KINDS := {
	"tap_fast": "res://ui/minigames/TapFast.gd",
	"timing": "res://ui/minigames/Timing.gd",
	"sequence": "res://ui/minigames/Sequence.gd",
	"hold": "res://ui/minigames/Hold.gd",
	"swipe": "res://ui/minigames/Swipe.gd",
	"code": "res://ui/minigames/Code.gd",
	"drag": "res://ui/minigames/Drag.gd",
}

var params := {}
var runner              # LogicRunner
var player              # Player
var finished := false
var panel: PanelContainer
var box: VBoxContainer
var title_label: Label

static func create(kind: String) -> Minigame:
	if not KINDS.has(kind):
		return null
	return load(KINDS[kind]).new()

func p(key: String, def = null):
	return params.get(key, def)

func set_var(k: String, v) -> void:
	if runner:
		runner.vars[k] = v

## Стандартная панель снизу по центру с заголовком.
func make_panel(width := 640.0) -> VBoxContainer:
	UI.full(self)
	mouse_filter = Control.MOUSE_FILTER_STOP
	panel = PanelContainer.new()
	panel.custom_minimum_size = Vector2(width, 0)
	UI.pin(panel, 0.5, 1.0, 0, -40 - UI.safe_margins().w)
	add_child(panel)
	box = VBoxContainer.new()
	box.add_theme_constant_override("separation", 14)
	panel.add_child(box)
	title_label = UI.label(String(p("title", "")), UI.FS_SMALL, true)
	title_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	title_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	title_label.custom_minimum_size = Vector2(width - 48, 0)
	title_label.visible = title_label.text != ""
	box.add_child(title_label)
	panel.modulate.a = 0.0
	create_tween().tween_property(panel, "modulate:a", 1.0, 0.2)
	return box

func bar(color: Color = Color(1, 0.6, 0.2)) -> ProgressBar:
	var b := ProgressBar.new()
	b.show_percentage = false
	b.max_value = 1.0
	b.step = 0.0
	b.custom_minimum_size = Vector2(0, 36)
	var fill: StyleBox = UI.theme.get_stylebox("fill", "ProgressBar").duplicate()
	b.add_theme_stylebox_override("fill", fill)
	b.self_modulate = Color.WHITE
	if color != Color(1, 0.6, 0.2):
		var sb := StyleBoxFlat.new()
		sb.bg_color = color
		sb.border_color = color.darkened(0.4)
		sb.set_border_width_all(3)
		b.add_theme_stylebox_override("fill", sb)
	return b

func finish(success: bool) -> void:
	if finished:
		return
	finished = true
	Audio.play_sfx("success" if success else "fail", -4.0)
	if not success:
		Settings.vibrate(120)
	# отложенно: done может прийти ещё до того, как проигрыватель начал его ждать
	done.emit.call_deferred(success)

func close() -> void:
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	var tw := create_tween()
	tw.tween_property(self, "modulate:a", 0.0, 0.25)
	tw.tween_callback(queue_free)
