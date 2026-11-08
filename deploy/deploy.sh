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
git log -1 --format='%H %s' | $SSH "cat > $APP_DIR/REVISION"

echo "== build + start (first build takes several minutes)"
$SSH "cd $APP_DIR/deploy && docker compose -p bip-erp --env-file clients/bip-gulf.env up -d --build --remove-orphans 2>&1 | tail -5"

echo "== health"
for i in $(seq 1 60); do
  if $SSH "curl -fs http://localhost:$WEB_PORT/api/v1/health >/dev/null"; then echo "healthy after $((i*5))s"; break; fi
  [ "$i" = 60 ] && { echo "NOT healthy"; $SSH "cd $APP_DIR/deploy && docker compose -p bip-erp logs --tail 60 api"; exit 1; }
  sleep 5
done
$SSH "cd $APP_DIR/deploy && docker compose -p bip-erp ps --format 'table {{.Service}}\t{{.Status}}'"

set -a; . "$ENV_FILE"; set +a
BASE=http://${HOST#*@}:$WEB_PORT
if [ "${RUN_SMOKE:-0}" = 1 ]; then
  echo "== full smoke test (creates test records)"
  BASE=$BASE EMAIL=$FATOORA_ADMIN_EMAIL PASSWORD=$FATOORA_ADMIN_PASSWORD ISSUE=${SMOKE_ISSUE:-0} bash deploy/smoke.sh
else
  echo "== login check"
  J=$(mktemp); x() { awk '$6=="XSRF-TOKEN"{print $7}' "$J" | tail -1; }
  curl -s -c "$J" "$BASE/api/v1/auth/csrf" >/dev/null
  curl -s -b "$J" -c "$J" -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(x)" "$BASE/api/v1/auth/login"     -d "{\"email\":\"$FATOORA_ADMIN_EMAIL\",\"password\":\"$FATOORA_ADMIN_PASSWORD\"}" | grep -q '"role":"ADMIN"'     && echo "admin login OK" || { echo "admin login FAILED"; exit 1; }
  # First deploy of a fresh database: brand it with the company logo.
  if ! curl -fs -o /dev/null "$BASE/api/v1/organization/logo"; then
    curl -s -b "$J" -X PUT -H "Content-Type: image/png" -H "X-XSRF-TOKEN: $(x)" --data-binary @deploy/assets/bip-logo.png \
      "$BASE/api/v1/organization/logo" | grep -q logoUrl && echo "logo uploaded"
  fi
  rm -f "$J"
fi
echo "== deployed: http://${HOST#*@}:$WEB_PORT"
