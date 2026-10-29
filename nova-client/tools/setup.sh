#!/bin/sh
# Fresh container -> ready to build NOVA. Needs (not in the public repo): /home/claude/apk/mcpe.apk (original 0.14.3),
# keys/nova.jks (signing key), /home/claude/jt/j/textures (Java 1.19.4 textures for "new textures").
set -e
W=$(cd "$(dirname "$0")/.." && pwd)
command -v clang-18 >/dev/null && command -v apksigner >/dev/null && command -v dalvik-exchange >/dev/null || \
  (apt-get update -qq && apt-get install -y -qq clang-18 lld-18 rsync zipalign apksigner dalvik-exchange android-sdk-platform-23 default-jdk-headless binutils >/dev/null)
python3 -c "import capstone, keystone, elftools, PIL" 2>/dev/null || \
  pip install -q --break-system-packages capstone keystone-engine pyelftools pillow androguard
[ -d /home/claude/apk/x ] || { [ -f /home/claude/apk/mcpe.apk ] && mkdir -p /home/claude/apk/x && (cd /home/claude/apk/x && unzip -q ../mcpe.apk); }
printf '#!/bin/sh\nexec python3 %s/tools/nova.py "$@"\n' "$W" > /usr/local/bin/nova && chmod +x /usr/local/bin/nova
[ -f /home/claude/apk/x/lib/armeabi-v7a/libminecraftpe.so ] && nova index >/dev/null && echo "nova: index ok"
[ -f "$W/keys/nova.jks" ] || echo "WARN: keys/nova.jks missing (needed to sign updates)"
echo "setup ok"
