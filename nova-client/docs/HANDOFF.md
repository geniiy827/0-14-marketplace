# NOVA client — переезд в новый чат (руководство)

Этот файл первым читает новый чат. Дальше: `MEMORY.md` (устройство проекта, всё, что выяснено), `BACKLOG.md`
(задачи и баги), `docs/MCMOD_SCRIPT.md` + `mcmod/README.md` (моды). Большие файлы целиком не читать — grep.

## 0. Правила работы (от пользователя)

- Отвечать **по-русски**, коротко. Пользователь — RCodeStudio, делает клиент **NOVA** для Minecraft PE **0.14.3**
  (armeabi-v7a, пакет `com.mojang.minecraftpn`, телефон Android 15).
- Схема: собрал APK → отправил файлом → он тестирует на телефоне → присылает скрины/видео/`NOVA-crash.txt`.
  Тесты на ПК-стенде (Ninecraft) не нужны.
- **Каждое обновление:** поднять `VERSION` → собрать → синхронизировать в репозиторий → commit + push → отправить APK
  → коротко написать, что добавлено. Держать `BACKLOG.md` и `MEMORY.md` в актуальном состоянии.
- К проекту относиться серьёзно: переносить 1 в 1, проверять, что блок/фича уже есть в 0.14, прежде чем добавлять
  своё (история с блоком слизи, который в 0.14 уже есть).
- **Безопасность:** репозиторий `geniiy827/0-14-marketplace` **ПУБЛИЧНЫЙ**. Никогда не коммитить `keys/`
  (ключ подписи `nova.jks`, `pass.txt`, RSA-ключ аккаунтов `nova_accounts_private.pem`), `*.db`, разбор `re014/`,
  `genisys/`, `raklib/`, `mega/`, `nukkit/`, `a13/` — `tools/sync_repo.sh` их уже исключает.
- Порт стороннего «Ice-Cream Craft» на 64 бита — отказано, не возвращаться.
- `keys/nova.jks` + `pass.txt` были в публичной истории git с alpha8 до коммита 332cc35 — ключ подписи считать
  скомпрометированным (решение о смене ключа / чистке истории — за пользователем).

## 1. Где что лежит

| Что | Где |
|---|---|
| Исходники (рабочая копия) | `/home/claude/work` (пути в скриптах зашиты именно так) |
| Публичный репозиторий | github `geniiy827/0-14-marketplace`, ветка **`nova-client`**, папка `nova-client/` |
| Оригинальная игра | `/home/claude/apk/mcpe.apk` (распакованная — `/home/claude/apk/x`) |
| Текстуры Java 1.19.4 («новые текстуры») | `/home/claude/jt/j/textures` |
| Ключи | `/home/claude/work/keys/` (только в приватном архиве, не в git) |
| Готовые APK / моды | `work/out/NOVA-0.14.3-<VERSION>.apk`, `work/out/<мод>.mcmod` |

### Новый чат = пустой контейнер. Как восстановить

1. Пользователь прикладывает **`NOVA-private.zip`** (ключи + оригинальная игра + текстуры Java; не в git).
2. В новом чате:
```
mkdir -p /home/claude && cd /home/claude
git clone -b nova-client https://github.com/geniiy827/0-14-marketplace geniiy827/0-14-marketplace
cp -r geniiy827/0-14-marketplace/nova-client work
unzip -o <путь к NOVA-private.zip> -d /home/claude      # -> apk/mcpe.apk, jt/, work/keys/
cd work && sh tools/setup.sh                              # apt/pip-зависимости, распаковка apk, команда nova
python3 tools/build_ref.py                                # (по желанию) справочник по libminecraftpe.so -> re014/
sh tools/build.sh                                         # -> out/NOVA-0.14.3-<VERSION>.apk
```
Для push нужен доступ к репозиторию в сессии (инструмент add_repo, `access: push`) или токен в `gh`.

### Карта папок `work/`

- `java/src/com/nova/client/` — Java-слой (`NovaActivity` — наследник MainActivity игры; паки, атласы, UI, сеть,
  `ModBlocks`, `ModItems`, `ModBridge`). `java/.../mcmod/` — язык модов (Lexer, Parser, Ast, Interp, ModRuntime).
- `native/` — `libnova.so` (C, clang без NDK). `nova.c` — главный, подключает `.inc`:
  `mcmod.inc` (мост модов, свои блоки, кадр/тик), `menus.inc` (настройки NOVA, crash handler), `recipes.inc`,
  `items.inc`, `creative.inc`, `hud.inc`, `net.inc`/`modnet.inc` (мультиплеер), `hook.inc` (перехват функций),
  `winter.inc` (погода/снег, alpha39), `v015.inc` (поршни 0.15 — **спит**, версия зафиксирована на 14).
