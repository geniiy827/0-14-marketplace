# NOVA client — MCPE 0.14.3 (исходники и инструменты)

Сборка: положить оригинальный APK в /home/claude/apk/mcpe.apk, распаковать в /home/claude/apk/x, затем `sh tools/build.sh`.
Нужны: python3 (capstone, keystone-engine, pyelftools, pillow, androguard), javac, android.jar (android-23),
dalvik-exchange (dx), zipalign, apksigner.

- ui_new/           — переписанные JSON UI (ui_orig/ — оригиналы для сравнения)
- assets_new/       — новые/изменённые картинки (иконка паков, spritesheet с кнопками)
- java/src/         — Java-слой клиента (classes2.dex): паки .mcres, LAN-синхронизация, меню, создание мира
- tools/so_patch.py — правки libminecraftpe.so (каждая проверяет исходные инструкции)
- tools/make_manifest.py, axml.py — сборка бинарного манифеста
- tools/build.sh    — полная сборка APK; validate.py — проверка JSON
- testpack/         — тестовый набор ресурсов (.mcres = zip этой папки)
- keys/nova.jks     — НЕ в репозитории (публичный). Ключ хранится в архиве NOVA-source.tar.gz; положите его в keys/ перед сборкой.
