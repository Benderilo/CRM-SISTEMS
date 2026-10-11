#!/bin/bash
# Установка магазина в WSL-дистрибутив CrmTest теми же файлами, что лежат в APK
# (app/build/generated/shopServer — собираются задачей buildShopServer).
#
#   wsl -d CrmTest -u root -- bash test/install-local.sh [install|update] [SLUG] [DOMAIN]
set -euo pipefail
cd "$(dirname "$0")"
. ./crmtest.env

MODE=${1:-install}
SLUG=${2:-shop}
DOMAIN=${3:-}
G=../app/build/generated/shopServer/server

D=$(mktemp -d /tmp/crmshop-upload.XXXXXX)
gunzip -c < "$G/crmshop-amd64.pack" > "$D/crmshop"
chmod 755 "$D/crmshop"
cp "$G/install.sh" "$G/backup.sh" "$D/"
cat > "$D/install.env" <<EOF
MODE=$MODE
SLUG=$SLUG
DOMAIN=$DOMAIN
ADMIN_LOGIN=admin
ADMIN_PASS=$CRMTEST_PASS
SSH_PORT=$CRMTEST_SSH_PORT
SERVER_IP=127.0.0.1
EOF
chmod 600 "$D/install.env"
rc=0
bash "$D/install.sh" || rc=$?
rm -rf "$D"
echo "exit=$rc"
exit $rc
