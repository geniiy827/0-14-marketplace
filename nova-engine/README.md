# NOVA Engine

Свой движок на Java (OpenGL ES 3.0), без кода Mojang. Текстуры берутся из установленного на телефоне
Minecraft PE (NOVA или оригинал). План: `../nova-client/docs/ENGINE_PLAN.md`.

Сборка без Gradle: `sh build.sh` (javac --release 8, dx, aapt, zipalign, apksigner) -> out/NOVA-Engine-<VERSION>.apk

- `world/` — блоки (реестр), чанки 16x128x16, генератор
- `render/` — атлас, меши секций 16^3 (AO), отрисовка мира, HUD/шрифт
- `game/` — игрок (физика 20 TPS), управление, луч выбора блока, игровой цикл
- `mod/` — система модов: сама игра = мод `CoreContent`
