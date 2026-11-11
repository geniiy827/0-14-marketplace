# NOVA client — память проекта 

Этот файл — главное. Текущие задачи и что на чём остановилось — BACKLOG.md. Новый чат: прочитай оба, потом `docs/MCMOD_SCRIPT.md` и `mcmod/README.md`.
Остальное читай точечно (grep), чтобы не тратить лимит.

## 0. Кто и как

- Пользователь русскоязычный, отвечать по-русски, коротко и по делу. Делает клиент **NOVA** для Minecraft PE **0.14.3**
  (armeabi-v7a, пакет `com.mojang.minecraftpn`, его телефон — Android 15). Студия: **RCodeStudio**.
- Просит экономить лимит: не читать большие файлы целиком, не гонять лишние тесты. **Тесты на ПК-стенде (Ninecraft)
  не нужны** — пользователь сказал, что они не гарантируют результат (стенд x86, а телефон ARM). Схема работы:
  собрал APK → отправил → он тестирует на телефоне → присылает скрины/видео/`NOVA-crash.txt`.
- Коммит/пуш только по просьбе. Ключ подписи не выкладывать в публичный репозиторий. Раньше был репозиторий
  `geniiy827/0-14-marketplace` (ветка `nova-client`), этот архив новее него.
- Версии: beta1..15 → alpha1..7. Версия сборки — в файле `VERSION` (`nova build` → `out/NOVA-0.14.3-<VERSION>.apk`).

## 1. Как восстановить рабочее окружение

Архив содержит: `work/` (исходники и инструменты), `apk/mcpe.apk` (оригинальная игра), `jt/` (текстуры Java 1.19.4
для «новых текстур»), `archive/` (старые варианты кода).

```
mkdir -p /home/claude && cd /home/claude && unzip NOVA-memory.zip          # -> work/ apk/ jt/ archive/
mkdir -p apk/x && cd apk/x && unzip -q ../mcpe.apk && cd /home/claude/work
pip install --break-system-packages capstone keystone-engine pyelftools pillow androguard
# нужны: python3, javac (JDK 17+), clang-18 + lld (target armv7a-linux-androideabi21), zipalign, apksigner,
# dalvik-exchange (dx), android.jar API 23 в /usr/lib/android-sdk/platforms/android-23/android.jar
# (apt: android-sdk, android-sdk-platform-23, dalvik-exchange, zipalign, apksigner, clang-18, lld-18)
sh tools/setup.sh      # зависимости + команда nova
nova build             # -> out/NOVA-0.14.3-<VERSION>.apk
```
Пути зашиты как `/home/claude/work`, `/home/claude/apk/x`, `/home/claude/jt/j/textures` — распаковывать именно туда.
Подпись: `keys/nova.jks`, пароль в `keys/pass.txt` (лежит рядом с ключом, не в репозитории), alias `nova` (без него обновление не встанет поверх старой версии).

## 2. Сборка (tools/build.sh, по шагам)

1. `validate.py` — проверка JSON UI (`ui_new/` против `ui_orig/`).
2. `make_manifest.py` — бинарный AndroidManifest (`AndroidManifest.nova.xml`).
3. `gen_entities.py` — мобы/модели (Mobs.json и т.п.) в `assets_new/`.
4. `nova_lang.py assets_new/loc` — строки NOVA во все .lang (14 языков; ключи в списке `K`, переводы в `T`, доп. `EXTRA`).
5. `gen_intro_mesh.py` (3D-буквы NOVA для интро из `intro/title.bbmodel`), `gen_intro_sfx.py` (звуки интро).
6. `gen_textures.py` — режет атлас блоков/предметов на отдельные PNG (`assets_new/textures/...`) + layout.json;
   «новые текстуры» из `jt/`. (`WAVE_STRIP=False` — остаток от удалённых шейдеров.)
7. `gen_mcmod.py` — справочник McMod Script → `java/.../mcmod/ApiTable.java`, `ApiOps.java`,
   `native/mcmod_ops.h`, `docs/MCMOD_API.md`, `assets_new/nova/mcmod/prelude.mcs`.
