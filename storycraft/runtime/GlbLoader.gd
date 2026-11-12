class_name GlbLoader
extends RefCounted
## Загрузка glb в рантайме через GLTFDocument (без импорта редактора) и починка материалов:
## Nearest-фильтр, alpha scissor для MASK, cull off для doubleSided, вершинный цвет (AO) для мира.

## Загрузить glb (res:// или user://). Возвращает корневой Node3D или null.
static func load_glb(path: String, keep_keys := false) -> Node3D:
	if not FileAccess.file_exists(path):
		push_warning("glb не найден: " + path)
		return null
	var doc := GLTFDocument.new()
	var st := GLTFState.new()
	st.handle_binary_image = GLTFState.HANDLE_BINARY_EMBED_AS_UNCOMPRESSED
	var err := doc.append_from_file(path, st)
	if err != OK:
		push_warning("Ошибка чтения glb %s: %d" % [path, err])
		return null
	var root: Node3D = doc.generate_scene(st)
	if root and keep_keys:
		_rebuild_animations(path, st, root)
	return root

## GLTFDocument при импорте пересэмплирует анимацию (bake_fps = 30) — дублирующие ключи на склейках
## (за 1 мс до смены плана) теряются, и между планами появляется промежуточный кадр.
## Поэтому дорожки TRS строим сами из исходных ключей glTF: время и значения как есть, LINEAR.
static func _rebuild_animations(path: String, st: GLTFState, root: Node3D) -> void:
	var ap := anim_player(root)
	if ap == null:
		return
	var bytes := FileAccess.get_file_as_bytes(path)
	if bytes.size() < 20 or bytes.decode_u32(0) != 0x46546C67:
		return
	var json_len := bytes.decode_u32(12)
	var j = JSON.parse_string(bytes.slice(20, 20 + json_len).get_string_from_utf8())
	if not j is Dictionary or not j.get("animations") is Array:
		return
	var bin_start := 20 + json_len
	var bin := PackedByteArray()
	if bytes.size() > bin_start + 8:
		var bin_len := bytes.decode_u32(bin_start)
		bin = bytes.slice(bin_start + 8, bin_start + 8 + bin_len)
	var accessors: Array = j.get("accessors", [])
	var views: Array = j.get("bufferViews", [])
	var anim_root := ap.get_node_or_null(ap.root_node)
	if anim_root == null:
		return
	for ga in j["animations"]:
		var name := String(ga.get("name", "main"))
		var lib_name := ""
		if not ap.has_animation(name):
			continue
		var old := ap.get_animation(name)
		var anim := Animation.new()
		anim.length = old.length
		var length := 0.0
		for ch in ga.get("channels", []):
			var tgt: Dictionary = ch.get("target", {})
			var node_idx := int(tgt.get("node", -1))
			var kind := String(tgt.get("path", ""))
			if node_idx < 0 or not kind in ["translation", "rotation", "scale"]:
				continue
			# узел сцены ищем по имени из glTF (имена узлов в сценах mcanim уникальны)
			var gnodes: Array = j.get("nodes", [])
			if node_idx >= gnodes.size():
				continue
			var nname := String(gnodes[node_idx].get("name", ""))
			var node: Node = root if String(root.name) == nname else root.find_child(nname.validate_node_name(), true, false)
			if node == null:
				continue
			var smp: Dictionary = ga["samplers"][int(ch["sampler"])]
			var times := _floats(accessors, views, bin, int(smp["input"]))
			var vals := _floats(accessors, views, bin, int(smp["output"]))
			if times.is_empty():
				continue
			var np := anim_root.get_path_to(node)
			var tr := -1
			match kind:
				"translation":
					tr = anim.add_track(Animation.TYPE_POSITION_3D)
				"rotation":
					tr = anim.add_track(Animation.TYPE_ROTATION_3D)
				"scale":
					tr = anim.add_track(Animation.TYPE_SCALE_3D)
			anim.track_set_path(tr, np)
			anim.track_set_interpolation_type(tr, Animation.INTERPOLATION_LINEAR)
			var n := times.size()
			for k in n:
				var tk := float(times[k])
				length = maxf(length, tk)
				match kind:
					"translation":
						anim.position_track_insert_key(tr, tk, Vector3(vals[k * 3], vals[k * 3 + 1], vals[k * 3 + 2]))
					"rotation":
						anim.rotation_track_insert_key(tr, tk, Quaternion(vals[k * 4], vals[k * 4 + 1], vals[k * 4 + 2], vals[k * 4 + 3]).normalized())
					"scale":
						anim.scale_track_insert_key(tr, tk, Vector3(vals[k * 3], vals[k * 3 + 1], vals[k * 3 + 2]))
		if anim.get_track_count() == 0:
			continue
		anim.length = maxf(length, old.length)
		for ln in ap.get_animation_library_list():
			var lib := ap.get_animation_library(ln)
			if lib.has_animation(name):
				lib.remove_animation(name)
				lib.add_animation(name, anim)
				break

