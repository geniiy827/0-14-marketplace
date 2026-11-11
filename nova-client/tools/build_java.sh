#!/bin/sh
set -e
cd /home/claude/work/java
AJ=/usr/lib/android-sdk/platforms/android-23/android.jar
rm -rf build && mkdir -p build/stub build/classes
javac --release 8 -nowarn -d build/stub -cp $AJ stub/com/mojang/minecraftpe/MainActivity.java 2>&1 | grep -v -e JAVA_TOOL -e '^Note:' || true
# a compile error must stop the build (it used to go on and pack an APK without the code)
if ! javac --release 8 -Xlint:-options -encoding UTF-8 -d build/classes -cp $AJ:build/stub $(find src -name '*.java') > build/javac.log 2>&1; then
    grep -v -e JAVA_TOOL -e '^Note:' build/javac.log; echo "JAVA BUILD FAILED"; exit 1
fi
grep -v -e JAVA_TOOL -e '^Note:' build/javac.log || true
dalvik-exchange --dex --min-sdk-version=17 --output=build/classes2.dex build/classes 2>&1 | grep -v JAVA_TOOL || true
test -s build/classes2.dex
