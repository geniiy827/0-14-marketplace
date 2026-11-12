class_name GlbLoader
extends RefCounted
## Загрузка glb в рантайме через GLTFDocument (без импорта редактора) и починка материалов:
## Nearest-фильтр, alpha scissor для MASK, cull off для doubleSided, вершинный цвет (AO) для мира.

## Загрузить glb (res:// или user://). Возвращает корневой Node3D или null.
static func load_glb(path: String) -> Node3D:
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
	return root

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
