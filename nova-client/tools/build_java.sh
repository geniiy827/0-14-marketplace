#!/bin/sh
set -e
cd /home/claude/work/java
AJ=/usr/lib/android-sdk/platforms/android-23/android.jar
rm -rf build && mkdir -p build/stub build/classes
javac --release 8 -nowarn -d build/stub -cp $AJ stub/com/mojang/minecraftpe/MainActivity.java 2>&1 | grep -v -e JAVA_TOOL -e '^Note:' || true
javac --release 8 -Xlint:-options -encoding UTF-8 -d build/classes -cp $AJ:build/stub $(find src -name '*.java') 2>&1 | grep -v -e JAVA_TOOL -e '^Note:' || true
dalvik-exchange --dex --min-sdk-version=17 --output=build/classes2.dex build/classes 2>&1 | grep -v JAVA_TOOL || true
test -s build/classes2.dex
