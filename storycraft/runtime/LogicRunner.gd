class_name LogicRunner
extends RefCounted
## Граф узлов серии (SPEC_LOGIC): scene / choice / interact / minigame / overlay / end,
## действия (goto, set, add, if, sfx, say, play, wait, show/hide, shake, flash, vibrate, checkpoint, end),
## переменные серии и приключения (adv.*). UI и 3D делает host (Player.gd).

var host                       # Player
var ep: EpisodeLoader
var vars := {}                 # переменные серии + встроенные (taps, time_left, score, choice)
var adv := {}                  # переменные приключения
var node_id := ""
var node := {}
var _gen := 0                  # «поколение»: растёт при каждом переходе, старые цепочки действий прерываются
var ended := false

func _init(h, e: EpisodeLoader, adv_vars: Dictionary) -> void:
	host = h
	ep = e
	adv = adv_vars.duplicate(true)
	var v = e.data.get("variables", {})
	vars = v.duplicate(true) if v is Dictionary else {}
	for k in ["taps", "time_left", "score", "choice"]:
		if not vars.has(k):
			vars[k] = 0

func gen() -> int:
	return _gen

func alive(g: int) -> bool:
	return g == _gen and not ended

# ---------------------------------------------------------------- переменные

func get_var(name: String):
	if name.begins_with("adv."):
		return adv.get(name.substr(4), 0)
	return vars.get(name, 0)

func set_var(name: String, v) -> void:
	if name.begins_with("adv."):
		adv[name.substr(4)] = v
	else:
		vars[name] = v

func eval(src, def = null):
	return Expr.eval(src, vars, adv, def)

func test(src) -> bool:
	return Expr.test(src, vars, adv)

func text(s) -> String:
	return Expr.interpolate(String(s), vars, adv)

## next/goto может быть строкой или {"if": "...", "then": ..., "else": ...}
func resolve(target):
	if target is Dictionary:
		if target.has("if"):
			return resolve(target.get("then") if test(target["if"]) else target.get("else"))
		return null
	if target == null:
		return null
	return String(target)

# ---------------------------------------------------------------- узлы

func start(id: String, saved_vars := {}) -> void:
	for k in saved_vars:
		vars[k] = saved_vars[k]
	goto(id)

func goto(id) -> void:
	if ended:
		return
	if id == null or String(id) == "":
		host.logic_error("Переход в никуда из узла «%s»" % node_id)
		return
	_enter(String(id))

func _enter(id: String) -> void:
	_gen += 1
	var g := _gen
	var n = ep.nodes().get(id)
	if not n is Dictionary:
		host.logic_error("Нет узла «%s»" % id)
		return
	node_id = id
	node = n
	var type := String(n.get("type", ""))
	host.on_node_enter(id, n)
	if not await run_actions(n.get("on_enter", []), g):
		return
	match type:
		"scene":
			var ok: bool = await host.play_scene(String(n.get("scene", "")), g)
			if not alive(g) or not ok:
				return
			await _exit_to(n, resolve(n.get("next")), g)
		"choice":
			await _choice(n, g)
		"interact":
			host.show_background(String(n.get("background", "")))
			host.ui_interact(n, g)
			var to = n.get("timeout")
			if (to is int or to is float) and float(to) > 0.0:
				await host.wait(float(to))
				if alive(g):
					if not await run_actions(n.get("on_timeout", []), g):
						return
					if n.has("next"):
						await _exit_to(n, resolve(n.get("next")), g)
		"minigame":
			host.show_background(String(n.get("background", "")))
			var success: bool = await host.ui_minigame(n, g)
			if not alive(g):
				return
			if not await run_actions(n.get("on_exit", []), g):
				return
			if not await run_actions(n.get("success" if success else "fail", []), g):
				return
			if n.has("next"):
				goto(resolve(n.get("next")))
		"overlay":
			host.show_background(String(n.get("background", "")))
			host.ui_overlay(n, g)
		"end":
			_finish(n, id)
		_:
			host.logic_error("Неизвестный тип узла «%s» (%s)" % [type, id])
			if n.has("next"):
				goto(resolve(n.get("next")))

func _exit_to(n: Dictionary, target, g: int) -> void:
	if not await run_actions(n.get("on_exit", []), g):
		return
	if target == null:
		host.logic_error("У узла «%s» нет next" % node_id)
		return
	goto(target)

func _choice(n: Dictionary, g: int) -> void:
	host.show_background(String(n.get("background", "")))
	var opts: Array = n.get("options", [])
	var shown := []         # [{index, text, locked, locked_text}]
	for i in opts.size():
		var o = opts[i]
		if not o is Dictionary:
			continue
		var ok := test(o.get("if")) if o.has("if") else true
		if ok:
			shown.append({"index": i, "text": text(o.get("text", "…")), "locked": false})
		elif o.has("locked_text"):
			shown.append({"index": i, "text": text(o.get("text", "…")), "locked": true, "locked_text": text(o["locked_text"])})
	if shown.is_empty():
		host.logic_error("В выборе «%s» нет доступных вариантов" % node_id)
		return
	var timeout_idx := int(n.get("timeout", -1))
	var idx: int = await host.ui_choice(n, shown, float(n.get("timer", 0)), timeout_idx, g)
	if not alive(g):
		return
	if idx < 0 or idx >= opts.size():
		# таймаут указывает на скрытый вариант — берём первый доступный
		idx = shown[0]["index"]
		for s in shown:
			if not s["locked"]:
				idx = s["index"]
				break
	var o: Dictionary = opts[idx]
	vars["choice"] = idx
	if o.has("remember"):
		host.remember(text(o["remember"]))
	if not await run_actions(o.get("actions", []), g):
		return
	await _exit_to(n, resolve(o.get("goto")), g)

