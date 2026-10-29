#!/bin/sh
# Mirror sources into the public repo (no keys, no build output, no game binaries). Commit/push separately.
set -e
W=$(cd "$(dirname "$0")/.." && pwd); R=${1:-/home/claude/geniiy827/0-14-marketplace/nova-client}
rsync -a --delete \
  --exclude out/ --exclude .cache/ --exclude java/build/ --exclude '__pycache__/' \
  --exclude '*.so' --exclude so_strings.txt --exclude ui_backup_before_scale/ --exclude jvmtest/ \
  --exclude assets_new/textures/ --exclude assets_new/textures_new/ --exclude '*.apk' "$W/" "$R/"
cd "$R/.." && git add -A nova-client && git status --short | wc -l | xargs echo "changed files:"
