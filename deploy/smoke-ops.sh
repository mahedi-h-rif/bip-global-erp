#!/usr/bin/env bash
# Phase H acceptance test (Odoo/Oracle benchmark): fleet, trips with costs and profit, credit control, payment terms,
# reorder suggestions → PO, CRM lead → customer, recurring contract invoice.
#   BASE=... EMAIL=... PASSWORD=... deploy/smoke-ops.sh
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
S=$(date +%s); SOON=$(python -c "import datetime as d; print(d.date.today()+d.timedelta(days=10))")
WH=$(call GET /api/v1/warehouses | j 'd[0]["id"]')

DRV=$(call POST /api/v1/hr/employees "{\"name\":\"Driver $S\",\"joinDate\":\"2025-01-01\",\"basic\":3000,\"phone\":\"0551112222\",\"jobTitle\":\"Driver\"}" | j 'd["id"]')
V=$(call POST /api/v1/fleet/vehicles "{\"plate\":\"ABC-$((S%10000))\",\"name\":\"Isuzu NPR\",\"vehicleType\":\"TRUCK\",\"capacity\":\"5 t\",\"driverEmployeeId\":$DRV,\"istimaraExpiry\":\"$SOON\",\"insuranceExpiry\":\"2027-06-01\",\"odometer\":12000}")
VID=$(echo "$V" | j 'd["id"]'); echo "$V" | j 'd["nextExpiryDays"]' | grep -qE '^(9|10)$' && ok "vehicle with Istimara expiring in 10 days" || bad "vehicle $V"
call GET /api/v1/erp-dashboard | j '[v["id"] for v in d["expiringVehicles"]]' | grep -q "$VID" && ok "dashboard warns about the vehicle" || bad "dashboard vehicle"
call POST /api/v1/fleet/logs "{\"vehicleId\":$VID,\"kind\":\"FUEL\",\"amount\":250,\"liters\":110,\"odometer\":12350}" | j 'float(d["amount"])' | grep -q '^250.0$' && ok "fuel 250 SAR logged" || bad "fuel"
near "$(call GET /api/v1/fleet/vehicles | python -c "import sys,json; print([v for v in json.load(sys.stdin) if v['id']==$VID][0]['odometer'])")" 12350 && ok "odometer updated to 12350" || bad "odometer"

C=$(call POST /api/v1/customers "{\"nameAr\":\"عميل الشحن $S\",\"nameEn\":\"Freight Client $S\",\"phone\":\"0500000000\"}" | j 'd["id"]')
P=$(call POST /api/v1/products "{\"name\":\"Stretch film $S\",\"unitPrice\":100,\"trackStock\":true,\"costPrice\":60,\"lowStockThreshold\":10}" | j 'd["id"]')
call POST /api/v1/stock/adjust "{\"productId\":$P,\"warehouseId\":$WH,\"quantity\":30,\"opening\":true,\"unitCost\":60}" >/dev/null
DN=$(call POST /api/v1/delivery-notes "{\"customerId\":$C,\"warehouseId\":$WH,\"lines\":[{\"productId\":$P,\"name\":\"Stretch film\",\"quantity\":10,\"unitPrice\":100}]}" | j 'd["id"]')
T=$(call POST /api/v1/fleet/trips "{\"vehicleId\":$VID,\"origin\":\"Riyadh\",\"destination\":\"Dammam\",\"deliveryNoteIds\":[$DN]}")
TID=$(echo "$T" | j 'd["id"]'); echo "$T" | j 'd["driverName"]' | grep -q "Driver $S" && ok "trip $(echo "$T" | j 'd["number"]') takes the vehicle's driver" || bad "trip $T"
call GET "/api/v1/delivery-notes/$DN" | j 'd["vehicleNo"]' | grep -q "ABC-" && ok "delivery note gets the plate and driver" || bad "dn vehicle"
call POST "/api/v1/fleet/trips/$TID/costs" '{"kind":"FUEL","amount":300}' >/dev/null
T2=$(call POST "/api/v1/fleet/trips/$TID/costs" '{"kind":"TOLL","amount":50,"description":"Highway"}')
near "$(echo "$T2" | j 'd["totalCost"]')" 350 && near "$(echo "$T2" | j 'd["revenue"]')" 1000 && near "$(echo "$T2" | j 'd["profit"]')" 650 && ok "trip cost 350, revenue 1000, profit 650" || bad "trip money $T2"
T3=$(call POST /api/v1/fleet/trips "{\"vehicleId\":$VID,\"deliveryNoteIds\":[$DN]}")
echo "$T3" | grep -q delivery_on_trip && ok "same delivery on two trips refused" || bad "double trip: $T3"
call POST "/api/v1/fleet/trips/$TID/status" '{"status":"IN_TRANSIT"}' >/dev/null
call POST "/api/v1/fleet/trips/$TID/status" '{"status":"COMPLETED","endOdometer":12750}' | j 'd["status"]' | grep -q COMPLETED && ok "trip completed, odometer 12750" || bad "complete"
call GET "/api/v1/erp-reports/expenses?groupBy=category" | j '[r[0] for r in d["rows"]]' | grep -q VEHICLE && ok "fuel + trip costs posted as VEHICLE expenses" || bad "vehicle expense"

