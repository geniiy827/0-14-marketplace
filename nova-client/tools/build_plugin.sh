#!/bin/sh
# NovaAccounts.phar for Genisys/PocketMine 0.14. The RSA private key comes from keys/ (never in the repo);
# its public half is assets/nova/account_pub.pem in the client.
set -e
W=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d)
cp -r "$W/server/NovaAccounts/." "$T/"
cp "$W/keys/nova_accounts_private.pem" "$T/resources/private.pem"
mkdir -p "$W/out"
rm -f "$W/out/NovaAccounts.phar"
php -d phar.readonly=0 -r '
$p = new Phar($argv[2]);
$p->setMetadata(["name" => "NovaAccounts", "version" => "1.0.0", "main" => "NovaAccounts\\Main", "api" => ["2.0.0"], "creationDate" => time()]);
$p->setStub("<?php __HALT_COMPILER();");
$p->buildFromDirectory($argv[1]);
$p->compressFiles(Phar::GZ);
' "$T" "$W/out/NovaAccounts.phar"
rm -rf "$T"
echo "plugin: $W/out/NovaAccounts.phar"
