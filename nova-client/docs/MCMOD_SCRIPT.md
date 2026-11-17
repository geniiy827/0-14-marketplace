# McMod Script — язык модов NOVA

Мод — архив **.mcmod** (zip):

```
mod.json     {"id": "my_mod", "name": "Мой мод", "version": "1.0.0", "author": "Я", "main": "main.mcs"}
main.mcs     скрипт
icon.png     иконка (необязательно)
```

`id` — латиница, цифры, `_` `-`. Импорт: Хранилище → Импорт. Включение — вкладка «Моды» (без перезапуска игры: мод стартует при входе в мир).
Список всех функций и событий — `MCMOD_API.md`.

## Основы

```
// комментарий        # тоже комментарий
var hp = 20                       // переменная (число, строка, true/false, null, список, таблица)
var name = "Steve"
var list = [1, 2, 3]
var cfg = {power: 2, msg: "hi"}   // таблица: cfg.power, cfg["msg"]

chat("Привет, {name}! hp = {hp + 1}")   // {выражение} внутри строки; \{ — обычная скобка
```

Операции: `+ - * / %`, сравнения `== != < > <= >=`, логика `and or not` (или `&& || !`),
`a ? b : c`, диапазон `1..10`, присваивание `= += -= *= /= %=`.
Ложь: `false`, `null`, `0`, `""`. Всё остальное — истина.

## Условия и циклы

```
if hp < 5 {
    chat("мало здоровья")
} else if hp < 10 {
    chat("так себе")
} else {
    chat("норм")
}

for i in 1..10 { ... }            // числа от 1 до 10
for item in list { ... }          // элементы списка (у таблицы — ключи)
while x > 0 { x -= 1 }
break   continue   return значение
```

Защита: если скрипт крутится слишком долго (бесконечный цикл), он останавливается с ошибкой, игра не зависает.

## Функции

```
fn greet(who, excl = "!") {       // параметр со значением по умолчанию
    return "Привет, {who}{excl}"
}
var f = fn(x) { return x * 2 }    // функция как значение
```

## События

```
on world.join(e) { ... }          // вошёл в мир
on block.break(e) {               // e.x e.y e.z e.block e.data e.item
    if e.block == 1 { e.cancel() }   // отменить действие игры
}
on player.jump(e) { }             // прыжок (e.cancel() — не прыгать)
on player.sneak(e) { }            // e.sneaking = 1 / 0
on block.use(e) { }               // тап по блоку: e.x e.y e.z e.face e.block e.item
on entity.attack(e) { }           // удар: e.target (номер сущности), e.type, e.item
on chat(e) {                      // e.text — что написал игрок (команды тоже)
    if e.text == "/heal" { player.setHealth(20); e.cancel() }
}
every 20 ticks { ... }            // каждую секунду (20 тиков)
every 5 seconds { ... }
```

Переменные верхнего уровня создаются заново при каждом входе в мир.
Сохранить между запусками: `data.save("ключ", значение)` / `data.load("ключ", поумолчанию)`.

## Вся игра без API: hook, mc.call, mem

Мод может перехватить **любую** функцию Minecraft PE 0.14.3 и вызвать любую функцию по имени — их в игре ~22 800,
имена как в C++ (`Класс::метод`). Список с типами аргументов: `assets/nova/symbols.tsv` в APK
(генерирует `tools/gen_symbols.py`), разобранный код игры — `work/re014/classes/<Класс>.s` (`nova ref-build`).

```
hook "LocalPlayer::travel" (self, strafe, forward) {     // перехват: тело выполняется ВМЕСТО функции
    return original(self, strafe * 2, forward * 2)         // original() - настоящая функция (без аргументов = те же)
}
hook "Mob::getJumpPower" float (self) {                     // float/int/double/void - что возвращает (если не угадано)
    return self == mc.player() ? original() * 2 : original()
}
hook "Mob::causeFallDamage" (self, d) { }                  // не вызвали original() и ничего не вернули = функция отменена
```

- Первый аргумент метода класса — сам объект (`self`, это адрес в памяти). Дальше — аргументы функции по порядку.
- Аргументы-числа приходят числами (`float` — дробные), `Vec3`/`BlockPos` по значению — таблицей `{x, y, z}`,
  указатели и ссылки — адресом (число).
- Если имя есть в нескольких вариантах (перегрузки), пиши полностью: `"Level::setTime(int)"`. Вместо длинного
  `std::basic_string<...>` можно писать `std::string`. Пример целиком — мод `mcmod/examples/nova_pistol` (пистолет).
