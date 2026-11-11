#!/bin/sh
# Desktop run of every example mod against a fake game (mcmod/test/ModCheck.java): script errors show up here
# before they show up on a phone. usage: sh tools/check_mods.sh [mod ...]
set -e
W=/home/claude/work; cd $W
O=/tmp/nova_modcheck; rm -rf $O; mkdir -p $O
javac -nowarn -encoding UTF-8 -d $O $(find java/src/com/nova/client/mcmod -name '*.java') mcmod/test/ModCheck.java 2>&1 | grep -v -e JAVA_TOOL -e '^Note:' || true
test -f $O/ModCheck.class
bad=0
for m in ${@:-$(ls mcmod/examples)}; do
    [ -f mcmod/examples/$m/main.mcs ] || continue
    cmds=$(grep -o '"/[a-z0-9 ]*"' mcmod/examples/$m/main.mcs | tr -d '"' | sort -u | head -12 | sed 's/.*/"&"/' | tr '\n' ' ')
    eval java -cp $O ModCheck mcmod/prelude.mcs assets_new/nova/symbols.tsv mcmod/examples/$m $cmds 2>&1 | grep -v JAVA_TOOL > $O/$m.log || bad=1
    tail -1 $O/$m.log
    grep -n "main.mcs:\|Exception" $O/$m.log | head -8 || true
done
exit $bad
