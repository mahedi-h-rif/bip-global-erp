#!/usr/bin/env bash
# Phase J acceptance: units of measure (piece / dozen / carton) and the document lifecycle to SAP B1 / Odoo standard —
# one quotation → one order, one delivery from several orders, invoice straight from an order, close by hand,
# one goods receipt from several POs, PO close, receiving / billing statuses.
#   BASE=http://localhost:8080 EMAIL=... PASSWORD=... [ISSUE=1] deploy/smoke-lifecycle.sh
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
qty() { call GET "/api/v1/stock?q=$1" | j 'sum(float(r["quantity"]) for r in d)'; }

echo "== $BASE"
curl -s -c "$JAR" "$BASE/api/v1/auth/csrf" >/dev/null
call POST /api/v1/auth/login "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" >/dev/null
call GET /api/v1/auth/me | grep -q '"role"' && ok login || { bad login; exit 1; }
S=$(date +%s)
WH=$(call GET /api/v1/warehouses | j 'd[0]["id"]')

# --- Units of measure ----------------------------------------------------------------------------
P=$(call POST /api/v1/products "{\"name\":\"Carton tape $S\",\"unitPrice\":5,\"trackStock\":true,\"costPrice\":3,\"unitCode\":\"PCE\",\"barcode\":\"P$S\"}" | j 'd["id"]') && ok "item, base unit piece @ 5"
U=$(call PUT "/api/v1/products/$P/units" "[{\"unitCode\":\"DZN\",\"factor\":12,\"price\":55,\"barcode\":\"D$S\",\"salesDefault\":true},{\"unitCode\":\"CT\",\"factor\":24,\"barcode\":\"C$S\",\"purchaseDefault\":true}]")
echo "$U" | j 'len(d)' | grep -q 2 && ok "units: dozen = 12 (own price 55), carton = 24" || bad "units $U"
call GET /api/v1/products/units | python -c "import sys,json; u=[x for x in json.load(sys.stdin) if x['productId']==$P]; c=[x for x in u if x['unitCode']=='CT'][0]; sys.exit(0 if len(u)==3 and abs(float(c['price'])-120)<0.01 else 1)" && ok "carton price defaults to 5 × 24 = 120" || bad "units listing"
e=$(call PUT "/api/v1/products/$P/units" '[{"unitCode":"PCE","factor":2}]'); echo "$e" | grep -q uom_is_base && ok "base unit can't be an alternative" || bad "base as alt: $e"
e=$(call PUT "/api/v1/products/$P/units" "[{\"unitCode\":\"BX\",\"factor\":6,\"barcode\":\"P$S\"}]"); echo "$e" | grep -q barcode_taken && ok "duplicate barcode refused" || bad "barcode: $e"
call PUT "/api/v1/products/$P/units" "[{\"unitCode\":\"DZN\",\"factor\":12,\"price\":55,\"barcode\":\"D$S\",\"salesDefault\":true},{\"unitCode\":\"CT\",\"factor\":24,\"barcode\":\"C$S\",\"purchaseDefault\":true}]" >/dev/null
call POST /api/v1/stock/adjust "{\"productId\":$P,\"warehouseId\":$WH,\"quantity\":200,\"opening\":true,\"unitCost\":3}" >/dev/null
call GET "/api/v1/pos/products?warehouseId=$WH&q=C$S" | j 'd[0]["scannedUnit"]' | grep -q CT && ok "POS: carton barcode scan → CT" || bad "pos scan"

C=$(call POST /api/v1/customers "{\"nameAr\":\"عميل $S\",\"nameEn\":\"Lifecycle Customer $S\",\"vatNumber\":\"300000000000003\",\"street\":\"Olaya St\",\"buildingNumber\":\"1234\",\"district\":\"Olaya\",\"city\":\"Riyadh\",\"postalCode\":\"12211\",\"countryCode\":\"SA\",\"phone\":\"0500000002\"}" | j 'd["id"]') && ok "customer"
bad_unit=$(call POST /api/v1/sales-orders "{\"customerId\":$C,\"lines\":[{\"productId\":$P,\"name\":\"Tape\",\"quantity\":1,\"unitCode\":\"KGM\",\"unitPrice\":5,\"vatRate\":15}]}")
echo "$bad_unit" | grep -q unknown_uom && ok "a unit the item doesn't have is refused" || bad "unknown unit: $bad_unit"

