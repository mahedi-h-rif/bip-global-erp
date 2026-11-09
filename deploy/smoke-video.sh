#!/usr/bin/env bash
# Video integration acceptance test (SAP B1 features from the 7 Nov meeting):
#   purchase request → PO, goods return from a receipt, A/P credit memo, gross profit on order/invoice,
#   gross-profit report, business-partner relationship maps, dashboard charts, print with/without logo, proforma.
#   BASE=http://localhost:8080 EMAIL=... PASSWORD=... [ISSUE=1] deploy/smoke-video.sh
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
pdf() { curl -s -b "$JAR" -o "$2" -w '%{http_code}' "$BASE$1"; }

echo "== $BASE"
curl -s -c "$JAR" "$BASE/api/v1/auth/csrf" >/dev/null
call POST /api/v1/auth/login "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" >/dev/null
call GET /api/v1/auth/me | grep -q '"role"' && ok login || { bad login; exit 1; }
S=$(date +%s)
WH=$(call GET /api/v1/warehouses | j 'd[0]["id"]')

# --- Purchase request → purchase order -----------------------------------------------------------
V=$(call POST /api/v1/suppliers "{\"name\":\"Film Supplier $S\",\"currency\":\"SAR\",\"paymentTermsDays\":30}" | j 'd["id"]') && ok "supplier"
P=$(call POST /api/v1/products "{\"name\":\"Stretch film $S\",\"unitPrice\":60,\"trackStock\":true,\"costPrice\":40,\"unitCode\":\"PCE\"}" | j 'd["id"]') && ok "stock item"
PR=$(call POST /api/v1/purchase-requests "{\"requester\":\"Warehouse - Imran\",\"department\":\"Warehouse\",\"warehouseId\":$WH,\"lines\":[{\"productId\":$P,\"name\":\"Stretch film\",\"quantity\":36,\"unitCode\":\"PCE\"}]}")
PRID=$(echo "$PR" | j 'd["id"]'); echo "$PR" | j 'd["status"]' | grep -q OPEN && ok "purchase request $(echo "$PR" | j 'd["number"]') OPEN" || bad "PR $PR"
PO=$(call POST "/api/v1/purchase-requests/$PRID/purchase-order" "{\"supplierId\":$V}")
POID=$(echo "$PO" | j 'd["id"]') && ok "Copy To purchase order $(echo "$PO" | j 'd["number"]')"
POD=$(call GET "/api/v1/purchase-orders/$POID")
echo "$POD" | j 'd["remarks"]' | grep -q "Based on Purchase Request" && ok "PO 'Based on Purchase Request'" || bad "PO remarks"
near "$(echo "$POD" | j 'd["lines"][0]["unitCost"]')" 40 && ok "PO priced at item cost 40" || bad "PO cost $(echo "$POD" | j 'd["lines"][0]["unitCost"]')"
call GET "/api/v1/purchase-requests/$PRID" | j 'd["status"]' | grep -q ORDERED && ok "request ORDERED" || bad "PR status"
again=$(call POST "/api/v1/purchase-requests/$PRID/purchase-order" "{\"supplierId\":$V}")
echo "$again" | grep -q request_closed && ok "request can't be ordered twice" || bad "reorder: $again"
call POST "/api/v1/purchase-orders/$POID/approve" >/dev/null
G=$(call GET "/api/v1/goods-receipts/from-purchase-order/$POID" | jx 'pass' | { read -r b; call POST /api/v1/goods-receipts "$b"; })
GID=$(echo "$G" | j 'd["id"]'); GL=$(echo "$G" | j 'd["lines"][0]["id"]')
[ "$(qty "stretch+film+$S")" = 36.0 ] && ok "received 36 rolls" || bad "stock after GRN $(qty "stretch+film+$S")"

# --- Goods return ----------------------------------------------------------------------------------
call GET "/api/v1/goods-returns/from-goods-receipt/$GID" | j 'float(d[0]["returnable"])' | grep -q '^36.0$' && ok "36 returnable" || bad "returnable"
RT=$(call POST /api/v1/goods-returns "{\"goodsReceiptId\":$GID,\"reason\":\"Damaged rolls\",\"lines\":[{\"goodsReceiptLineId\":$GL,\"quantity\":6}]}")
RTID=$(echo "$RT" | j 'd["id"]'); echo "$RT" | j 'd["status"]' | grep -q POSTED && ok "goods return $(echo "$RT" | j 'd["number"]') (6 damaged)" || bad "return $RT"
[ "$(qty "stretch+film+$S")" = 30.0 ] && ok "stock back to 30" || bad "stock after return $(qty "stretch+film+$S")"
over=$(call POST /api/v1/goods-returns "{\"goodsReceiptId\":$GID,\"lines\":[{\"goodsReceiptLineId\":$GL,\"quantity\":31}]}")
echo "$over" | grep -q over_return && ok "over-return refused" || bad "over-return: $over"
call GET "/api/v1/goods-receipts/$GID" | j 'float(d["lines"][0]["remaining"])' | grep -q '^30.0$' && ok "only the 30 kept can be billed" || bad "billable $(call GET "/api/v1/goods-receipts/$GID" | j 'd["lines"][0]["remaining"]')"
nocred=$(call GET "/api/v1/supplier-credit-notes/from-goods-return/$RTID")
echo "$nocred" | grep -q not_invoiced && ok "no credit memo needed before billing" || bad "credit before invoice: $nocred"
cg=$(call POST "/api/v1/goods-receipts/$GID/cancel"); echo "$cg" | grep -q grn_returned && ok "receipt with a return can't be cancelled" || bad "cancel GRN: $cg"

