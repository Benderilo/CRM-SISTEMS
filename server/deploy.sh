#!/bin/sh
# Обновление магазина с компьютера разработчика — то же, что кнопка
# «Обновить сервер» в приложении:
#
#   sh server/deploy.sh root@IP [SLUG]      # SLUG по умолчанию shop
#
# Чтобы не вводить пароль: ssh-copy-id root@IP
set -e

HOST="${1:?укажите сервер: sh deploy.sh root@IP [SLUG]}"
SLUG="${2:-shop}"
cd "$(dirname "$0")"

VERSION="$(date +%Y.%m.%d-%H%M)"
echo "==> Собираю crmshop $VERSION"
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags="-s -w -X main.version=$VERSION" -o crmshop .

echo "==> Копирую на $HOST"
ssh "$HOST" 'mkdir -p /tmp/crmshop-upload'
scp crmshop install.sh backup.sh "$HOST:/tmp/crmshop-upload/"

echo "==> Обновляю магазин $SLUG"
ssh "$HOST" "MODE=update SLUG=$SLUG bash /tmp/crmshop-upload/install.sh"
