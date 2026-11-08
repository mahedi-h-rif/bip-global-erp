#!/usr/bin/env bash
# End-to-end smoke test of the ERP API: login, warehouses, products, stock, transfer, invoice (stock deduction),
# delivery note (partial + PDF), credit note (stock return).
#   BASE=http://localhost:8080 EMAIL=... PASSWORD=... [ISSUE=1] deploy/smoke.sh
# ISSUE=1 issues invoices; it needs an ACTIVE ZATCA device whose certificate VAT matches the company VAT.
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
JAR=$(mktemp); trap 'rm -f "$JAR"' EXIT
pass=0; fail=0
ok()   { echo "  PASS  $1"; pass=$((pass+1)); }
bad()  { echo "  FAIL  $1"; fail=$((fail+1)); }
xsrf() { awk '$6=="XSRF-TOKEN"{print $7}' "$JAR" | tail -1; }
call() { # method path [json]
  local m=$1 p=$2 d=${3:-}
  if [ -n "$d" ]; then
    curl -s -b "$JAR" -c "$JAR" -X "$m" -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(xsrf)" "$BASE$p" -d "$d"
  else
    curl -s -b "$JAR" -c "$JAR" -X "$m" -H "X-XSRF-TOKEN: $(xsrf)" "$BASE$p"
  fi
}
j() { python -c "import sys,json; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }

echo "== $BASE"
curl -s -c "$JAR" "$BASE/api/v1/auth/csrf" >/dev/null
call POST /api/v1/auth/login "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" >/dev/null
role=$(call GET /api/v1/auth/me | j 'd["role"]') && ok "login as $role" || { bad login; exit 1; }

S=$(date +%s)
WH1=$(call GET /api/v1/warehouses | j 'd[0]["id"]') && ok "default warehouse $WH1"
WH2=$(call POST /api/v1/warehouses "{\"code\":\"JED$S\",\"name\":\"Jeddah $S\"}" | j 'd["id"]') && ok "create warehouse $WH2"

P=$(call POST /api/v1/products "{\"name\":\"Pallet wrap $S\",\"unitPrice\":100,\"trackStock\":true,\"costPrice\":60,\"lowStockThreshold\":5,\"unitCode\":\"PCE\"}" | j 'd["id"]') && ok "stock product $P"
SV=$(call POST /api/v1/products "{\"name\":\"Freight service $S\",\"unitPrice\":500}" | j 'd["id"]') && ok "service product $SV"

call POST /api/v1/stock/adjust "{\"productId\":$P,\"warehouseId\":$WH1,\"quantity\":20,\"opening\":true,\"note\":\"opening\"}" >/dev/null
q=$(call GET "/api/v1/stock?warehouseId=$WH1&q=pallet+wrap+$S" | j 'd[0]["quantity"]'); [ "${q%.*}" = 20 ] && ok "opening stock 20" || bad "opening stock ($q)"

T=$(call POST /api/v1/stock/transfers "{\"fromWarehouseId\":$WH1,\"toWarehouseId\":$WH2,\"lines\":[{\"productId\":$P,\"quantity\":4}]}")
echo "$T" | j 'd["number"]' | grep -q '^TR-' && ok "transfer $(echo "$T" | j 'd["number"]')" || bad "transfer: $T"
q=$(call GET "/api/v1/stock?warehouseId=$WH2&q=pallet+wrap+$S" | j 'd[0]["quantity"]'); [ "${q%.*}" = 4 ] && ok "transfer landed (4)" || bad "transfer qty ($q)"

over=$(call POST /api/v1/stock/transfers "{\"fromWarehouseId\":$WH2,\"toWarehouseId\":$WH1,\"lines\":[{\"productId\":$P,\"quantity\":99}]}")
echo "$over" | grep -q insufficient_stock && ok "over-transfer refused" || bad "over-transfer: $over"

DN=$(call POST /api/v1/delivery-notes "{\"clientName\":\"Walk-in $S\",\"warehouseId\":$WH1,\"driverName\":\"Ali\",\"vehicleNo\":\"ABC 1234\",\"lines\":[{\"productId\":$P,\"name\":\"Pallet wrap\",\"quantity\":2}]}" | j 'd["id"]') && ok "standalone delivery note $DN"
st=$(call POST "/api/v1/delivery-notes/$DN/deliver" '{"receivedBy":"Mohammed"}' | j 'd["status"]'); [ "$st" = DELIVERED ] && ok "delivered" || bad "deliver ($st)"
q=$(call GET "/api/v1/stock?warehouseId=$WH1&q=pallet+wrap+$S" | j 'd[0]["quantity"]'); [ "${q%.*}" = 14 ] && ok "delivery took stock (14)" || bad "after delivery ($q)"
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/api/v1/delivery-notes/$DN/pdf"); [ "$code" = 200 ] && ok "delivery note PDF" || bad "PDF $code"

if [ "${ISSUE:-0}" = 1 ]; then
  C=$(call POST /api/v1/customers "{\"nameAr\":\"شركة اختبار $S\",\"nameEn\":\"Test Co $S\",\"vatNumber\":\"300000000000003\",\"street\":\"King Fahd\",\"buildingNumber\":\"1234\",\"district\":\"Olaya\",\"city\":\"Riyadh\",\"postalCode\":\"12345\",\"countryCode\":\"SA\",\"phone\":\"0500000000\"}" | j 'd["id"]') && ok "customer $C"
  INV=$(call POST /api/v1/invoices "{\"kind\":\"STANDARD\",\"customerId\":$C,\"warehouseId\":$WH1,\"lines\":[{\"name\":\"Pallet wrap\",\"quantity\":5,\"unitPrice\":100,\"vatRate\":15,\"productId\":$P},{\"name\":\"Freight\",\"quantity\":1,\"unitPrice\":500,\"vatRate\":15,\"productId\":$SV}]}" | j 'd["id"]') && ok "draft invoice $INV"
  r=$(call POST "/api/v1/invoices/$INV/issue" '{"sendMode":"NOW"}'); s=$(echo "$r" | j 'd.get("status")'); n=$(echo "$r" | j 'd.get("number")')
  case "$s" in CLEARED*|SIGNED|REPORTED*) ok "invoice $n $s";; *) bad "issue: $r";; esac
  q=$(call GET "/api/v1/stock?warehouseId=$WH1&q=pallet+wrap+$S" | j 'd[0]["quantity"]'); [ "${q%.*}" = 9 ] && ok "invoice took stock (9)" || bad "after invoice ($q)"
  PF=$(call GET "/api/v1/delivery-notes/from-invoice/$INV"); echo "$PF" | j 'len(d["lines"])' | grep -q 2 && ok "prefill from invoice" || bad "prefill $PF"
  L1=$(echo "$PF" | j 'd["lines"][0]["invoiceLineId"]')
  DN2=$(call POST /api/v1/delivery-notes "{\"invoiceId\":$INV,\"lines\":[{\"invoiceLineId\":$L1,\"name\":\"Pallet wrap\",\"quantity\":3}]}" | j 'd["id"]') && ok "partial delivery note $DN2"
  rem=$(call GET "/api/v1/delivery-notes/from-invoice/$INV" | j 'd["lines"][0]["remaining"]'); [ "${rem%.*}" = 2 ] && ok "remaining 2 after partial" || bad "remaining ($rem)"
  o=$(call POST /api/v1/delivery-notes "{\"invoiceId\":$INV,\"lines\":[{\"invoiceLineId\":$L1,\"name\":\"Pallet wrap\",\"quantity\":3}]}"); echo "$o" | grep -q over_delivery && ok "over-delivery refused" || bad "over-delivery: $o"
  q=$(call GET "/api/v1/stock?warehouseId=$WH1&q=pallet+wrap+$S" | j 'd[0]["quantity"]'); [ "${q%.*}" = 9 ] && ok "invoice-linked note did not double-deduct" || bad "double deduct ($q)"
  CN=$(call POST "/api/v1/invoices/$INV/notes" '{"type":"CREDIT_NOTE","reason":"Returned goods"}' | j 'd["id"]') && ok "credit note draft $CN"
  cs=$(call POST "/api/v1/invoices/$CN/issue" '{"sendMode":"NOW"}' | j 'd.get("status")'); case "$cs" in CLEARED*|SIGNED|REPORTED*) ok "credit note $cs";; *) bad "credit note ($cs)";; esac
  q=$(call GET "/api/v1/stock?warehouseId=$WH1&q=pallet+wrap+$S" | j 'd[0]["quantity"]'); [ "${q%.*}" = 14 ] && ok "credit note returned stock (14)" || bad "after credit ($q)"
  code=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/api/v1/invoices/$INV/pdf"); [ "$code" = 200 ] && ok "invoice PDF" || bad "invoice PDF $code"
fi

m=$(call GET "/api/v1/stock/movements?productId=$P" | j 'len(d)'); [ "$m" -ge 4 ] && ok "movement ledger ($m rows)" || bad "ledger ($m)"
echo "== $pass passed, $fail failed"
[ "$fail" = 0 ]
