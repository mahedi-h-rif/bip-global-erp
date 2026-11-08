#!/usr/bin/env bash
# One-time (idempotent) server preparation for SoudaCore ERP. Runs as root on Ubuntu/Debian.
# Docker + compose, firewall (SSH, HTTP/S, the portal port), fail2ban, swap, unattended security updates,
# and a nightly database backup kept for 14 days.
set -euo pipefail
WEB_PORT=${WEB_PORT:-8088}
APP_DIR=/opt/bip-erp
export DEBIAN_FRONTEND=noninteractive

echo "== packages"
apt-get update -qq
apt-get install -y -qq ca-certificates curl ufw fail2ban unattended-upgrades >/dev/null

if ! command -v docker >/dev/null; then
  echo "== docker"
  curl -fsSL https://get.docker.com | sh >/dev/null
fi
systemctl enable --now docker >/dev/null
docker compose version >/dev/null

echo "== swap"
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile >/dev/null && swapon /swapfile
  grep -q /swapfile /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

echo "== firewall"
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw allow OpenSSH >/dev/null
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw allow "$WEB_PORT"/tcp >/dev/null
ufw --force enable >/dev/null
# Docker publishes ports through iptables and bypasses ufw; only "web" is published, Postgres never is.

echo "== fail2ban"
cat > /etc/fail2ban/jail.d/sshd.local <<'EOF'
[sshd]
enabled = true
maxretry = 5
bantime = 1h
EOF
systemctl enable --now fail2ban >/dev/null
systemctl restart fail2ban

echo "== unattended security updates"
dpkg-reconfigure -f noninteractive unattended-upgrades >/dev/null || true

echo "== backups"
mkdir -p "$APP_DIR/deploy/backups"
cat > /etc/cron.d/bip-erp-backup <<EOF
# Nightly dump of both ERP databases at 02:30 server time, 14 days kept.
30 2 * * * root cd $APP_DIR/deploy && for d in bip_uat bip_prod; do docker compose -p bip-erp exec -T db pg_dump -U erp -Fc \$d > backups/\$d-\$(date +\%F).dump; done; find backups -name '*.dump' -mtime +14 -delete
EOF

echo "== done"
docker --version
ufw status | head -12
