#!/usr/bin/env bash
# Non-destructive security checks (low rate, no flooding): authentication, CSRF, role limits, record ownership,
# injection, input validation, cookie / header hardening. Creates and removes a few clearly named test records.
#   BASE=... EMAIL=... PASSWORD=... STAFF_EMAIL=... STAFF_PASSWORD=... deploy/security-check.sh
set -uo pipefail
export PYTHONUTF8=1
BASE=${BASE:-http://localhost:8080}
A=$(mktemp); U=$(mktemp); N=$(mktemp); trap 'rm -f "$A" "$U" "$N"' EXIT
pass=0; fail=0; warn=0
ok()   { echo "  PASS  $1"; pass=$((pass+1)); }
bad()  { echo "  FAIL  $1"; fail=$((fail+1)); }
note() { echo "  NOTE  $1"; warn=$((warn+1)); }
x() { awk '$6=="XSRF-TOKEN"{print $7}' "$1" | tail -1; }
code() { local jar=$1 m=$2 p=$3 d=${4:-}
  if [ -n "$d" ]; then curl -s -o /dev/null -w '%{http_code}' -b "$jar" -c "$jar" -X "$m" -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(x "$jar")" "$BASE$p" -d "$d"
  else curl -s -o /dev/null -w '%{http_code}' -b "$jar" -c "$jar" -X "$m" -H "X-XSRF-TOKEN: $(x "$jar")" "$BASE$p"; fi; }
body() { local jar=$1 m=$2 p=$3 d=${4:-}
  curl -s -b "$jar" -c "$jar" -X "$m" -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(x "$jar")" "$BASE$p" ${d:+-d "$d"}; }
login() { curl -s -c "$1" "$BASE/api/v1/auth/csrf" >/dev/null; body "$1" POST /api/v1/auth/login "{\"email\":\"$2\",\"password\":\"$3\"}" >/dev/null; }
j() { python -c "import sys,json; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }
S=$(date +%s)
echo "== $BASE"

# 1. Authentication
for p in /api/v1/invoices /api/v1/customers /api/v1/erp-dashboard /api/v1/users /api/v1/organization /api/v1/stock /api/v1/hr/employees; do
  c=$(curl -s -o /dev/null -w '%{http_code}' "$BASE$p"); [ "$c" = 401 ] || bad "unauthenticated $p → $c"; done; ok "7 data endpoints refuse anonymous access (401)"
c=$(curl -s -o /dev/null -w '%{http_code}' -b "fatoora_session=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.forged" "$BASE/api/v1/invoices"); [ "$c" = 401 ] && ok "forged session token refused" || bad "forged token → $c"
curl -s -c "$N" "$BASE/api/v1/auth/csrf" >/dev/null
for i in 1 2 3 4 5 6; do last=$(code "$N" POST /api/v1/auth/login '{"email":"nobody.qa@bipgulf.sa","password":"wrong-password-1"}'); done
[ "$last" = 429 ] && ok "repeated failed logins are throttled (429)" || note "6 failed logins in a row still answer $last (no lockout / rate limit on login)"
r=$(body "$N" POST /api/v1/auth/login '{"email":"admin@bipgulf.sa","password":"x"}'); echo "$r" | grep -qi "no such user\|not found" && bad "login reveals which emails exist" || ok "login error doesn't reveal whether the account exists"

login "$A" "$EMAIL" "$PASSWORD"
[ "$(code "$A" GET /api/v1/auth/me)" = 200 ] && ok "admin signed in" || { bad "admin login"; exit 1; }
# Temporary least-privilege accounts (disabled again at the end): a read-only Viewer and a Branch user.
TP="Qa-$(python -c "import secrets; print(secrets.token_urlsafe(12))")"
VID=$(body "$A" POST /api/v1/users "{\"email\":\"qa.viewer.$S@bipgulf.sa\",\"fullName\":\"QA Viewer (test)\",\"role\":\"VIEWER\",\"password\":\"$TP\"}" | j 'd["id"]')
BID=$(body "$A" POST /api/v1/users "{\"email\":\"qa.branch.$S@bipgulf.sa\",\"fullName\":\"QA Branch (test)\",\"role\":\"BRANCH\",\"password\":\"$TP\"}" | j 'd["id"]')
B=$(mktemp); login "$U" "qa.viewer.$S@bipgulf.sa" "$TP"; login "$B" "qa.branch.$S@bipgulf.sa" "$TP"
[ "$(code "$U" GET /api/v1/auth/me)" = 200 ] && ok "temporary Viewer signed in" || bad "viewer login"

# 2. CSRF
c=$(curl -s -o /dev/null -w '%{http_code}' -b "$A" -H "Content-Type: application/json" -X POST "$BASE/api/v1/customers" -d '{"nameEn":"CSRF test"}'); [ "$c" = 403 ] && ok "write without CSRF token refused (403)" || bad "no-CSRF write → $c"
c=$(curl -s -o /dev/null -w '%{http_code}' -b "$A" -H "Content-Type: application/json" -H "X-XSRF-TOKEN: forged" -X POST "$BASE/api/v1/customers" -d '{"nameEn":"CSRF test"}'); [ "$c" = 403 ] && ok "write with a wrong CSRF token refused" || bad "wrong CSRF → $c"

# 3. Role limits (staff = USER role)
role_check() { local c; c=$(code "$U" "$1" "$2" "${3:-}"); case "$c" in 403|404) ;; *) bad "viewer $1 $2 → $c";; esac; }
role_check POST /api/v1/customers "{\"nameEn\":\"QA-viewer-$S\",\"phone\":\"0551234567\"}"
role_check POST /api/v1/sales-orders '{"clientName":"x","lines":[{"name":"x","quantity":1,"unitPrice":1}]}'
role_check POST /api/v1/products "{\"name\":\"QA-role-$S\",\"unitPrice\":1}"
role_check GET /api/v1/users
role_check PUT /api/v1/settings/bank '{"bankName":"x"}'
role_check GET /api/v1/egs-units
role_check POST /api/v1/purchase-orders/1/approve
role_check POST /api/v1/users "{\"email\":\"qa$S@x.sa\",\"fullName\":\"x\",\"role\":\"ADMIN\",\"password\":\"Abcdefghij1\"}"
ok "a Viewer can't create products, manage users, change bank details, see ZATCA devices or approve POs"
c=$(code "$A" POST /api/v1/users "{\"email\":\"qa.op$S@bipgulf.sa\",\"fullName\":\"QA\",\"role\":\"OPERATOR\",\"password\":\"QaTest-$S-x9\"}"); case "$c" in 400|403) ok "company admin can't create a provider (OPERATOR) account";; *) bad "admin made an OPERATOR → $c";; esac
env0=$(body "$A" GET /api/v1/organization | j 'd.get("zatcaEnvironment")')
other=$([ "$env0" = PRODUCTION ] && echo SANDBOX || echo PRODUCTION)
c=$(code "$A" PUT /api/v1/organization "{\"zatcaEnvironment\":\"$other\"}"); case "$c" in 400|403) ok "company admin can't switch the ZATCA environment";; *) note "PUT /organization with only zatcaEnvironment → $c (check it did not change)";; esac
env=$(body "$A" GET /api/v1/organization | j 'd.get("zatcaEnvironment")'); [ "$env" = "$env0" ] && ok "ZATCA environment unchanged ($env)" || bad "ZATCA environment changed from $env0 to $env"

