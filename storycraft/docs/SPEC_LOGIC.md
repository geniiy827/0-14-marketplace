# SPEC_LOGIC — интерактив серии: узлы, действия, выборы, хотспоты, мини-игры, 2D-наложения

Цель: **несложно собирать, но не шаблонно**. Поэтому вместо десятка жёстких «мини-игр» —
небольшой набор примитивов + действия + переменные + условия. Из них собирается что угодно:
рычаг, кодовый замок, «найди предмет», «собери из кусочков», QTE, диалог с выбором, разветвления,
сохранение выбора на всё приключение.

## Переменные и выражения
- `episode.variables` — начальные значения переменных серии.
- `adv.<имя>` — переменные **приключения** (переживают серию, хранятся на сервере в прогрессе).
- Выражения — синтаксис Godot `Expression` (как GDScript): `tries < 2`, `ending == 'door'`,
  `adv.trust >= 3 and not saw_zina`, `'дверь' if ending == 'door' else 'прутья'`.
- В текстах подставляется `{выражение}`: `"Вы сбежали через {'дверь' if ending == 'door' else 'прутья'}."`
- Встроенные переменные во время узла: `taps`, `time_left`, `score`, `choice` (индекс выбранного).

## Узлы (`episode.nodes`, id → объект)
Общие поля любого узла: `on_enter: [actions]`, `on_exit: [actions]`.

### scene — проиграть сцену
`{"type":"scene","scene":"intro","next":"choose"}` — по окончании сцены переход к `next`
(`next` может быть выражением-объектом `{"if":"x>1","then":"a","else":"b"}`).

### choice — выбор
```json
{"type":"choice","background":"think_loop","prompt":"Как выбираемся?","speaker":"kvas",
 "timer":15, "timeout":2,
 "options":[{"text":"Дёрнуть рычаг","goto":"lever"},
            {"text":"Разогнуть прутья","goto":"bars","if":"tries < 2"},
            {"text":"Смыться в унитаз","goto":"flush","actions":[{"set":{"adv.coward":true}}]}]}
```
- Фон — зацикленная сцена `background`. Панель выбора снизу/справа, 2–4 варианта, кнопки в стиле игры.
- `timer` (сек, опц.) — полоса таймера; по истечении выбирается вариант с индексом `timeout`.
- Вариант с ложным `if` скрыт (или серый с замком, если `"locked_text"` задан).
- Как в Story Mode: после выбора — короткая плашка «Квас это запомнит» если у варианта `"remember": "…"`.

### interact — 3D-взаимодействие с объектами
```json
{"type":"interact","background":"lever_loop","hint":"Найди рычаг и дёрни его",
 "hotspots":[{"id":"lever","node":"rychag__main","radius":90,"label":"Рычаг",
              "on_tap":[{"sfx":"ui_click"},{"goto":"lever_pull"}]},
             {"id":"toilet","pos":[3.0,1.0,4.0],"radius":70,"label":"Унитаз","once":true,
              "on_tap":[{"say":{"who":"kvas","text":"Нет, туда я больше не полезу."}}]}],
 "timeout": null}
```
- Хотспот привязан к узлу фоновой сцены (`node` — имя из glb, позиция берётся каждый кадр) или к точке `pos`.
  Мировая позиция → экран через `Camera3D.unproject_position`; рисуется пульсирующее кольцо
  `assets/ui/hotspot_ring.png` + подпись. Тап в радиусе `radius` (в px при 720p, масштабировать) → действия.
- Поля: `if` (показывать при условии), `once` (после срабатывания исчезает), `hold` (сек удержания вместо тапа,
  с круговым прогрессом), `on_tap`.
- Можно несколько хотспотов, порядок/сочетания — через переменные (например, «сначала ключ, потом дверь»).
- `"hidden": true` — **невидимый хотспот** (жанр «найди отличия»): ни кольца, ни подписи, ни пульсации.
  Тап в радиусе срабатывает как обычно; после `once` ничего визуально не исчезает — только короткий звук и `on_tap`.
  В отладочном оверлее (5 тапов по версии в настройках) невидимые хотспоты обводятся красным кругом; в обычной игре — никогда.

Поля узла `interact` (дополнительно):
- `"on_miss": [actions]` — тап мимо всех активных хотспотов (промах). Между двумя промахами минимум 0.35 с
  (двойной тап = одна ошибка). Тапы по кнопке паузы, подсказке и полосе времени промахом не считаются.
  Ошибки серия считает сама, например `{"add":{"misses":1}}`, `{"if":"misses >= 3","then":[{"goto":"lose"}]}`.
- `"timeout": сек` + `"on_timeout": [actions]` — время на узел.
- `"timer_visible": true` — показать полосу времени сверху (как у `choice`); последние 5 с она красная и тикает `ui_click`.

