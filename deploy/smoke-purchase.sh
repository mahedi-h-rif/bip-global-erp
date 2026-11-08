#!/usr/bin/env bash
# Phase B + C acceptance test: supplier → PO (BHD, approval) → partial goods receipts with landed cost → moving-average
# cost → sale at average → inventory audit report → supplier invoice from receipts → supplier payment (cancel) → map.
#   BASE=http://localhost:8080 EMAIL=... PASSWORD=... deploy/smoke-purchase.sh
set -euo pipefail
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
jx() { python -c "import sys,json; d=json.load(sys.stdin); exec(sys.argv[1]); print(json.dumps(d))" "$1"; }
near() { python -c "import sys; sys.exit(0 if abs(float(sys.argv[1])-float(sys.argv[2]))<0.02 else 1)" "$1" "$2"; }

echo "== $BASE"
curl -s -c "$JAR" "$BASE/api/v1/auth/csrf" >/dev/null
call POST /api/v1/auth/login "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" >/dev/null
call GET /api/v1/auth/me | grep -q '"role"' && ok login || { bad login; exit 1; }
S=$(date +%s)
WH=$(call GET /api/v1/warehouses | j 'd[0]["id"]')

V=$(call POST /api/v1/suppliers "{\"name\":\"Season International Trading $S\",\"country\":\"BH\",\"currency\":\"BHD\",\"paymentTermsDays\":30,\"vatNumber\":\"200000000000003\"}" | j 'd["id"]') && ok "supplier (Bahrain, BHD)"
P=$(call POST /api/v1/products "{\"name\":\"BOPP tape $S\",\"nameAr\":\"شريط لاصق\",\"brand\":\"GS\",\"category\":\"Tapes\",\"barcode\":\"628$S\",\"unitPrice\":150,\"trackStock\":true,\"unitCode\":\"PCE\"}" | j 'd["id"]') && ok "item with Arabic name, brand, barcode"
call GET "/api/v1/products?q=628$S" | j 'len(d)' | grep -q 1 && ok "found by barcode" || bad "barcode search"
call POST /api/v1/stock/adjust "{\"productId\":$P,\"warehouseId\":$WH,\"quantity\":10,\"opening\":true,\"unitCost\":50}" >/dev/null
near "$(call GET "/api/v1/products?q=628$S" | j 'd[0]["avgCost"]')" 50 && ok "opening 10 @ 50 → avg 50" || bad "avg after opening"

PO=$(call POST /api/v1/purchase-orders "{\"supplierId\":$V,\"supplierRef\":\"QTN-77\",\"warehouseId\":$WH,\"currency\":\"BHD\",\"exchangeRate\":9.92,\"remarks\":\"Delivery 2 weeks\",\"lines\":[{\"productId\":$P,\"name\":\"BOPP tape\",\"quantity\":20,\"unitCost\":10,\"vatRate\":15}]}")
POID=$(echo "$PO" | j 'd["id"]'); echo "$PO" | j 'd["status"]' | grep -q PENDING && ok "PO $(echo "$PO" | j 'd["number"]') PENDING, total $(echo "$PO" | j 'd["totalInclVat"]') BHD" || bad "PO $PO"
early=$(call GET "/api/v1/goods-receipts/from-purchase-order/$POID" | jx 'pass' | { read -r b; call POST /api/v1/goods-receipts "$b"; })
echo "$early" | grep -q po_not_approved && ok "receipt before approval refused" || bad "early receipt: $early"
call POST "/api/v1/purchase-orders/$POID/approve" | j 'd["status"]' | grep -q APPROVED && ok "PO approved" || bad "approve"
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/api/v1/purchase-orders/$POID/pdf"); [ "$code" = 200 ] && ok "PO PDF (amount in words)" || bad "PO pdf $code"

G1=$(call GET "/api/v1/goods-receipts/from-purchase-order/$POID" | jx 'd["lines"][0]["quantity"]=8; d["supplierRef"]="DN-5521"; d["costs"]=[{"description":"Transport","amount":160}]' | { read -r b; call POST /api/v1/goods-receipts "$b"; })
G1ID=$(echo "$G1" | j 'd["id"]'); near "$(echo "$G1" | j 'd["lines"][0]["landedUnitCost"]')" 119.2 && ok "GRN 1: 8 × (10 BHD × 9.92) + 160 transport → landed 119.20 SAR" || bad "landed $(echo "$G1" | j 'd["lines"][0]["landedUnitCost"]')"
near "$(call GET "/api/v1/products?q=628$S" | j 'd[0]["avgCost"]')" 80.7556 && ok "moving average 80.7556" || bad "avg after GRN1 $(call GET "/api/v1/products?q=628$S" | j 'd[0]["avgCost"]')"
call GET "/api/v1/purchase-orders/$POID" | j 'd["status"]' | grep -q PARTIALLY_RECEIVED && ok "PO PARTIALLY_RECEIVED" || bad "PO status"
over=$(call GET "/api/v1/goods-receipts/from-purchase-order/$POID" | jx 'd["lines"][0]["quantity"]=13' | { read -r b; call POST /api/v1/goods-receipts "$b"; })
echo "$over" | grep -q over_receipt && ok "over-receipt refused" || bad "over-receipt: $over"
G2ID=$(call GET "/api/v1/goods-receipts/from-purchase-order/$POID" | jx 'pass' | { read -r b; call POST /api/v1/goods-receipts "$b"; } | j 'd["id"]') && ok "GRN 2 (remaining 12)"
call GET "/api/v1/purchase-orders/$POID" | j 'd["status"]' | grep -q '^RECEIVED' && ok "PO RECEIVED" || bad "PO not received"
near "$(call GET "/api/v1/products?q=628$S" | j 'd[0]["avgCost"]')" 88.1333 && ok "moving average 88.1333" || bad "avg after GRN2"

