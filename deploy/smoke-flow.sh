#!/usr/bin/env bash
# Phase A acceptance test: Quotation → Sales Order → partial Deliveries → Invoice from deliveries → Receipt,
# plus the relationship map. ISSUE=1 also issues invoices (needs an ACTIVE ZATCA device for the company VAT).
#   BASE=http://localhost:8080 EMAIL=... PASSWORD=... [ISSUE=1] deploy/smoke-flow.sh
set -euo pipefail
# Python on Windows would read stdin as cp1252 and mangle Arabic text in the bodies.
export PYTHONUTF8=1 PYTHONIOENCODING=utf-8
BASE=${BASE:-http://localhost:8080}
JAR=$(mktemp); trap 'rm -f "$JAR"' EXIT
pass=0; fail=0
ok()  { echo "  PASS  $1"; pass=$((pass+1)); }
bad() { echo "  FAIL  $1"; fail=$((fail+1)); }
xsrf() { awk '$6=="XSRF-TOKEN"{print $7}' "$JAR" | tail -1; }
call() { local m=$1 p=$2 d=${3:-}
  if [ -n "$d" ]; then curl -s -b "$JAR" -c "$JAR" -X "$m" -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(xsrf)" "$BASE$p" -d "$d"
  else curl -s -b "$JAR" -c "$JAR" -X "$m" -H "X-XSRF-TOKEN: $(xsrf)" "$BASE$p"; fi; }
j()  { python -c "import sys,json; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }
# Edit a JSON document with a Python expression over d, print the result.
jx() { python -c "import sys,json; d=json.load(sys.stdin); exec(sys.argv[1]); print(json.dumps(d))" "$1"; }
qty() { call GET "/api/v1/stock?q=$1" | j 'sum(float(r["quantity"]) for r in d)'; }

echo "== $BASE"
curl -s -c "$JAR" "$BASE/api/v1/auth/csrf" >/dev/null
call POST /api/v1/auth/login "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" >/dev/null
call GET /api/v1/auth/me | grep -q '"role"' && ok login || { bad login; exit 1; }

S=$(date +%s)
C=$(call POST /api/v1/customers "{\"nameAr\":\"شركة التمور $S\",\"nameEn\":\"Dates Factory $S\",\"vatNumber\":\"300000000000003\",\"street\":\"Southern Ring Rd\",\"buildingNumber\":\"2335\",\"district\":\"Al Aziziyah\",\"city\":\"Riyadh\",\"postalCode\":\"14512\",\"countryCode\":\"SA\",\"phone\":\"0500000000\"}" | j 'd["id"]') && ok "customer $C"
P=$(call POST /api/v1/products "{\"name\":\"Shrink film $S\",\"unitPrice\":160,\"trackStock\":true,\"costPrice\":100,\"unitCode\":\"PCE\"}" | j 'd["id"]') && ok "stock product"
SV=$(call POST /api/v1/products "{\"name\":\"Transport $S\",\"unitPrice\":500}" | j 'd["id"]') && ok "service product"
WH=$(call GET /api/v1/warehouses | j 'd[0]["id"]')
call POST /api/v1/stock/adjust "{\"productId\":$P,\"warehouseId\":$WH,\"quantity\":50,\"opening\":true}" >/dev/null
[ "$(qty "shrink+film+$S")" = 50.0 ] && ok "opening stock 50" || bad "opening stock"

# --- Quotation → Sales Order ----------------------------------------------------------------------
Q=$(call POST /api/v1/quotations "{\"customerId\":$C,\"terms\":\"Delivery 2-3 days. Payment 50% advance.\",\"lines\":[{\"name\":\"Shrink film\",\"quantity\":10,\"unitPrice\":160,\"vatRate\":15,\"productId\":$P},{\"name\":\"Transport\",\"quantity\":1,\"unitPrice\":500,\"vatRate\":15,\"productId\":$SV}]}" | j 'd["id"]') && ok "quotation $Q"
PRE=$(call GET "/api/v1/sales-orders/from-quotation/$Q")
echo "$PRE" | j 'len(d["lines"])' | grep -q 2 && ok "Copy To order prefill (2 lines)" || bad "prefill $PRE"
SOJ=$(echo "$PRE" | jx 'd["customerRef"]="PO-0000004114"; d["salesEmployee"]="Imran"' | { read -r b; call POST /api/v1/sales-orders "$b"; })
SO=$(echo "$SOJ" | j 'd["id"]'); SON=$(echo "$SOJ" | j 'd["number"]')
echo "$SOJ" | j 'd["status"]' | grep -q OPEN && ok "sales order $SON OPEN" || bad "sales order: $SOJ"
echo "$SOJ" | j 'd["remarks"]' | grep -q "Based on Quotation" && ok "order remarks 'Based on Quotation'" || bad "order remarks"
call GET "/api/v1/quotations/$Q" | j 'd["status"]' | grep -q ACCEPTED && ok "quotation ACCEPTED" || bad "quotation status"
OVER=$(echo "$PRE" | jx "pass" | { read -r b; call POST /api/v1/sales-orders "$b"; })
echo "$OVER" | grep -q over_order && ok "second order beyond quoted qty refused" || bad "over-order: $OVER"

# --- Partial deliveries ---------------------------------------------------------------------------
D1=$(call GET "/api/v1/delivery-notes/from-sales-order/$SO" | jx 'd["lines"][0]["quantity"]=4; d["driverName"]="Ali"; d["vehicleNo"]="ABC 1234"' | { read -r b; call POST /api/v1/delivery-notes "$b"; } | j 'd["id"]') && ok "delivery 1 (4 of 10 + transport)"
call POST "/api/v1/delivery-notes/$D1/deliver" '{"receivedBy":"Mohammed"}' >/dev/null
call GET "/api/v1/sales-orders/$SO" | j 'd["status"]' | grep -q PARTIALLY_DELIVERED && ok "order PARTIALLY_DELIVERED" || bad "order status after D1"
[ "$(qty "shrink+film+$S")" = 46.0 ] && ok "delivery took stock (46)" || bad "stock after D1 $(qty "shrink+film+$S")"
PF2=$(call GET "/api/v1/delivery-notes/from-sales-order/$SO")
echo "$PF2" | j 'float(d["lines"][0]["remaining"])' | grep -q '^6.0$' && ok "remaining 6 on order" || bad "remaining: $PF2"
O2=$(echo "$PF2" | jx 'd["lines"][0]["quantity"]=7' | { read -r b; call POST /api/v1/delivery-notes "$b"; })
echo "$O2" | grep -q over_delivery && ok "over-delivery refused" || bad "over-delivery: $O2"
D2=$(echo "$PF2" | jx "pass" | { read -r b; call POST /api/v1/delivery-notes "$b"; } | j 'd["id"]') && ok "delivery 2 (6)"
call POST "/api/v1/delivery-notes/$D2/deliver" '{"receivedBy":"Mohammed"}' >/dev/null
call GET "/api/v1/sales-orders/$SO" | j 'd["status"]' | grep -q CLOSED && ok "order CLOSED" || bad "order status after D2"
[ "$(qty "shrink+film+$S")" = 40.0 ] && ok "stock 40" || bad "stock after D2"
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/api/v1/sales-orders/$SO/pdf"); [ "$code" = 200 ] && ok "sales order PDF" || bad "SO pdf $code"

# --- Copy To Invoice from both deliveries ---------------------------------------------------------
call GET "/api/v1/delivery-notes/to-invoice?customerId=$C" | j 'len(d)' | grep -q 2 && ok "2 deliveries waiting for invoice" || bad "to-invoice list"
INV=$(call POST /api/v1/delivery-notes/invoice "{\"deliveryNoteIds\":[$D1,$D2]}" | j 'd["invoiceId"]') && ok "draft invoice $INV from 2 deliveries"
ID=$(call GET "/api/v1/invoices/$INV")
echo "$ID" | j 'len(d["lines"])' | grep -q 3 && ok "invoice has 3 lines" || bad "invoice lines $(echo "$ID" | j 'len(d["lines"])')"
echo "$ID" | j 'd["internalNote"]' | grep -q "Based on Sales Orders $SON" && ok "invoice 'Based on Sales Orders… Deliveries…'" || bad "basedOn: $(echo "$ID" | j 'd["internalNote"]')"
echo "$ID" | j 'd["customerRef"]' | grep -q PO-0000004114 && ok "customer PO carried to invoice" || bad "customerRef"
echo "$ID" | j 'float(d["totalInclVat"])' | grep -q '^2415.0$' && ok "invoice total 2415.00 (10×160 + 500 + 15%)" || bad "total $(echo "$ID" | j 'd["totalInclVat"]')"

if [ "${ISSUE:-0}" = 1 ]; then
  s=$(call POST "/api/v1/invoices/$INV/issue" '{"sendMode":"NOW"}' | j 'd.get("status")'); case "$s" in CLEARED*|REPORTED*|SIGNED) ok "invoice issued ($s)";; *) bad "issue $s";; esac
  [ "$(qty "shrink+film+$S")" = 40.0 ] && ok "issuing did not take stock again (40)" || bad "double deduction $(qty "shrink+film+$S")"
  again=$(call POST /api/v1/delivery-notes/invoice "{\"deliveryNoteIds\":[$D1,$D2]}")
  echo "$again" | grep -q nothing_to_invoice && ok "second invoice from same deliveries refused" || bad "re-invoice: $again"
  cn=$(call POST "/api/v1/delivery-notes/$D1/cancel")
  echo "$cn" | grep -q delivery_invoiced && ok "cancelling an invoiced delivery refused" || bad "cancel invoiced: $cn"
  # a second, direct invoice so the receipt can settle two invoices
  INV2=$(call POST /api/v1/invoices "{\"kind\":\"STANDARD\",\"customerId\":$C,\"lines\":[{\"name\":\"Transport extra\",\"quantity\":1,\"unitPrice\":100,\"vatRate\":15,\"productId\":$SV}]}" | j 'd["id"]')
  call POST "/api/v1/invoices/$INV2/issue" '{"sendMode":"NOW"}' >/dev/null
  OPEN=$(call GET "/api/v1/receipts/open-invoices?customerId=$C")
  echo "$OPEN" | j 'len(d)' | grep -q 2 && ok "2 open invoices for the customer" || bad "open invoices $OPEN"
  RC=$(call POST /api/v1/receipts "{\"customerId\":$C,\"method\":\"BANK_TRANSFER\",\"reference\":\"TT-77\",\"allocations\":[{\"invoiceId\":$INV,\"amount\":2000},{\"invoiceId\":$INV2,\"amount\":115}]}")
  echo "$RC" | j 'float(d["amount"])' | grep -q '^2115.0$' && ok "receipt $(echo "$RC" | j 'd["number"]') 2115 over 2 invoices" || bad "receipt $RC"
  call GET "/api/v1/invoices/$INV2" | j 'd["paymentStatus"]' | grep -q PAID && ok "invoice 2 PAID" || bad "inv2 status"
  call GET "/api/v1/invoices/$INV" | j 'float(d["dueAmount"])' | grep -q '^415.0$' && ok "invoice 1 due 415" || bad "inv1 due"
  tooMuch=$(call POST /api/v1/receipts "{\"customerId\":$C,\"method\":\"CASH\",\"allocations\":[{\"invoiceId\":$INV,\"amount\":999}]}")
  echo "$tooMuch" | grep -q "exceed" && ok "receipt above due refused" || bad "over-receipt: $tooMuch"
fi

# --- Relationship map -----------------------------------------------------------------------------
G=$(call GET "/api/v1/relations/sales-order/$SO")
types=$(echo "$G" | j '",".join(sorted(set(n["type"] for n in d["nodes"])))')
python -c "import sys; t=set(sys.argv[1].split(',')); sys.exit(0 if {'QUOTATION','SALES_ORDER','DELIVERY_NOTE','INVOICE'} <= t and ('${ISSUE:-0}' != '1' or 'RECEIPT' in t) else 1)" "$types" && ok "relationship map: $types ($(echo "$G" | j 'len(d["nodes"])') nodes, $(echo "$G" | j 'len(d["edges"])') edges)" || bad "map $types"
echo "$G" | j 'sum(1 for n in d["nodes"] if n["type"]=="DELIVERY_NOTE")' | grep -q 2 && ok "map shows both deliveries" || bad "map deliveries"
call GET "/api/v1/relations/invoice/$INV" | j 'len(d["nodes"])' | grep -q -v '^1$' && ok "map from the invoice reaches the chain" || bad "map from invoice"

echo "== $pass passed, $fail failed"
[ "$fail" = 0 ]
