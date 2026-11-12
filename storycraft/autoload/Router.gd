extends Node
## Навигация между экранами с затемнением, слои (3D-фон меню, экраны, модалки, затемнение),
## Android «Назад», окно «Нет подключения», диалоги подтверждения.

const SCREENS := {
	"main_menu": "res://screens/MainMenu.tscn",
	"adventures": "res://screens/Adventures.tscn",
	"episodes": "res://screens/Episodes.tscn",
	"player": "res://screens/Player.tscn",
	"settings": "res://screens/Settings.tscn",
}
## Экраны, под которыми живёт 3D-фон меню и играет музыка меню.
const MENU_SCREENS := ["main_menu", "adventures", "episodes", "settings"]

signal screen_changed(name: String)

var menu_bg: Node3D = null          # живой фон меню (создаётся в Boot)
var current: Control = null
var current_name := ""
var _world_root: Node                # сюда кладём 3D (фон меню или серия)
var _screen_layer: CanvasLayer
var _modal_layer: CanvasLayer
var _fade_layer: CanvasLayer
var _fade: ColorRect
var _busy := false
## Во время логотипа музыку меню включает Boot (после затемнения).
var boot_mode := false
var _no_net: Control = null
var _modals: Array[Control] = []

func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	_world_root = Node.new()
	_world_root.name = "World"
	_screen_layer = CanvasLayer.new()
	_screen_layer.layer = 1
	_screen_layer.name = "Screens"
	_modal_layer = CanvasLayer.new()
	_modal_layer.layer = 20
	_modal_layer.name = "Modals"
	_fade_layer = CanvasLayer.new()
	_fade_layer.layer = 90
	_fade = ColorRect.new()
	_fade.color = Color(0, 0, 0, 0)
	_fade.mouse_filter = Control.MOUSE_FILTER_IGNORE
	UI.full(_fade)
	_fade_layer.add_child(_fade)
	_build_debug_overlay()
	add_child(_world_root)
	add_child(_screen_layer)
	add_child(_modal_layer)
	add_child(_fade_layer)

func _exit_tree() -> void:
	# фон меню может быть вне дерева (во время серии) — освобождаем вручную
	if menu_bg != null and is_instance_valid(menu_bg) and menu_bg.get_parent() == null:
		menu_bg.free()

func world_root() -> Node:
	return _world_root

func modal_layer() -> CanvasLayer:
	return _modal_layer

# ---------------------------------------------------------------- переходы

## Перейти на экран name с параметрами. fade — затемнение в чёрный: только для входа в серию и выхода из неё.
## Между экранами меню переход мгновенный — живой фон не перекрывается.
func go(name: String, params := {}, fade := false) -> void:
	if _busy:
		return
	_busy = true
	if fade:
		_fade.mouse_filter = Control.MOUSE_FILTER_STOP
		var tw := create_tween()
		tw.tween_property(_fade, "color:a", 1.0, 0.22)
		await tw.finished
	_swap(name, params)
	if fade:
		# даём экрану кадр на построение, затем открываем
		await get_tree().process_frame
		await get_tree().process_frame
		var tw2 := create_tween()
		tw2.tween_property(_fade, "color:a", 0.0, 0.28)
		await tw2.finished
		_fade.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_busy = false

func _swap(name: String, params: Dictionary) -> void:
	if current != null:
		current.queue_free()
		current = null
	var in_menu := name in MENU_SCREENS
	_set_menu_bg(in_menu)
	if in_menu and not boot_mode:
		Audio.play_menu_music()
	var scene: PackedScene = load(SCREENS[name])
	var s: Control = scene.instantiate()
	if s.has_method("setup"):
		s.setup(params)
	_screen_layer.add_child(s)
	current = s
	current_name = name
	screen_changed.emit(name)

func _set_menu_bg(on: bool) -> void:
	if menu_bg == null:
		return
	if on and menu_bg.get_parent() == null:
		_world_root.add_child(menu_bg)
	elif not on and menu_bg.get_parent() != null:
		_world_root.remove_child(menu_bg)
	if on and menu_bg.has_method("activate"):
		menu_bg.activate()

## Затемнить экран до чёрного / открыть (для вспомогательных переходов, например начала серии).
func fade_to_black(t := 0.3) -> void:
	var tw := create_tween()
	tw.tween_property(_fade, "color:a", 1.0, t)
	await tw.finished

