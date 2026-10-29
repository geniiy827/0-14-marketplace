#!/bin/sh
# Rebuild NOVA test client: patched manifest + changed UI json -> aligned, signed APK
set -e
T0=$(date +%s)
V=$(cat VERSION 2>/dev/null || echo dev); APK=out/NOVA-0.14.3-$V.apk
W=/home/claude/work; cd $W
python3 -I tools/validate.py ui_new ui_orig /home/claude/apk/x/assets/images:/home/claude/work/assets_new/images
python3 -I tools/make_manifest.py /home/claude/apk/x/AndroidManifest.xml AndroidManifest.nova.xml > /dev/null
python3 -I tools/gen_entities.py /home/claude/apk/x/assets assets_new
python3 -I tools/nova_lang.py assets_new/loc
python3 -I tools/gen_intro_mesh.py intro/title.bbmodel assets_new/nova/intro
[ -f assets_new/nova/intro/sfx/hit.ogg ] || python3 -I tools/gen_intro_sfx.py assets_new/nova/intro/sfx
python3 -W ignore -I tools/gen_textures.py /home/claude/apk/x/assets /home/claude/jt/j/textures assets_new
python3 -I tools/gen_mcmod.py
sh tools/build_java.sh
python3 -I tools/so_patch.py /home/claude/apk/x/lib/armeabi-v7a/libminecraftpe.so libminecraftpe.patched.so
sh native/build.sh
args="AndroidManifest.xml=AndroidManifest.nova.xml classes2.dex=java/build/classes2.dex"
for f in $(cd assets_new && find . -type f | sed 's#^./##'); do args="$args assets/$f=assets_new/$f"; done
for f in ui_new/*.json; do b=$(basename $f); cmp -s $f ui_orig/$b || args="$args assets/ui/$b=$f"; done
args="$args lib/armeabi-v7a/libminecraftpe.so=libminecraftpe.patched.so lib/armeabi-v7a/libnova.so=native/libnova.so"
python3 -I tools/build_apk.py /home/claude/apk/mcpe.apk out/unsigned.apk $args $(cat entity_renames.txt texture_renames.txt)
zipalign -p -f 4 out/unsigned.apk out/aligned.apk
zipalign -c 4 out/aligned.apk
rm -f $APK*
apksigner sign --ks keys/nova.jks --ks-pass file:keys/pass.txt --ks-key-alias nova \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false \
  --out $APK out/aligned.apk 2>/dev/null
rm -f $APK.idsig
apksigner verify --min-sdk-version 17 $APK 2>/dev/null && echo SIGNED-OK
rm -f out/unsigned.apk out/aligned.apk
echo "APK $W/$APK ($(($(date +%s)-T0))s)"
