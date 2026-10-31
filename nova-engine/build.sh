#!/bin/sh
# NOVA Engine: javac -> dx -> aapt -> zipalign -> apksigner (no Gradle needed)
set -e
cd "$(dirname "$0")"
AJ=/usr/lib/android-sdk/platforms/android-23/android.jar
V=$(cat VERSION 2>/dev/null || echo beta1)
OUT=out/NOVA-Engine-$V.apk
rm -rf build && mkdir -p build/classes out
javac --release 8 -Xlint:-options -encoding UTF-8 -d build/classes -cp $AJ $(find src -name '*.java') 2>&1 | grep -v -e JAVA_TOOL -e '^Note:' || true
ls build/classes/com/nova/engine/EngineActivity.class >/dev/null
dalvik-exchange --dex --min-sdk-version=21 --output=build/classes.dex build/classes 2>&1 | grep -v JAVA_TOOL || true
aapt package -f -M AndroidManifest.xml -S res -I $AJ -F build/unsigned.apk 2>&1 | grep -v JAVA_TOOL || true
(cd build && aapt add -f unsigned.apk classes.dex >/dev/null)
zipalign -p -f 4 build/unsigned.apk build/aligned.apk
apksigner sign --ks ../nova-client/keys/nova.jks --ks-pass file:../nova-client/keys/pass.txt --ks-key-alias nova --out $OUT build/aligned.apk 2>/dev/null
rm -f $OUT.idsig
apksigner verify $OUT 2>/dev/null && echo "APK $(pwd)/$OUT"
