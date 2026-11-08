#!/usr/bin/env bash
# Phases E–G acceptance test: POS sale (cash + change, stock, simplified invoice, 80 mm receipt), credit sale needs a
# customer, partial sales return (credit note + stock back), bank details on the invoice PDF, department role
# permissions, ERP reports (JSON/Excel/PDF), dashboard tiles and quick search.
#   BASE=... EMAIL=... PASSWORD=... STAFF_EMAIL=... STAFF_PASSWORD=... [ISSUE=1] deploy/smoke-erp.sh
set -euo pipefail
export PYTHONUTF8=1 PYTHONIOENCODING=utf-8
BASE=${BASE:-http://localhost:8080}
JAR=$(mktemp); SJAR=$(mktemp); trap 'rm -f "$JAR" "$SJAR"' EXIT
pass=0; fail=0
ok()  { echo "  PASS  $1"; pass=$((pass+1)); }
bad() { echo "  FAIL  $1"; fail=$((fail+1)); }
xsrf() { awk '$6=="XSRF-TOKEN"{print $7}' "$1" | tail -1; }
callj() { local jar=$1 m=$2 p=$3 d=${4:-}
  if [ -n "$d" ]; then curl -s -b "$jar" -c "$jar" -X "$m" -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(xsrf "$jar")" "$BASE$p" -d "$d"
  else curl -s -b "$jar" -c "$jar" -X "$m" -H "X-XSRF-TOKEN: $(xsrf "$jar")" "$BASE$p"; fi; }
call() { callj "$JAR" "$@"; }
code() { curl -s -o /dev/null -w '%{http_code}' -b "$1" -c "$1" -X "$2" -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(xsrf "$1")" "$BASE$3" ${4:+-d "$4"}; }
j()  { python -c "import sys,json; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }
near() { python -c "import sys; sys.exit(0 if abs(float(sys.argv[1])-float(sys.argv[2]))<0.02 else 1)" "$1" "$2"; }
login() { curl -s -c "$1" "$BASE/api/v1/auth/csrf" >/dev/null; callj "$1" POST /api/v1/auth/login "{\"email\":\"$2\",\"password\":\"$3\"}" >/dev/null; }

echo "== $BASE"
login "$JAR" "$EMAIL" "$PASSWORD"
call GET /api/v1/auth/me | grep -q '"role"' && ok login || { bad login; exit 1; }
S=$(date +%s)
WH=$(call GET /api/v1/warehouses | j 'd[0]["id"]')
P=$(call POST /api/v1/products "{\"name\":\"Carton box $S\",\"barcode\":\"629$S\",\"category\":\"Packing\",\"unitPrice\":20,\"trackStock\":true,\"costPrice\":12}" | j 'd["id"]')
call POST /api/v1/stock/adjust "{\"productId\":$P,\"warehouseId\":$WH,\"quantity\":10,\"opening\":true,\"unitCost\":12}" >/dev/null
call GET "/api/v1/pos/products?warehouseId=$WH&q=629$S" | j 'float(d[0]["onHand"])' | grep -q '^10.0$' && ok "POS grid finds item by barcode with stock 10" || bad "pos grid"

if [ "${ISSUE:-0}" = 1 ]; then
  SALE=$(call POST /api/v1/pos/sale "{\"warehouseId\":$WH,\"lines\":[{\"productId\":$P,\"quantity\":3}],\"payments\":[{\"method\":\"CASH\",\"amount\":100}]}")
  INV=$(echo "$SALE" | j 'd["invoiceId"]')
  near "$(echo "$SALE" | j 'd["total"]')" 69 && near "$(echo "$SALE" | j 'd["change"]')" 31 && ok "POS cash sale: 3 × 20 + VAT = 69.00, change 31.00 ($(echo "$SALE" | j 'd["number"]') $(echo "$SALE" | j 'd["status"]'))" || bad "sale $SALE"
  call GET "/api/v1/invoices/$INV" | j 'd["paymentStatus"]' | grep -q PAID && ok "POS invoice paid" || bad "pos paid"
  call GET "/api/v1/pos/products?warehouseId=$WH&q=629$S" | j 'float(d[0]["onHand"])' | grep -q '^7.0$' && ok "stock 10 → 7" || bad "pos stock"
  c=$(code "$JAR" GET "/api/v1/pos/receipt/$INV?tendered=100"); [ "$c" = 200 ] && ok "80 mm receipt" || bad "receipt $c"
  short=$(call POST /api/v1/pos/sale "{\"warehouseId\":$WH,\"lines\":[{\"productId\":$P,\"quantity\":50}],\"payments\":[{\"method\":\"CASH\",\"amount\":2000}]}")
  echo "$short" | grep -q insufficient_stock && ok "sale beyond stock refused" || bad "short: $short"
  cr=$(call POST /api/v1/pos/sale "{\"warehouseId\":$WH,\"lines\":[{\"productId\":$P,\"quantity\":1}],\"payments\":[]}")
  echo "$cr" | grep -q credit_needs_customer && ok "credit sale without customer refused" || bad "credit: $cr"

  RT=$(call GET "/api/v1/invoices/$INV/returns"); echo "$RT" | j 'float(d[0]["returnable"])' | grep -q '^3.0$' && ok "3 returnable" || bad "returnable $RT"
  R=$(call POST "/api/v1/invoices/$INV/returns" '{"reason":"Damaged box","issue":true,"lines":[{"lineIndex":0,"quantity":2}]}')
  near "$(echo "$R" | j 'd["total"]')" 46 && ok "partial return: credit note 46.00 ($(echo "$R" | j 'd["status"]'))" || bad "return $R"
  call GET "/api/v1/pos/products?warehouseId=$WH&q=629$S" | j 'float(d[0]["onHand"])' | grep -q '^9.0$' && ok "returned stock back (9)" || bad "return stock"
  over=$(call POST "/api/v1/invoices/$INV/returns" '{"reason":"x","lines":[{"lineIndex":0,"quantity":2}]}')
  echo "$over" | grep -q over_return && ok "returning more than sold refused" || bad "over-return: $over"

  call PUT /api/v1/settings/bank '{"bankName":"Saudi Awwal Bank (SAB)","bankAccountName":"BIP Gulf Logistics Services Co.","bankAccountNo":"012345678001","bankIban":"SA03 4500 0000 0123 4567 8001","invoiceFooter":"Riyadh head office - Ibn Mufleh St, Al Malaz"}' | j 'd["bankIban"]' | grep -q SA0345 && ok "bank details saved" || bad "bank"
  curl -s -b "$JAR" "$BASE/api/v1/invoices/$INV/pdf" -o /tmp/erp-inv-$S.pdf
  python -c "import sys,pypdf; t=''.join(p.extract_text() for p in pypdf.PdfReader(sys.argv[1]).pages).replace(' ',''); sys.exit(0 if 'SA0345000000012345678001' in t and 'only' in t.lower() else 1)" "/tmp/erp-inv-$S.pdf"     && ok "invoice PDF shows IBAN and amount in words" || bad "invoice pdf text"
fi

# --- department role ----------------------------------------------------------------------------
if [ -n "${STAFF_EMAIL:-}" ]; then
  ROLE=$(call POST /api/v1/settings/roles "{\"name\":\"Sales dept $S\",\"permissions\":{\"sales\":[\"create\"],\"inventory\":[\"view\"]}}")
  RID=$(echo "$ROLE" | j 'd["id"]'); echo "$ROLE" | j 'd["permissions"]["sales"]' | grep -q view && ok "role saved (view added to create)" || bad "role $ROLE"
  UID_=$(call GET /api/v1/users | python -c "import sys,json; print([u['id'] for u in json.load(sys.stdin) if u['email']=='$STAFF_EMAIL'][0])")
  call PUT "/api/v1/settings/users/$UID_/role" "{\"customRoleId\":$RID}" >/dev/null
  login "$SJAR" "$STAFF_EMAIL" "$STAFF_PASSWORD"
  [ "$(code "$SJAR" GET /api/v1/sales-orders)" = 200 ] && ok "sales role: can view sales orders" || bad "sales view"
  [ "$(code "$SJAR" GET /api/v1/products)" = 200 ] && ok "sales role: can view products" || bad "inventory view"
  [ "$(code "$SJAR" GET /api/v1/suppliers)" = 403 ] && ok "sales role: purchasing blocked" || bad "purchasing not blocked"
  [ "$(code "$SJAR" GET /api/v1/hr/employees)" = 403 ] && ok "sales role: HR blocked" || bad "hr not blocked"
  [ "$(code "$SJAR" POST /api/v1/warehouses '{"code":"X","name":"X"}')" = 403 ] && ok "sales role: inventory create blocked" || bad "inv create not blocked"
  callj "$SJAR" GET /api/v1/settings/my-permissions | j 'd["restricted"]' | grep -q True && ok "my-permissions reports restricted" || bad "my-permissions"
  call PUT "/api/v1/settings/users/$UID_/role" '{"customRoleId":null}' >/dev/null
  [ "$(code "$SJAR" GET /api/v1/suppliers)" = 200 ] && ok "role removed → access restored" || bad "restore"
  call DELETE "/api/v1/settings/roles/$RID" >/dev/null
fi

# --- reports, dashboard, search -----------------------------------------------------------------
for r in "sales?groupBy=day" "sales?groupBy=product" "sales?groupBy=customer" "stock" "purchases?groupBy=supplier" "expenses" "vat" "pnl" "receivables" "payables"; do
  name=${r%%\?*}; qs=${r#*\?}; [ "$qs" = "$r" ] && qs=""
  body=$(call GET "/api/v1/erp-reports/$name${qs:+?$qs}")
  echo "$body" | j 'd["title"]' >/dev/null 2>&1 && x=$(code "$JAR" GET "/api/v1/erp-reports/$name.xlsx${qs:+?$qs}") && p=$(code "$JAR" GET "/api/v1/erp-reports/$name.pdf${qs:+?$qs}") \
    && [ "$x" = 200 ] && [ "$p" = 200 ] && ok "report $r: $(echo "$body" | j 'len(d["rows"])') rows, Excel + PDF" || bad "report $r ($body)"
done
D=$(call GET /api/v1/erp-dashboard)
echo "$D" | j 'len(d["tiles"])' | grep -q 12 && echo "$D" | j 'len(d["salesTrend"])' | grep -q 7 && ok "dashboard: 12 tiles, 7-day trend, stock by warehouse" || bad "dashboard $D"
call GET "/api/v1/search?q=carton+box+$S" | j '[h["type"] for h in d]' | grep -q Product && ok "quick search finds the product" || bad "search"

echo "== $pass passed, $fail failed"
[ "$fail" = 0 ]
