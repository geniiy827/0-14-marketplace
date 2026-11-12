extends MenuScreen
## Настройки: громкости (Общая/Музыка/Звуки/Голоса), субтитры, графика, вибрация, кэш, «О игре».
## Открывается из меню (экран) и из паузы (поверх серии, overlay = true).

signal closed

var overlay := false
var _list: Carousel
var _w := 900.0
var _cache_btn: Button
var _ver_taps := 0
var _ver_last := 0
var _ver_down := 0
var _toast: Label

func setup(params: Dictionary) -> void:
	overlay = bool(params.get("overlay", false))

func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	build_frame("Настройки")
	var sm := UI.safe_margins()
	_w = maxf(600.0, get_viewport_rect().size.x - 240.0 - sm.x - sm.z - 64.0)
	_list = Carousel.new(true)
	UI.full(_list)
	_list.padding = 8.0
	body.add_child(_list)
	_list.content.add_theme_constant_override("separation", 10)

	_section("Звук")
	_slider("Общая громкость", "master", "ui_click")
	_slider("Музыка", "music", "")
	_slider("Звуки", "sfx", "pop")
	_slider("Голоса", "voice", "ui_select")

	_section("Субтитры")
	_check("Показывать субтитры", "subtitles")
	_cycle("Размер субтитров", "sub_size", Settings.SUB_SIZE_NAMES, [0, 1, 2])

	_section("Графика")
	_check("Тени", "shadows")
	_cycle("Качество", "quality", Settings.QUALITY_NAMES, [0, 1, 2])
	_cycle("Частота кадров", "fps", ["Как у экрана", "60 FPS", "30 FPS"], [0, 60, 30])

	_section("Прочее")
	_check("Вибрация", "vibration")
	var row := _row("Скачано за сессию")
	_cache_btn = UI.button("Очистить (%s)" % Content.human_size(Content.cache_size()), _clear_cache)
	_cache_btn.custom_minimum_size = Vector2(320, 0)
	row.add_child(_cache_btn)

	_section("О игре")
	var about := VBoxContainer.new()
	about.custom_minimum_size = Vector2(_w, 0)
	about.mouse_filter = Control.MOUSE_FILTER_PASS
	_list.content.add_child(about)
	var logo_row := HBoxContainer.new()
	logo_row.add_theme_constant_override("separation", 16)
	logo_row.mouse_filter = Control.MOUSE_FILTER_PASS
	var logo := TextureRect.new()
	logo.texture = load("res://assets/branding/sc_logo_source.png")
	logo.expand_mode = TextureRect.EXPAND_IGNORE_SIZE
	logo.stretch_mode = TextureRect.STRETCH_KEEP_ASPECT_CENTERED
	logo.custom_minimum_size = Vector2(72, 72)
	logo_row.add_child(logo)
	var nm := UI.label("Story Craft — RCodeStudio", UI.FS_SMALL, true)
	nm.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	logo_row.add_child(nm)
	about.add_child(logo_row)
	var ver := UI.label("Версия " + str(ProjectSettings.get_setting("application/config/version")), UI.FS_SMALL, false, UI.C_DIM)
	ver.mouse_filter = Control.MOUSE_FILTER_PASS
	ver.gui_input.connect(_on_version_input)
	about.add_child(ver)
	var dev := UI.label("ID устройства: " + Progress.device_id + "  (нажми, чтобы скопировать)", UI.FS_SMALL, false, UI.C_DIM)
	dev.autowrap_mode = TextServer.AUTOWRAP_ARBITRARY
	dev.custom_minimum_size = Vector2(_w, 0)
	dev.mouse_filter = Control.MOUSE_FILTER_PASS
	dev.gui_input.connect(func(e):
		if e is InputEventMouseButton and not e.pressed and e.button_index == MOUSE_BUTTON_LEFT and not _list.dragged:
			DisplayServer.clipboard_set(Progress.device_id)
			_show_toast("ID скопирован"))
	about.add_child(dev)
	var srv := UI.label("Сервер: " + Net.base_url(), UI.FS_SMALL, false, Color(1, 1, 1, 0.45))
	srv.visible = Settings.get_value("server_url", "") != ""
	about.add_child(srv)

	_toast = UI.label("", UI.FS_SMALL, true)
	_toast.custom_minimum_size = Vector2(400, 0)
	UI.pin(_toast, 0.5, 1.0, 0, -100)
	_toast.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	_toast.modulate.a = 0.0
	add_child(_toast)

func _section(title: String) -> void:
	var l := UI.label(title, UI.FS_MID, true, Color(1.0, 0.82, 0.45))
	l.custom_minimum_size = Vector2(_w, 52)
	l.vertical_alignment = VERTICAL_ALIGNMENT_BOTTOM
	l.mouse_filter = Control.MOUSE_FILTER_PASS
	_list.content.add_child(l)

func _row(title: String) -> HBoxContainer:
	var h := HBoxContainer.new()
	h.custom_minimum_size = Vector2(_w, 64)
	h.mouse_filter = Control.MOUSE_FILTER_PASS
	h.add_theme_constant_override("separation", 24)
	var l := UI.label(title, UI.FS_SMALL)
	l.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	l.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	l.mouse_filter = Control.MOUSE_FILTER_PASS
	h.add_child(l)
	_list.content.add_child(h)
	return h

