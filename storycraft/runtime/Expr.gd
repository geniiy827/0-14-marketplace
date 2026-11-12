class_name Expr
extends RefCounted
## Выражения серии (синтаксис Godot Expression): `tries < 2`, `adv.trust >= 3 and not saw_zina`,
## `'дверь' if ending == 'door' else 'прутья'`. Переменные приключения — через `adv.<имя>`.
## Неизвестные переменные считаются равными 0 (false), чтобы серия не падала.

const KEYWORDS := ["and", "or", "not", "if", "else", "true", "false", "null", "in", "adv", "PI", "TAU", "INF", "NAN", "self"]

static var _re_ident: RegEx
static var _re_adv: RegEx
static var _re_str: RegEx

static func _init_re() -> void:
	if _re_ident != null:
		return
	_re_ident = RegEx.create_from_string("(?<![\\w\\.])([A-Za-z_][A-Za-z0-9_]*)(?!\\s*\\()")
	_re_adv = RegEx.create_from_string("adv\\.([A-Za-z_][A-Za-z0-9_]*)")
	_re_str = RegEx.create_from_string("'[^']*'|\"[^\"]*\"")

## Вычислить выражение. vars — переменные серии (+ встроенные), adv — переменные приключения.
static func eval(src, vars: Dictionary, adv: Dictionary, def = null):
	if src is bool or src is int or src is float:
		return src
	var s := String(src).strip_edges()
	if s == "":
		return def
	_init_re()
	var names := PackedStringArray()
	var values := []
	# строки не трогаем при поиске имён
	var bare := _re_str.sub(s, "''", true)
	var adv_copy := adv.duplicate()
	for m in _re_adv.search_all(bare):
		var k := m.get_string(1)
		if not adv_copy.has(k):
			adv_copy[k] = 0
	names.append("adv")
	values.append(adv_copy)
	for m in _re_ident.search_all(bare):
		var n := m.get_string(1)
		if n in KEYWORDS or names.has(n):
			continue
		# функции Expression (min, max, str, …) вызываются со скобками — их regex пропускает
		names.append(n)
		values.append(vars.get(n, 0))
	var e := Expression.new()
	if e.parse(s, names) != OK:
		push_warning("Ошибка в выражении «%s»: %s" % [s, e.get_error_text()])
		return def
	var r = e.execute(values, null, false)
	if e.has_execute_failed():
		push_warning("Не удалось вычислить «%s»: %s" % [s, e.get_error_text()])
		return def
	return r

static func truthy(v) -> bool:
	if v == null:
		return false
	if v is bool:
		return v
	if v is int or v is float:
		return v != 0
	if v is String:
		return v != ""
	return true

static func test(src, vars: Dictionary, adv: Dictionary) -> bool:
	if src == null:
		return true
	return truthy(eval(src, vars, adv, false))

## Подставить {выражение} в текст.
static func interpolate(text: String, vars: Dictionary, adv: Dictionary) -> String:
	if not text.contains("{"):
		return text
	var out := ""
	var i := 0
	while i < text.length():
		var c := text[i]
		if c == "{":
			var depth := 1
			var j := i + 1
			var in_str := ""
			while j < text.length() and depth > 0:
				var ch := text[j]
				if in_str != "":
					if ch == in_str:
						in_str = ""
				elif ch == "'" or ch == "\"":
					in_str = ch
				elif ch == "{":
					depth += 1
				elif ch == "}":
					depth -= 1
				j += 1
			if depth == 0:
				var v = eval(text.substr(i + 1, j - i - 2), vars, adv, "")
				out += _fmt(v)
				i = j
				continue
		out += c
		i += 1
	return out

static func _fmt(v) -> String:
	if v == null:
		return ""
	if v is float and is_equal_approx(v, round(v)):
		return str(int(v))
	if v is bool:
		return "да" if v else "нет"
	return str(v)
