# re014 — разложенный libminecraftpe.so 0.14.3 (armeabi-v7a)

Собрано `tools/re/build_ref.py` + `gen_packets.py` из APK на этой машине (~9 с). Пересборка: `nova ref-build`.
Само дерево никуда не коммитится (это код Mojang). В репо лежат только генераторы и этот файл.

## С чего начинать
| Нужно | Где |
|---|---|
| Все методы класса, с аннотациями | `classes/<Class>.s` (вложенные: `A.B.s`, шаблоны `X_T.s`, коллизии `X~1.s`) |
| Найти класс по теме | `topics.md`, `classes.tsv` (класс, методов, байт, файл) |
| Поля объекта: какое смещение кто читает/пишет | `fields/<Class>.txt` (`this+0x15a8  r: getAmbientBrightness  w: LevelRenderer`) |
| Виртуальные методы, номер слота | `vtables/<Class>.txt` (слот = индекс от vt+8, как в patch_slot) |
| Пакеты 0.14 (id, порядок полей write/read) | `packets.md` (wire = getId + 0x8e) |
| Кто кого вызывает | `calls.tsv` (caller → callee), в .s заголовок `called by:` |
| Строки и где они используются | `strings.tsv` (с xref), `strings_all.tsv` (вся .rodata) |
| Все символы | `symbols.tsv` (addr, size, func/obj, demangled, mangled) |
| Таблицы блоков (эмуляция инициализации) | `data/blocks.json`, `data/LightTables.txt`, `data/init2.bin` (vaddr − 0x6eb000) |
| Уже разобранные функции (свет, AO, небо, туман) | `notes/*.s` |
| Ассеты (json UI, шейдеры, текстуры) | `/home/claude/apk/x/assets` |

## Как читать .s
```
;;; LevelRenderer::getAmbientBrightness() const
;;; @0x446538 size 140  [mangled]
;;; vtable: Class[slot]           <- если метод виртуальный
;;; called by: A::f; B::g         <- кто вызывает
  446546  vldr    s15, [pc, #0x70]   ; =55 f          <- float-литерал
  446564  vldr    s12, [r4, #8]      ; this+0x15a8    <- поле объекта (this отслеживается через mov/add)
  44661e  ldr     r2, [pc, #..]      ; =0x3e800000 (..)  0.25f   <- int-литерал, + как float
  44662a  add     r2, pc             ; -> 0x6ae008 "terrain_alpha"   <- строка / GOT-символ / объект
  446542  bl      #0x2e31ac          ; MinecraftClient::getLocalPlayer()
  4465xx  bpl     #0x446588          ; +0x50          <- переход внутри функции
```
`movw/movt/mov.w` с большими числами тоже подписаны как float, если похоже.

## Быстрые команды
- `grep -n "^;;; Dimension::" classes/Dimension.s` — список методов
- `awk '/^;;; LevelRenderer::_setupFog/,/^;;; =/' classes/LevelRenderer.s` — один метод
- `grep -rn "this+0x15a8" classes/` — все обращения к полю
- `grep -P "\tRenderChunk::_initLayers" calls.tsv` — что вызывает функция
- `nova d|callers|refs|vt|str` — старый интерактивный инструмент (тот же индекс)

## Ограничения
- Поля отслеживаются линейно (без графа переходов) и только для `this` в методах своего класса;
  запись чужим классом через указатель не видна → проверять `grep "#0x<off>\]"`.
- u32 в packets.md может быть float (WriteBits 32). Типы смотреть в read()/handle() соответствующего класса.
- Хвосты функций после `pop {.., pc}` часто литерал-пулы, дизассемблированные как мусор.

## Найдено при сборке
- `getAmbientBrightness()` = `min(f + (v < 55 ? 0.35 : 3.5), 10) · 0.1`, где v = `[getLocalPlayer() + 0xc]` (поле ещё
  не опознано), f = `LevelRenderer+0x15a8`. Через `this` его пишет только конструктор (0) — нужно проверить запись
  через чужой указатель / memcpy, прежде чем убирать хак. Результат умножает цвет неба в `renderSky`
  и задаёт fade тумана в `updateFarChunksDistance` (`0.5 + 0.5·amb`).
- `LevelRenderer::tick`: `+0x15a0` — u64-счётчик тиков; `+0x1c90` сглаживает `getBrightness(игрок)` с учётом
  дальности обзора (`(1 − r/192)`, шаг 0.1).
