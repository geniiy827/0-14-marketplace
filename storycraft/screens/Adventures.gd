extends MenuScreen
## Список приключений: горизонтальные карточки со свайпом и инерцией поверх размытого живого фона.
## Обновляется при входе и каждые 30 с; изменения каталога анимируются.

const REFRESH_SEC := 30.0

var _carousel: Carousel
var _empty: Label
var _loading: Label
var _cards := {}        # id → Card
var _timer: Timer
var _refreshing := false

func _ready() -> void:
	build_frame("Приключения")
	_carousel = Carousel.new(false)
	UI.full(_carousel)
	_carousel.offset_top = 8
	_carousel.offset_bottom = -8
	body.add_child(_carousel)
	_empty = UI.label("Тут пока что пусто", UI.FS_MID, true, UI.C_DIM)
	_empty.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	_empty.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	UI.full(_empty)
	_empty.visible = false
	body.add_child(_empty)
	_loading = UI.label("Загрузка…", UI.FS_SMALL, false, UI.C_DIM)
	_loading.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	_loading.vertical_alignment = VERTICAL_ALIGNMENT_CENTER
	UI.full(_loading)
	body.add_child(_loading)
	# сначала показываем то, что уже есть, потом обновляем
	if int(Content.catalog.get("version", 0)) > 0:
		_rebuild(false)
	_timer = Timer.new()
	_timer.wait_time = REFRESH_SEC
	_timer.autostart = true
	_timer.timeout.connect(_refresh)
	add_child(_timer)
	_refresh()

func _refresh() -> void:
	if _refreshing:
		return
	_refreshing = true
	var r: Dictionary = await Content.refresh()
	if not r["ok"]:
		await Net.ensure_online()
		r = await Content.refresh()
	if not is_inside_tree():
		return
	_refreshing = false
	_loading.visible = false
	if r["ok"] and (r["changed"] or _cards.is_empty()):
		_rebuild(true)
	elif _cards.is_empty():
		_rebuild(false)

func _rebuild(animated: bool) -> void:
	_loading.visible = false
	var advs: Array = Content.adventures()
	var keep := {}
	var i := 0
	for a in advs:
		var id := String(a.get("id", ""))
		keep[id] = true
		var card: Card = _cards.get(id)
		if card == null or card.title != a.get("title", "") or card.description != a.get("description", "") \
				or card.accent != UI.hex(a.get("accent", "")):
			if card != null:
				card.queue_free()
			card = Card.new()
			card.card_id = id
			card.title = String(a.get("title", id))
			card.description = String(a.get("description", ""))
			card.accent = UI.hex(String(a.get("accent", "")))
			card.card_width = 420
			card.tapped.connect(_open.bind(id))
			_carousel.content.add_child(card)
			_cards[id] = card
			_load_cover(card, String(a.get("cover", "")), String(a.get("cover_sha1", "")))
			if animated:
				card.appear(0.05 * i)
		_carousel.content.move_child(card, i)
		i += 1
	# удалённые на сервере — исчезают
	for id in _cards.keys():
		if not keep.has(id):
			var c: Card = _cards[id]
			_cards.erase(id)
			if is_instance_valid(c):
				if animated:
					c.disappear()
				else:
					c.queue_free()
	var empty := advs.is_empty()
	if empty and not _empty.visible:
		UI.fade_in(_empty)
	_empty.visible = empty

func _load_cover(card: Card, url: String, sha: String) -> void:
	var tex: Texture2D = await Content.get_cover(url, sha)
	if is_instance_valid(card):
		card.set_cover(tex)

func _open(id: String) -> void:
	Router.go("episodes", {"adventure": id})

func on_back() -> void:
	Router.go("main_menu")
