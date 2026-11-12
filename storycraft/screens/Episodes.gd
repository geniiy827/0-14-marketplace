extends MenuScreen
## Серии приключения: закрыта / доступна / пройдена. Тап — загрузка (только изменившиеся файлы) и старт;
## долгий тап по пройденной — «Начать заново». Если есть сохранённая точка — «Продолжить».

var adv_id := ""
var _list: Carousel
var _rows := []
var _busy := false

func setup(params: Dictionary) -> void:
	adv_id = String(params.get("adventure", ""))

func _ready() -> void:
	var adv := Content.adventure(adv_id)
	build_frame(String(adv.get("title", "Серии")))
	_list = Carousel.new(true)
	UI.full(_list)
	_list.padding = 8.0
	body.add_child(_list)
	_list.content.add_theme_constant_override("separation", 14)
	_build()
	Progress.updated.connect(_on_progress)
	_refresh()

func _refresh() -> void:
	var r: Dictionary = await Content.refresh()
	if not r["ok"]:
		await Net.ensure_online()
		r = await Content.refresh()
	await Progress.fetch()
	if not is_inside_tree():
		return
	if Content.adventure(adv_id).is_empty():
		Router.go("adventures")   # приключение удалили на сервере
		return
	_build()

func _on_progress() -> void:
	if is_inside_tree() and not _busy:
		_build()

func _build() -> void:
	for c in _list.content.get_children():
		c.queue_free()
	_rows.clear()
	var adv := Content.adventure(adv_id)
	var eps: Array = adv.get("episodes", [])
	var accent := UI.hex(String(adv.get("accent", "")))
	var sm := UI.safe_margins()
	var w: float = maxf(600.0, get_viewport_rect().size.x - 240.0 - sm.x - sm.z - 64.0)
	if eps.is_empty():
		var l := UI.label("Тут пока что пусто", UI.FS_MID, true, UI.C_DIM)
		l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		l.custom_minimum_size = Vector2(w, 300)
		l.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
		_list.content.add_child(l)
		return
	for i in eps.size():
		var e: Dictionary = eps[i]
		var ep_id := String(e.get("id", ""))
		var row := EpisodeRow.new()
		row.card_id = ep_id
		row.number = int(e.get("number", i + 1))
		row.title = String(e.get("title", ep_id))
		row.description = String(e.get("description", ""))
		row.accent = accent
		row.row_width = w
		var mins := int(round(float(e.get("duration", 0)) / 60.0))
		var dur := ("%d мин" % mins) if mins >= 1 else ("%d с" % int(e.get("duration", 0)))
		var need := Content.download_size(adv_id, e)
		row.meta_text = dur + "  ·  " + (("загрузка " + Content.human_size(need)) if need > 0 else "скачана")
		if Progress.is_completed(adv_id, ep_id):
			row.state = EpisodeRow.State.COMPLETED
			row.ending_text = _ending_title(e, Progress.ending_of(adv_id, ep_id))
		elif Progress.is_unlocked(adv_id, eps, i):
			row.state = EpisodeRow.State.AVAILABLE
		else:
			row.state = EpisodeRow.State.LOCKED
		row.tapped.connect(_on_tap.bind(i))
		row.long_pressed.connect(_on_long.bind(i))
		_list.content.add_child(row)
		_rows.append(row)
		_cover(row, String(e.get("cover", "")), String(e.get("cover_sha1", "")))

func _cover(row: EpisodeRow, url: String, sha: String) -> void:
	var now := Content.cover_now(url, sha)
	if now:
		row.set_cover(now, false)
		return
	var t: Texture2D = await Content.get_cover(url, sha)
	if is_instance_valid(row):
		row.set_cover(t)

## Название концовки из кэшированного episode.json (если серия скачана).
func _ending_title(e: Dictionary, ending: String) -> String:
	var path := Content.episode_dir(adv_id, String(e.get("id", ""))) + "/episode.json"
	if not FileAccess.file_exists(path):
		return ""
	var j = JSON.parse_string(FileAccess.get_file_as_string(path))
	if not j is Dictionary:
		return ""
	for nid in j.get("nodes", {}):
		var n = j["nodes"][nid]
		if n is Dictionary and n.get("type", "") == "end" and (n.get("ending", nid) == ending or nid == ending):
			return String(n.get("title", ""))
	return ""