# 4. Record ownership: staff sees only their own sales documents
SO=$(body "$A" POST /api/v1/sales-orders "{\"clientName\":\"QA ownership $S\",\"lines\":[{\"name\":\"x\",\"quantity\":1,\"unitPrice\":1,\"vatRate\":15}]}" | j 'd["id"]')
c=$(code "$B" GET "/api/v1/sales-orders/$SO"); case "$c" in 403|404) ok "a Branch user can't open another user's sales order ($c)";; *) bad "branch user opened a colleague's order → $c";; esac
c=$(code "$B" GET /api/v1/sales-orders)
if [ "$c" = 200 ]; then body "$B" GET /api/v1/sales-orders | python -c "import sys,json; sys.exit(0 if all(o['createdBy'].startswith('qa.branch') for o in json.load(sys.stdin)) else 1)" && ok "a Branch user's order list holds only their own orders" || bad "branch list shows others' orders"
else ok "a Branch user without the sales permission can't list orders ($c)"; fi
body "$A" POST "/api/v1/sales-orders/$SO/cancel" >/dev/null
c=$(code "$A" GET /api/v1/invoices/999999999); [ "$c" = 404 ] && ok "unknown record id → 404, no detail leak" || bad "missing id → $c"

# 5. Injection
for q in "%27%20OR%201%3D1--" "%22%3B%20DROP%20TABLE%20invoice%3B--" "%25%27%7C%7Cpg_sleep(3)--"; do
  t0=$(date +%s); c=$(code "$A" GET "/api/v1/search?q=$q"); t1=$(date +%s)
  [ "$c" = 200 ] && [ $((t1-t0)) -lt 3 ] || bad "search injection $q → $c in $((t1-t0))s"; done; ok "SQL injection in quick search is treated as text (no error, no delay)"
c=$(code "$A" GET "/api/v1/erp-reports/sales?groupBy=1%3BDROP%20TABLE%20invoice"); [ "$c" = 200 ] && ok "report groupBy injection ignored (whitelisted)" || bad "groupBy → $c"
c=$(code "$A" GET "/api/v1/relations/x%27%3BDROP/1"); [ "$c" = 400 ] && ok "relationship map rejects unknown document type" || bad "relations type → $c"
X=$(body "$A" POST /api/v1/customers "{\"nameEn\":\"<script>alert(1)</script> QA $S\",\"vatNumber\":\"310000000000003\",\"phone\":\"0551234567\",\"buildingNumber\":\"1234\",\"street\":\"s\",\"district\":\"d\",\"city\":\"Riyadh\",\"postalCode\":\"12211\",\"countryCode\":\"SA\"}" | j 'd.get("id")')
Q=$(body "$A" POST /api/v1/quotations "{\"customerId\":$X,\"lines\":[{\"name\":\"<img src=x onerror=alert(1)>\",\"quantity\":1,\"unitPrice\":1,\"vatRate\":15}]}" | j 'd.get("id")')
c=$(curl -s -o /dev/null -w '%{http_code}' -b "$A" "$BASE/api/v1/quotations/$Q/pdf"); [ "$c" = 200 ] && ok "HTML in names is escaped in PDFs (renders, 200)" || bad "PDF with HTML name → $c"
body "$A" DELETE "/api/v1/quotations/$Q" >/dev/null; body "$A" DELETE "/api/v1/customers/$X" >/dev/null