func _slider(title: String, key: String, sample: String) -> void:
	var h := _row(title)
	var s := HSlider.new()
	s.min_value = 0.0
	s.max_value = 1.0
	s.step = 0.05
	s.value = float(Settings.get_value(key, 1.0))
	s.custom_minimum_size = Vector2(380, 48)
	s.size_flags_vertical = Control.SIZE_SHRINK_CENTER
	s.focus_mode = Control.FOCUS_NONE
	var pct := UI.label("%d%%" % int(s.value * 100), UI.FS_SMALL)
	pct.custom_minimum_size = Vector2(84, 0)
	pct.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	s.value_changed.connect(func(v):
		Settings.set_value(key, v)
		pct.text = "%d%%" % int(round(v * 100)))
	s.drag_ended.connect(func(_c):
		if sample != "":
			if key == "voice":
				_play_voice_sample()
			else:
				Audio.play_sfx(sample))
	h.add_child(s)
	h.add_child(pct)

## Образец голоса: короткий звук через шину Voice.
func _play_voice_sample() -> void:
	var p := AudioStreamPlayer.new()
	p.bus = "Voice"
	p.stream = Audio.load_stream("res://assets/audio/ui_select.ogg")
	add_child(p)
	p.play()
	p.finished.connect(p.queue_free)

func _check(title: String, key: String) -> void:
	var h := _row(title)
	var c := CheckBox.new()
	c.button_pressed = bool(Settings.get_value(key, true))
	c.text = "Вкл" if c.button_pressed else "Выкл"
	c.focus_mode = Control.FOCUS_NONE
	c.custom_minimum_size = Vector2(160, 0)
	c.toggled.connect(func(on):
		Settings.set_value(key, on)
		c.text = "Вкл" if on else "Выкл"
		Audio.click())
	h.add_child(c)

func _cycle(title: String, key: String, names: Array, vals: Array) -> void:
	var h := _row(title)
	var cur := vals.find(Settings.get_value(key))
	if cur < 0:
		cur = vals.size() - 1
	var b := UI.button(String(names[cur]))
	b.custom_minimum_size = Vector2(320, 0)
	b.pressed.connect(func():
		var i := (vals.find(Settings.get_value(key)) + 1) % vals.size()
		Settings.set_value(key, vals[i])
		b.text = String(names[i]))
	h.add_child(b)

func _clear_cache() -> void:
	if await Router.confirm("Очистить кэш серий?", "Скачанные серии удалятся (%s). Их можно будет скачать снова." % Content.human_size(Content.cache_size()), "Очистить", "Отмена"):
		Content.clear_cache()
		_cache_btn.text = "Очистить (%s)" % Content.human_size(Content.cache_size())
		_show_toast("Кэш очищен")

## Скрытое по номеру версии: 5 тапов — оверлей отладки (FPS, draw calls, полигоны);
## удержание 1.5 с — адрес сервера для тестов.
func _on_version_input(e: InputEvent) -> void:
	if not (e is InputEventMouseButton and e.button_index == MOUSE_BUTTON_LEFT):
		return
	var now := Time.get_ticks_msec()
	if not e.pressed:
		if _ver_down > 0 and now - _ver_down >= 1500:
			_ver_taps = 0
			_server_dialog()
		_ver_down = 0
		return
	_ver_down = now
	_ver_taps = _ver_taps + 1 if now - _ver_last < 700 else 1
	_ver_last = now
	if _ver_taps >= 5:
		_ver_taps = 0
		var on := not bool(Settings.get_value("debug_overlay", false))
		Settings.set_value("debug_overlay", on)
		_show_toast("Отладка: " + ("вкл" if on else "выкл"))

func _server_dialog() -> void:
	var m: Modal = load("res://ui/Modal.tscn").instantiate()
	m.configure("Адрес сервера", "Пусто — по умолчанию:\n" + Net.DEFAULT_URL, ["Сохранить", "Отмена"], true)
	Router.push_modal(m)
	await get_tree().process_frame
	var le := LineEdit.new()
	le.text = String(Settings.get_value("server_url", ""))
	le.placeholder_text = Net.DEFAULT_URL
	le.custom_minimum_size = Vector2(500, 56)
	var box := m.content_box()
	box.add_child(le)
	box.move_child(le, box.get_child_count() - 2)
	var idx: int = await m.chosen
	if idx == 0:
		Settings.set_value("server_url", le.text.strip_edges())
		Content.catalog = {"version": 0, "adventures": []}
		_show_toast("Сервер: " + Net.base_url())

func _show_toast(t: String) -> void:
	_toast.text = t
	var tw := create_tween()
	tw.tween_property(_toast, "modulate:a", 1.0, 0.15)
	tw.tween_interval(1.4)
	tw.tween_property(_toast, "modulate:a", 0.0, 0.3)

func on_back() -> void:
	if overlay:
		closed.emit()
		var tw := create_tween()
		tw.tween_property(self, "modulate:a", 0.0, 0.15)
		tw.tween_callback(queue_free)
	else:
		Router.go("main_menu")
