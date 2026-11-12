extends Node3D
## Живой 3D-фон меню по assets/menu/menu.json: лесная поляна, мягкий свет и тени, небо, туман,
## медленный облёт камеры и животные, которые каждый запуск гуляют по-разному.

const CONFIG := "res://assets/menu/menu.json"

var cfg := {}
var cam: Camera3D
var env3d: Env3D
var rng := RandomNumberGenerator.new()
var _angle := 0.0
var _cells := {}             # Vector2i(клетка) → true
var _cell_list: Array[Vector2] = []
var mobs: Array = []
var _sound_timer := 0.0

class Mob:
	var root: Node3D
	var rig: Node3D
	var legs: Array[Node3D] = []
	var leg_phase: Array[float] = []
	var wings: Array[Node3D] = []
	var head: Node3D
	var head_rest: Basis
	var leg_rest: Array[Basis] = []
	var wing_rest: Array[Basis] = []
	var kind := ""
	var anim := "quadruped"
	var speed := 0.8
	var sound := ""
	var target := Vector2.ZERO
	var walking := false
	var wait := 0.0
	var phase := 0.0
	var swing := 0.0        # амплитуда шага (плавно 0..1)
	var head_yaw := 0.0
	var head_yaw_target := 0.0
	var head_timer := 0.0
	var player: AudioStreamPlayer3D

func _ready() -> void:
	name = "MenuBackground"
	rng.randomize()     # каждый запуск — по-новому
	var txt := FileAccess.get_file_as_string(CONFIG)
	var j = JSON.parse_string(txt)
	cfg = j if j is Dictionary else {}
	env3d = Env3D.new()
	add_child(env3d)
	env3d.apply(cfg.get("env", {}))
	env3d.sky_mat.set_shader_parameter("cloud_amount", 0.45)
	# мир
	var world := GlbLoader.load_glb("res://" + String(cfg.get("world", "assets/menu/menu_world.glb")))
	if world:
		GlbLoader.fix_materials(world, true)
		add_child(world)
	# камера
	cam = Camera3D.new()
	var c: Dictionary = cfg.get("camera", {})
	cam.fov = float(c.get("fov", 50))
	# орбита проходит по краю поляны, рядом со стволами и листвой; ближняя плоскость отсекает
	# то, что вплотную к камере (животные гуляют не ближе ~3 м от неё)
	cam.near = 2.5
	cam.far = 200.0
	add_child(cam)
	_angle = rng.randf_range(0.0, TAU)
	# клетки для прогулок
	for p in cfg.get("walkable_cells", []):
		var v := Vector2(float(p[0]), float(p[1]))
		_cell_list.append(v)
		_cells[_cell_key(v)] = true
	# животные
	for m in cfg.get("mobs", []):
		for i in int(m.get("count", 1)):
			_spawn(m)
	_sound_timer = rng.randf_range(6.0, 14.0)
	_update_camera(0.0)

func activate() -> void:
	if cam:
		cam.make_current()

func _enter_tree() -> void:
	if cam:
		cam.call_deferred("make_current")

func _cell_key(v: Vector2) -> Vector2i:
	return Vector2i(floori(v.x), floori(v.y))

func _walkable(v: Vector2) -> bool:
	return _cells.has(_cell_key(v))

func _spawn(m: Dictionary) -> void:
	if _cell_list.is_empty():
		return
	var rig := GlbLoader.load_glb("res://" + String(m.get("rig", "")))
	if rig == null:
		return
	GlbLoader.fix_materials(rig, false)
	var mob := Mob.new()
	mob.kind = String(m.get("kind", ""))
	mob.anim = String(m.get("anim", "quadruped"))
	mob.speed = float(m.get("speed", 0.8)) * rng.randf_range(0.85, 1.15)
	mob.sound = "res://" + String(m.get("sound", "")) if m.get("sound", "") != "" else ""
	mob.root = Node3D.new()
	mob.root.name = mob.kind
	add_child(mob.root)
	mob.root.add_child(rig)
	mob.rig = rig
	var head_name := String(m.get("head", "head"))
	for n in rig.find_children("*", "Node3D", true, false):
		var nm := String(n.name)
		if nm == head_name:
			mob.head = n
			mob.head_rest = n.basis
		elif nm.begins_with("leg"):
			mob.legs.append(n)
			mob.leg_rest.append(n.basis)
			# диагональная походка: 0 и 3 вместе, 1 и 2 — в противофазе
			var idx := int(nm.substr(3))
			mob.leg_phase.append(0.0 if idx == 0 or idx == 3 else PI)
		elif nm.begins_with("wing"):
			mob.wings.append(n)
			mob.wing_rest.append(n.basis)
	# головные «аксессуары» курицы двигаются вместе с головой
	if mob.head:
		for n in rig.find_children("*", "Node3D", true, false):
			if String(n.name) in ["beak", "comb", "wattle"]:
				var t: Transform3D = n.transform
				var rel := mob.head.transform.affine_inverse() * t
				n.get_parent().remove_child(n)
				mob.head.add_child(n)
				n.transform = rel
	var start := _cell_list[rng.randi() % _cell_list.size()]
	mob.root.position = Vector3(start.x, float(cfg.get("ground_y", 0.0)), start.y)
	mob.root.rotation.y = rng.randf_range(0.0, TAU)
	mob.wait = rng.randf_range(0.0, 3.0)
	mob.phase = rng.randf_range(0.0, TAU)
	if mob.sound != "":
		mob.player = AudioStreamPlayer3D.new()
		mob.player.bus = "SFX"
		mob.player.volume_db = -14.0
		mob.player.unit_size = 4.0
		mob.player.max_distance = 40.0
		mob.root.add_child(mob.player)
	mobs.append(mob)