# --- Supplier invoice + A/P credit memo --------------------------------------------------------------
SI=$(call GET "/api/v1/supplier-invoices/from-goods-receipts?ids=$GID" | jx 'd["supplierInvoiceNo"]="FS-77"' | { read -r b; call POST /api/v1/supplier-invoices "$b"; })
SIID=$(echo "$SI" | j 'd["id"]'); near "$(echo "$SI" | j 'd["totalSar"]')" 1380 && ok "supplier invoice 30 × 40 + VAT = 1380" || bad "SINV $(echo "$SI" | j 'd["totalSar"]')"
CR=$(call GET "/api/v1/supplier-credit-notes/from-supplier-invoice/$SIID" | jx 'd["lines"][0]["quantity"]=5; d["supplierRef"]="CN-9"; d["remarks"]="Price difference"' | { read -r b; call POST /api/v1/supplier-credit-notes "$b"; })
CRID=$(echo "$CR" | j 'd["id"]'); near "$(echo "$CR" | j 'd["totalSar"]')" 230 && ok "A/P credit memo $(echo "$CR" | j 'd["number"]') 230" || bad "credit $CR"
near "$(call GET "/api/v1/supplier-invoices/$SIID" | j 'd["due"]')" 1150 && ok "invoice due 1380 − 230 = 1150" || bad "due $(call GET "/api/v1/supplier-invoices/$SIID" | j 'd["due"]')"
near "$(call GET /api/v1/suppliers | python -c "import sys,json; print([s for s in json.load(sys.stdin) if s['id']==$V][0]['payable'])")" 1150 && ok "supplier payable 1150" || bad "payable"
big=$(call GET "/api/v1/supplier-credit-notes/from-supplier-invoice/$SIID" | jx 'pass' | { read -r b; call POST /api/v1/supplier-credit-notes "$b"; })
echo "$big" | grep -q over_credit && ok "credit above what is owed refused" || bad "over-credit: $big"
call GET "/api/v1/erp-reports/payables" | python -c "import sys,json; d=json.load(sys.stdin); r=[x for x in d['rows'] if x[0]=='Film Supplier $S']; sys.exit(0 if r and abs(float(r[0][-1])-1150)<0.02 else 1)" && ok "payables aging uses the credit" || bad "aging"
M=$(call GET "/api/v1/relations/purchase-order/$POID")
echo "$M" | j '",".join(sorted(set(n["type"] for n in d["nodes"])))' | grep -q "GOODS_RECEIPT,GOODS_RETURN,PURCHASE_ORDER,PURCHASE_REQUEST,SUPPLIER_CREDIT,SUPPLIER_INVOICE" && ok "purchase map: request → PO → receipt → return / invoice → credit memo" || bad "map $(echo "$M" | j 'sorted(set(n["type"] for n in d["nodes"]))')"
BP=$(call GET "/api/v1/relations/supplier/$V")
echo "$BP" | j 'd["nodes"][0]["type"]' | grep -q SUPPLIER && echo "$BP" | j 'len(d["nodes"])' | grep -q -E '^[6-9]$' && ok "supplier relationship map ($(echo "$BP" | j 'len(d["nodes"])') nodes)" || bad "supplier map $(echo "$BP" | j '[n["type"] for n in d["nodes"]]')"
call POST "/api/v1/supplier-credit-notes/$CRID/cancel" '{"reason":"test"}' | j 'd["status"]' | grep -q CANCELLED && ok "credit memo cancelled" || bad "cancel credit"
near "$(call GET "/api/v1/supplier-invoices/$SIID" | j 'd["due"]')" 1380 && ok "due back to 1380" || bad "due after cancel"