func _finish(n: Dictionary, id: String) -> void:
	if ended:
		return
	ended = true
	var ending := String(n.get("ending", id))
	host.ui_end(text(n.get("title", "Конец серии")), text(n.get("text", "")), ending, bool(n.get("unlock_next", true)))

## Немедленно завершить серию с концовкой (действие end).
func end_with(ending: String) -> void:
	# если есть узел end с такой концовкой — используем его заголовок и текст
	for nid in ep.nodes():
		var n = ep.nodes()[nid]
		if n is Dictionary and n.get("type", "") == "end" and (n.get("ending", nid) == ending or nid == ending):
			_gen += 1
			node_id = nid
			_finish(n, nid)
			return
	_gen += 1
	_finish({"title": "Конец серии", "ending": ending}, ending)

# ---------------------------------------------------------------- действия

## Выполнить список действий по очереди. false — цепочка прервана (переход/конец/устарела).
func run_actions(list, g: int) -> bool:
	if not list is Array:
		return alive(g)
	for a in list:
		if not alive(g):
			return false
		if not a is Dictionary:
			continue
		if not await _action(a, g):
			return false
	return alive(g)

func _action(a: Dictionary, g: int) -> bool:
	if a.has("goto"):
		goto(resolve(a["goto"]))
		return false
	if a.has("set") and a["set"] is Dictionary:
		for k in a["set"]:
			set_var(String(k), a["set"][k])
	if a.has("add") and a["add"] is Dictionary:
		for k in a["add"]:
			var cur = get_var(String(k))
			var add = a["add"][k]
			if (cur is int or cur is float) and (add is int or add is float):
				set_var(String(k), cur + add)
			else:
				set_var(String(k), add)
	if a.has("if"):
		var branch = a.get("then", []) if test(a["if"]) else a.get("else", [])
		if not await run_actions(branch, g):
			return false
	if a.has("sfx"):
		host.sfx(String(a["sfx"]))
	if a.has("say") and a["say"] is Dictionary:
		var s: Dictionary = a["say"]
		host.say(String(s.get("who", "")), text(s.get("text", "")), String(s.get("voice", "")), float(s.get("time", 0.0)))
	if a.has("show"):
		host.set_visible_item(String(a["show"]), true)
	if a.has("hide"):
		host.set_visible_item(String(a["hide"]), false)
	if a.has("shake"):
		host.shake(float(a["shake"]))
	if a.has("flash"):
		host.flash(String(a["flash"]))
	if a.has("vibrate"):
		Settings.vibrate(int(a["vibrate"]))
	if a.has("checkpoint"):
		host.checkpoint()
	if a.has("wait"):
		await host.wait(float(a["wait"]))
		if not alive(g):
			return false
	if a.has("play"):
		var ok: bool = await host.play_scene(String(a["play"]), g)
		if not alive(g):
			return false
		if a.has("then"):
			goto(resolve(a["then"]))
			return false
		if ok:
			# после вставной сцены возвращаемся к интерактиву текущего узла
			host.restore_interactive(node, g)
	if a.has("end"):
		end_with(String(a["end"]))
		return false
	var known := ["goto", "set", "add", "if", "then", "else", "sfx", "say", "show", "hide", "shake", "flash", "vibrate", "checkpoint", "wait", "play", "end"]
	for k in a.keys():
		if not k in known:
			host.logic_error("Неизвестное действие «%s»" % k)
	return alive(g)

## Все узлы/сцены, куда можно попасть из узла за один шаг (для предзагрузки).
func neighbor_scenes(n: Dictionary) -> Array:
	var out := []
	var targets := []
	_collect(n, targets, out)
	for t in targets:
		var nn = ep.nodes().get(t)
		if nn is Dictionary:
			if nn.get("type", "") == "scene" and nn.has("scene"):
				out.append(String(nn["scene"]))
			if nn.has("background"):
				out.append(String(nn["background"]))
	var uniq := []
	for s in out:
		if s != "" and not uniq.has(s):
			uniq.append(s)
	return uniq

func _collect(v, targets: Array, scenes: Array) -> void:
	if v is Dictionary:
		for k in v:
			var x = v[k]
			if (k == "goto" or k == "next" or k == "then" or k == "else") and x is String:
				targets.append(x)
			elif k == "play" and x is String:
				scenes.append(x)
			elif k == "background" and x is String:
				scenes.append(x)
			elif k == "scene" and x is String:
				scenes.append(x)
			else:
				_collect(x, targets, scenes)
	elif v is Array:
		for x in v:
			_collect(x, targets, scenes)
