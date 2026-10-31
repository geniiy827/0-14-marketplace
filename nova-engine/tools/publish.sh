#!/bin/sh
# build, copy into the repo (nova-engine/), commit, push to nova-client and check the remote has it.
# usage: tools/publish.sh "commit message"
set -e
cd "$(dirname "$0")/.."
./build.sh | tail -1
R=/home/claude/geniiy827/0-14-marketplace
rsync -a --delete --exclude build --exclude out ./ $R/nova-engine/
sed -i 's#../work/keys/#../nova-client/keys/#g' $R/nova-engine/build.sh
cp /home/claude/work/BACKLOG.md $R/nova-client/BACKLOG.md 2>/dev/null || true
cd $R
git add -A nova-engine nova-client/BACKLOG.md .github/workflows/nova-engine.yml
git commit -q -F - <<MSG
$1

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FPnhV5jz7Po2rdLDwVTQiZ
MSG
git push -q origin nova-client
git fetch -q origin nova-client:refs/remotes/origin/nova-client
L=$(git rev-parse HEAD); RM=$(git ls-remote origin nova-client | cut -f1)
[ "$L" = "$RM" ] && echo "pushed $L" || { echo "PUSH MISMATCH $L $RM"; exit 1; }