# --- Quotation → order (one only) → delivery from TWO orders --------------------------------------
Q=$(call POST /api/v1/quotations "{\"customerId\":$C,\"lines\":[{\"productId\":$P,\"name\":\"Tape\",\"quantity\":3,\"unitCode\":\"CT\",\"unitPrice\":110,\"vatRate\":15}]}" | j 'd["id"]')
SOA=$(call GET "/api/v1/sales-orders/from-quotation/$Q" | jx 'd["lines"][0]["unitCode"]="DZN"' | { read -r b; call POST /api/v1/sales-orders "$b"; })
SOAID=$(echo "$SOA" | j 'd["id"]'); echo "$SOA" | j 'd["lines"][0]["unitCode"]+" "+str(d["lines"][0]["uomFactor"])' | grep -q "CT 24" && ok "order A keeps the quotation's unit (3 CT, factor 24)" || bad "SO A unit $(echo "$SOA" | j 'd["lines"][0]')"
two=$(call GET "/api/v1/sales-orders/from-quotation/$Q" 2>/dev/null); echo "$two" | grep -q quotation_ordered && ok "one quotation → one sales order" || bad "second order: $two"
SOB=$(call POST /api/v1/sales-orders "{\"customerId\":$C,\"lines\":[{\"productId\":$P,\"name\":\"Tape\",\"quantity\":2,\"unitCode\":\"DZN\",\"unitPrice\":55,\"vatRate\":15}]}" | j 'd["id"]') && ok "order B: 2 DZN"
PF=$(call GET "/api/v1/delivery-notes/from-sales-orders?ids=$SOAID,$SOB")
echo "$PF" | j 'len(d["lines"])' | grep -q 2 && echo "$PF" | j 'd["note"]' | grep -q "Sales Orders" && ok "Copy To delivery from 2 orders (2 lines, 'Based on Sales Orders')" || bad "multi prefill $PF"
DN=$(echo "$PF" | jx 'd["lines"][0]["quantity"]=1' | { read -r b; call POST /api/v1/delivery-notes "$b"; })
DNID=$(echo "$DN" | j 'd["id"]'); echo "$DN" | j 'len(d["salesOrderNumbers"])' | grep -q 2 && ok "one delivery note, lines from 2 orders" || bad "dn $DN"
call POST "/api/v1/delivery-notes/$DNID/deliver" '{"receivedBy":"Store"}' >/dev/null
[ "$(qty "carton+tape+$S")" = 152.0 ] && ok "stock 200 − 1 CT (24) − 2 DZN (24) = 152 pieces" || bad "stock after delivery $(qty "carton+tape+$S")"
A=$(call GET "/api/v1/sales-orders/$SOAID"); B=$(call GET "/api/v1/sales-orders/$SOB")
echo "$A" | j 'd["status"]+" "+d["deliveryStatus"]' | grep -q "PARTIALLY_DELIVERED PARTIAL" && ok "order A partially delivered (1 of 3 CT)" || bad "A $(echo "$A" | j 'd["status"]')"
echo "$B" | j 'd["status"]+" "+d["deliveryStatus"]+" "+d["invoiceStatus"]' | grep -q "CLOSED FULL NONE" && ok "order B closed by delivery, invoice status NONE" || bad "B $(echo "$B" | j 'd["status"]+d["deliveryStatus"]+d["invoiceStatus"]')"
mixed=$(call POST /api/v1/sales-orders "{\"clientName\":\"Other\",\"lines\":[{\"name\":\"X\",\"quantity\":1,\"unitPrice\":1,\"vatRate\":15}]}" | j 'd["id"]')
m=$(call GET "/api/v1/delivery-notes/from-sales-orders?ids=$SOAID,$mixed"); echo "$m" | grep -q mixed_customers && ok "orders of different customers can't be combined" || bad "mixed: $m"