- `tools/` — сборка и генераторы (см. §2), `tools/nova.py` = команда `nova` (поиск по символам/индексу).
- `mcmod/api/*.tsv` — справочник API модов (одна строка = одна функция), `mcmod/prelude.mcs`, `mcmod/examples/` —
  моды-примеры, `mcmod/test/` — прогон модов на ПК.
- `ui_new/` (наш UI JSON) vs `ui_orig/` (оригинал), `assets_new/`, `res_new/` — то, что кладётся в APK.
- `docs/` — `MCMOD_SCRIPT.md` (язык), `MCMOD_API.md` (генерируется), `AI_MOD_GUIDE.md`, `MULTIPLAYER.md`,
  `ACCOUNTS.md`, `ENGINE_PLAN.md` (NOVA Engine — свой движок на Java).
- `re014/` — разбор 0.14.3 (vtables, поля, пакеты, строки) — **только локально**, генерируется `build_ref.py`.
- Неиспользуемое (можно не трогать): `tools/classic_ui.py`, `classic_gui.py`, `gen_title.py`, `ui115_assets.py`,
  `native/nui*.inc`, `tools/nui_preview/`, эмуляторы `tools/emu_*.py` (работают для 0.14).

## 2. Как разрабатывать клиент

Сборка — `sh tools/build.sh` (по шагам расписано в `MEMORY.md` §2): validate UI → манифест → мобы → языки
(`nova_lang.py`, новые строки — в `EXTRA`) → текстуры → `gen_mcmod.py` → `build_java.sh` (Java 8, **без лямбд**)
→ `so_patch.py` (патчи `libminecraftpe.so` + гейты) → `native/build.sh` → APK, zipalign, подпись `keys/nova.jks`.

Выпуск версии:
```
cd /home/claude/work && echo alpha40 > VERSION && sh tools/build.sh && sh tools/check_mods.sh
sh tools/sync_repo.sh
cd /home/claude/geniiy827/0-14-marketplace && git add -A && git commit -q -m "alpha40: ..." \
  && git push -q origin HEAD:nova-client && git fetch -q origin nova-client:refs/remotes/origin/nova-client
cp /home/claude/work/out/NOVA-0.14.3-alpha40.apk <scratchpad>/ && SendUserFile
```
Подпись коммита: `Co-Authored-By: ...` + `Claude-Session: ...` (как велит system-reminder).

Приёмы:
- Функции игры берутся через `dlsym(mc, "_ZN...")` (символы есть), поля — по смещениям (искать в `re014/`,
  `nova` index, `tools/disasm.py`, `tools/xref.py`, `tools/vt.py`).
- Перехват функций игры: **гейты** (`so_patch.py`, `GATE(...)` в C, места ~23, занято 13+) или `hook.inc`
  (перенос пролога во время работы).
- В libnova **нет деления** (нет `__aeabi_*div`) — умножение со сдвигом; нет libc кроме заглушек `native/stubs/`
  (нужное объявлять вручную, как `void free(void*)`).
- Падения: `crash_handler` в `menus.inc` пишет `NOVA-crash.txt` + LOGE; `nova_guard`/`sigsetjmp` вокруг опасных
  вызовов (рецепты).

## 3. Как делать моды (McMod Script)

Мод = папка `mod.json` + `main.mcs` (+ текстуры), сборка упаковывает в `out/<id>.mcmod`; включается в паках клиента,
по сети синхронизируется хостом.
```
{"id": "my_mod", "name": "My Mod", "version": "1.0.0", "author": "RCodeStudio", "main": "main.mcs", "description": "..."}
```
```
on world.join(e) { ui.tip("Привет") }
on tick(e) { ... }                      // 20 раз в секунду
every 5 seconds { ... }
on chat(e) { if e.text == "/cmd" { e.cancel(); chat.send("ok") } }
block my_block { name: "...", texture: "..." }      // объявления block/item/recipe/food
hook "Mob::hurt" (self, src, dmg) { original() }    // перехват любой функции игры
```
- Строки: методы `sub`, `split`, `startsWith`, `find`, `replace`, `trim`; `"{выражение}"` внутри строк;
  `num(s)` → `null`, если не число. Сохранение — `data.save/load`, хост — `host.isMe()`.
- Новая функция API: одна строка в `mcmod/api/<группа>.tsv`
  (`имя<TAB>аргументы<TAB>возвращает<TAB>native:OP|java:имя|script<TAB>статус<TAB>описание`) → `gen_mcmod.py`
  создаёт `OP_*` в `native/mcmod_ops.h` → реализация в `mm_op` (`native/mcmod.inc`) или своём `.inc`.
