#!/bin/sh
#
# Резервная копия магазина: база и фотографии товаров.
#
# Службу останавливать не нужно. База снимается командой SQLite «.backup» —
# она сама сводит журнал WAL и отдаёт согласованный файл. Простое копирование
# shop.db на ходу так не умеет: свежие заказы остаются в журнале, и копия
# уезжает почти пустой.
#
# Запускать по расписанию (install.sh ставит его в /etc/cron.d сам):
#   0 4 * * * root /opt/crm/shop/backup.sh
#
# Настраивается переменными окружения:
#   SHOP_DATA        папка с данными        (по умолчанию /opt/crm/shop/data)
#   SHOP_BACKUP_DIR  куда складывать копии  (по умолчанию /var/backups/crm-shop)
#   SHOP_BACKUP_KEEP сколько копий хранить  (по умолчанию 14)

set -eu

DATA_DIR=${SHOP_DATA:-/opt/crm/shop/data}
DEST=${SHOP_BACKUP_DIR:-/var/backups/crm-shop}
KEEP=${SHOP_BACKUP_KEEP:-14}

if [ ! -f "$DATA_DIR/shop.db" ]; then
    echo "backup: базы нет: $DATA_DIR/shop.db" >&2
    exit 1
fi

if ! command -v sqlite3 >/dev/null 2>&1; then
    echo "backup: нужен sqlite3 — поставьте: apt install -y sqlite3" >&2
    exit 1
fi

mkdir -p "$DEST"

# Копию собираем во временной папке: если место кончится или снимок базы
# сорвётся, в $DEST не останется обрезанного архива, который потом примут
# за рабочий.
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT INT TERM

sqlite3 "$DATA_DIR/shop.db" ".backup '$tmp/shop.db'"

stamp=$(date +%F_%H-%M)
archive="$DEST/crmshop-$stamp.tar.gz"

if [ -d "$DATA_DIR/uploads" ]; then
    tar czf "$tmp/archive.tar.gz" -C "$tmp" shop.db -C "$DATA_DIR" uploads
else
    tar czf "$tmp/archive.tar.gz" -C "$tmp" shop.db
fi
mv "$tmp/archive.tar.gz" "$archive"

# Старые копии удаляем, иначе за год они забьют весь диск (на сервере его 20 ГБ).
ls -1t "$DEST"/crmshop-*.tar.gz 2>/dev/null | tail -n +$((KEEP + 1)) | while read -r old; do
    rm -f "$old"
done

echo "backup: $archive ($(du -h "$archive" | cut -f1)), копий в $DEST: $(ls -1 "$DEST"/crmshop-*.tar.gz 2>/dev/null | wc -l)"
