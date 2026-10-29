# McMod Script — справочник (источник правды)

Каждая функция = одна строка в `api/<группа>.tsv`. Править точечно (grep + одна строка), файлы целиком не читать.
`tools/gen_mcmod.py` собирает из них: Java-таблицу (ApiTable.java), номера нативных операций
(native/mcmod_ops.h) и документацию `docs/MCMOD_API.md`.

Колонки (через TAB): `имя  аргументы  возвращает  реализация  статус  описание`
- аргументы: `x:num, y:num, block:block, data:num=0` (тип: num str bool any block item list map fn; `=` значение по умолчанию)
- возвращает: `-` ничего, `num` `str` `bool` `vec` (таблица {x,y,z}) `list` `map` `any`
- реализация:
  - `native:OP` или `native:OP#i` — операция в libnova (native/mcmod.inc), `#i` = какой элемент ответа вернуть
  - `java:имя` — метод в java/.../mcmod/ApiJava.java
  - `script` — написана на самом McMod Script в `prelude.mcs`
- статус: `ok` работает, `wip` в работе / не проверено, `no` пока нет
События: `api/events.tsv` — `имя  поля  отмена(yes/no)  статус  описание`.