# --- Gross profit ---------------------------------------------------------------------------------
C=$(call POST /api/v1/customers "{\"nameAr\":\"\u0639\u0645\u064a\u0644 $S\",\"nameEn\":\"GP Customer $S\",\"vatNumber\":\"300000000000003\",\"street\":\"King Fahd Rd\",\"buildingNumber\":\"1234\",\"district\":\"Olaya\",\"city\":\"Riyadh\",\"postalCode\":\"12211\",\"countryCode\":\"SA\",\"phone\":\"0500000001\"}" | j 'd["id"]') && ok "customer"
SO=$(call POST /api/v1/sales-orders "{\"customerId\":$C,\"warehouseId\":$WH,\"lines\":[{\"productId\":$P,\"name\":\"Stretch film\",\"quantity\":10,\"unitPrice\":60,\"vatRate\":15},{\"name\":\"Delivery charge\",\"quantity\":1,\"unitPrice\":100,\"vatRate\":15}]}" | j 'd["id"]')
SG=$(call GET "/api/v1/sales-orders/$SO/gross-profit")
near "$(echo "$SG" | j 'd["grossProfit"]')" 300 && ok "order GP estimate 700 − 400 = 300 ($(echo "$SG" | j 'd["gpPct"]') %)" || bad "SO GP $SG"
D=$(call GET "/api/v1/delivery-notes/from-sales-order/$SO" | jx 'pass' | { read -r b; call POST /api/v1/delivery-notes "$b"; } | j 'd["id"]')
call POST "/api/v1/delivery-notes/$D/deliver" '{"receivedBy":"Store"}' >/dev/null
INV=$(call POST /api/v1/delivery-notes/invoice "{\"deliveryNoteIds\":[$D]}" | j 'd["invoiceId"]')
IG=$(call GET "/api/v1/invoices/$INV/gross-profit")
near "$(echo "$IG" | j 'd["revenue"]')" 700 && near "$(echo "$IG" | j 'd["cost"]')" 400 && ok "invoice GP: revenue 700, cost 400 (actual stock cost), GP $(echo "$IG" | j 'd["grossProfit"]') = $(echo "$IG" | j 'd["gpPct"]') %" || bad "invoice GP $IG"
if [ "${ISSUE:-0}" = 1 ]; then
  s=$(call POST "/api/v1/invoices/$INV/issue" '{"sendMode":"NOW"}' | j 'd.get("status")'); case "$s" in CLEARED*|REPORTED*|SIGNED) ok "invoice issued ($s)";; *) bad "issue $s";; esac
  call GET "/api/v1/erp-reports/gross-profit?groupBy=customer" | python -c "import sys,json; d=json.load(sys.stdin); r=[x for x in d['rows'] if x[0]=='GP Customer $S']; sys.exit(0 if r and abs(float(r[0][3])-300)<0.02 else 1)" && ok "gross-profit report by customer" || bad "GP report"
fi
for g in invoice customer product month; do code=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/api/v1/erp-reports/gross-profit.xlsx?groupBy=$g"); [ "$code" = 200 ] || bad "GP xlsx $g $code"; done; ok "gross-profit report Excel (4 groupings)"
CM=$(call GET "/api/v1/relations/customer/$C")
echo "$CM" | j '",".join(sorted(set(n["type"] for n in d["nodes"])))' | grep -q "CUSTOMER,DELIVERY_NOTE,INVOICE,SALES_ORDER" && ok "customer relationship map" || bad "customer map $(echo "$CM" | j 'sorted(set(n["type"] for n in d["nodes"]))')"

# --- Dashboard charts -----------------------------------------------------------------------------
DB=$(call GET /api/v1/erp-dashboard)
echo "$DB" | j 'len(d["monthly"])' | grep -q 12 && ok "12-month sales / purchases / GP chart data" || bad "monthly"
echo "$DB" | j 'len(d["receivablesAging"]) == 5 and len(d["payablesAging"]) == 5' | grep -q True && ok "aging chart buckets" || bad "aging buckets"
echo "$DB" | j '"topCustomers" in d and "topProducts" in d' | grep -q True && ok "top customers / products" || bad "tops"

# --- Printing -------------------------------------------------------------------------------------
T=$(mktemp -d)
if [ "${ISSUE:-0}" = 1 ]; then  # drafts have no PDF
  [ "$(pdf "/api/v1/invoices/$INV/pdf" "$T/a.pdf")" = 200 ] && [ "$(pdf "/api/v1/invoices/$INV/pdf?logo=false" "$T/b.pdf")" = 200 ] && ok "invoice PDF with and without logo" || bad "invoice pdf"
fi
Q=$(call POST /api/v1/quotations "{\"customerId\":$C,\"lines\":[{\"name\":\"Stretch film\",\"quantity\":5,\"unitPrice\":60,\"vatRate\":15,\"productId\":$P}]}" | j 'd["id"]')
[ "$(pdf "/api/v1/quotations/$Q/pdf?proforma=true" "$T/p.pdf")" = 200 ] && ok "proforma invoice PDF from quotation" || bad "proforma"
[ "$(pdf "/api/v1/quotations/$Q/pdf?proforma=true&logo=false" "$T/p2.pdf")" = 200 ] && ok "proforma without logo" || bad "proforma no logo"
sa=$(stat -c %s "$T/p.pdf"); sb=$(stat -c %s "$T/p2.pdf")
[ "$sb" -lt "$sa" ] && ok "logo-free print is lighter ($sb < $sa bytes)" || echo "  note  pdf sizes $sa / $sb (no logo uploaded?)"
rm -rf "$T"

echo "== $pass passed, $fail failed"
[ "$fail" = 0 ]
