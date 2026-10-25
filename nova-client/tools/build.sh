#!/bin/sh
# Rebuild NOVA test client: patched manifest + changed UI json -> aligned, signed APK
set -e
W=/home/claude/work; cd $W
python3 -I tools/validate.py ui_new ui_orig /home/claude/apk/x/assets/images:/home/claude/work/assets_new/images
python3 -I tools/make_manifest.py /home/claude/apk/x/AndroidManifest.xml AndroidManifest.nova.xml > /dev/null
sh tools/build_java.sh
args="AndroidManifest.xml=AndroidManifest.nova.xml classes2.dex=java/build/classes2.dex"
for f in $(cd assets_new && find . -type f | sed 's#^./##'); do args="$args assets/$f=assets_new/$f"; done
for f in ui_new/*.json; do b=$(basename $f); cmp -s $f ui_orig/$b || args="$args assets/ui/$b=$f"; done
python3 -I tools/so_patch.py /home/claude/apk/x/lib/armeabi-v7a/libminecraftpe.so libminecraftpe.patched.so
args="$args lib/armeabi-v7a/libminecraftpe.so=libminecraftpe.patched.so"
python3 -I tools/build_apk.py /home/claude/apk/mcpe.apk out/unsigned.apk $args
zipalign -p -f 4 out/unsigned.apk out/aligned.apk
zipalign -c 4 out/aligned.apk
rm -f out/NOVA-0.14.3-beta4.apk*
apksigner sign --ks keys/nova.jks --ks-pass env:NOVA_KS_PASS --ks-key-alias nova \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false \
  --out out/NOVA-0.14.3-beta4.apk out/aligned.apk 2>/dev/null
rm -f out/NOVA-0.14.3-beta4.apk.idsig
apksigner verify --min-sdk-version 17 out/NOVA-0.14.3-beta4.apk 2>/dev/null && echo SIGNED-OK