DN=$(call POST /api/v1/delivery-notes "{\"clientName\":\"Walk-in\",\"warehouseId\":$WH,\"lines\":[{\"productId\":$P,\"name\":\"BOPP tape\",\"quantity\":5}]}" | j 'd["id"]')
call POST "/api/v1/delivery-notes/$DN/deliver" '{"receivedBy":"X"}' >/dev/null
A=$(call GET "/api/v1/stock/audit?productId=$P")
echo "$A" | j 'len(d["rows"])' | grep -q 4 && ok "inventory audit: 4 movements" || bad "audit rows $(echo "$A" | j 'len(d["rows"])')"
near "$(echo "$A" | j 'd["rows"][3]["cost"]')" 88.1333 && ok "sale issued at moving average" || bad "issue cost"
echo "$A" | j 'float(d["closingQty"])' | grep -q '^25.0$' && ok "closing qty 25" || bad "closing qty"
near "$(echo "$A" | j 'd["closingValue"]')" 2203.33 && ok "closing value 2203.33 SAR" || bad "closing value $(echo "$A" | j 'd["closingValue"]')"
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/api/v1/stock/audit.xlsx?productId=$P"); [ "$code" = 200 ] && ok "audit Excel export" || bad "audit xlsx $code"

SI=$(call GET "/api/v1/supplier-invoices/from-goods-receipts?ids=$G1ID,$G2ID" | jx 'd["supplierInvoiceNo"]="INV-BH-901"' | { read -r b; call POST /api/v1/supplier-invoices "$b"; })
SIID=$(echo "$SI" | j 'd["id"]'); near "$(echo "$SI" | j 'd["totalSar"]')" 2281.6 && ok "supplier invoice from 2 receipts: 230 BHD = 2281.60 SAR" || bad "SINV $SI"
again=$(call GET "/api/v1/supplier-invoices/from-goods-receipts?ids=$G1ID")
echo "$again" | grep -q nothing_to_invoice && ok "receipts can't be billed twice" || bad "rebill: $again"
cg=$(call POST "/api/v1/goods-receipts/$G1ID/cancel")
echo "$cg" | grep -q grn_invoiced && ok "cancelling a billed receipt refused" || bad "cancel GRN: $cg"
SP=$(call POST /api/v1/supplier-payments "{\"supplierId\":$V,\"method\":\"BANK_TRANSFER\",\"reference\":\"TT-1\",\"allocations\":[{\"supplierInvoiceId\":$SIID,\"amount\":1000}]}")
SPID=$(echo "$SP" | j 'd["id"]'); near "$(call GET "/api/v1/supplier-invoices/$SIID" | j 'd["due"]')" 1281.6 && ok "paid 1000 → due 1281.60" || bad "due after pay"
op=$(call POST /api/v1/supplier-payments "{\"supplierId\":$V,\"method\":\"CASH\",\"allocations\":[{\"supplierInvoiceId\":$SIID,\"amount\":5000}]}")
echo "$op" | grep -q "exceed" && ok "over-payment refused" || bad "overpay: $op"
near "$(call GET /api/v1/suppliers | python -c "import sys,json; print([s for s in json.load(sys.stdin) if s['id']==$V][0]['payable'])")" 1281.6 && ok "supplier payable 1281.60" || bad "payable"
call POST "/api/v1/supplier-payments/$SPID/cancel" '{"reason":"wrong amount"}' | j 'd["status"]' | grep -q CANCELLED && ok "supplier payment cancelled" || bad "cancel pay"
near "$(call GET "/api/v1/supplier-invoices/$SIID" | j 'd["due"]')" 2281.6 && ok "due back to 2281.60" || bad "due after cancel"

G=$(call GET "/api/v1/relations/purchase-order/$POID")
echo "$G" | j '",".join(sorted(set(n["type"] for n in d["nodes"])))' | grep -q "GOODS_RECEIPT,PURCHASE_ORDER,SUPPLIER_INVOICE,SUPPLIER_PAYMENT" && ok "purchase map: $(echo "$G" | j 'len(d["nodes"])') nodes" || bad "purchase map $(echo "$G" | j '[n["type"] for n in d["nodes"]]')"

echo "== $pass passed, $fail failed"
[ "$fail" = 0 ]