call PUT "/api/v1/crm/credit/$C" '{"creditLimit":1000,"paymentTermsDays":30}' | j 'd["creditLimit"]' | grep -q 1000 && ok "credit limit 1000, terms 30 days" || bad "credit set"
SO=$(call POST /api/v1/sales-orders "{\"customerId\":$C,\"lines\":[{\"name\":\"Freight\",\"quantity\":1,\"unitPrice\":2000}]}")
echo "$SO" | grep -q credit_limit && ok "order above the credit limit refused" || bad "credit block: $SO"

R=$(call GET /api/v1/reorder | python -c "import sys,json; print(json.dumps([s for s in json.load(sys.stdin) if s['productId']==$P]))")
echo "$R" | j 'len(d)' | grep -q 0 && ok "item above its alert level is not suggested" || bad "reorder early $R"
call POST /api/v1/stock/adjust "{\"productId\":$P,\"warehouseId\":$WH,\"quantity\":-21}" >/dev/null
R=$(call GET /api/v1/reorder | python -c "import sys,json; print(json.dumps([s for s in json.load(sys.stdin) if s['productId']==$P]))")
near "$(echo "$R" | j 'd[0]["suggested"]')" 11 && ok "9 on hand, alert 10 → suggest 11 (back to 20)" || bad "suggest $R"
SUP=$(call POST /api/v1/suppliers "{\"name\":\"Local Supplier $S\"}" | j 'd["id"]')
PO=$(call POST /api/v1/reorder/purchase-order "{\"supplierId\":$SUP,\"warehouseId\":$WH,\"lines\":[{\"productId\":$P,\"quantity\":11}]}")
echo "$PO" | j 'd["status"]' | grep -q PENDING && near "$(echo "$PO" | j 'd["lines"][0]["unitCost"]')" 60 && ok "requisition $(echo "$PO" | j 'd["number"]') at average cost 60" || bad "reorder PO $PO"
call GET /api/v1/reorder | python -c "import sys,json; sys.exit(0 if not [s for s in json.load(sys.stdin) if s['productId']==$P] else 1)" && ok "item on order is no longer suggested" || bad "on-order"

L=$(call POST /api/v1/crm/leads "{\"title\":\"Monthly freight Riyadh-Jeddah\",\"company\":\"Lead Co $S\",\"phone\":\"0509998888\",\"expectedValue\":50000,\"source\":\"Referral\"}")
LID=$(echo "$L" | j 'd["id"]'); echo "$L" | j 'd["stage"]' | grep -q NEW && ok "lead NEW" || bad "lead $L"
lost=$(call PUT "/api/v1/crm/leads/$LID" "{\"title\":\"x\",\"stage\":\"LOST\"}")
echo "$lost" | grep -q lostReason && ok "lost needs a reason" || bad "lost: $lost"
CV=$(call POST "/api/v1/crm/leads/$LID/convert")
echo "$CV" | j 'd["customerName"]' | grep -q "Lead Co $S" && echo "$CV" | j 'd["stage"]' | grep -q PROPOSAL && ok "lead → customer, stage PROPOSAL" || bad "convert $CV"

TODAY=$(python -c "import datetime as d; print(d.date.today())")
RC=$(call POST /api/v1/crm/recurring "{\"customerId\":$C,\"name\":\"Warehouse rent\",\"kind\":\"SIMPLIFIED\",\"intervalMonths\":1,\"nextDate\":\"$TODAY\",\"lines\":[{\"name\":\"Warehouse space\",\"quantity\":1,\"unitPrice\":800}]}")
near "$(echo "$RC" | j 'd["amount"]')" 920 && ok "recurring contract 920 / month" || bad "recurring $RC"
RUN=$(call POST /api/v1/crm/recurring/run)
INV=$(echo "$RUN" | j 'd["created"][-1]')
call GET "/api/v1/invoices/$INV" | j 'd["status"]' | grep -q DRAFT && ok "draft invoice made for this month" || bad "recurring draft $RUN"
call GET /api/v1/crm/recurring | python -c "import sys,json,datetime as D; r=[x for x in json.load(sys.stdin) if x['name']=='Warehouse rent' and x['customerId']==$C][0]; sys.exit(0 if r['nextDate']>'$TODAY' else 1)" && ok "next date moved one month" || bad "next date"
call POST /api/v1/crm/recurring/run | j "len([x for x in d['created'] if x==$INV])" | grep -q '^0$' && ok "running again makes no duplicate" || bad "duplicate"

if [ "${ISSUE:-0}" = 1 ]; then
  is=$(call POST "/api/v1/invoices/$INV/issue" '{"sendMode":"NOW"}')
  echo "$is" | j 'd.get("dueDate")' | grep -q "$(python -c "import datetime as d; print(d.date.today()+d.timedelta(days=30))")" && ok "due date from 30-day terms" || bad "terms $is"
fi

echo "== $pass passed, $fail failed"
[ "$fail" = 0 ]