- Перехват ставится, когда мод запускается (вход в мир), и снимается при перезагрузке мода.
- Функцию может перехватить только один мод.

Вызов функций игры:

```
mc.call("GuiData::displayClientMessage", mc.gui(), "привет")      // строки сами превращаются в std::string / char*
mc.call("LocalPlayer::setPos", mc.player(), {x: 0, y: 80, z: 0}) // {x,y,z} -> Vec3 / BlockPos в памяти
var eye = mc.callf("Mob::getJumpPower", mc.player())             // callf/calld/calli - явный тип результата
mc.hook("Level::setTime(int)", fn(self, t, original) { return original(self, 6000) })   // перехват из кода
```

Готовые объекты: `mc.client()`, `mc.player()`, `mc.region()` (BlockSource игрока), `mc.level()`, `mc.gui()` (GuiData)
— адреса или `null`, если не в мире. Поиск: `mc.find("Jump")` — список имён, `mc.info("Mob::travel")` — адрес,
размер, аргументы, как перехватывается.

Память: `mem.int(p, смещение)`, `mem.float(p, off)`, `mem.byte/ubyte/short/ushort/ptr`, `mem.vec3(p, off)`,
`mem.setInt(p, v)`, `mem.setFloat(p, v)`, `mem.setByte(p, v)`..., `mem.str(p)` (std::string), `mem.cstr(p)`,
`mem.string("текст")` (новая std::string), `mem.alloc(n)` / `mem.free(p)`, `mem.hex(n)`.
Пример: позиция игрока — `mem.vec3(mc.player(), 8)` (y — глаза, ноги = y - 1.62).
Неверный адрес = вылет игры (как в C++), отчёт будет в `NOVA-crash.txt`.

### Консоль и живые моды

- Консоль: сообщение в чат, начинающееся с `>`, выполняется как код, результат пишется в чат:
  `> mc.find("Sheep")`, `> mem.vec3(mc.player(), 8)`, `> var p = mc.player()` (переменные консоли живут до выхода).
- Живые моды: файлы `*.mcs` (или папки с `main.mcs`) в `games/com.mojang/nova_mods/` на телефоне. Сохранил файл —
  через секунду мод перезапущен в игре, старые перехваты сняты. Ничего не надо собирать и импортировать.

## Замена встроенных функций

Любую функцию можно заменить для всех модов. `original(...)` — вызвать старую версию:

```
override chat.send(text) {
    original("§b[Мой мод]§r " + text)
}
override world.explode(x, y, z, power, fire) {
    chat("взрывы отключены")
}
```

## Методы значений

- строка: `len() upper() lower() trim() contains(s) starts(s) ends(s) find(s) replace(a, b) sub(от, до) split(разделитель) num()`
- список: `len() add(x) insert(i, x) pop() removeAt(i) remove(x) contains(x) find(x) join(", ") clear() copy() random()`
- таблица: `keys() values() has(k) remove(k) len()`

## Свои блоки

Блок объявляется в main.mcs. Имя блока сразу становится его номером: `world.setBlock(x, y, z, ruby_block)`.
Блоки мода попадают в творческий инвентарь: обычные — в раздел «Строительство», модельные — в «Декор». После включения/выключения мода игра перезапускается.

**Классический блок** — один куб, у каждой стороны своя текстура:

```
block ruby_block {
    name: {ru: "Рубиновый блок", en: "Ruby Block"}
    texture: "textures/ruby_block.png"        // картинка из мода
    hardness: 3                                // время ломания
    sound: "metal"                             // stone wood dirt sand gravel glass wool metal leaves
}

block crate {
    name: "Ящик"
    textures: {up: "planks", down: "planks", side: "log:0"}   // текстуры самой игры
}

block stone_plate {
    texture: "stonebrick"
    box: [0, 0, 0, 16, 4, 16]                  // не весь куб: пиксели 0..16
}
```

Текстура — это имя текстуры игры (`"stone"`, `"planks:2"`), или путь к PNG: сначала ищется в моде,
потом в наборах ресурсов, потом в игре.

**Модельный блок** — модель из Blockbench, любое количество кубов, текстура-развёртка как у мобов:

```
block lamp {
    name: "Лампа"
    model: "models/lamp.bbmodel"              // .bbmodel (Java Block или Bedrock Block) или .geo.json
    texture: "textures/lamp.png"               // только если в модели нет своей текстуры (.geo.json)
    light: 15
}
```