## Данные accessor'а как PackedFloat32Array (только float, плотная упаковка — как пишет mcanim).
static func _floats(accessors: Array, views: Array, bin: PackedByteArray, idx: int) -> PackedFloat32Array:
	if idx < 0 or idx >= accessors.size():
		return PackedFloat32Array()
	var a: Dictionary = accessors[idx]
	if int(a.get("componentType", 0)) != 5126 or not a.has("bufferView"):
		return PackedFloat32Array()
	var v: Dictionary = views[int(a["bufferView"])]
	var comps := {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}.get(String(a.get("type", "SCALAR")), 1) as int
	var start := int(v.get("byteOffset", 0)) + int(a.get("byteOffset", 0))
	var count := int(a.get("count", 0)) * comps
	if int(v.get("byteStride", 0)) not in [0, comps * 4]:
		return PackedFloat32Array()
	return bin.slice(start, start + count * 4).to_float32_array()

## Привести материалы к пиксельному виду. world = true — мир с вершинным цветом (затенение углов).
static func fix_materials(root: Node, world := false) -> void:
	var seen := {}
	for mi in root.find_children("*", "MeshInstance3D", true, false):
		var m: MeshInstance3D = mi
		m.gi_mode = GeometryInstance3D.GI_MODE_DISABLED
		if m.mesh == null:
			continue
		for i in m.mesh.get_surface_count():
			var mat := m.mesh.surface_get_material(i)
			if mat == null or seen.has(mat):
				continue
			seen[mat] = true
			fix_material(mat, world)

static func fix_material(mat: Material, world: bool) -> void:
	if not mat is BaseMaterial3D:
		return
	var b: BaseMaterial3D = mat
	b.texture_filter = BaseMaterial3D.TEXTURE_FILTER_NEAREST
	b.specular_mode = BaseMaterial3D.SPECULAR_DISABLED
	b.metallic = 0.0
	b.roughness = 1.0
	if b.transparency == BaseMaterial3D.TRANSPARENCY_ALPHA_SCISSOR or b.transparency == BaseMaterial3D.TRANSPARENCY_ALPHA_HASH:
		b.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA_SCISSOR
		b.alpha_scissor_threshold = 0.5
	if b.cull_mode == BaseMaterial3D.CULL_DISABLED or b.resource_name in ["solid", "cutout", "water"] or not world:
		b.cull_mode = BaseMaterial3D.CULL_DISABLED
	if world:
		b.vertex_color_use_as_albedo = true
		# эталон умножает AO на цвет текстуры в sRGB — так совпадает яркость
		b.vertex_color_is_srgb = true
		if b.transparency == BaseMaterial3D.TRANSPARENCY_ALPHA:
			# вода: полупрозрачная, без записи глубины за собой
			b.depth_draw_mode = BaseMaterial3D.DEPTH_DRAW_OPAQUE_ONLY
	else:
		b.vertex_color_use_as_albedo = false

## Найти AnimationPlayer внутри сцены.
static func anim_player(root: Node) -> AnimationPlayer:
	return root.find_child("AnimationPlayer", true, false) as AnimationPlayer