8. `build_java.sh` — javac (`--release 8`, без лямбд!) → `classes2.dex` (dx, min-sdk 17).
9. `so_patch.py` — патчи `libminecraftpe.so` + **гейты** (inline-хуки, см. §4) → `libminecraftpe.patched.so`, `native/gates.h`.
10. `native/build.sh` — `libnova.so` (C, clang без NDK, stub libc в `native/stubs/`).
11. `build_apk.py` (png/ogg кладутся без сжатия), zipalign, apksigner.

## 3. Архитектура клиента

- **Java-слой** `java/src/com/nova/client/` — наследник MainActivity игры (`NovaActivity`), все чтения ассетов игры
  идут через него (`getFileDataBytes`, `getImageData`) — так подменяются файлы паков, атласы, .lang, terrain.meta.
  - `PackManager` — хранилище паков/модов: `files/nova/packs/<uuid>/` (files/, manifest.json или mod.json,
    pack.mcres, nova_meta.json), `files/nova/state.json` (включённые). `.mcres` = набор ресурсов, `.mcmod` = мод.
  - `AtlasBuilder` — собирает атлас блоков/предметов из отдельных PNG (паки могут заменять одну текстуру).
    Умеет добавлять полосу под атласом для текстур блоков из модов (512x256 → 512x512).
  - `EntityRegistry`, `AnimationCompiler` — свои модели/анимации мобов. `McKit`, `NovaUi` — UI-помощники.
  - `IntroView` — интро (логотип RC + 3D-буквы NOVA, GLES2 поверх игры, звуки SoundPool).
  - `LanSync` — обмен включёнными паками по LAN. `WorldWriter`, `CreateWorldActivity` — создание мира.
  - `ModBridge` (+ пакет `mcmod/`) — моды, см. §5. `ModBlocks` — свои блоки модов.
- **Нативный слой** `native/nova.c` + `menus.inc` + `mcmod.inc` → `libnova.so`. Подгружается вместе с игрой.
  - хуки через подмену слотов vtable (`patch_slot`) и через гейты (§4);
  - `menus.inc`: меню NOVA в настройках (кукла, F3, FPS, границы чанков, автобег, новые текстуры), экран
    «Хранилище» (вкладки: Ресурс-паки / Моды / Импорт; карточки паков), создание мира, тосты, фикс ширины
    шрифта (латиница в смешанных строках), анизотропная фильтрация (GOT glTexParameteri);
  - `h_client_update` (слот 18 vtable MinecraftClient) — каждый кадр; оттуда же `mcmod_frame`.
  - Полезные смещения 0.14.3 ARM: client+0x120 = LocalPlayer, client+0x130 = GuiData, client+0x7c = Font,
    player+8 = pos (y глаз, ноги = y-1.62), +56 pitch, +60 yaw, +0x16c = BlockSource (region),
    player+0xda0 = Inventory, Level+0x48 = LevelData (время +0x34).
- UI: `ui_new/*.json` (стартовый экран: кнопка «Хранилище» = hyperlink `nova://packs` → `NovaActivity.launchUri`).
- Аккаунты (alpha15, docs/ACCOUNTS.md) и игра с друзьями (alpha16, docs/MULTIPLAYER.md): `AccountClient`, `NovaNet`
  (туннель UDP, P2P + relay), `Social` (экраны друзей/сети/приглашений, баннер), `native/net.inc` (настоящие кнопки
  в меню NOVA = TButton поверх OptionButton, кнопка «Сеть» в создании мира, joinMultiplayer, хук RakNetInstance::host,
  перезагрузка JSON UI). Плагин `server/NovaAccounts` (Genisys 0.14, PHP 7.0), сборка `sh tools/build_plugin.sh`
  (кладёт keys/nova_accounts_private.pem в phar; ключ НИКОГДА не в репо). Тест: server/test/pm_server.php + N.java.
