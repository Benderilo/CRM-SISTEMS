#!/bin/bash
# Установка и обновление магазина CRM SISTEMS на Ubuntu / Debian.
#
# Обычно скрипт запускает само приложение: заливает по SSH бинарь crmshop,
# этот файл и install.env с настройками, затем выполняет его от root.
# Руками — так же:
#
#   scp crmshop install.sh backup.sh root@IP:/tmp/crmshop-upload/
#   ssh root@IP 'SLUG=shop DOMAIN=shop.example.ru bash /tmp/crmshop-upload/install.sh'
#
# Настройки — переменными окружения или строками в install.env рядом:
#   MODE         install (по умолчанию) | update — только заменить бинарь
#   SLUG         короткое имя магазина латиницей: shop, flowers, sklad-2.
#                На одном сервере можно держать несколько магазинов с разными SLUG.
#   DOMAIN       домен сайта (A-запись на IP сервера). Пусто — сайт по IP без HTTPS.
#   EMAIL        почта для Let's Encrypt (необязательно)
#   PUBLIC_PORT  внешний порт, если домена нет (по умолчанию 80)
#   ADMIN_LOGIN  логин панели /admin (по умолчанию admin)
#   ADMIN_PASS   пароль панели — применяется только при первой установке
#   SSH_PORT     порт SSH: его откроем в файрволе до включения ufw
#   SERVER_IP    внешний адрес сервера — для ссылки на сайт, если домена нет
#
# Приложение читает вывод построчно: «@@STEP текст» — новый шаг,
# «@@WARN текст» — предупреждение, «@@RESULT ключ=значение» — итог,
# «@@DONE» — всё прошло, «@@FAIL текст» — ошибка.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$HERE/install.env" ]; then
    # shellcheck disable=SC1091
    . "$HERE/install.env"
fi

MODE=${MODE:-install}
SLUG=${SLUG:-shop}
DOMAIN=${DOMAIN:-}
EMAIL=${EMAIL:-}
PUBLIC_PORT=${PUBLIC_PORT:-80}
ADMIN_LOGIN=${ADMIN_LOGIN:-admin}
ADMIN_PASS=${ADMIN_PASS:-}
SSH_PORT=${SSH_PORT:-22}
BIN_SRC=${BIN_SRC:-$HERE/crmshop}

APP="/opt/crm/$SLUG"
SVC="crm-$SLUG"
ENV_FILE="$APP/crmshop.env"
CONF="$APP/install.conf"

step() { echo "@@STEP $*"; }
warn() { echo "@@WARN $*"; }
fail() { echo "@@FAIL $*"; exit 1; }
result() { echo "@@RESULT $1=$2"; }

# install.env хранит пароль админки — после запуска он не нужен.
cleanup() { rm -f "$HERE/install.env"; }
trap cleanup EXIT
trap 'echo "@@FAIL Ошибка в строке $LINENO: $BASH_COMMAND"' ERR

[ "$(id -u)" -eq 0 ] || fail "Нужны права root (войдите как root или пользователем с sudo)"
echo "$SLUG" | grep -Eq '^[a-z0-9][a-z0-9-]{0,30}$' || fail "Имя магазина (SLUG) — латиница, цифры и дефис: $SLUG"
[ -f "$BIN_SRC" ] || fail "Нет файла сервера: $BIN_SRC"

# Ждём, пока apt освободит блокировку: на свежем VPS его часто держат
# автоматические обновления первые минуты после запуска.
APT="apt-get -y -q -o DPkg::Lock::Timeout=600"
export DEBIAN_FRONTEND=noninteractive

# ---------------------------------------------------------------- бинарь

install_binary() {
    step "Устанавливаю сервер магазина"
    mkdir -p "$APP"
    if [ -f "$APP/crmshop" ]; then
        # Прежний бинарь остаётся рядом — откат одним mv. Держим три последних.
        cp -a "$APP/crmshop" "$APP/crmshop.bak-$(date +%Y%m%d-%H%M%S)"
        ls -1 "$APP"/crmshop.bak* 2>/dev/null | sort | head -n -3 | xargs -r rm -f
    fi
    install -m 755 "$BIN_SRC" "$APP/crmshop.new"
    mv "$APP/crmshop.new" "$APP/crmshop"
}

wait_up() {
    local port=$1 i
    for i in $(seq 1 30); do
        if curl -s -o /dev/null "http://127.0.0.1:$port/"; then
            return 0
        fi
        sleep 1
    done
    journalctl -u "$SVC" -n 20 --no-pager || true
    fail "Сервер магазина не отвечает — журнал выше"
}

backup_now() {
    if [ -x "$APP/backup.sh" ] && [ -f "$APP/data/shop.db" ]; then
        SHOP_DATA="$APP/data" SHOP_BACKUP_DIR="/var/backups/crm-$SLUG" "$APP/backup.sh" || \
            warn "Резервная копия не снялась"
    fi
}

