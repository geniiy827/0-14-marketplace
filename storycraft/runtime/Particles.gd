class_name CubeParticles
extends MultiMeshInstance3D
## Частицы-кубики из дорожки particles: [x, y, z, size, r, g, b] на кадр. Без освещения,
## цвет на экземпляр. Один MultiMesh с переиспользуемым буфером.

const MAX := 4096

var _mm: MultiMesh
var _buf := PackedFloat32Array()
var _last_key := ""

func _init() -> void:
	name = "Particles"
	_mm = MultiMesh.new()
	_mm.transform_format = MultiMesh.TRANSFORM_3D
	_mm.use_colors = true
	var box := BoxMesh.new()
	box.size = Vector3.ONE
	var mat := StandardMaterial3D.new()
	mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	mat.vertex_color_use_as_albedo = true
	mat.vertex_color_is_srgb = true
	box.material = mat
	_mm.mesh = box
	_mm.instance_count = 0
	multimesh = _mm
	cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	gi_mode = GeometryInstance3D.GI_MODE_DISABLED
	custom_aabb = AABB(Vector3(-512, -512, -512), Vector3(1024, 1024, 1024))

## Показать набор частиц (Array из [x,y,z,size,r,g,b]); key — чтобы не пересобирать тот же кадр.
func show_set(list, key: String) -> void:
	if key == _last_key:
		return
	_last_key = key
	if not list is Array or list.is_empty():
		_mm.visible_instance_count = 0
		return
	var n: int = mini(list.size(), MAX)
	if _mm.instance_count < n:
		_mm.instance_count = maxi(n, mini(MAX, _mm.instance_count * 2 + 64))
	# буфер: 12 float трансформ + 4 float цвет
	var stride := 16
	if _buf.size() != _mm.instance_count * stride:
		_buf.resize(_mm.instance_count * stride)
	for i in n:
		var p: Array = list[i]
		var s := float(p[3]) if p.size() > 3 else 0.1
		var o := i * stride
		_buf[o + 0] = s; _buf[o + 1] = 0.0; _buf[o + 2] = 0.0; _buf[o + 3] = float(p[0])
		_buf[o + 4] = 0.0; _buf[o + 5] = s; _buf[o + 6] = 0.0; _buf[o + 7] = float(p[1])
		_buf[o + 8] = 0.0; _buf[o + 9] = 0.0; _buf[o + 10] = s; _buf[o + 11] = float(p[2])
		_buf[o + 12] = float(p[4]) if p.size() > 4 else 1.0
		_buf[o + 13] = float(p[5]) if p.size() > 5 else 1.0
		_buf[o + 14] = float(p[6]) if p.size() > 6 else 1.0
		_buf[o + 15] = 1.0
	_mm.buffer = _buf
	_mm.visible_instance_count = n

func clear() -> void:
	_last_key = ""
	_mm.visible_instance_count = 0
