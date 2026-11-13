#!/usr/bin/env bash
# Cancel / close acceptance test: cancelling an invoice issues a full credit note and reverses stock and payment
# only when ticked; closing writes off the rest. Needs an ACTIVE ZATCA device (it issues invoices and notes).
#   BASE=http://localhost:8080 EMAIL=... PASSWORD=... deploy/smoke-cancel.sh
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
j() { python -c "import sys,json; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }
S=$(date +%s)

echo "== $BASE"
call GET /api/v1/auth/csrf >/dev/null
call POST /api/v1/auth/login "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" >/dev/null
qty() { call GET "/api/v1/stock?q=$1" | j 'sum(float(r["quantity"]) for r in d)'; }

P=$(call POST /api/v1/products "{\"name\":\"Cancel test $S\",\"unitPrice\":10,\"trackStock\":true,\"costPrice\":6,\"unitCode\":\"PCE\",\"barcode\":\"C$S\"}" | j 'd["id"]')
W=$(call GET /api/v1/warehouses | j 'd[0]["id"]')
call POST /api/v1/stock/adjust "{\"productId\":$P,\"warehouseId\":$W,\"quantity\":20,\"opening\":true}" >/dev/null
[ "$(qty "Cancel%20test%20$S")" = "20.0" ] && ok "opening stock 20" || bad "opening stock $(qty "Cancel%20test%20$S")"

issue() { local id; id=$(call POST /api/v1/invoices "{\"kind\":\"SIMPLIFIED\",\"warehouseId\":$W,\"lines\":[{\"name\":\"Cancel test $S\",\"quantity\":$1,\"unitCode\":\"PCE\",\"unitPrice\":10,\"productId\":$P}]}" | j 'd["id"]')
  call POST "/api/v1/invoices/$id/issue" '{"sendMode":"NOW"}' >/dev/null; echo "$id"; }

# 1. goods returned + payment refunded
A=$(issue 5)
[ "$(qty "Cancel%20test%20$S")" = "15.0" ] && ok "invoice took 5 from stock (15)" || bad "after issue $(qty "Cancel%20test%20$S")"
call POST "/api/v1/invoices/$A/payments" "{\"amount\":57.5,\"paidOn\":\"$(date +%F)\",\"method\":\"CASH\"}" >/dev/null
R=$(call POST "/api/v1/invoices/$A/cancel-invoice" '{"reason":"Returned by customer","goodsReturned":true,"paymentRefunded":true}')
[ "$(echo "$R" | j 'd["lifecycle"]')" = "CANCELLED" ] && ok "invoice CANCELLED with credit note $(echo "$R" | j 'd["cancelNoteNumber"]')" || bad "cancel: $R"
[ "$(qty "Cancel%20test%20$S")" = "20.0" ] && ok "goods returned: stock back to 20" || bad "stock after return $(qty "Cancel%20test%20$S")"
[ "$(echo "$R" | j 'float(d["paidAmount"])')" = "0.0" ] && ok "payment refunded: nothing shows as paid" || bad "paid after refund $(echo "$R" | j 'd["paidAmount"]')"
[ "$(echo "$R" | j 'float(d["dueAmount"])')" = "0.0" ] && ok "nothing due on the cancelled invoice" || bad "due $(echo "$R" | j 'd["dueAmount"]')"

# 2. goods kept, payment kept as credit
B=$(issue 4)
call POST "/api/v1/invoices/$B/payments" "{\"amount\":46,\"paidOn\":\"$(date +%F)\",\"method\":\"CASH\"}" >/dev/null
R=$(call POST "/api/v1/invoices/$B/cancel-invoice" '{"reason":"Written off","goodsReturned":false,"paymentRefunded":false}')
[ "$(qty "Cancel%20test%20$S")" = "16.0" ] && ok "goods not returned: stock stays 16" || bad "stock $(qty "Cancel%20test%20$S")"
[ "$(echo "$R" | j 'float(d["overpaidAmount"])')" = "46.0" ] && ok "payment not refunded: 46 stays as customer credit" || bad "overpaid $(echo "$R" | j 'd["overpaidAmount"]')"

# 3. close writes off the rest
C=$(issue 2)
call POST "/api/v1/invoices/$C/payments" "{\"amount\":10,\"paidOn\":\"$(date +%F)\",\"method\":\"CASH\"}" >/dev/null
R=$(call POST "/api/v1/invoices/$C/close" '{"reason":"Customer will not pay the rest"}')
[ "$(echo "$R" | j 'd["lifecycle"]+" "+str(float(d["dueAmount"]))')" = "CLOSED 0.0" ] && ok "closed: rest written off" || bad "close: $R"
[ "$(qty "Cancel%20test%20$S")" = "14.0" ] && ok "closing does not touch stock (14)" || bad "stock $(qty "Cancel%20test%20$S")"
L=$(call GET "/api/v1/invoices?size=50" | j '",".join(str(i["id"])+":"+str(i.get("lifecycle")) for i in d["content"])')
echo "$L" | grep -q "$A:CANCELLED" && echo "$L" | grep -q "$C:CLOSED" && ok "list shows Cancelled and Closed" || bad "list $L"

echo "== $pass passed, $fail failed"
[ "$fail" = 0 ]