- Проверка всех модов без телефона: `sh tools/check_mods.sh` (должно быть `errors 0`).
- Полный язык — `docs/MCMOD_SCRIPT.md`, все функции — `docs/MCMOD_API.md`, пример большой игры —
  `mcmod/examples/adventure_craft`.

## 4. Где остановились (alpha57 выпущена)

alpha57: порталы — игра больше не сбивает наш трафарет во время прохода.


alpha56: порталы — обход «проявления» чанков в проходе, счётчики слоёв в /portal info.


alpha55: порядок проходов портала, каттеры, режим отладки. Ждём скрины.


alpha54: сквозные порталы (portals.inc, docs/PORTALS.md), тест-мод portal_test. Не проверено на телефоне — ждём /portal info и скрины.


alpha53: падающие блоки под островами; обсуждаем бесшовные порталы.


alpha52: краш булыжного/алмазного мира, возврат к своему порталу.


alpha51: булыжный и алмазный миры в wood_world; ищем причину «не отвечает» при переходе.


alpha50: исправлено «неизвестный блок planks» в dimension.


alpha49: краш поджога исправлен, измерения — API модов (`dimension`), пример wood_world. Ждём проверки.


alpha48: деревянное измерение (номер 2) с порталом из досок — native/dims.inc. Ждём проверки на телефоне.


alpha47: багфикс — модель игрока при повторном входе, синхронизация модов с другом, краш экрана мира; исследованы измерения (план в MEMORY.md).


alpha46: книга — кнопка/вкладки крупнее, поиск без закрытия инвентаря. Дальше: возможно порт Iron Chests (GPL-3.0, открыт).


alpha45: книга рецептов переделана в стиле JEI под цвета клиента, кнопка в левом верхнем углу, есть в творческом, тап/звуки/защита от вылетов. Ждём скрины и NOVA-crash.txt, если вылеты остались.


alpha44: книга рецептов — `native/recipebook.inc`, контрол `nova_rb` в `ui_new/inventory_screen.json`, картинки
`tools/gen_recipebook.py` -> `assets_new/images/gui/nova/rb/`. alpha43: crop картинок, упаковка предметов с action.

### alpha42

alpha42: упаковка простых блоков по данным (ModBlocks.packKey/packSlot, mcmod.inc mblock_sub/mb_of, ключи
"pack:/pdn:/data:" в файле номеров), анимации блоков (FlipbookTexture), предметы с кадрами (item.frame),
частицы/звуки, оверлей кожаной брони. Подробно — BACKLOG.

### alpha41

alpha41: температура SimpleDifficulty в Adventure Craft 0.4.0 (см. BACKLOG). Ассеты делает
`tools/port_sd_temp.py <SimpleDifficulty> <Java textures> mcmod/examples/adventure_craft` (исходники SD:
`git clone https://github.com/Charles445/SimpleDifficulty`). Новое API — `native/env.inc`, `ui.image` в `hud.inc`.

### alpha40

alpha40: void-функции модов (weather.*, ui.hud, __recipe) больше не дают «нужно находиться в мире» (мост в
`mcmod.inc` возвращал null); снег ставится пачкой раз в секунду. Ждём отзыв по снегу и FPS.

### alpha39

Направление: гибрид 0.15→1.1.5 **отменён**, продолжаем NOVA на 0.14.
Сделано в рабочей копии (закоммичено как WIP):
- Убран переключатель «0.15» из настроек NOVA, в `NovaActivity` версия зафиксирована `playVersion = 14`.
- Новый API `weather.*` (`mcmod/api/weather.tsv`, `native/winter.inc`):
  `weather.rain(уровень)` — вечный дождь; `weather.freeze(1)` — все биомы холодные (снег вместо дождя, вода
  замерзает); `weather.snow(слоёв_в_минуту, предел=0)` — на хосте снег нарастает вокруг игроков слой за слоем,
  8 слоёв → блок снега, дальше растёт на нём, без предела (0). Не засыпает клетку, где стоит игрок.
- Мод `mcmod/examples/infinity_winter` (Infinity Winter): всё это при входе в мир, команды `/winter`,
  `/winter speed N`, `/winter limit N` (меняет хост), сохранение в `data`. `check_mods` — 0 ошибок.

alpha39 выпущена (APK + `out/infinity_winter.mcmod` отправлены). Мод включать у всех; сугробы растит хост.
Раздел `weather.*` в `docs/MCMOD_SCRIPT.md` дописан. В чистом контейнере `build.sh` сам создаёт `out/`.
Ждём отзыв: модель игрока после перезахода в мир (фикс alpha38 — проверка `anim_valid`), поршни (теперь спят).
