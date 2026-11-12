# Плагин StoryCraft для Genisys (MCPE 0.14.3, PHP 7)

Раздаёт игре Story Craft каталог приключений и файлы серий по HTTP, хранит прогресс игроков.
HTTP работает в отдельном потоке и не тормозит игровой сервер.

## Установка
1. Возьми `StoryCraft.phar` (артефакт сборки `StoryCraft-plugin` в GitHub Actions) **или** папку
   `StoryCraft/` целиком из этого каталога.
2. Положи в `plugins/` сервера и перезапусти сервер.
3. При первом запуске создаётся `plugins/StoryCraft/`:
   ```
   config.yml                 http_port: 12055, bind: 0.0.0.0, scan_interval: 5, max_connections: 64, public_url: "", admin_key
   content/adventures/        ← сюда класть приключения (README.txt внутри)
   progress/                  ← прогресс по устройствам (JSON)
   ```
4. Порт: MCPE слушает **UDP** 12055, плагин — **TCP** 12055 (хостинг пробрасывает оба).
   Проверка с телефона/браузера: `http://nova-host.mcbe.in:12055/api/ping` → `{"ok":true,"name":"StoryCraft",…}`.

> В `plugin.yml` указано `api: [2.0.0]` — это API PocketMine/Genisys для 0.14. Если ядро ругается на версию API,
> посмотри строку `API_VERSION` в консоли при старте сервера и впиши её в `plugin.yml`.

## Как добавлять серии

**Проще всего — архивом.** Положи `last_men.zip` (внутри сразу папка приключения `last_men/…`) прямо в
`plugins/StoryCraft/content/adventures/`. Через несколько секунд плагин распакует его (папка с тем же именем
заменяется) и переименует архив в `last_men.zip.done`. То же делает `/storycraft reload`.
Нужно расширение PHP `zip` (класс `ZipArchive`); если его нет — в консоли будет сообщение «распакуй вручную».

Вручную — папками:
```
plugins/StoryCraft/content/adventures/<id>/
  adventure.json   {"id","title","description","order":1,"accent":"#ff8a3a","cover":"cover.png","hidden":false}
  cover.png        превью 16:9
  episodes/<NN_id>/   ← папка из mcanim --export-game (episode.json, cover.png, scenes/, worlds/, textures/, audio/)
```
Положил / удалил / заменил папку → через `scan_interval` секунд каталог обновится, игра это увидит
(список приключений обновляется каждые 30 с). Изменённые файлы игра перекачает сама (сверка по sha1).
`"hidden": true` скрывает приключение или серию.

## Пакеты серий (быстрая загрузка)
При сканировании плагин собирает для каждой серии пакет `plugins/StoryCraft/packs/<adv>/<ep>.pack`:
все файлы серии, каждый сжат gzip (нужен только zlib). Пробная серия: 17.7 МБ → 3.8 МБ.
Игра качает пакет в 5 параллельных потоков (Range) и распаковывает у себя. Пакет пересобирается сам,
если у любого файла серии изменились размер или время изменения.

## Команды (op)
- `/storycraft status` — порт, подключения, версия каталога
- `/storycraft reload` — пересканировать папку сейчас
- `/storycraft list` — приключения и серии с размерами
- `/storycraft progress <device_id>` — прогресс устройства (device_id виден в игре: Настройки → «О игре»)

## API
| метод | путь | |
|---|---|---|
| GET | `/api/ping` | проверка связи |
| GET | `/api/catalog` | каталог (`If-None-Match` → 304) |
| GET/HEAD | `/files/<adventure>/<path>` | файлы (Range, ETag) |
| GET | `/api/pack/<adventure>/<episode>/index` | индекс пакета + `data_offset`, `pack_size` |
| GET/HEAD | `/api/pack/<adventure>/<episode>` | пакет серии (Range) |
| POST | `/api/register` | `{"device_id","model","app_version"}` → `{"ok":true,"token"}` |
| GET/POST | `/api/progress` | заголовки `X-Device`, `X-Token`; POST — события `checkpoint` / `complete` / `reset` |

## Локальная проверка без ядра
```
php tools/standalone.php /путь/к/data 12055
curl http://127.0.0.1:12055/api/ping
```
Сборка phar: `php -d phar.readonly=0 tools/build_phar.php StoryCraft.phar`