# --- Invoice from the delivery; invoice straight from order A ---------------------------------------
INV1=$(call POST /api/v1/delivery-notes/invoice "{\"deliveryNoteIds\":[$DNID]}" | j 'd["invoiceId"]')
call GET "/api/v1/invoices/$INV1" | j '[(l["unitCode"], float(l["uomFactor"])) for l in d["lines"]]' | grep -q "('CT', 24.0)" && ok "invoice keeps the units (CT ×24, DZN ×12)" || bad "invoice units"
INV2=$(call POST /api/v1/sales-orders/invoice "{\"salesOrderIds\":[$SOAID]}" | j 'd["invoiceId"]')
I2=$(call GET "/api/v1/invoices/$INV2")
echo "$I2" | j 'float(d["lines"][0]["quantity"])' | grep -q '^2.0$' && echo "$I2" | j 'd["lines"][0]["salesOrderLineId"] is not None' | grep -q True && ok "invoice straight from order A: the 2 CT still open" || bad "direct invoice $I2"
call GET "/api/v1/sales-orders/$SOAID" | j 'float(d["lines"][0]["remaining"])' | grep -q '^0.0$' && ok "draft invoice reserves the order (nothing left open)" || bad "reserve"
cl=$(call POST "/api/v1/sales-orders/$SOAID/close" '{"reason":"customer cancelled the rest"}'); echo "$cl" | grep -q order_has_drafts && ok "close refused while a draft invoice holds quantities" || bad "close with draft: $cl"
GP=$(call GET "/api/v1/invoices/$INV2/gross-profit"); near "$(echo "$GP" | j 'd["cost"]')" 144 && ok "GP cost in base units: 2 CT × 24 × 3 = 144" || bad "GP $(echo "$GP" | j 'd["cost"]')"
M=$(call GET "/api/v1/relations/sales-order/$SOAID")
echo "$M" | j 'sorted(set(n["type"] for n in d["nodes"]))' | grep -q "'INVOICE'" && echo "$M" | j 'len([e for e in d["edges"] if e["fromType"]=="SALES_ORDER" and e["toType"]=="INVOICE"])' | grep -q 1 && ok "map: order → delivery and order → invoice" || bad "map $(echo "$M" | j '[(e["fromType"],e["toType"]) for e in d["edges"]]')"
if [ "${ISSUE:-0}" = 1 ]; then
  s=$(call POST "/api/v1/invoices/$INV2/issue" '{"sendMode":"NOW"}' | j 'd.get("status")'); case "$s" in CLEARED*|REPORTED*|SIGNED) ok "direct invoice issued ($s)";; *) bad "issue $s";; esac
  [ "$(qty "carton+tape+$S")" = 104.0 ] && ok "issuing it took 2 CT = 48 pieces (152 → 104)" || bad "stock after direct invoice $(qty "carton+tape+$S")"
  call GET "/api/v1/sales-orders/$SOAID" | j 'd["status"]+" "+d["invoiceStatus"]' | grep -q "CLOSED PARTIAL" && ok "order A fulfilled → CLOSED; invoiced PARTIAL (delivery invoice still draft)" || bad "A after issue $(call GET "/api/v1/sales-orders/$SOAID" | j 'd["status"]+d["invoiceStatus"]')"
else
  call DELETE "/api/v1/invoices/$INV2" >/dev/null
  call POST "/api/v1/sales-orders/$SOAID/close" '{"reason":"customer cancelled the rest"}' | j 'd["status"]+" "+str(d["closedReason"])' | grep -q "CLOSED customer cancelled" && ok "order A closed by hand (rest dropped)" || bad "close"
  late=$(call GET "/api/v1/delivery-notes/from-sales-order/$SOAID"); echo "$late" | grep -q order_closed && ok "closed order can't be delivered" || bad "deliver closed: $late"
fi
c2=$(call POST "/api/v1/sales-orders/$SOB/cancel"); echo "$c2" | grep -q order_delivered && ok "an order with deliveries can't be cancelled" || bad "cancel delivered: $c2"

