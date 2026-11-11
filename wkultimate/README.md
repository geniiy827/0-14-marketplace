# Wall Kickers Ultimate

Мод Wall Kickers (Kumobius): без Google Play сервисов, свой пакет
`com.kumobius.android.walljumpes`, хардкорная сложность. Генератор уровней
игры не трогается — меняются только данные (`assets/Media/WJScenarios.dat`).

## Сборка

Автоматически: любой push в `wkultimate/**` → GitHub Actions
(`.github/workflows/wkultimate.yml`) собирает APK и публикует релиз
`wku-<номер>`. **Работает только в приватном репозитории** (оригинальный APK — чужой контент).

Локально (нужны Java 17 и Python 3):

```
pip install pillow          # только для --preview
python3 build.py            # -> out/WallKickersUltimate-<версия>.apk
python3 build.py --data-only --preview   # быстро: только WJScenarios.dat + картинка всех кусков
```

`original/WallKickers.apk` — универсальный APK оригинала (с папкой `lib/`, не split).

## Что где

| Путь | Что |
|---|---|
| `build.py` | весь конвейер: apktool d → патчи → данные уровней → apktool b → подпись |
| `mod/hardcore.py` | сложность: веса кусков, быстрые (X) и двойные (D) варианты, CONFIG |
| `patches/java_manifest.py` | отвязка от Google Play Games/billing, переименование пакета |
| `patches/native_trampoline.py` | экспериментальный горизонтальный батут (arm64, выключен) |
| `tools/wkfmt.py` | парсер/писатель кусков уровня (scenarios) |
| `tools/levels.py` | парсер «рецептов» уровней (какие куски, веса, номера уровней) |
| `tools/render.py` | рисует куски в PNG для проверки |
| `keystore/wku.jks` | ключ подписи (одинаковый → обновления ставятся поверх) |

## Формат данных (кратко)

Кусок: `exit(x,y,dir)` + списки: walls(x,y1,y2), startWalls, endWalls, markers,
blocks(rect), flipping, spikes(x1,x2,y1,y2), conveyors(x,y1,y2,speed), coins,
flipSync(x,y1,y2,period,phase), electric(x,y1,y2,period,phase), crumbling,
bouncy(x,y1,y2,delay), pathWalls, spikeBalls(траектории), floors(x0,x1,y), …

Рецепт уровня: min/max номер уровня, флаг intro, кривая веса (2 точки),
требуемые intro, высота уровня, список (кусок, вес).