- Правила дизайна от пользователя: кнопки — настоящие кнопки (не переключатели), иконки — из ассетов игры,
  меню строит сама игра. Кнопка входа = текст «Войти», после входа — nova_player.png.
- Строки NOVA: `tools/nova_lang.py`.

## 4. Гейты (inline-хуки) — tools/so_patch.py

Список `GATES` (имя, адрес функции, символ). Первые ≥4 байта функции переносятся в «пещеру» после .text,
в начало функции ставится `b.w` на пещеру: если libnova записала адрес хука в SLOT — прыжок в хук, иначе оригинал.
В C: `GATE(ИМЯ, hook, orig_var)` (макрос в nova.c, константы из gates.h). Перенесённые инструкции не должны
зависеть от pc (кроме ldr literal) — so_patch проверяет. **Места на ~23 гейта** (`new_end < 0x6ea000`), занято 13.
Текущие: modelpart_load/render, opt_* (8 шт., настройки NOVA), nametags, init_creative, tess_in_world.

## 5. Моды: McMod Script (главная текущая работа)

Пользовательская документация: `docs/MCMOD_SCRIPT.md` (язык, блоки), справочник: `docs/MCMOD_API.md` (генерируется).

- **Язык** (свой, решено пользователем; JSON и JS отвергнуты): `java/src/com/nova/client/mcmod/`
  - `Lexer` (переносы строк = конец команды, кроме внутри () и []; строки с `{выражение}` внутри, вложенные кавычки ок),
  - `Parser` (on событие(e) {}, every N ticks/seconds {}, fn, override ns.f() { original() }, объявления
    `block/item/recipe/food имя { ключ: значение }` — поля через запятую или с новой строки),
  - `Ast` (узлы сами себя вычисляют), `Interp` (правила значений, методы строк/списков/таблиц, лимит шагов 300000
    против зависаний), `ModRuntime` (привязка API по таблице, события, ошибки в чат с файлом:строкой, после 6 ошибок
    мод выключается до след. входа в мир), `Json` (data.save/load).
  - Тест на ПК без игры: `mcmod/test/Test.java` (javac всех `mcmod/*.java` + Test, запуск с prelude и .mcs).
- **Справочник (экономит лимит):** `mcmod/api/<группа>.tsv`, одна строка = одна функция:
  `имя  аргументы  возвращает  реализация  статус  описание` (TAB). Реализация: `native:OP[#i][:k1,k2|:subop]`,
  `nativestr:OP`, `java:имя` (в `ModRuntime.java()`), `script` (функция `__группа_имя` в `mcmod/prelude.mcs`).
  События — `mcmod/api/events.tsv`. После правки → `python3 -I tools/gen_mcmod.py` (build.sh делает сам).
  Правило: искать нужную строку grep'ом, править одну строку, файлы целиком не читать.
- **Сейчас в API мало (по решению пользователя «пишем с нуля, только нужное»):** chat/ui, позиция игрока,
  world.block/setBlock/fill/time, item.id, data.*, math.*, базовые (print, str, num, random...).
  События: world.join, world.leave, tick (тики считаются по часам в `mcmod_frame`, без хуков игры).
- **Мост:** `ModBridge.java` ↔ `native/mcmod.inc`: Java→C `nativeApi(op, double[], String)` (числа по одному на
  параметр, строки через \x1f), C→Java `ModBridge.event(int ev, String, double[])` (JNI с потока игры).
- **alpha14: моды без API.** native/hook_reloc.h + hook.inc: перехват ЛЮБОЙ функции во время работы (перенос пролога
  Thumb-2, проверен эмулятором на 6000 функций; мелкие/непереносимые — через слоты vtable и bl-вызовы), вызов по
  имени, память. Таблица `assets/nova/symbols.tsv` (tools/gen_symbols.py: имя, mangled, адрес, размер, типы
  аргументов s/p/f/d/q/S/c/v, float-возврат, режим i/c, места вызова). Java: mcmod/Hooks.java (+Machine.java,
  ModBridge.Mach/onHook). Скрипт: `hook "Класс::метод" [float] (self, ...) { original() }`, mc.*, mem.*, консоль
  `> код` в чате, живые моды из games/com.mojang/nova_mods. Тест на ПК: mcmod/test (FAKE Machine).