func fade_from_black(t := 0.4) -> void:
	var tw := create_tween()
	tw.tween_property(_fade, "color:a", 0.0, t)
	await tw.finished

func set_black(a: float) -> void:
	_fade.color.a = a

# ---------------------------------------------------------------- «Назад»

func _notification(what: int) -> void:
	if what == NOTIFICATION_WM_GO_BACK_REQUEST:
		_back()
	elif what == NOTIFICATION_WM_CLOSE_REQUEST:
		# выход: удаляем скачанное за сессию (если ОС даёт время)
		Content.clear_cache()

func _unhandled_input(e: InputEvent) -> void:
	if e is InputEventKey and e.pressed and not e.echo and e.keycode == KEY_ESCAPE:
		get_viewport().set_input_as_handled()
		_back()

func _back() -> void:
	if _busy:
		return
	if _no_net != null:
		return  # окно «Нет подключения» не закрывается
	# закрываем верхнюю закрываемую модалку
	for i in range(_modals.size() - 1, -1, -1):
		var m := _modals[i]
		if is_instance_valid(m) and m.has_method("on_back"):
			m.on_back()
			return
	if current != null and current.has_method("on_back"):
		current.on_back()

# ---------------------------------------------------------------- модалки

func push_modal(m: Control) -> void:
	_modals.append(m)
	_modal_layer.add_child(m)
	m.tree_exited.connect(func(): _modals.erase(m))

func has_modals() -> bool:
	return not _modals.is_empty()

## Диалог «Да/Нет». Возвращает true, если нажали «Да».
func confirm(title: String, text: String, yes := "Да", no := "Нет") -> bool:
	var m: Modal = load("res://ui/Modal.tscn").instantiate()
	m.configure(title, text, [yes, no], true)
	push_modal(m)
	var idx: int = await m.chosen
	return idx == 0

## Простое сообщение с кнопкой «Понятно».
func alert(title: String, text: String, btn := "Понятно") -> void:
	var m: Modal = load("res://ui/Modal.tscn").instantiate()
	m.configure(title, text, [btn], true)
	push_modal(m)
	await m.chosen

## Показать неснимаемое окно «Нет подключения»; возвращает управление после нажатия «Попробовать снова».
func show_no_internet() -> void:
	if _no_net == null:
		_no_net = load("res://ui/NoInternet.tscn").instantiate()
		_modal_layer.add_child(_no_net)
	_no_net.set_busy(false)
	await _no_net.retry
	_no_net.set_busy(true)

func hide_no_internet() -> void:
	if _no_net != null:
		_no_net.close()
		_no_net = null

# ---------------------------------------------------------------- оверлей отладки

var _dbg_layer: CanvasLayer
var _dbg_label: Label
var _dbg_t := 0.0

## Скрытый оверлей (5 тапов по версии в настройках): FPS, draw calls, полигоны в кадре.
func _build_debug_overlay() -> void:
	_dbg_layer = CanvasLayer.new()
	_dbg_layer.layer = 99
	_dbg_label = Label.new()
	_dbg_label.position = Vector2(8, 4)
	_dbg_label.add_theme_font_size_override("font_size", 24)
	_dbg_label.add_theme_color_override("font_color", Color(0.6, 1.0, 0.5))
	_dbg_label.add_theme_color_override("font_outline_color", Color.BLACK)
	_dbg_label.add_theme_constant_override("outline_size", 6)
	_dbg_label.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_dbg_layer.add_child(_dbg_label)
	add_child(_dbg_layer)
	_dbg_layer.visible = false

func _process(delta: float) -> void:
	var on := bool(Settings.get_value("debug_overlay", false))
	_dbg_layer.visible = on
	if not on:
		return
	_dbg_t += delta
	if _dbg_t < 0.5:
		return
	_dbg_t = 0.0
	_dbg_label.text = "FPS %d  ·  draw calls %d  ·  полигоны %d" % [
		Performance.get_monitor(Performance.TIME_FPS),
		Performance.get_monitor(Performance.RENDER_TOTAL_DRAW_CALLS_IN_FRAME),
		Performance.get_monitor(Performance.RENDER_TOTAL_PRIMITIVES_IN_FRAME)]
