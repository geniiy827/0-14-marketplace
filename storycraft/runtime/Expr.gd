class_name Expr
extends RefCounted
## Выражения серии (синтаксис Godot Expression): `tries < 2`, `adv.trust >= 3 and not saw_zina`.
## Тернарный `A if C else B` встроенный Expression НЕ поддерживает (молча отдаёт A) — его разбираем сами
## до Expression, с вложенностью и скобками. Переменные приключения — через `adv.<имя>`.
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
	var s := _strip_parens(String(src).strip_edges())
	if s == "":
		return def
	var tern := _split_ternary(s)
	if not tern.is_empty():
		var branch: String = tern[0] if truthy(eval(tern[1], vars, adv, false)) else tern[2]
		return eval(branch, vars, adv, def)
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

## «A if C else B» на верхнем уровне (вне строк и скобок) → [A, C, B]; иначе [].
## Вложенные if до парного else учитываются счётчиком, «B» может сам быть тернарным.
static func _split_ternary(s: String) -> Array:
	var depth := 0
	var q := ""
	var if_pos := -1
	var nest := 0
	var i := 0
	var n := s.length()
	while i < n:
		var ch := s[i]
		if q != "":
			if ch == q:
				q = ""
		elif ch == "'" or ch == "\"":
			q = ch
		elif ch == "(" or ch == "[" or ch == "{":
			depth += 1
		elif ch == ")" or ch == "]" or ch == "}":
			depth -= 1
		elif depth == 0 and ch == " ":
			if s.substr(i, 4) == " if ":
				if if_pos < 0:
					if_pos = i
				else:
					nest += 1
			elif if_pos >= 0 and s.substr(i, 6) == " else ":
				if nest == 0:
					return [s.substr(0, if_pos).strip_edges(), s.substr(if_pos + 4, i - if_pos - 4).strip_edges(), s.substr(i + 6).strip_edges()]
				nest -= 1
		i += 1
	return []

## Снять внешние скобки, если они парные: "('x' if a else 'y')" → "'x' if a else 'y'".
static func _strip_parens(s: String) -> String:
	while s.length() >= 2 and s[0] == "(" and s[s.length() - 1] == ")":
		var depth := 0
		var q := ""
		var closes_at_end := true
		for i in s.length():
			var ch := s[i]
			if q != "":
				if ch == q:
					q = ""
				continue
			if ch == "'" or ch == "\"":
				q = ch
			elif ch == "(":
				depth += 1
			elif ch == ")":
				depth -= 1
				if depth == 0 and i < s.length() - 1:
					closes_at_end = false   # "(a) and (b)" — скобки не внешние
					break
		if not closes_at_end:
			break
		s = s.substr(1, s.length() - 2).strip_edges()
	return s

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
