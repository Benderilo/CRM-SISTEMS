#!/bin/bash
# Что получилось после установки — смотрим изнутри CrmTest.
#   wsl -d CrmTest -u root -- bash test/check.sh [SLUG]
SLUG=${1:-shop}
echo "--- служба";      systemctl is-active crm-$SLUG nginx
echo "--- процесс от";  ps -o user= -C crmshop | sort -u
echo "--- порты";       ss -ltnp | grep -E 'nginx|crmshop|sshd' | awk '{print $4, $6}'
echo "--- install.conf"; cat /opt/crm/$SLUG/install.conf
echo "--- env (права)"; ls -l /opt/crm/$SLUG/crmshop.env
echo "--- ufw";         ufw status | head -8
echo "--- сайт изнутри"
curl -s --max-time 5 http://127.0.0.1/ | grep -o '<title>[^<]*</title>' | head -1
curl -s --max-time 5 -o /dev/null -w 'главная %{http_code}\n' http://127.0.0.1/
KEY=$(sed -n 's/^SHOP_API_KEY=//p' /opt/crm/$SLUG/crmshop.env)
curl -s --max-time 5 -H "X-API-Key: $KEY" http://127.0.0.1/api/v1/crm/ping; echo
curl -s --max-time 5 -o /dev/null -w 'чужой ключ %{http_code}\n' -H 'X-API-Key: nope' http://127.0.0.1/api/v1/crm/ping
echo "--- резервная копия"
SHOP_DATA=/opt/crm/$SLUG/data SHOP_BACKUP_DIR=/var/backups/crm-$SLUG /opt/crm/$SLUG/backup.sh
cat /etc/cron.d/crm-$SLUG-backup | tail -1