Повороты кубов — любые углы (как в Blockbench). В инвентаре и в руке модель видна в 3D. 
**Столкновения** (`collision`) — сколько угодно боксов, в пикселях 0..16:

```
block shelf {
    model: "models/shelf.bbmodel"
    collision: [[0, 0, 0, 16, 2, 16], [0, 14, 0, 16, 16, 16]]   // пол и полка, между ними можно пройти
}
```

`collision: "model"` — по кубам модели (так по умолчанию у модельных блоков; повёрнутый куб — по его габаритам),
`"full"` — целый блок, `"none"` — проходим насквозь. У обычного блока по умолчанию — его `box`.
Рамка выделения — общий габарит всех боксов.

Общие поля: `name`, `hardness`, `resistance`, `light` (0..15), `sound`, `transparent` (true — прозрачные части
текстуры), `id` (свой номер 1..255, по умолчанию выдаётся сам из 210..242 и запоминается).

## Свои предметы

```
item ruby {
    name: {ru: "Рубин", en: "Ruby"}
    texture: "textures/ruby.png"      // квадратная картинка любого размера (16, 32, 64...)
}

item big_sword {
    texture: "textures/big_sword.png"
    size: "pixel"                      // 32x32 -> в руке в 2 раза больше обычного предмета
    hand: "tool"                       // держать как оружие/инструмент
    durability: 500
}

item wand {
    texture: "textures/wand.png"
    action: "Построить"                // своя кнопка действия, пока предмет в руке -> on action(e)
}
```

`size`: `"hd"` (по умолчанию) — обычный размер, лишние пиксели дают детальность; `"pixel"` — каждый пиксель
как у обычного предмета, большая картинка = большой предмет в руке. Текстура может быть и предметом игры (`"apple"`).
Ещё: `stack` (1..64), `category` (`"tools"`, `"items"`). Имя предмета — его номер: `if e.item == wand { ... }`.

## Своя кнопка действия

`ui.action("Сесть")` — показать кнопку (как у лодки), `ui.action("")` — убрать. Нажатие — событие `on action(e)`.

## Блоки и предметы

Где нужен блок или предмет, можно писать имя (`"stone"`, `"diamond_sword"`) или номер (`1`, `276`).
`item.id("golden_pickaxe")` — номер по имени.

## Ошибки

Ошибки показываются в чате красным: `[Мой мод] main.mcs:12: неизвестное имя 'helth'`.
После 6 ошибок мод останавливается до следующего входа в мир.

## Редстоун у блоков (alpha18)
```
block my_source { texture: "textures/a.png"  redstone: "source" }                 // сигнал 15
block weak      { texture: "textures/b.png"  redstone: {type: "source", power: 4} }
block my_lamp   { texture: "textures/off.png" redstone: {type: "lamp", on: "my_lamp_lit"} }
block my_lamp_lit { texture: "textures/on.png" light: 15 redstone: {type: "lamp_on", off: "my_lamp"} }
```
Работает через схемы самой игры (CircuitSystem: ProducerComponent / ConsumerComponent), как блок редстоуна и лампа.
Крафты: пример в `mcmod/examples/redstone_update/main.mcs`.

## Постройки в генерации мира (alpha19)
Файл `structures.bin` в корне .mcmod: постройки ставит генератор мира NOVA (native/structs.inc, перехват
`StructureFeature::postProcess`) в новых чанках. Генерирует хост, друзья получают готовые чанки.
Делается конвертером `tools/port_rs.py` из `.nbt`-шаблонов Java (блоки переводятся в блоки 0.14 для 4 поворотов,
сундуки получают лут через `WeighedTreasureItem::addChestItems`, спавнеры — моба). Пример — `mcmod/examples/adventure_craft`
(перенос Repurposed Structures, LGPL-3.0).

## Ряд иконок на HUD (alpha22)
`ui.hud("thirst", ["textures/a.png", ...], 0, "right")` — ряд иконок над хотбаром (картинки 16x16, иконка 9x9 в левом верхнем углу):
до 20 картинок мода слева направо; row 0 — под голодом/сердцами, 1 — ещё ряд ниже, 2 — ниже; side "right" (голод) или "left" (сердца).
`ui.hud("thirst", [])` — убрать. Пример — жажда в `mcmod/examples/adventure_craft`.

## Инструменты и броня игры (alpha27)