func _on_tap(i: int) -> void:
	if _busy:
		return
	var eps: Array = Content.episodes(adv_id)
	if i >= eps.size():
		return
	if not Progress.is_unlocked(adv_id, eps, i):
		Audio.play_sfx("fail", -6.0)
		var row: Control = _rows[i]
		var tw := row.create_tween()
		var x := row.position.x
		for k in 3:
			tw.tween_property(row, "position:x", x + 8, 0.04)
			tw.tween_property(row, "position:x", x - 8, 0.04)
		tw.tween_property(row, "position:x", x, 0.04)
		return
	var e: Dictionary = eps[i]
	var resume := Progress.resume_of(adv_id)
	if resume.get("episode", "") == e.get("id", "") and String(resume.get("node", "")) != "":
		var m: Modal = load("res://ui/Modal.tscn").instantiate()
		m.configure(String(e.get("title", "")), "Продолжить с места, где ты остановился?", ["Продолжить", "Сначала", "Отмена"], true)
		Router.push_modal(m)
		var idx: int = await m.chosen
		if idx == 0:
			_start(e, true)
		elif idx == 1:
			_start(e, false)
		return
	_start(e, false)

func _on_long(i: int) -> void:
	if _busy:
		return
	var eps: Array = Content.episodes(adv_id)
	if i >= eps.size() or not Progress.is_completed(adv_id, String(eps[i].get("id", ""))):
		return
	if await Router.confirm("Начать заново?", "Серия «%s» начнётся с самого начала." % eps[i].get("title", ""), "Начать заново", "Отмена"):
		_start(eps[i], false)

func _start(e: Dictionary, resume: bool) -> void:
	if _busy:
		return
	if int(e.get("version", 1)) > Content.SUPPORTED_EPISODE_VERSION:
		await Router.alert("Обновите игру", "Эта серия сделана для более новой версии Story Craft. Обнови игру, чтобы её пройти.")
		return
	_busy = true
	if not Content.is_cached(adv_id, e):
		var ok := await _download(e)
		if not ok:
			_busy = false
			_build()
			return
	_busy = false
	Router.go("player", {"adventure": adv_id, "episode": String(e.get("id", "")), "resume": resume}, true)

## Окно загрузки с прогресс-баром; можно отменить. true — всё скачано.
func _download(e: Dictionary) -> bool:
	while true:
		await Net.ensure_online()
		var m: Modal = load("res://ui/Modal.tscn").instantiate()
		var total := Content.download_size(adv_id, e)
		m.configure("Загрузка серии", "«%s» — %s" % [e.get("title", ""), Content.human_size(total)], ["Отмена"], true)
		Router.push_modal(m)
		await get_tree().process_frame
		var bar := ProgressBar.new()
		bar.custom_minimum_size = Vector2(480, 30)
		bar.show_percentage = false
		bar.max_value = 1.0
		bar.step = 0.0
		var pct := UI.label("0%", UI.FS_SMALL, false, UI.C_DIM)
		pct.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
		var box := m.content_box()
		box.add_child(bar)
		box.move_child(bar, box.get_child_count() - 2)
		box.add_child(pct)
		box.move_child(pct, box.get_child_count() - 2)
		var dl := Net.Download.new()
		m.chosen.connect(func(_i): dl.abort())
		var err: String = await Content.download_episode(adv_id, e, dl, func(done, tot):
			if is_instance_valid(bar) and tot > 0:
				bar.value = float(done) / float(tot)
				pct.text = "%d%%  ·  %s / %s" % [int(100.0 * done / tot), Content.human_size(done), Content.human_size(tot)])
		if err == "":
			if is_instance_valid(m):
				m.close()
			Audio.play_sfx("success", -6.0)
			return true
		if is_instance_valid(m):
			m.close()
		if err == "cancel":
			return false
		if not await Net.ping():
			continue   # связь пропала — окно «Нет подключения», затем докачка
		if not await Router.confirm("Не удалось скачать", "Ошибка: %s" % err, "Повторить", "Отмена"):
			return false
	return false

func on_back() -> void:
	if not _busy:
		Router.go("adventures")
