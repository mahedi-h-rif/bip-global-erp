#!/usr/bin/env bash
# One-command deploy of the committed code to the BIP server.
#   deploy/deploy.sh            → build + start, wait for health, smoke test
#   deploy/deploy.sh bootstrap  → also prepare the server first (Docker, firewall, backups)
# Ships `git archive HEAD` (only committed code), plus the env file, which stays out of git.
set -euo pipefail
cd "$(dirname "$0")/.."

HOST=${DEPLOY_HOST:-root@2.24.60.124}
KEY=${DEPLOY_KEY:-$HOME/.ssh/bip_erp}
ENV_FILE=deploy/clients/bip-gulf.env
APP_DIR=/opt/bip-erp
SSH="ssh -i $KEY -o StrictHostKeyChecking=accept-new -o BatchMode=yes $HOST"

[ -f "$ENV_FILE" ] || { echo "missing $ENV_FILE"; exit 1; }
[ -z "$(git status --porcelain -- backend frontend whatsapp deploy ':!deploy/clients')" ] || echo "warning: uncommitted changes are NOT deployed"
WEB_PORT=$(grep -E '^WEB_PORT=' "$ENV_FILE" | cut -d= -f2 | tr -d '"')
WEB_PORT=${WEB_PORT:-8088}

echo "== $(git log -1 --format='%h %s') → $HOST"
$SSH "mkdir -p $APP_DIR"
if [ "${1:-}" = bootstrap ]; then
  $SSH "WEB_PORT=$WEB_PORT bash -s" < deploy/server-bootstrap.sh
fi

echo "== upload"
git archive --format=tar HEAD | $SSH "mkdir -p $APP_DIR/new && tar -x -C $APP_DIR/new"
$SSH "set -e; cd $APP_DIR; for d in backend frontend whatsapp docs; do rm -rf \$d; mv new/\$d .; done;
      mkdir -p deploy/clients deploy/backups; cp -r new/deploy/. deploy/; rm -rf new; chmod +x deploy/*.sh deploy/db-init/*.sh"
cat "$ENV_FILE" | $SSH "cat > $APP_DIR/deploy/clients/bip-gulf.env && chmod 600 $APP_DIR/deploy/clients/bip-gulf.env"
PROD_ENV=deploy/clients/bip-gulf-prod.env
if [ -f "$PROD_ENV" ]; then
  cat "$PROD_ENV" | $SSH "cat > $APP_DIR/deploy/clients/bip-gulf-prod.env && chmod 600 $APP_DIR/deploy/clients/bip-gulf-prod.env"
  WEB_PORT_PROD=$(grep -E '^WEB_PORT_PROD=' "$ENV_FILE" | cut -d= -f2 | tr -d '"'); WEB_PORT_PROD=${WEB_PORT_PROD:-8089}
  $SSH "ufw allow $WEB_PORT_PROD/tcp >/dev/null 2>&1 || true"
fi
git log -1 --format='%H %s' | $SSH "cat > $APP_DIR/REVISION"

echo "== build + start (first build takes several minutes)"
$SSH "cd $APP_DIR/deploy && docker compose -p bip-erp --env-file clients/bip-gulf.env up -d --build --remove-orphans 2>&1 | tail -5"
# WhatsApp is turned off for now: stop the container left from earlier deploys (its login data volume is kept).
$SSH "docker rm -f bip-erp-whatsapp-1 >/dev/null 2>&1 || true"

echo "== health"
for port in $WEB_PORT ${WEB_PORT_PROD:-}; do
  for i in $(seq 1 60); do
    if $SSH "curl -fs http://localhost:$port/api/v1/health >/dev/null"; then echo ":$port healthy after $((i*5))s"; break; fi
    [ "$i" = 60 ] && { echo ":$port NOT healthy"; $SSH "cd $APP_DIR/deploy && docker compose -p bip-erp logs --tail 60 api api-prod"; exit 1; }
    sleep 5
  done
done
$SSH "cd $APP_DIR/deploy && docker compose -p bip-erp ps --format 'table {{.Service}}\t{{.Status}}'"

set -a; . "$ENV_FILE"; set +a
BASE=http://${HOST#*@}:$WEB_PORT
if [ "${RUN_SMOKE:-0}" = 1 ]; then
  echo "== full smoke test (creates test records)"
  BASE=$BASE EMAIL=$FATOORA_ADMIN_EMAIL PASSWORD=$FATOORA_ADMIN_PASSWORD ISSUE=${SMOKE_ISSUE:-0} bash deploy/smoke.sh
else
  # Login check + first-deploy logo, for UAT and (when present) production.
  check() { local base=$1 email=$2 password=$3
    J=$(mktemp); x() { awk '$6=="XSRF-TOKEN"{print $7}' "$J" | tail -1; }
    curl -s -c "$J" "$base/api/v1/auth/csrf" >/dev/null
    curl -s -b "$J" -c "$J" -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(x)" "$base/api/v1/auth/login"       -d "{\"email\":\"$email\",\"password\":\"$password\"}" | grep -q '"role":"ADMIN"' && echo "$base admin login OK" || { echo "$base admin login FAILED"; exit 1; }
    if ! curl -fs -o /dev/null "$base/api/v1/organization/logo"; then
      curl -s -b "$J" -X PUT -H "Content-Type: image/png" -H "X-XSRF-TOKEN: $(x)" --data-binary @deploy/assets/bip-logo.png         "$base/api/v1/organization/logo" | grep -q logoUrl && echo "$base logo uploaded"
    fi
    rm -f "$J"
  }
  check "$BASE" "$FATOORA_ADMIN_EMAIL" "$FATOORA_ADMIN_PASSWORD"
  if [ -n "${WEB_PORT_PROD:-}" ]; then
    PROD_PASSWORD=$(grep -E '^FATOORA_ADMIN_PASSWORD=' "$PROD_ENV" | cut -d= -f2-)
    check "http://${HOST#*@}:$WEB_PORT_PROD" "$FATOORA_ADMIN_EMAIL" "$PROD_PASSWORD"
  fi
fi
echo "== deployed: UAT http://${HOST#*@}:$WEB_PORT${WEB_PORT_PROD:+ · PRODUCTION http://${HOST#*@}:$WEB_PORT_PROD}"