Предмет становится настоящим инструментом игры (скорость копания, что может добывать, прочность, урон) или бронёй
(очки защиты, прочность, вид на игроке):

```
item mythril_pickaxe {
    texture: "textures/mythril_pickaxe.png"
    tool: "pickaxe"                     // "pickaxe" | "axe" | "shovel" | "sword" | "hoe"
    tier: {level: 2, uses: 800, speed: 8, damage: 3, enchant: 12}   // level: 0 дерево, 1 камень, 2 железо, 3 алмаз
}
item mythril_helmet {
    texture: "textures/mythril_helmet.png"
    armor: "helmet"                     // "helmet" | "chestplate" | "leggings" | "boots"
    defense: 3                          // очки защиты (половинки нагрудника)
    durability: 22                      // множитель прочности, как у ArmorMaterial (железо 15, алмаз 33)
    enchant: 12
    armorTexture: "textures/armor/mythril"   // mythril_layer_1.png (шлем, нагрудник, ботинки) и mythril_layer_2.png (поножи), 64x32
}
```

## Руды, добыча и выпадение блоков (alpha27)

```
block mythril_ore {
    texture: "textures/mythril_ore.png"
    hardness: 4
    harvest: 2                          // нужна кирка уровня 2 (железная) и лучше, иначе блок ничего не даёт
    drop: "mythril_gem"                 // что выпадает (предмет этого мода); {item: "...", count: [1, 3]}
    xp: [1, 3]                          // опыт
    ore: {count: 8, size: 4, min: 1, max: 48, in: "stone"}   // генерация: жил на чанк, размер, высоты; in: "stone" | "netherrack"
}
```

Руды ставятся в новых чанках, как у игры (OreFeature): в обычном мире вместо камня, в Аду вместо адского камня.

## Вкладки творческого инвентаря (alpha27)

У каждого мода своя вкладка (название мода, иконка - первый блок/предмет). Вверху творческого инвентаря стрелки
листают страницы: 1 - вкладки игры, дальше - по 4 вкладки модов. Предметы модов во вкладках игры больше не дублируются.

```
item ruby_sword { tab: {ru: "Оружие", en: "Weapons"}  tabIcon: true  ... }   // своя вкладка мода и её иконка
```

## Крафты и печь: recipe.* (alpha28)

Игра при входе в мир заново заполняет книгу рецептов (CraftingDataPacket), поэтому рецепты, добавленные напрямую
через Recipes::addShapedRecipe, пропадали. recipe.* хранит рецепты модов в NOVA и возвращает их каждый раз.

```
on world.join(e) {
    recipe.shaped(tin_sword, ["I", "I", "S"], {I: tin_ingot, S: 280})       // строки 1..3, ширина 1..3
    recipe.shaped(tin_ingot, ["B"], {B: tin_block}, 9)                       // последнее - сколько выходит
    recipe.smelt(tin_ore, tin_ingot)                                         // печь; [номер, данные] тоже можно
}
```

## Предметы по данным: variants (alpha28)

Один номер предмета - много предметов (как красители): экономит номера (в 0.14 их всего 512).

```
item metals {
    variants: [
        {name: "tin_ingot", title: {ru: "Оловянный слиток", en: "Tin Ingot"}, texture: "textures/tin_ingot.png"},
        {name: "tin_nugget", title: {ru: "Оловянный самородок", en: "Tin Nugget"}, texture: "textures/tin_nugget.png"}
    ]
}
```
Имя варианта в скрипте = номер + 65536 * данные (tin_nugget = номер metals + 65536). recipe.*, drop блоков и вкладки
понимают это сами.

## Связь хоста и игроков: host.* / players.* (alpha33)

Мод работает у каждого игрока сам по себе. Хост — тот, чей мир (в одиночной игре хост — ты сам). Всё, что решает
сервер (урон, лечение, выдача предметов, перемещение других игроков), делает хост. Сообщения идут по соединению самой
игры (TextPacket), отдельный канал не нужен; хост без NOVA их просто не увидит.

