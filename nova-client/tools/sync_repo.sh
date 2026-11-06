#!/bin/sh
# Mirror sources into the public repo (no keys, no build output, no game binaries). Commit/push separately.
set -e
W=$(cd "$(dirname "$0")/.." && pwd); R=${1:-/home/claude/geniiy827/0-14-marketplace/nova-client}
rsync -a --delete \
  --exclude out/ --exclude .cache/ --exclude java/build/ --exclude '__pycache__/' \
  --exclude '*.so' --exclude so_strings.txt --exclude ui_backup_before_scale/ --exclude jvmtest/ \
  --exclude assets_new/textures/ --exclude assets_new/textures_new/ --exclude '*.apk' \
  --exclude re014/ --exclude full.dis --exclude mega/ --exclude nukkit/ --exclude a13/ --exclude hooktest/ \
  --exclude enttest/ --exclude nettest2/ --exclude rtest/ --exclude uitest/ --exclude lighttest/ --exclude sky/ \
  --exclude jsonjava/ --exclude net/ --exclude sdk/ --exclude vid/ --exclude assets_new/nova/symbols.tsv \
  --exclude keys/ --exclude genisys/ --exclude raklib/ --exclude 'server/test/c/' --exclude '*.db' --exclude private.pem --exclude '*.bin' "$W/" "$R/"
cd "$R/.." && git add -A nova-client && git status --short | wc -l | xargs echo "changed files:"
