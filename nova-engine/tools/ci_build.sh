#!/bin/sh
# NOVA Engine build on GitHub Actions (ubuntu runner: Android SDK in $ANDROID_HOME, d8 instead of dx)
set -e
cd "$(dirname "$0")/.."
SDK=${ANDROID_HOME:-$ANDROID_SDK_ROOT}
AJ=$(ls -d $SDK/platforms/android-* | sort -V | tail -1)/android.jar
BT=$(ls -d $SDK/build-tools/* | sort -V | tail -1)
V=$(cat VERSION 2>/dev/null || echo dev)
OUT=out/NOVA-Engine-$V.apk
sed -i "s/\"NOVA Engine [^\"]*\"/\"NOVA Engine $V\"/" src/com/nova/engine/game/Game.java
rm -rf build && mkdir -p build/classes out
javac --release 8 -Xlint:-options -encoding UTF-8 -d build/classes -cp "$AJ" $(find src -name '*.java')
$BT/d8 --min-api 21 --lib "$AJ" --output build $(find build/classes -name '*.class')
$BT/aapt package -f -M AndroidManifest.xml -S res -A assets -I "$AJ" -F build/unsigned.apk
(cd build && $BT/aapt add -f unsigned.apk classes.dex >/dev/null)
$BT/zipalign -p -f 4 build/unsigned.apk build/aligned.apk
$BT/apksigner sign --ks ../nova-client/keys/nova.jks --ks-pass file:../nova-client/keys/pass.txt --ks-key-alias nova --out $OUT build/aligned.apk
echo "APK $OUT"