- Отказ от BlockLauncher-порта (удалён в alpha14): был перенесён весь ModPE API (176 функций, 19 событий; исходник BlockLauncher:
  github.com/zhuowei/MCPELauncher коммит `fb1f78a` = поддержка 0.14.3, файлы jni/modscript*.cpp).
  На телефоне вылетало: открыть дверь, ударить свинью, команда /top. Причина НЕ найдена (отчётов не было).
  Пользователь решил писать с нуля только нужное. Оттуда можно брать смещения/символы, но добавлять по одному
  и проверять.

## 6. Свои блоки модов (текущая задача — ВЫЛЕТ при входе в мир)

Синтаксис — в `docs/MCMOD_SCRIPT.md` («Свои блоки»). Как устроено:
- `ModBridge.load()` (в `NovaActivity.onCreate` до `super.onCreate`) → `ModBlocks.prepare()`: номера 210..242
  (`files/nova/blockids.json`), текстуры (имя из layout.json или PNG: мод → паки → игра), полоса 512x(256..512) атласа,
  `.bbmodel`/`.geo.json`, строки `tile.<мод>_<имя>.name`. Затем `nativeDefineBlocks(double[], names)`.
- `getFileDataBytes`: terrain.meta с высотой 512, дописывает имена в `loc/*.lang`. `AtlasBuilder.setBlockExtras`.
- Натив `mcmod.inc`, регистрация в гейте `Item::initCreativeItems` (вызывается из `Item::initItems`, ПОСЛЕ initBlocks):
  `new(0x98)`, `Block::Block(name,id,material)`, копия vtable с getTexture (76,77,78) и getCarriedTexture (7),
  +48 слой (3 непрозр., 8 как стекло), +52 форма 0, `setSolid` (сам ставит mLightBlock 0/15), mLightEmission,
  `mBlocks[id]`, vtable[94] `Block::init()`, `setCategory` (+0x78: 1 строительство, 2 декор), BlockItem(64 б) в mItems, addCreativeItem.
  Модельные блоки: гейт `tessellateInWorld` полностью рисует сам (свет: getLightColor(sret)→toUV→_tex1, потом vertexUV).
- ВАЖНО (найдено в alpha7): `Block::initBlocks` в конце заполняет ВСЕ пустые id 1..255 безымянными заглушками →
  раньше наши блоки пропускались («id taken»). Теперь занятым считается только id, у которого есть `Item::mItems[id]`.
- Ошибки alpha5/6: offsets из BlockLauncher другой версии (слой писался в форму, 0x7f — во флаги +56, lightBlock 255).
- Творческий инвентарь (ПК-вид): `CraftingContainerManagerModel::init` берёт тот же `Item::mCreativeList` и раскладывает
  по `Block+0x78` / `Item+0x24` категории — отдельно ничего делать не надо.
- Вылет при входе в мир всё ещё не объяснён (в alpha6 блоки вообще не регистрировались!) → нужен `NOVA-crash.txt`
  (`nova crash файл`). С alpha7 отчёт ещё и копируется в буфер обмена.

- alpha8: вылет при входе был в `world.setBlock` — `BlockSource::setBlockAndData(int,int,int,BlockID,uchar,int)` берёт
  BlockID ПО УКАЗАТЕЛЮ (нетривиальный тип; так же у всех функций с BlockID/FullBlock по значению). fault 0xd2 = id 210.
- Главный экран: метка версии = ключ `nova.version` (из файла `VERSION`, nova_lang.py); `title_image` скрыт, пока
  IntroView не закончится (`nativeIntroDone` → custom-контрол `nova_title_gate` прячет соседа, флаг видимости UIControl+0x30 бит 2;
  parent weak_ptr +0x38, children vector<shared_ptr> +0x40, имя +0xc).