```
// у игрока: попросить хоста
host.send("dehydrate", {level: 3})               // у хоста сработает on host.receive

// у хоста
on host.receive(e) {                              // e.player - имя, e.channel, e.data (число, строка, список, {..})
    if e.channel == "dehydrate" { players.hurt(e.player, 1, 16) }
}
on players.join(e) { players.send(e.player, "hello", "привет!") }    // у игрока: on client.receive(e)
on client.receive(e) { ui.tip(e.data) }

host.isMe()                                       // я хост?
players.list()                                    // имена игроков (только хост)
players.hurt(name, 2, 0)  players.heal(name, 4)  players.teleport(name, x, y, z)
players.give(name, ruby, 3)  players.pos(name)  players.health(name)  players.tell(name, "текст")
players.sendAll("event", data)                    // всем, и себе
player.give(item, count)                          // себе (хост / одиночная игра)
```

Сообщение — до 4000 символов JSON; у хоста не больше 60 сообщений в секунду от одного игрока. Сообщение приходит только
тому же моду (по id мода) у получателя.

## Номера предметов больше не кончаются (alpha33)

Простые предметы (картинка и название: без tool/armor/type/durability/action/hand/stack/size/category) не занимают по
номеру: все простые предметы мода сидят на одном номере и различаются данными, как красители — до 1024 на номер,
номеров у модов 79. Для скрипта ничего не меняется: имя предмета, player.item(), события, рецепты, выпадение блоков,
вкладки говорят одним кодом (номер + 65536 × данные). `pack: false` — оставить предмету свой номер.

## Луки модов: встроенные чары (alpha33)

`type: "bow"` + `power: 2` (Сила), `flame: true` (Горение), `infinity: 50` (шанс в %, что стрела не потратится).

## Погода и снег: weather.* (alpha39)

- `weather.rain(уровень)` — осадки идут всегда, сила `0..1` (там, где холодно, — снег); `-1` — вернуть погоду игры.
- `weather.freeze(1)` — все биомы холодные: снег вместо дождя, вода замерзает; `weather.freeze(0)` — как было.
- `weather.snow(слоёв_в_минуту, предел=0)` — снег копится вокруг игроков слой за слоем (8 слоёв → блок снега,
  дальше растёт поверх). `предел` — высота сугробов в блоках, `0` — без предела. Клетку, где стоит игрок,
  не засыпает. Сугробы растит **хост** (в мультиплеере вызывать под `host.isMe()`), игроки получают блоки по сети.

```
on world.join(e) {
  weather.freeze(1)
  weather.rain(1)
  if host.isMe() { weather.snow(4, 0) }
}
```
Готовый мод — `mcmod/examples/infinity_winter`.

## Климат, картинки на HUD, броня и огонь (alpha41)

Для температуры Adventure Craft (перенос SimpleDifficulty), годится любому моду:

```
world.biomeTemp(x, y, z)      // температура биома, как в Java (пустыня 2, равнины 0.8, ледяные равнины 0)
world.biomeSnow(x, y, z)      // снежный биом;  world.snowTemp(x, y, z) - тут достаточно холодно для снега
world.sky(x, y, z)            // видно небо;    world.rainingAt(x, y, z) - сюда падает дождь;  world.raining()
world.rainTop(x, y, z)        // высота, до которой долетают осадки в этом столбце
world.count(x1, y1, z1, x2, y2, z2, [50, 62, lava]) // сколько каких блоков в коробке (до 10 номеров), считает C
world.drop(x, y, z, item, n)  // ХОСТ: предмет выпадает из блока
player.armor(0..3)            // номер брони: 0 шлем, 1 нагрудник, 2 поножи, 3 ботинки (0 - пусто)
player.has(item)              // предмет есть в инвентаре
entity.setFire(e, сек)  players.fire(имя, сек)       // ХОСТ: поджечь
ui.image("slot", "textures/a.png", x, y, размер, "bottom")   // одна картинка: x от центра экрана, y от низа/верха
```
`ui.image` рисует картинки в порядке появления слотов (сначала фон, потом то, что поверх); `""` вместо картинки —
спрятать. Блоку можно запретить выпадение: `drop: {item: "свой_блок", count: 0}`.

## Номера блоков, анимации, частицы (alpha42)

**Простые блоки делят номер.** В 0.14 у блока в мире один байт номера (свободных 51) и 4 бита данных. Простые блоки
одного мода (без `type`, без `redstone`, без `id: N`) с одинаковыми светом, звуком, прозрачностью и формой теперь
сидят на одном номере, по одному на значение данных 0..15, как шерсть. По данным свои: текстуры, модель, боксы,
прочность, кирка (`harvest`), выпадение, руда, название. В скрипте такой блок — число `номер + 65536 × данные`
(как предметы по данным): `world.block`, `e.block`, `world.setBlock`, `world.count` его понимают.
Блок, который сам хранит что-то в данных (`world.data`, `world.setBlock(.., данные)`, как возраст костра), пишет
`data: true` — у него будет свой номер.