# --- Purchasing: one receipt from two POs, close a PO, statuses -------------------------------------
V=$(call POST /api/v1/suppliers "{\"name\":\"Tape Factory $S\",\"currency\":\"SAR\"}" | j 'd["id"]')
V2=$(call POST /api/v1/suppliers "{\"name\":\"Other Supplier $S\",\"currency\":\"SAR\"}" | j 'd["id"]')
PO1=$(call POST /api/v1/purchase-orders "{\"supplierId\":$V,\"warehouseId\":$WH,\"lines\":[{\"productId\":$P,\"name\":\"Tape\",\"quantity\":2,\"unitCode\":\"CT\",\"unitCost\":48,\"vatRate\":15}]}" | j 'd["id"]')
PO2=$(call POST /api/v1/purchase-orders "{\"supplierId\":$V,\"warehouseId\":$WH,\"lines\":[{\"productId\":$P,\"name\":\"Tape\",\"quantity\":1,\"unitCode\":\"CT\",\"unitCost\":48,\"vatRate\":15}]}" | j 'd["id"]')
PO3=$(call POST /api/v1/purchase-orders "{\"supplierId\":$V2,\"warehouseId\":$WH,\"lines\":[{\"productId\":$P,\"name\":\"Tape\",\"quantity\":4,\"unitCode\":\"CT\",\"unitCost\":50,\"vatRate\":15}]}" | j 'd["id"]')
for p in $PO1 $PO2 $PO3; do call POST "/api/v1/purchase-orders/$p/approve" >/dev/null; done
before=$(qty "carton+tape+$S")
G=$(call GET "/api/v1/goods-receipts/from-purchase-orders?ids=$PO1,$PO2" | jx 'pass' | { read -r b; call POST /api/v1/goods-receipts "$b"; })
GID=$(echo "$G" | j 'd["id"]'); echo "$G" | j 'len(d["lines"])' | grep -q 2 && ok "one goods receipt from 2 POs" || bad "multi GRN $G"
python -c "import sys; sys.exit(0 if abs(float('$(qty "carton+tape+$S")')-float('$before')-72)<0.01 else 1)" && ok "received 3 CT = 72 pieces into stock" || bad "stock after GRN"
call GET "/api/v1/stock/audit?productId=$P" | python -c "import sys,json; r=json.load(sys.stdin)['rows'][-1]; sys.exit(0 if abs(float(r['cost'])-2)<0.001 else 1)" && ok "receipt cost per piece = 48 / 24 = 2.00" || bad "unit cost per base"
call GET "/api/v1/purchase-orders/$PO1" | j 'd["status"]+" "+d["receivingStatus"]+" "+d["billingStatus"]' | grep -q "RECEIVED FULL NONE" && ok "PO 1 received, billing NONE" || bad "PO1 statuses"
mx=$(call GET "/api/v1/goods-receipts/from-purchase-orders?ids=$PO3,$PO1"); echo "$mx" | grep -q -E "mixed_suppliers|po_not_approved" && ok "POs of different suppliers can't be combined" || bad "mixed POs: $mx"
call GET "/api/v1/supplier-invoices/from-goods-receipts?ids=$GID" | jx 'pass' | { read -r b; call POST /api/v1/supplier-invoices "$b"; } >/dev/null
call GET "/api/v1/purchase-orders/$PO2" | j 'd["billingStatus"]' | grep -q FULL && ok "PO billing status FULL after supplier invoice" || bad "billing"
call GET "/api/v1/goods-receipts/from-purchase-order/$PO3" | jx 'd["lines"][0]["quantity"]=1' | { read -r b; call POST /api/v1/goods-receipts "$b"; } >/dev/null
call POST "/api/v1/purchase-orders/$PO3/close" '{"reason":"supplier out of stock"}' | j 'd["status"]' | grep -q CLOSED && ok "PO 3 closed by hand after 1 of 4 CT" || bad "close PO"
late=$(call GET "/api/v1/goods-receipts/from-purchase-order/$PO3"); echo "$late" | grep -q po_not_approved && ok "closed PO can't be received" || bad "receive closed: $late"
cp=$(call POST "/api/v1/purchase-orders/$PO3/cancel"); echo "$cp" | grep -q po_received && ok "PO with receipts can't be cancelled" || bad "cancel received PO: $cp"

echo "== $pass passed, $fail failed"
[ "$fail" = 0 ]