- alpha9: слой 4 (как листва fancy) для модельных/прозрачных: слой 8 (стекло) сортируется по граням и ломал модели
  (пропадающие куски, «шипы»). Полоса атласа начинается с y=272 (flowing_water/lava до 272 — затирали рубиновый блок).
  Кубы моделей: 36 чисел (бокс 6, uv 24, поворот xyz°, центр xyz), порядок как в Blockbench (three.js 'ZYX': X, потом Y, потом Z).
  3D в инвентаре/руке: гейты `renderGuiBlock` (как ветка наковальни: begin, кубы в 0..1, draw(Tessellator::instance, bt+0x2c0, tex))
  и `appendTessellatedBlock` (bt[1]=1, bt+0x28=1, addOffset(-.5), кубы, addOffset(.5)). Свет: проверять ночью.
  Интро: introDone() вызывается, когда плоский логотип встал на место, а сам IntroView уходит через 0.15 с.

## 6a. Инструменты (экономия лимита)
`nova` (= `python3 tools/nova.py`, индекс .so кешируется в `.cache/`, ~8 с один раз):
`nova s REGEX` символы · `nova d СИМВОЛ|АДРЕС [N]` дизасм с именами вызовов/GOT/строк · `nova dg СИМВОЛ RE` только нужные
строки · `nova callers X` · `nova refs X` (в т.ч. через GOT) · `nova vt Block` слоты vtable (индекс как patch_slot) ·
`nova at АДРЕС` · `nova str ТЕКСТ` · `nova crash [файл]` расшифровка отчёта · `nova build` тихая сборка (версия в `VERSION`, моды из `mcmod/examples/*` пакуются в `out/*.mcmod`).
Репозиторий: `tools/sync_repo.sh` копирует исходники в `geniiy827/0-14-marketplace/nova-client` (ветка nova-client).

## 7. Что сделано раньше (кратко, работает у пользователя)

- Хранилище паков `.mcres` (импорт, включение, порядок, удаление при выходе, иконки, LAN-обмен), смена экрана
  на «Хранилище» с тремя вкладками.
- Новые текстуры 1.19.4 (переключатель, перезапуск), отдельные PNG-плитки вместо атласа, анизотропия.
- Свои модели/анимации мобов, кукла игрока, F3, счётчик FPS, границы чанков, автобег.
- Интро RC → 3D NOVA (5–6 с), заменяет экраны загрузки/перезапуска; звуки.
- alpha3: фикс разреженных латинских букв в смешанных строках (кэш ширин шрифта), скрытие кодов цвета §.
- Шейдеры (alpha4) — **полностью вырезаны** по просьбе пользователя. V-sync на Android не отключается (объяснено).
- NoCubes — отложено (сложно: нужна своя сетка чанков + коллизии).
- ПК-версия (Ninecraft, x86) — заброшена пользователем (`/home/claude/ninecraft`, `/home/claude/pc` не в архиве).

## 8. Идеи/планы пользователя по модам

- Блоки: классика (1 бокс, текстура на сторону) и модульные (модели Blockbench, развёртка как у мобов);
  коллизия пока у всех полный блок. Дальше — предметы, рецепты, еда, свои действия/события (добавлять по одному).
- Язык должен быть удобен и человеку, и ИИ; справочник функций держать построчно (см. §5).

- ВАЖНО (alpha19 сломалась): в native нельзя `__thread` (нужен __emutls_get_address, которого нет в процессе игры -> libnova не грузится, клиент выглядит как старый). build.sh теперь проверяет. Деление по модулю в горячем коде — через умножение (st_mod).

