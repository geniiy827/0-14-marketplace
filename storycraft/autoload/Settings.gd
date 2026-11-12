extends Node
## Настройки игры: громкости, субтитры, графика, вибрация, скрытый адрес сервера.
## Хранятся в user://settings.cfg и применяются сразу при изменении.

signal changed(key: String)

const PATH := "user://settings.cfg"

## Размеры шрифта субтитров (кратны пикселю шрифта Minecraft = 12 px).
const SUB_SIZES := [24, 36, 48]
const SUB_SIZE_NAMES := ["Маленький", "Средний", "Большой"]
const QUALITY := [0.6, 0.8, 1.0]
const QUALITY_NAMES := ["Низкое", "Среднее", "Высокое"]

var values := {
	"master": 1.0,
	"music": 0.8,
	"sfx": 1.0,
	"voice": 1.0,
	"subtitles": true,
	"sub_size": 0,
	"shadows": true,
	"quality": 2,
	"fps": 60,
	"vibration": true,
	"server_url": "",
	"launched_before": false,
}

func _ready() -> void:
	_load()
	# применяем после того, как все автозагрузки готовы (шины создаёт Audio)
	call_deferred("apply_all")

func get_value(key: String, def = null):
	return values.get(key, def)

func set_value(key: String, v) -> void:
	if values.get(key) == v:
		return
	values[key] = v
	_apply(key)
	save()
	changed.emit(key)

func _load() -> void:
	var cf := ConfigFile.new()
	if cf.load(PATH) != OK:
		return
	for k in values.keys():
		if cf.has_section_key("settings", k):
			var v = cf.get_value("settings", k)
			# защита от битых типов
			if typeof(v) == typeof(values[k]) or (typeof(values[k]) == TYPE_FLOAT and typeof(v) == TYPE_INT):
				values[k] = v

func save() -> void:
	var cf := ConfigFile.new()
	for k in values.keys():
		cf.set_value("settings", k, values[k])
	cf.save(PATH)

func apply_all() -> void:
	for k in values.keys():
		_apply(k)

func _apply(key: String) -> void:
	match key:
		"master", "music", "sfx", "voice":
			Audio.set_bus_volume(key, float(values[key]))
		"fps":
			Engine.max_fps = int(values["fps"])
		"quality":
			var q: float = QUALITY[clampi(int(values["quality"]), 0, 2)]
			get_viewport().scaling_3d_scale = q

func subtitle_font_size() -> int:
	return SUB_SIZES[clampi(int(values["sub_size"]), 0, SUB_SIZES.size() - 1)]

func vibrate(ms: int) -> void:
	if values["vibration"] and OS.has_feature("mobile"):
		Input.vibrate_handheld(ms)
