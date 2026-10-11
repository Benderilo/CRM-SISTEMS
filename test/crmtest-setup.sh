#!/bin/bash
# Превращает свежий WSL-дистрибутив CrmTest в подобие голого VPS:
# SSH на порту 2222 с входом root по паролю — как у хостинга после покупки.
# Пароль берётся из test/crmtest.env (в git не попадает).
#
#   wsl -d CrmTest -u root -- bash test/crmtest-setup.sh
set -euo pipefail
cd "$(dirname "$0")"
. ./crmtest.env

export DEBIAN_FRONTEND=noninteractive
apt-get update -q >/dev/null
apt-get install -y -q openssh-server >/dev/null

echo "root:$CRMTEST_PASS" | chpasswd
cat > /etc/ssh/sshd_config.d/crmtest.conf <<EOF
Port $CRMTEST_SSH_PORT
PermitRootLogin yes
PasswordAuthentication yes
KbdInteractiveAuthentication yes
EOF
# В Ubuntu 24.04 sshd запускается через ssh.socket и порт берёт оттуда.
systemctl disable --now ssh.socket >/dev/null 2>&1 || true
systemctl enable ssh >/dev/null 2>&1
systemctl restart ssh
# Особенность WSL в режиме mirrored: обращения к localhost (и с Windows,
# и из эмулятора Android) приходят через интерфейс loopback0, а не lo — ufw,
# который включает install.sh, их бы резал. На настоящем VPS такого нет.
apt-get install -y -q ufw >/dev/null
ufw allow in on loopback0 >/dev/null
sleep 1
ss -ltnp | grep ":$CRMTEST_SSH_PORT" && echo "sshd готов на порту $CRMTEST_SSH_PORT"