## alpha26-27 (ноябрь 2026)
- HUD-картинки модов грузятся игрой через getImageData (не getFileDataBytes), путь может быть с префиксом -> indexOf("nova/mod/").
- ui.hud(..., shake): дрожание как у голода (hud.inc, без деления).
- creative.inc: страницы вкладок творческого. mFilteredItems[4] (vector<ItemInstance const*>) подменяются, панели
  this+0x164+8i -> Touch::InventoryPane::refreshItems; иконки - хук getItemFromType(sret); таблица панелей this+0x200
  (12 байт, pane +4), выбранная вкладка this+0x268, кнопки вкладок this+0x30. Стрелки - TButton + gui/newgui/arrowLeft|Right.png,
  касания через hc_touch (net.inc). При D1/ctor экрана - вернуть векторы игры.
- items.inc: kind 1..5 инструменты (PickaxeItem/HatchetItem/ShovelItem ctor 0x158, WeaponItem собирается вручную 0x48,
  HoeItem 0x54 Tier по значению), 6..9 броня (ArmorItem 0x50, ArmorMaterial {mul, def x4, ench}, renderIndex 5+k).
  Tier = {level, uses, float speed, damage, ench}, у DiggerItem указатель на Tier в +0x44.
  Броня на модели: хук HumanoidMobRenderer::prepareArmor подменяет this+0xd0 (массив TexturePtr по 16 байт).
  Уровень кирки для блоков модов: хук PickaxeItem::canDestroySpecial.
- Свободные id предметов 0.14.3: 432-437, 439-456, 467-497, 499-511 (498 = камера, items.json). ModItems.POOL.
- mcmod.inc: drop/xp через vtable 48/49/63, nativeBlockExtra; руды - structs.inc st_ores (хук StructureFeature::postProcess,
  Ад = биом 8, netherrack 87).
- Пример simple_ores (LGPL, SimpleOres2): 15 блоков, 50 предметов.

## alpha28fixver
- Крафты модов пропадали: клиент чистит книгу рецептов при CraftingDataPacket (ClientNetworkHandler::handle ->
  Recipes::clearRecipes). recipes.inc держит рецепты модов (recipe.shaped/recipe.smelt) и возвращает их после пакета.
- Творческий инвентарь 0.14.3 - JSON (CraftingScreenController, crafting.creative_inventory_screen), не InventoryScreen.
  Страницы: хук ContainerManagerModel::_addContainer (модели creative_*: размер +0xc, vector<ItemInstance> +0x14),
  ~CraftingContainerManagerModel возвращает векторы игры; иконки вкладок - InventoryItemRenderer::update (слот 4) пишет
  #item_id_aux контролам nova_ct_icon_<k>; подписи/стрелки - debug_overlay_renderer nova_ct_label_<k>/nova_ct_arrows.
- Свободные id предметов: + 416-426 (итого 79). Предметы по данным: variants (getIcon слот 50, buildDescriptionName 40).
- Журнал игры пишется в games/com.mojang/nova_log.txt (logcat своего процесса).
- Не сделано в Simple Ores: плиты, ступени, решётки, двери, нажимные плиты, ножницы, луки, медное ведро
  (есть классы игры: SlabBlock, StairBlock, ThinFenceBlock, DoorBlock/DoorItem::place, PressurePlateBlock, BowItem, BucketItem;
  нужны текстуры по имени в terrain.meta/items.meta).
- Не найдено: мультиплеер в 27 (зависание/Server is full - нужен nova_log.txt), кукла (короткие руки, одна нога).

## alpha29
- Имена вариантов (variants) попадают в глобальные имена скрипта (ModRuntime.startMod: все customIds "mod:имя").
- Страницы вкладок: после подмены вектора ContainerModel::_onItemChanged(слот) по всем слотам (иначе экран держит старое);
  пустые вкладки прячутся (бит видимости +0x30: родители nova_ct_icon_k и interactive_tabs/<имя>); стрелки -
  nova_ct_prev/nova_ct_next снаружи панели, номер страницы в строке названия.
