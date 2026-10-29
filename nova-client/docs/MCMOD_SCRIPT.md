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
on chat(e) {                      // e.text — что написал игрок (команды тоже)
    if e.text == "/heal" { player.setHealth(20); e.cancel() }
}
every 20 ticks { ... }            // каждую секунду (20 тиков)
every 5 seconds { ... }
```

Переменные верхнего уровня создаются заново при каждом входе в мир.
Сохранить между запусками: `data.save("ключ", значение)` / `data.load("ключ", поумолчанию)`.

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

Повороты кубов внутри модели пока не учитываются. Столкновения у всех блоков — обычный куб 1×1×1.

Общие поля: `name`, `hardness`, `resistance`, `light` (0..15), `sound`, `transparent` (true — прозрачные части
текстуры), `id` (свой номер 1..255, по умолчанию выдаётся сам из 210..242 и запоминается).

## Блоки и предметы

Где нужен блок или предмет, можно писать имя (`"stone"`, `"diamond_sword"`) или номер (`1`, `276`).
`item.id("golden_pickaxe")` — номер по имени.

## Ошибки

Ошибки показываются в чате красным: `[Мой мод] main.mcs:12: неизвестное имя 'helth'`.
После 6 ошибок мод останавливается до следующего входа в мир.
