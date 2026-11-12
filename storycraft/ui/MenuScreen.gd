class_name MenuScreen
extends Control
## Основа экранов меню поверх живого фона: размытие/затемнение фона, большая панель panel.png,
## заголовок и кнопка «Назад» (иконка) слева сверху.

var backdrop: ColorRect
var frame: PanelContainer
var body: Control
var header: HBoxContainer
var back_btn: Button
var title_label: Label

## Построить рамку экрана; body — область содержимого внутри панели.
func build_frame(title: String) -> void:
	UI.full(self)
	var sm := UI.safe_margins()
	backdrop = ColorRect.new()
	var mat := ShaderMaterial.new()
	mat.shader = load("res://shaders/blur_dim.gdshader")
	backdrop.material = mat
	UI.full(backdrop)
	backdrop.mouse_filter = Control.MOUSE_FILTER_STOP
	add_child(backdrop)
	mat.set_shader_parameter("amount", 0.0)
	create_tween().tween_method(func(v): mat.set_shader_parameter("amount", v), 0.0, 1.0, 0.35)

	frame = PanelContainer.new()
	UI.full(frame)
	frame.offset_left = 120 + sm.x
	frame.offset_right = -120 - sm.z
	frame.offset_top = 56 + sm.y
	frame.offset_bottom = -56 - sm.w
	add_child(frame)
	var vb := VBoxContainer.new()
	vb.add_theme_constant_override("separation", 12)
	frame.add_child(vb)
	header = HBoxContainer.new()
	header.add_theme_constant_override("separation", 20)
	vb.add_child(header)
	title_label = UI.label(title, UI.FS_BIG, true)
	header.add_child(title_label)
	body = Control.new()
	body.size_flags_vertical = Control.SIZE_EXPAND_FILL
	body.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	body.mouse_filter = Control.MOUSE_FILTER_PASS
	vb.add_child(body)

	back_btn = UI.icon_button("res://assets/ui/icon_back.png", on_back)
	back_btn.position = Vector2(20 + sm.x, 16 + sm.y)
	add_child(back_btn)

	# появление панели
	frame.modulate.a = 0.0
	frame.position.y += 24
	var tw := create_tween().set_parallel(true)
	tw.tween_property(frame, "modulate:a", 1.0, 0.25)
	tw.tween_property(frame, "position:y", frame.position.y - 24, 0.3).set_trans(Tween.TRANS_CUBIC).set_ease(Tween.EASE_OUT)

## Переопределяется: куда ведёт «Назад».
func on_back() -> void:
	Router.go("main_menu")