## Новая цель: случайная клетка неподалёку, до которой можно дойти по прямой по проходимым клеткам.
func _pick_target(mob: Mob) -> void:
	var pos := Vector2(mob.root.position.x, mob.root.position.z)
	for attempt in 12:
		var c := _cell_list[rng.randi() % _cell_list.size()]
		var tgt := c + Vector2(rng.randf_range(-0.3, 0.3), rng.randf_range(-0.3, 0.3))
		var dist := pos.distance_to(tgt)
		if dist < 1.0 or dist > 6.0:
			continue
		var ok := true
		var steps := int(dist / 0.25) + 1
		for s in steps + 1:
			if not _walkable(pos.lerp(tgt, float(s) / steps)):
				ok = false
				break
		if ok:
			mob.target = tgt
			mob.walking = true
			return
	mob.wait = rng.randf_range(1.0, 3.0)

func _process(delta: float) -> void:
	_update_camera(delta)
	for mob in mobs:
		_update_mob(mob, delta)
	# изредка кто-нибудь подаёт голос (тихо)
	_sound_timer -= delta
	if _sound_timer <= 0.0:
		_sound_timer = rng.randf_range(8.0, 22.0)
		var m: Mob = mobs[rng.randi() % mobs.size()] if not mobs.is_empty() else null
		if m and m.player:
			m.player.stream = Audio.load_stream(m.sound)
			m.player.pitch_scale = rng.randf_range(0.9, 1.1)
			m.player.play()

func _update_camera(delta: float) -> void:
	var c: Dictionary = cfg.get("camera", {})
	_angle += deg_to_rad(float(c.get("speed_deg_per_s", 2.0))) * delta
	var ctr: Array = c.get("center", [0, 1, 0])
	var r := float(c.get("radius", 10.5))
	var center := Vector3(float(ctr[0]), float(ctr[1]), float(ctr[2]))
	cam.position = Vector3(center.x + cos(_angle) * r, float(c.get("height", 3.6)), center.z + sin(_angle) * r)
	cam.look_at(Vector3(center.x, float(c.get("look_height", 0.8)), center.z), Vector3.UP)

func _update_mob(mob: Mob, delta: float) -> void:
	if mob.walking:
		var pos := Vector2(mob.root.position.x, mob.root.position.z)
		var to := mob.target - pos
		var dist := to.length()
		if dist < 0.08:
			mob.walking = false
			mob.wait = rng.randf_range(1.0, 5.0)    # стоит 1–5 с
			mob.head_timer = rng.randf_range(0.3, 1.5)
		else:
			# поворот по направлению (модель смотрит в +Z)
			var want := atan2(to.x, to.y)
			mob.root.rotation.y = lerp_angle(mob.root.rotation.y, want, minf(1.0, delta * 5.0))
			var facing := Vector2(sin(mob.root.rotation.y), cos(mob.root.rotation.y))
			var align := clampf(facing.dot(to / dist), 0.0, 1.0)
			var step := mob.speed * delta * (0.25 + 0.75 * align)
			pos += to / dist * minf(step, dist)
			mob.root.position.x = pos.x
			mob.root.position.z = pos.y
		mob.swing = move_toward(mob.swing, 1.0, delta * 4.0)
	else:
		mob.swing = move_toward(mob.swing, 0.0, delta * 4.0)
		mob.wait -= delta
		if mob.wait <= 0.0:
			_pick_target(mob)
		# иногда крутит головой
		mob.head_timer -= delta
		if mob.head_timer <= 0.0:
			mob.head_timer = rng.randf_range(1.0, 3.0)
			mob.head_yaw_target = rng.randf_range(-0.7, 0.7) if rng.randf() < 0.6 else 0.0
	if mob.walking:
		mob.head_yaw_target = 0.0
	mob.head_yaw = lerpf(mob.head_yaw, mob.head_yaw_target, minf(1.0, delta * 3.0))
	# шаги: синус по оси X у костей ног
	var freq := 7.0 if mob.anim == "quadruped" else 11.0
	mob.phase += delta * freq * (0.3 + 0.7 * mob.swing)
	var amp := (0.6 if mob.anim == "quadruped" else 0.8) * mob.swing
	for i in mob.legs.size():
		var a := sin(mob.phase + mob.leg_phase[i]) * amp
		mob.legs[i].basis = Basis(Vector3.RIGHT, a) * mob.leg_rest[i]
	for i in mob.wings.size():
		# курица слегка помахивает крыльями
		var w := (sin(mob.phase * 1.5) * 0.15 * mob.swing) * (1.0 if i == 0 else -1.0)
		mob.wings[i].basis = Basis(Vector3.FORWARD, w) * mob.wing_rest[i]
	if mob.head:
		var bob := sin(mob.phase * 2.0) * 0.05 * mob.swing
		mob.head.basis = Basis(Vector3.UP, mob.head_yaw) * Basis(Vector3.RIGHT, bob) * mob.head_rest
