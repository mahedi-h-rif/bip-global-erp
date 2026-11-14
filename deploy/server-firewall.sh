#!/usr/bin/env bash
# Server firewall and brute-force protection (idempotent; run as root; deploy.sh runs it on every deploy).
#  1. ufw: deny all incoming except SSH (rate-limited), HTTP/HTTPS and the two portal ports.
#  2. DOCKER-USER: Docker-published ports skip ufw, so connection floods to the web ports are limited here:
#     per source IP at most CONN_RATE new connections a second (burst CONN_BURST) and CONN_MAX open at once.
#     The rules live in a systemd unit, so they come back after a reboot or a Docker restart.
#  3. fail2ban: bans an address for 1 hour after 5 failed SSH logins (Ubuntu 24.04 names the unit ssh.service).
set -euo pipefail
WEB_PORT=${WEB_PORT:-8088}
WEB_PORT_PROD=${WEB_PORT_PROD:-8089}
CONN_RATE=${CONN_RATE:-50}
CONN_BURST=${CONN_BURST:-200}
CONN_MAX=${CONN_MAX:-300}
IFACE=$(ip -o -4 route show default | awk '{print $5; exit}')

echo "== ufw"
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
# "limit" = allow, but refuse an address that opens 6+ SSH connections within 30 seconds.
ufw limit OpenSSH >/dev/null
for p in 80 443 "$WEB_PORT" "$WEB_PORT_PROD"; do ufw allow "$p"/tcp >/dev/null; done
ufw --force enable >/dev/null

echo "== connection limits for Docker ports ($IFACE)"
cat > /usr/local/sbin/bip-docker-limits <<EOF
#!/bin/sh
# Rebuilt from scratch each run. Only NEW connections arriving from the internet interface are counted.
iptables -N BIP-LIMITS 2>/dev/null || iptables -F BIP-LIMITS
iptables -A BIP-LIMITS -m connlimit --connlimit-above $CONN_MAX --connlimit-mask 32 -j DROP
iptables -A BIP-LIMITS -m hashlimit --hashlimit-name bip_new --hashlimit-mode srcip --hashlimit-above $CONN_RATE/sec --hashlimit-burst $CONN_BURST -j DROP
iptables -A BIP-LIMITS -j RETURN
iptables -N DOCKER-USER 2>/dev/null || true
iptables -C DOCKER-USER -i $IFACE -p tcp -m conntrack --ctstate NEW -j BIP-LIMITS 2>/dev/null \\
  || iptables -I DOCKER-USER 1 -i $IFACE -p tcp -m conntrack --ctstate NEW -j BIP-LIMITS
EOF
chmod 755 /usr/local/sbin/bip-docker-limits
cat > /etc/systemd/system/bip-docker-limits.service <<'EOF'
[Unit]
Description=Per-IP connection limits for Docker-published ports (BIP ERP)
After=docker.service
PartOf=docker.service

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/bip-docker-limits
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target docker.service
EOF
systemctl daemon-reload
systemctl enable bip-docker-limits.service >/dev/null 2>&1
systemctl restart bip-docker-limits.service

echo "== fail2ban"
cat > /etc/fail2ban/jail.d/sshd.local <<'EOF'
[sshd]
enabled = true
backend = systemd
journalmatch = _SYSTEMD_UNIT=ssh.service + _COMM=sshd
maxretry = 5
findtime = 10m
bantime = 1h
EOF
systemctl enable --now fail2ban >/dev/null
systemctl restart fail2ban

echo "== now"
ufw status | sed -n '1,8p'
iptables -S DOCKER-USER
iptables -S BIP-LIMITS
sleep 2; fail2ban-client status sshd | sed -n '2,4p'
