class_name Env3D
extends Node3D
## Окружение сцены: солнце (DirectionalLight3D), ambient, небо-градиент со звёздами и облаками, линейный туман.
## Параметры — словарь env из дорожек серии или из menu.json.

var world_env: WorldEnvironment
var sun: DirectionalLight3D
var env: Environment
var sky_mat: ShaderMaterial
var _key := ""

func _init() -> void:
	name = "Env3D"
	env = Environment.new()
	env.background_mode = Environment.BG_SKY
	sky_mat = ShaderMaterial.new()
	sky_mat.shader = load("res://shaders/sky.gdshader")
	var sky := Sky.new()
	sky.sky_material = sky_mat
	sky.process_mode = Sky.PROCESS_MODE_REALTIME
	sky.radiance_size = Sky.RADIANCE_SIZE_32
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.reflected_light_source = Environment.REFLECTION_SOURCE_DISABLED
	env.tonemap_mode = Environment.TONE_MAPPER_LINEAR
	env.fog_enabled = true
	env.fog_mode = Environment.FOG_MODE_DEPTH
	env.fog_sky_affect = 0.0
	env.fog_density = 1.0
	env.fog_depth_curve = 1.0
	world_env = WorldEnvironment.new()
	world_env.environment = env
	add_child(world_env)
	sun = DirectionalLight3D.new()
	sun.light_energy = 1.0
	sun.light_specular = 0.0
	sun.directional_shadow_mode = DirectionalLight3D.SHADOW_ORTHOGONAL
	sun.directional_shadow_max_distance = 30.0
	sun.shadow_bias = 0.03
	sun.shadow_normal_bias = 1.0
	sun.shadow_blur = 1.0
	add_child(sun)
	apply_shadows()
	Settings.changed.connect(_on_settings)

func _on_settings(k: String) -> void:
	if k == "shadows":
		apply_shadows()

func apply_shadows() -> void:
	sun.shadow_enabled = bool(Settings.get_value("shadows", true))

static func _c(a, def: Color) -> Color:
	if a is Array and a.size() >= 3:
		return Color(float(a[0]), float(a[1]), float(a[2]))
	return def

static func _v(a, def: Vector3) -> Vector3:
	if a is Array and a.size() >= 3:
		return Vector3(float(a[0]), float(a[1]), float(a[2]))
	return def

## Применить env (пропускается, если не изменился).
func apply(e: Dictionary) -> void:
	var key := JSON.stringify(e)
	if key == _key:
		return
	_key = key
	var sd := _v(e.get("sun_dir"), Vector3(0.45, 0.8, 0.35)).normalized()
	# sun_dir — направление НА солнце; свет идёт против него
	if absf(sd.dot(Vector3.UP)) > 0.999:
		sun.basis = Basis.looking_at(-sd, Vector3.FORWARD)
	else:
		sun.basis = Basis.looking_at(-sd, Vector3.UP)
	sun.light_color = _c(e.get("sun_color"), Color(1, 1, 1))
	# солнце ниже горизонта — не светит
	sun.visible = sd.y > -0.05
	env.ambient_light_color = _c(e.get("ambient"), Color(0.6, 0.6, 0.65))
	env.ambient_light_energy = 1.0
	sky_mat.set_shader_parameter("zenith", _c(e.get("sky_zenith"), Color(0.33, 0.55, 0.95)))
	sky_mat.set_shader_parameter("horizon", _c(e.get("sky_horizon"), Color(0.68, 0.82, 1.0)))
	sky_mat.set_shader_parameter("sun_disc", _c(e.get("sun_disc"), Color(1.0, 0.95, 0.75)))
	sky_mat.set_shader_parameter("sun_dir", sd)
	sky_mat.set_shader_parameter("stars", float(e.get("stars", 0.0)))
	if e.has("clouds"):
		sky_mat.set_shader_parameter("clouds", _c(e.get("clouds"), Color.WHITE))
		sky_mat.set_shader_parameter("cloud_amount", 0.45)
	else:
		sky_mat.set_shader_parameter("cloud_amount", 0.0)
	env.fog_light_color = _c(e.get("fog_color"), Color(0.65, 0.78, 0.95))
	env.fog_depth_begin = float(e.get("fog_near", 18.0))
	env.fog_depth_end = maxf(float(e.get("fog_far", 46.0)), env.fog_depth_begin + 1.0)