**Анимированная текстура блока** — полоска квадратных кадров сверху вниз (как в Java), скорость `frametime`
(тиков на кадр). Играет её FlipbookTexture игры, как лаву и воду:
```
block magma { texture: "textures/magma.png", frametime: 8 }      // magma.png 16x48: 3 кадра
```
**Картинка предмета, которую выбирает скрипт** (как часы и компас): `frames: N` + полоска из N кадров,
`item.frame(предмет, k)` — кадр у всех таких предметов сразу (рука, инвентарь, сундуки).
```
item thermometer { texture: "textures/thermometer.png", frames: 26 }
every 1 seconds { item.frame(thermometer, 12) }
```
**Частицы и звуки** (видно и слышно на этом телефоне): `world.particle("flame" | "smoke" | "largesmoke" | "lava" |
"cloud" | номер, x, y, z, vx, vy, vz)`, `world.sound("fire.fire", x, y, z, громкость, высота)`.


## Измерения (alpha49)

```
dimension wood {
    name: {ru: "Деревянный мир", en: "Wooden world"}
    portal: {frame: "planks", color: "#e8a040"}      // или texture: "textures/portal.png" - полоса квадратных кадров
    generator: {
        type: "islands"            // "islands" - парящие острова, "flat" - слои
        height: 62                 // высота островов
        size: 56                   // размер островов (больше - крупнее)
        density: 0.56              // 0..1: больше - меньше островов
        top: "grass", filler: "dirt", filler_depth: 3
        body: ["planks:0", "planks:1"]   // тело острова полосами по 3 блока
        roots: "log"               // свисают снизу (без :data - разные породы)
        trees: 0.035, grass: 0.18  // доля столбиков с деревом / травой
        biome: "forest"            // биом игры: цвет травы, мобы
    }
    respawn: false                 // false: умер там - возрождаешься дома
}
// flat: generator: {type: "flat", layers: [{block: "bedrock"}, {block: "dirt", count: 3}, {block: "grass"}]}
```

- Портал строится из блока `frame` как адский (внутри 2..21 в ширину, 3..21 в высоту), зажигается огнём. Из обычного мира ведёт в измерение, из измерения - домой. По прибытии ищется портал рядом (16 блоков), иначе строится новый (над пустотой - на площадке).
- Блоки: имя игры (`"planks"`, `"minecraft:log"`), `"имя:data"`, блок своего мода, номер.
- Не больше 4 измерений из модов. Номера (2, 3, ...) хранятся в files/nova/dimids.json.
- Пример: mcmod/examples/wood_world.

## Мобы модов из Bedrock-аддонов (octa7)

Модель (`*.geo.json`), текстура, анимации (`*.animation.json`) и контроллеры анимаций (`*.animation_controllers.json`)
берутся из ресурс-пака Bedrock прямо как есть: положи папку RP в мод (например `rp/`) и объяви моба:
```
entity dweller {
    client: "pntmc:prehistoric_dweller"   // идентификатор из rp/entity/*.entity.json: модель, текстура,
                                          // анимации, scripts.animate и scripts.scale берутся оттуда
    size: [0.9, 0.6]                      // хитбокс (ширина, высота)
    health: 150
}
```
Без `client` можно указать файлы прямо: `geometry: "geometry.x"`, `texture: "textures/x.png"`, `animate: ["ctrl"]`, `scale`.
Молнг: `query.anim_time, life_time, modified_move_speed, modified_distance_moved, target_x/y_rotation, is_on_ground,
all_animations_finished, any_animation_finished, health, is_moving` считает игра; **любое другое** `query.x` /
`variable.x` — значение моба, которое ставит скрипт: `entity.set(e, "x", 1)` (так переносятся флаги из BP:
`is_sheared`, `mark_variant`...). Значения сохраняются в мире вместе с мобом.
```
var d = entity.spawn("dweller", x, y, z)   // номер сущности
entity.set(d, "mark_variant", 1)
entity.kind(d)                             // "мод:dweller"; null у обычных мобов
entity.remove(d)
```
Ключевые кадры: linear, step, catmullrom; каналы rotation / position / scale; переходы с blend_transition.
Пока: носитель — свинозомби (ходит, отвечает на удар, не горит), звуки носителя выключены.