- Классы игры для блоков модов (type): stairs (StairBlock 0xa0, base), bars (ThinFenceBlock 0xb4), pressure_plate
  (PressurePlateBlock 0xa0), door (DoorBlock 0xd0, текстуры +0x9c/+0xb4, getResource -> предмет двери).
  Текстуры по имени: Java добавляет записи nova_<mod>_<файл> в terrain.meta. Предметы: tool "shears" (vtable ShearsItem),
  type "bow" (BowItem 0x88, кадры +0x40/+0x58/+0x70), "bucket" (BucketItem 0xa0: +0x40 пустое, +0x88 вода, +0x58 лава,
  +0x70 молоко), "door" (DoorItem 0x48 + хуки useOn/place). Кадры - frames (MVar aux 1000+k).
- Номера блоков: 210-242, 200-209, 250-254, 29, 33, 34 (51).
- Плиты Simple Ores - полублоки (box), без двойных плит и верхней установки.

## alpha30
- ModItems: броня - только kind 6..9 (двери/ножницы/луки/ведро требовали armorTexture).
- Иконки вкладок: InventoryItemRenderer::update вызывается не каждый кадр -> слот 5 (render) зовёт update для nova_ct_icon_k.
- После смены страницы, если открытой вкладки нет на странице - показать панель creative_screen_building_blocks.
- Мультиплеер на alpha29 у пользователя работает.

## alpha31
- Вкладки: скрытие с запоминанием прежней видимости (ct_hid), тапы по скрытым кнопкам вкладок глотаются (ct_block).
- Дверь: верх/низ (грани 0/1) берут Block+0x10 -> тоже наша текстура.
- Item::mBow / Item::mBucket подменяются на время HudCursorRenderer::render, LocalPlayer::getFieldOfViewModifier,
  Player::canUseCarriedItemWhileMoving (лук модов) и BucketItem::use/useOn/useTimeDepleted (ведро модов).
  Имя ведра по содержимому: vtable-копия, слот 40, имена кадров (aux 1000+k).
- Не сделано: встроенные чары луков Simple Ores (Сила II, у оникса Горение).

## alpha33 (Opus/Fable, ноябрь 2026)
- modnet.inc: связь хост<->игроки через TextPacket игры. Игрок->хост: type 1 "/nv!mod\x1fchan\x1fjson" (хук
  ServerNetworkHandler::handle(TextPacket) забирает, игрок по guid _getPlayer); хост->игрок: type 0 "\x02nv!..." через
  PacketSender слот 3 send(guid=Player+0xd08, packet); хук ClientNetworkHandler::handle(TextPacket). Хост = !Level::isClientSide.
  nativeApiStr(1000+op). Свой канал "nova": tp (движение - клиентское в 0.14). Ограничение 60 сообщений/с.
  События host.receive/client.receive (только моду с тем же id, data - JSON), players.join/leave (onReady_ClientGeneration / onDisconnect).
  API host.*, players.*, player.give/name. Adventure Craft: урон обезвоживания наносит хост.
- Простые предметы упаковываются в "items_N" носители (ModItems.packPlain), rt.carriers: player.item()/события дают id+65536*data.
  mvars до 4096, поиск по индексу mv_first/mv_n.
- Вкладки: выбор первой вкладки через TabComponent::selectTab(VisualTree) (компоненты UIControl +0x4c маска, +0x50 массив),
  после смены видимости VisualTree(+0x50 ScreenView)+8 |= 7 (setDirty) - хуки ScreenView::setupAndRender/render ловят ScreenView.
- Дверь: верх/низ - текстура "edge:" (столбцы 13..15 нижней половины, размноженные).
- Ножницы модов: Item::mShears подменяется в Sheep/MushroomCow/SnowGolem и playerDestroy листвы/травы/лиан.
- Луки: хук EnchantUtils::getEnchantLevel (19 сила, 21 огонь, 22 бесконечность) по power/flame/infinity.
- tools/check_mods.sh: прогон всех примеров модов на ПК (mcmod/test/ModCheck.java), build_java.sh падает при ошибке javac.
- Медная руда без drop (в моде нет сырой меди), оловянная -> raw_tin.
