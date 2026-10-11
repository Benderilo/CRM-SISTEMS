#!/bin/bash
# Убрать магазины из CrmTest, чтобы проверить установку с нуля.
#   wsl -d CrmTest -u root -- bash test/uninstall-local.sh shop [second ...]
for SLUG in "$@"; do
    systemctl disable --now "crm-$SLUG" 2>/dev/null
    rm -f "/etc/systemd/system/crm-$SLUG.service" "/etc/nginx/sites-enabled/crm-$SLUG" \
          "/etc/nginx/sites-available/crm-$SLUG" "/etc/cron.d/crm-$SLUG-backup"
    rm -rf "/opt/crm/$SLUG" "/var/backups/crm-$SLUG"
    echo "убран $SLUG"
done
systemctl daemon-reload
systemctl reload nginx 2>/dev/null || true
