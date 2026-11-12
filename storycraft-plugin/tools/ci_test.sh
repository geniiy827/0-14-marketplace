#!/usr/bin/env bash
# Проверка HTTP-части плагина без ядра (запускается в CI под PHP 7.0 — как на Genisys).
set -euo pipefail
PHP=${PHP:-php}
D=$(mktemp -d)
mkdir -p "$D/content/adventures"
# синтетическое приключение, упакованное в zip — проверяем и автораспаковку
W=$(mktemp -d)
mkdir -p "$W/test_adv/episodes/01_test/scenes"
echo '{"id":"test_adv","title":"Тест","description":"d","order":1,"accent":"#ff8a3a","cover":"cover.png"}' > "$W/test_adv/adventure.json"
head -c 2000 /dev/urandom > "$W/test_adv/cover.png"
echo '{"format":"storycraft-episode","version":1,"id":"01_test","number":1,"title":"Т","nodes":{},"scenes":{}}' > "$W/test_adv/episodes/01_test/episode.json"
head -c 300000 /dev/urandom > "$W/test_adv/episodes/01_test/scenes/a.glb"
python3 -c "print('story craft ' * 40000)" > "$W/test_adv/episodes/01_test/scenes/b.json"
(cd "$W" && zip -qr "$D/content/adventures/test_adv.zip" test_adv)
touch -d "1 minute ago" "$D/content/adventures/test_adv.zip"
$PHP "$(dirname "$0")/standalone.php" "$D" 18099 > "$D/log.txt" 2>&1 &
PID=$!
trap 'kill $PID 2>/dev/null || true' EXIT
sleep 4
cat "$D/log.txt"
B=http://127.0.0.1:18099
curl -sf $B/api/ping
echo
test -f "$D/content/adventures/test_adv.zip.done"
CAT=$(curl -sf $B/api/catalog)
echo "$CAT" | python3 -c "import json,sys;d=json.load(sys.stdin);e=d['adventures'][0]['episodes'][0];assert e['pack_size']>0;print('catalog ok, pack',e['pack_size'],'raw',e['size'])"
IDX=$(curl -sf $B/api/pack/test_adv/01_test/index)
python3 - "$B" "$IDX" <<'PY'
import sys, json, urllib.request, gzip, hashlib
B, idx = sys.argv[1], json.loads(sys.argv[2])
S = idx["pack_size"]; n = 5; step = (S + n - 1) // n; data = b""
for i in range(n):
    a = i * step; b = min(S, a + step) - 1
    r = urllib.request.Request(B + "/api/pack/test_adv/01_test", headers={"Range": "bytes=%d-%d" % (a, b)})
    resp = urllib.request.urlopen(r); assert resp.status == 206; data += resp.read()
assert len(data) == S
off = idx["data_offset"]
for f in idx["files"]:
    raw = gzip.decompress(data[off + f["offset"]: off + f["offset"] + f["zsize"]])
    assert hashlib.sha1(raw).hexdigest() == f["sha1"], f["path"]
print("pack ok:", len(idx["files"]), "files")
PY
TOK=$(curl -sf -X POST -d '{"device_id":"ci-device-0001","model":"ci","app_version":"1"}' $B/api/register | python3 -c "import json,sys;print(json.load(sys.stdin)['token'])")
curl -sf -H "X-Device: ci-device-0001" -H "X-Token: $TOK" -X POST -d '{"type":"complete","adventure":"test_adv","episode":"01_test","ending":"e","adv_vars":{"a":1}}' $B/api/progress > /dev/null
curl -sf -H "X-Device: ci-device-0001" -H "X-Token: $TOK" $B/api/progress | grep -q '"01_test"'
echo "progress ok"
test "$(curl -s -o /dev/null -w '%{http_code}' --path-as-is $B/files/test_adv/../../log.txt)" = "404"
echo "ALL OK on $($PHP -r 'echo PHP_VERSION;')"