if [ "$MODE" = "update" ]; then
    [ -f "$CONF" ] || fail "Магазин $SLUG на этом сервере не установлен"
    # shellcheck disable=SC1090
    . "$CONF"
    step "Резервная копия базы перед обновлением"
    backup_now
    [ -f "$HERE/backup.sh" ] && install -m 755 "$HERE/backup.sh" "$APP/backup.sh"
    install_binary
    step "Перезапускаю службу"
    systemctl restart "$SVC"
    wait_up "$PORT"
    result url "$URL"
    result version "$("$APP/crmshop" -version 2>/dev/null || echo '?')"
    echo "@@DONE"
    exit 0
fi

# ---------------------------------------------------------------- установка

step "Проверяю систему"
. /etc/os-release
case "${ID:-}${ID_LIKE:-}" in
    *ubuntu*|*debian*) ;;
    *) fail "Поддерживаются Ubuntu и Debian, а здесь: ${PRETTY_NAME:-неизвестно}" ;;
esac
echo "$PRETTY_NAME, $(uname -m), свободно на диске: $(df -h / | awk 'NR==2{print $4}')"

# Без домена магазин занимает http://IP целиком — второй такой же на этом
# сервере встать не может. Проверяем до установки, чтобы не сломать nginx.
if [ -z "$DOMAIN" ]; then
    other=$(grep -ls "listen $PUBLIC_PORT default_server" /etc/nginx/sites-enabled/* 2>/dev/null | grep -v "/$SVC\$" | head -1 || true)
    if [ -n "$other" ]; then
        fail "Адрес http://IP уже занят другим сайтом ($(basename "$other")). Для второго магазина на этом сервере укажите домен — подойдёт и бесплатный вида 1-2-3-4.sslip.io"
    fi
fi

step "Ставлю пакеты (nginx, certbot, sqlite3, ufw) — пара минут"
$APT update >/dev/null
$APT install nginx certbot python3-certbot-nginx sqlite3 ufw curl openssl >/dev/null

step "Готовлю папки и пользователя службы"
# Новая ли это установка: от этого зависит, применится ли пароль панели
# (он задаётся только при первом запуске, дальше живёт в базе).
FRESH=0
[ -f "$APP/data/shop.db" ] || FRESH=1
id crmshop >/dev/null 2>&1 || useradd --system --home-dir /opt/crm --shell /usr/sbin/nologin crmshop
mkdir -p "$APP/data/uploads"
chown -R crmshop:crmshop "$APP/data"

# Порт сервиса: при переустановке — прежний, иначе первый свободный от 8100.
PORT=""
if [ -f "$ENV_FILE" ]; then
    PORT=$(sed -n 's/^SHOP_ADDR=127\.0\.0\.1://p' "$ENV_FILE" | head -1)
fi
if [ -z "$PORT" ]; then
    PORT=8100
    while ss -ltn "( sport = :$PORT )" | grep -q ":$PORT" || grep -rqs "SHOP_ADDR=127.0.0.1:$PORT\$" /opt/crm/*/crmshop.env; do
        PORT=$((PORT + 1))
    done
fi

install_binary
[ -f "$HERE/backup.sh" ] && install -m 755 "$HERE/backup.sh" "$APP/backup.sh"

step "Записываю настройки"
API_KEY=""
if [ -f "$ENV_FILE" ]; then
    API_KEY=$(sed -n 's/^SHOP_API_KEY=//p' "$ENV_FILE" | head -1)
fi
[ -n "$API_KEY" ] || API_KEY=$(openssl rand -hex 24)
# Без домена HTTPS нет — тогда cookie должны ходить и по http.
INSECURE=1
umask 077
{
    echo "SHOP_ADDR=127.0.0.1:$PORT"
    echo "SHOP_DB=$APP/data/shop.db"
    echo "SHOP_UPLOADS=$APP/data/uploads"
    echo "SHOP_ADMIN_LOGIN=$ADMIN_LOGIN"
    [ -n "$ADMIN_PASS" ] && echo "SHOP_ADMIN_PASS=$ADMIN_PASS"
    echo "SHOP_API_KEY=$API_KEY"
    echo "SHOP_INSECURE=$INSECURE"
} > "$ENV_FILE"
umask 022
chown root:crmshop "$ENV_FILE"
chmod 640 "$ENV_FILE"

step "Создаю службу $SVC"
cat > "/etc/systemd/system/$SVC.service" <<EOF
[Unit]
Description=Магазин CRM SISTEMS ($SLUG)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=crmshop
Group=crmshop
WorkingDirectory=$APP
EnvironmentFile=$ENV_FILE
ExecStart=$APP/crmshop
Restart=always
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=$APP/data
ProtectKernelTunables=true
ProtectControlGroups=true
RestrictSUIDSGID=true
StandardOutput=journal
StandardError=journal
SyslogIdentifier=$SVC

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable "$SVC" >/dev/null 2>&1
systemctl restart "$SVC"
wait_up "$PORT"

step "Настраиваю nginx"
NGX="/etc/nginx/sites-available/$SVC"
if [ -n "$DOMAIN" ]; then
    LISTEN="listen 80;
    listen [::]:80;"
    NAME="$DOMAIN"
else
    # Без домена сайт открывается по IP. На порту 80 стоит заглушка nginx
    # «Welcome» — убираем её, иначе она перехватит запросы.
    if [ "$PUBLIC_PORT" = "80" ] && [ -L /etc/nginx/sites-enabled/default ]; then
        rm -f /etc/nginx/sites-enabled/default
    fi
    LISTEN="listen $PUBLIC_PORT default_server;
    listen [::]:$PUBLIC_PORT default_server;"
    NAME="_"
fi
# Если certbot уже дописал сюда HTTPS — не затираем его блоки.
if ! grep -qs "managed by Certbot" "$NGX"; then
cat > "$NGX" <<EOF
# Магазин CRM SISTEMS ($SLUG). Файл создан install.sh.
server {
    $LISTEN
    server_name $NAME;

    client_max_body_size 64m;   # загрузка фото товаров из приложения

    location / {
        proxy_pass http://127.0.0.1:$PORT;
        proxy_http_version 1.1;
        proxy_set_header Host              \$host;
        proxy_set_header X-Real-IP         \$remote_addr;
        proxy_set_header X-Forwarded-For   \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 120s;
    }
}
EOF
fi
ln -sf "$NGX" "/etc/nginx/sites-enabled/$SVC"
nginx -t 2>&1 | tail -2
systemctl enable nginx >/dev/null 2>&1
systemctl reload nginx || systemctl restart nginx

step "Открываю порты в файрволе"
ufw allow "$SSH_PORT/tcp" >/dev/null
ufw allow 'Nginx Full' >/dev/null
[ -z "$DOMAIN" ] && ufw allow "$PUBLIC_PORT/tcp" >/dev/null
if ! ufw status | grep -q "Status: active"; then
    ufw --force enable >/dev/null
fi

# Адрес сервера: приложение присылает тот, по которому подключалось.
SERVER_IP=${SERVER_IP:-$(echo "${SSH_CONNECTION:-}" | awk '{print $3}')}
[ -n "$SERVER_IP" ] || SERVER_IP=$(hostname -I | awk '{print $1}')

URL=""
if [ -n "$DOMAIN" ]; then
    step "Получаю HTTPS-сертификат для $DOMAIN"
    RESOLVED=$(getent ahostsv4 "$DOMAIN" | awk 'NR==1{print $1}' || true)
    if [ -n "$RESOLVED" ] && [ "$RESOLVED" != "$SERVER_IP" ]; then
        warn "Домен $DOMAIN указывает на $RESOLVED, а сервер — $SERVER_IP. Проверьте A-запись."
    fi
    if [ -n "$EMAIL" ]; then MAILOPT="-m $EMAIL"; else MAILOPT="--register-unsafely-without-email"; fi
    # shellcheck disable=SC2086
    if certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos $MAILOPT --redirect >/tmp/certbot-$SLUG.log 2>&1; then
        URL="https://$DOMAIN"
        sed -i 's/^SHOP_INSECURE=.*/SHOP_INSECURE=/' "$ENV_FILE"
        systemctl restart "$SVC"
        wait_up "$PORT"
    else
        tail -5 "/tmp/certbot-$SLUG.log" || true
        warn "Сертификат не выдан — сайт пока работает по http. Когда домен заработает, запустите установку ещё раз."
        URL="http://$DOMAIN"
    fi
else
    if [ "$PUBLIC_PORT" = "80" ]; then URL="http://$SERVER_IP"; else URL="http://$SERVER_IP:$PUBLIC_PORT"; fi
fi

step "Ночные резервные копии"
cat > "/etc/cron.d/$SVC-backup" <<EOF
# Резервная копия магазина $SLUG каждый день в 4:00
0 4 * * * root SHOP_DATA=$APP/data SHOP_BACKUP_DIR=/var/backups/crm-$SLUG $APP/backup.sh >> /var/log/$SVC-backup.log 2>&1
EOF
chmod 644 "/etc/cron.d/$SVC-backup"

cat > "$CONF" <<EOF
PORT=$PORT
URL=$URL
DOMAIN=$DOMAIN
EOF

result url "$URL"
result key "$API_KEY"
result admin "$ADMIN_LOGIN"
result fresh "$FRESH"
result slug "$SLUG"
result version "$("$APP/crmshop" -version 2>/dev/null || echo '?')"
echo "@@DONE"