```json
{"type":"interact","background":"farm_loop","hint":"Найди 3 отличия","timeout":40,"timer_visible":true,
 "on_enter":[{"set":{"misses":0,"found":0,"retry":"diff"}}],
 "hotspots":[{"id":"pumpkin","pos":[4.5,1.5,3.5],"radius":70,"hidden":true,"once":true,
              "on_tap":[{"add":{"found":1}},{"if":"found >= 3","then":[{"goto":"win"}]}]}],
 "on_miss":[{"add":{"misses":1}},{"shake":0.3},{"if":"misses >= 3","then":[{"goto":"lose"}]}],
 "on_timeout":[{"goto":"lose"}]}
```

### minigame — готовые механики с параметрами
`{"type":"minigame","background":"bars_loop","kind":"tap_fast","params":{…},"success":[…],"fail":[…]}`
| kind | params | суть |
|---|---|---|
| `tap_fast` | `taps, time, title, bar_color` | натапать N раз за T сек (полоса силы, «тряска» камеры на тапах) |
| `timing` | `zone:[a,b], speed, rounds, title` | бегунок ходит туда-сюда, тапни, когда он в зелёной зоне |
| `sequence` | `items:[hotspot ids или 2D-кнопки], show:true, title` | повтори последовательность (как «Саймон») |
| `hold` | `time, title` | удерживай палец, не отпуская (полоса заполняется) |
| `swipe` | `directions:["left","up",…], time_each` | свайпы в нужную сторону (QTE) |
| `code` | `length, answer, title, hint` | кодовый замок (цифровая клавиатура пиксельная) |
| `drag` | `items:[{id,image}], targets:[{id,rect}], pairs:{item:target}` | перетащи предметы в нужные места (2D) |
Во время мини-игры доступны `taps/time_left/score`. `success`/`fail` — списки действий.

### overlay — свободная 2D-композиция (для любых нестандартных головоломок)
```json
{"type":"overlay","background":"think_loop","dim":0.4,
 "widgets":[
   {"id":"note","kind":"image","src":"textures/note.png","rect":[0.3,0.15,0.4,0.6],"anim":"pop"},
   {"id":"txt","kind":"text","text":"Код: {code_hint}","rect":[0.32,0.3,0.36,0.1],"size":28,"color":"#3b2a12"},
   {"id":"ok","kind":"button","text":"Понятно","rect":[0.42,0.8,0.16,0.08],"on_tap":[{"goto":"next"}]},
   {"id":"cat","kind":"image","src":"textures/cat.png","rect":[0.8,0.7,0.1,0.15],
    "on_tap":[{"sfx":"pop"},{"add":{"found":1}},{"hide":"cat"},{"if":"found >= 3","then":[{"goto":"win"}]}]}
 ]}
```
Виджеты: `image, text, button, panel, progress (bind: выражение 0..1), timer (сек, on_end), draggable,
dropzone (accepts, on_drop), keypad`. `rect` — доля экрана [x,y,w,h]. `anim`: `fade|pop|slide_left|slide_up|shake`.
Картинки — из папки серии (`textures/…`). Так собираются «найди отличия», записки, карты, инвентарь и т.п.

### end — конец серии
`{"type":"end","title":"Свобода!","text":"Вы сбежали через {…}.","ending":"free","unlock_next":true}`
→ экран итога, отправка прогресса (концовка, переменные `adv.*`), открытие следующей серии.

## Действия (элементы списков `on_*`, `success`, `fail`, `actions`)
| действие | пример | смысл |
|---|---|---|
| goto | `{"goto":"lever_pull"}` | перейти к узлу |
| set | `{"set":{"ending":"door","adv.trust":2}}` | присвоить |
| add | `{"add":{"tries":1}}` | прибавить |
| if | `{"if":"tries>=2","then":[…],"else":[…]}` | условие |
| sfx | `{"sfx":"ui_click"}` | звук из `assets/audio` игры или `audio/…` серии |
| say | `{"say":{"who":"kvas","text":"…","voice":"audio/x.ogg","time":2.5}}` | реплика-субтитр поверх |
| play | `{"play":"lever_pull","then":"node"}` | проиграть сцену внутри узла и продолжить |
| wait | `{"wait":0.5}` | пауза |
| show / hide | `{"show":"cat"}` | видимость виджета/хотспота |
| shake | `{"shake":0.3}` | тряска камеры (сила/время) |
| flash | `{"flash":"#ffffff"}` | вспышка экрана |
| vibrate | `{"vibrate":80}` | вибрация (мс), если включена |
| checkpoint | `{"checkpoint":true}` | точка продолжения для «выйти/продолжить» |
| end | `{"end":"free"}` | немедленно завершить серию с концовкой |

Действия выполняются по очереди (wait/play блокируют следующие). Неизвестное действие/узел → лог + понятное
сообщение в debug, в релизе — пропуск без краша.

## Пример целиком
См. `server_content/adventures/last_men/episodes/01_escape/episode.json` — путь выбора, хотспоты с
`once`, мини-игра с подсчётом попыток (`tries`) и скрытием варианта, две концовки с подстановкой текста.
Все пути должны проходиться без ошибок.
