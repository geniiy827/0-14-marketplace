extends Node
## Звук: шины Master/Music/SFX/Voice, музыка меню с кроссфейдом, UI-звуки и короткие эффекты.

const BUSES := {"master": "Master", "music": "Music", "sfx": "SFX", "voice": "Voice"}
const MENU_MUSIC := "res://assets/audio/menu_music.ogg"
const SFX_POOL := 8

var _music_a: AudioStreamPlayer
var _music_b: AudioStreamPlayer
var _music_cur: AudioStreamPlayer
var _music_tween: Tween
var _pool: Array[AudioStreamPlayer] = []
var _pool_i := 0
var _cache := {}          # путь → AudioStream
var menu_music_on := false

func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	_ensure_buses()
	_music_a = _make_player("Music")
	_music_b = _make_player("Music")
	_music_cur = _music_a
	for i in SFX_POOL:
		_pool.append(_make_player("SFX"))

func _make_player(bus: String) -> AudioStreamPlayer:
	var p := AudioStreamPlayer.new()
	p.bus = bus
	add_child(p)
	return p

func _ensure_buses() -> void:
	for b in ["Music", "SFX", "Voice"]:
		if AudioServer.get_bus_index(b) == -1:
			AudioServer.add_bus()
			var i := AudioServer.bus_count - 1
			AudioServer.set_bus_name(i, b)
			AudioServer.set_bus_send(i, "Master")

## Громкость шины по ключу настроек (0..1, линейно).
func set_bus_volume(key: String, linear: float) -> void:
	var idx := AudioServer.get_bus_index(BUSES.get(key, "Master"))
	if idx < 0:
		return
	linear = clampf(linear, 0.0, 1.0)
	AudioServer.set_bus_mute(idx, linear <= 0.001)
	AudioServer.set_bus_volume_db(idx, linear_to_db(maxf(linear, 0.0001)))

## Загрузка звука: res:// (импортированный) или user:// (ogg из кэша серии).
func load_stream(path: String, loop := false) -> AudioStream:
	var key := path + ("#loop" if loop else "")
	if _cache.has(key):
		return _cache[key]
	var s: AudioStream = null
	if path.begins_with("res://"):
		if ResourceLoader.exists(path):
			s = load(path)
			if s is AudioStreamOggVorbis and loop:
				s = s.duplicate()
	elif FileAccess.file_exists(path):
		if path.get_extension().to_lower() == "ogg":
			s = AudioStreamOggVorbis.load_from_file(path)
		elif path.get_extension().to_lower() == "wav":
			s = AudioStreamWAV.load_from_file(path)
		elif path.get_extension().to_lower() == "mp3":
			s = AudioStreamMP3.load_from_file(path)
	if s == null:
		return null
	if "loop" in s:
		s.loop = loop
	if path.begins_with("res://") or _cache.size() < 64:
		_cache[key] = s
	return s

func clear_cache_user() -> void:
	for k in _cache.keys():
		if not String(k).begins_with("res://"):
			_cache.erase(k)

# ---------------------------------------------------------------- музыка меню

func play_menu_music(fade := 2.0) -> void:
	if menu_music_on:
		return
	menu_music_on = true
	var s := load_stream(MENU_MUSIC, true)
	_crossfade_to(s, fade)

func stop_menu_music(fade := 1.5) -> void:
	if not menu_music_on:
		return
	menu_music_on = false
	_crossfade_to(null, fade)

func _crossfade_to(stream: AudioStream, fade: float) -> void:
	if _music_tween and _music_tween.is_valid():
		_music_tween.kill()
	var old := _music_cur
	var nxt := _music_b if _music_cur == _music_a else _music_a
	if old.playing:
		var t_out := create_tween()
		t_out.tween_property(old, "volume_db", -60.0, fade)
		t_out.tween_callback(old.stop)
	if stream != null:
		nxt.stream = stream
		nxt.volume_db = -60.0
		nxt.play()
		_music_tween = create_tween()
		_music_tween.tween_property(nxt, "volume_db", 0.0, fade).set_trans(Tween.TRANS_SINE).set_ease(Tween.EASE_OUT)
		_music_cur = nxt

# ---------------------------------------------------------------- эффекты

## Короткий звук: имя из assets/audio («ui_click») или полный путь.
func play_sfx(name_or_path: String, volume_db := 0.0, pitch := 1.0) -> void:
	var path := name_or_path
	if not path.contains("/"):
		path = "res://assets/audio/%s.ogg" % name_or_path
	var s := load_stream(path)
	if s == null:
		push_warning("Нет звука: " + path)
		return
	var p := _pool[_pool_i]
	_pool_i = (_pool_i + 1) % _pool.size()
	p.stream = s
	p.volume_db = volume_db
	p.pitch_scale = pitch
	p.play()

func click() -> void:
	play_sfx("ui_click", -2.0)

func select() -> void:
	play_sfx("ui_select", -2.0)
