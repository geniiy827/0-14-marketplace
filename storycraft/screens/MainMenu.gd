extends Control
## Главное меню: живой 3D-фон (Router.menu_bg) на весь экран, слева по вертикальному центру —
## логотип STORY CRAFT и кнопки «Приключения» и «Настройки».

var _col: VBoxContainer

func _ready() -> void:
	UI.full(self)
	mouse_filter = Control.MOUSE_FILTER_IGNORE
	var sm := UI.safe_margins()
	# лёгкое затемнение слева, чтобы кнопки читались на любом фоне
	var shade := TextureRect.new()
	var g := GradientTexture2D.new()
	var grad := Gradient.new()
	grad.set_color(0, Color(0, 0, 0, 0.35))
	grad.set_color(1, Color(0, 0, 0, 0.0))
	g.gradient = grad
	g.fill_from = Vector2(0, 0)
	g.fill_to = Vector2(1, 0)
	shade.texture = g
	shade.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	shade.stretch_mode = TextureRect.STRETCH_SCALE
	shade.set_anchors_preset(Control.PRESET_LEFT_WIDE)
	shade.offset_right = 640
	shade.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(shade)

	_col = VBoxContainer.new()
	_col.add_theme_constant_override("separation", 18)
	UI.pin(_col, 0.0, 0.5, 72 + sm.x, 0)
	_col.custom_minimum_size = Vector2(420, 0)
	add_child(_col)

	var title := TextureRect.new()
	title.texture = load("res://assets/branding/storycraft_title.png")
	title.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	title.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	title.texture_filter = CanvasItem.TEXTURE_FILTER_LINEAR
	title.custom_minimum_size = Vector2(420, 210)
	_col.add_child(title)
	var gap := Control.new()
	gap.custom_minimum_size = Vector2(0, 8)
	_col.add_child(gap)

	var b1 := UI.button("Приключения", func(): Router.go("adventures"), UI.FS_MID)
	b1.custom_minimum_size = Vector2(420, 84)
	_col.add_child(b1)
	var b2 := UI.button("Настройки", func(): Router.go("settings"), UI.FS_MID)
	b2.custom_minimum_size = Vector2(420, 84)
	_col.add_child(b2)

	# появление колонки
	_col.modulate.a = 0.0
	var tw := create_tween()
	tw.tween_interval(0.15)
	tw.tween_property(_col, "modulate:a", 1.0, 0.5)

	var ver := UI.label("RCodeStudio · v" + str(ProjectSettings.get_setting("application/config/version")), UI.FS_SMALL, false, Color(1, 1, 1, 0.6))
	UI.pin(ver, 1.0, 1.0, -24 - sm.z, -16 - sm.w)
	add_child(ver)

func on_back() -> void:
	if await Router.confirm("Выйти из игры?", "", "Выйти", "Остаться"):
		get_tree().quit()