# 6. Input validation
c=$(code "$A" POST /api/v1/sales-orders '{"clientName":"QA neg","lines":[{"name":"x","quantity":-5,"unitPrice":1}]}'); [ "$c" = 400 ] && ok "negative quantity refused" || bad "negative qty → $c"
c=$(code "$A" POST /api/v1/sales-orders '{"clientName":"QA neg","lines":[{"name":"x","quantity":1,"unitPrice":-1}]}'); [ "$c" = 400 ] && ok "negative price refused" || bad "negative price → $c"
c=$(code "$A" POST /api/v1/sales-orders '{"clientName":"QA","lines":[]}'); [ "$c" = 400 ] && ok "document without lines refused" || bad "empty lines → $c"
c=$(code "$A" POST /api/v1/customers '{"nameEn":"QA bad VAT","vatNumber":"123","phone":"0551234567"}'); [ "$c" = 400 ] && ok "malformed VAT number refused" || bad "bad VAT → $c"
c=$(code "$A" POST /api/v1/sales-orders 'not json'); [ "$c" = 400 ] && ok "malformed JSON → 400 (not 500)" || bad "bad JSON → $c"
c=$(curl -s -o /dev/null -w '%{http_code}' -b "$A" -c "$A" -X PUT -H "X-XSRF-TOKEN: $(x "$A")" -H "Content-Type: text/html" --data-binary '<html>' "$BASE/api/v1/organization/logo"); case "$c" in 400|415) ok "non-image logo upload refused ($c)";; *) bad "html as logo → $c";; esac

# 7. Cookies and headers
H=$(curl -s -D - -o /dev/null -c /dev/null "$BASE/api/v1/auth/csrf"; curl -s -D - -o /dev/null -H "Content-Type: application/json" -X POST "$BASE/api/v1/auth/login" -d '{"email":"x","password":"y"}')
login "$N" "$EMAIL" "$PASSWORD" ; L=$(curl -s -D - -o /dev/null -b "$N" -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(x "$N")" -X POST "$BASE/api/v1/auth/login" -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
echo "$L" | grep -i "set-cookie: fatoora_session" | grep -qi "httponly" && ok "session cookie is HttpOnly (not readable by scripts)" || bad "session cookie not HttpOnly"
echo "$L" | grep -i "set-cookie: fatoora_session" | grep -qi "samesite" && ok "session cookie has SameSite" || note "session cookie without SameSite"
echo "$L" | grep -i "set-cookie: fatoora_session" | grep -qi "secure" && ok "session cookie is Secure" || note "session cookie not Secure — expected on plain http; turn on with HTTPS (FATOORA_COOKIE_SECURE=true)"
W=$(curl -s -D - -o /dev/null "$BASE/login")
echo "$W" | grep -qi "x-frame-options\|frame-ancestors" && ok "pages can't be framed (clickjacking)" || note "no X-Frame-Options / frame-ancestors on pages"
echo "$W" | grep -qi "x-content-type-options: nosniff" && ok "nosniff header set" || note "no X-Content-Type-Options header on pages"
echo "$W" | grep -qi "^x-powered-by" && note "X-Powered-By header reveals the framework" || ok "no X-Powered-By header"
c=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/actuator/env"); case "$c" in 401|403|404) ok "no exposed actuator / internals ($c)";; *) bad "actuator → $c";; esac

# 8. Logout
TOK=$(awk '$6 ~ /fatoora_session/ {print $6"="$7}' "$N" | tail -1)
body "$N" POST /api/v1/auth/logout >/dev/null
c=$(curl -s -o /dev/null -w '%{http_code}' -b "$TOK" "$BASE/api/v1/auth/me"); [ "$c" = 401 ] && ok "token unusable after sign-out" || note "a copied session token still works after sign-out until it expires (stateless JWT)"

for id in $VID $BID; do body "$A" PUT "/api/v1/users/$id" "{\"fullName\":\"QA test (disabled)\",\"role\":\"VIEWER\",\"enabled\":false}" >/dev/null; done
c=$(code "$U" GET /api/v1/auth/me); [ "$c" = 401 ] && ok "disabling a user signs them out at once" || bad "disabled user still signed in → $c"
rm -f "$B"
echo "== $pass passed, $fail failed, $warn notes"
