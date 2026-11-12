extends CanvasLayer
## Запуск: логотип студии RC на чёрном экране. Параллельно — проверка сети, каталог, регистрация,
## подготовка живого фона меню. Затем чёрный фон плавно становится прозрачным — под ним главное меню.

const LOGO_W := 260.0

var _black: ColorRect
var _logo: TextureRect
var _mat: ShaderMaterial
var _anim: Tween
var _logo_done := false
var _prep_done := false
var _online := false
var _first_launch := true

func _ready() -> void:
	layer = 50
	_first_launch = not Settings.get_value("launched_before", false)
	# серии и обложки храним только одну сессию — стираем всё скачанное в прошлый раз
	Content.reset_session()
	Router.boot_mode = true

	_black = ColorRect.new()
	_black.color = Color.BLACK
	_black.mouse_filter = Control.MOUSE_FILTER_STOP
	UI.full(_black)
	add_child(_black)
	_black.gui_input.connect(_on_tap)

	_logo = TextureRect.new()
	_logo.texture = load("res://assets/branding/rc_logo_mask.png")
	_logo.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	_logo.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	_logo.texture_filter = CanvasItem.TEXTURE_FILTER_LINEAR
	var h := LOGO_W * 619.0 / 1013.0
	_logo.custom_minimum_size = Vector2(LOGO_W, h)
	UI.pin(_logo, 0.5, 0.5, 0, 0)
	_logo.pivot_offset = Vector2(LOGO_W, h) * 0.5
	_logo.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_mat = ShaderMaterial.new()
	_mat.shader = load("res://shaders/rc_logo.gdshader")
	_logo.material = _mat
	_black.add_child(_logo)

	_prepare()
	_play_logo()

## Анимация логотипа: появление 0.6 с, перелив 0.8 с, пауза 0.6 с, угасание 0.4 с.
func _play_logo() -> void:
	await get_tree().process_frame
	_logo.scale = Vector2(0.92, 0.92)
	_mat.set_shader_parameter("alpha", 0.0)
	_mat.set_shader_parameter("shine", -0.2)
	Audio.play_sfx("logo_swoosh")
	_anim = create_tween()
	_anim.set_parallel(true)
	_anim.tween_property(_logo, "scale", Vector2.ONE, 0.6).set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_OUT)
	_anim.tween_method(func(v): _mat.set_shader_parameter("alpha", v), 0.0, 1.0, 0.6).set_ease(Tween.EASE_OUT)
	_anim.chain().tween_method(func(v): _mat.set_shader_parameter("shine", v), -0.15, 1.15, 0.8).set_trans(Tween.TRANS_SINE).set_ease(Tween.EASE_IN_OUT)
	_anim.chain().tween_interval(0.6)
	_anim.chain().tween_method(func(v): _mat.set_shader_parameter("alpha", v), 1.0, 0.0, 0.4)
	await _anim.finished
	_logo_done = true
	_try_finish()

func _on_tap(e: InputEvent) -> void:
	# первый запуск — логотип не пропускается; потом тап ускоряет анимацию до конца
	if _first_launch or _anim == null or not _anim.is_valid():
		return
	if e is InputEventMouseButton and e.pressed:
		_anim.set_speed_scale(5.0)

## Параллельная подготовка: фон меню + сеть + каталог + регистрация.
func _prepare() -> void:
	# фон меню (3D) — сразу под чёрным слоем
	var bg := preload("res://screens/MenuBackground.gd").new()
	Router.menu_bg = bg
	Router.go("main_menu", {}, false)
	# сеть
	_online = await Net.ping()
	if _online:
		await _after_online()
	_prep_done = true
	_try_finish()

func _after_online() -> void:
	await Progress.register()
	await Content.refresh()
	await Progress.fetch()

func _try_finish() -> void:
	if not (_logo_done and _prep_done):
		return
	Settings.set_value("launched_before", true)
	# чёрный → прозрачный, под ним меню; музыка плавно нарастает
	Router.boot_mode = false
	Audio.play_menu_music(2.5)
	_black.mouse_filter = Control.MOUSE_FILTER_IGNORE
	var tw := create_tween()
	tw.tween_property(_black, "color:a", 0.0, 1.0).set_trans(Tween.TRANS_SINE)
	await tw.finished
	if not _online:
		await Net.ensure_online()
		await _after_online()
	if Router.current != null and Router.current.has_method("on_boot_done"):
		Router.current.on_boot_done()
	queue_free()
