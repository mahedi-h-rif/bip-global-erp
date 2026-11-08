#!/usr/bin/env bash
# Phase D acceptance test: employees, holidays, leave balance, unpaid leave, salary advance, payroll run
# (generate → edit → approve → pay), payslip PDF, payroll & leave Excel, advance carried to the next month.
#   BASE=http://localhost:8080 EMAIL=... PASSWORD=... deploy/smoke-hr.sh
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
near() { python -c "import sys; sys.exit(0 if abs(float(sys.argv[1])-float(sys.argv[2]))<0.02 else 1)" "$1" "$2"; }

echo "== $BASE"
curl -s -c "$JAR" "$BASE/api/v1/auth/csrf" >/dev/null
call POST /api/v1/auth/login "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" >/dev/null
call GET /api/v1/auth/me | grep -q '"role"' && ok login || { bad login; exit 1; }
S=$(date +%s); Y=$((2031 + S % 60))

A=$(call POST /api/v1/hr/employees "{\"name\":\"Ahmed $S\",\"nationality\":\"SA\",\"idNumber\":\"1012345678\",\"idExpiry\":\"$Y-12-01\",\"jobTitle\":\"Driver\",\"department\":\"Logistics\",\"joinDate\":\"$((Y-1))-01-01\",\"basic\":6000,\"housing\":1500,\"transport\":500,\"iban\":\"SA0380000000608010167519\"}")
AID=$(echo "$A" | j 'd["id"]'); near "$(echo "$A" | j 'd["gosiPct"]')" 9.75 && ok "Saudi employee, GOSI 9.75% by default" || bad "employee A $A"
B=$(call POST /api/v1/hr/employees "{\"name\":\"Rahim $S\",\"nationality\":\"IN\",\"joinDate\":\"$Y-11-16\",\"basic\":3000,\"housing\":750,\"jobTitle\":\"Helper\"}")
BID=$(echo "$B" | j 'd["id"]'); near "$(echo "$B" | j 'd["gosiPct"]')" 0 && ok "non-Saudi employee, GOSI 0%" || bad "employee B $B"

call POST /api/v1/hr/holidays "{\"date\":\"$Y-03-03\",\"name\":\"Test holiday\"}" >/dev/null
L=$(call POST /api/v1/hr/leaves "{\"employeeId\":$AID,\"type\":\"ANNUAL\",\"startDate\":\"$Y-03-01\",\"endDate\":\"$Y-03-07\"}")
python -c "import sys,json,datetime as D; d=json.loads(sys.argv[1]); s=D.date($Y,3,1); n=sum(1 for i in range(7) if (s+D.timedelta(i)).weekday() not in (4,5) and (s+D.timedelta(i))!=D.date($Y,3,3)); sys.exit(0 if float(d['days'])==n else 1)" "$L" \
  && ok "annual leave counts working days only ($(echo "$L" | j 'd["days"]'): no Fri/Sat, no holiday)" || bad "leave days $L"
over=$(call POST /api/v1/hr/leaves "{\"employeeId\":$AID,\"type\":\"ANNUAL\",\"startDate\":\"$Y-05-01\",\"endDate\":\"$Y-07-31\"}")
echo "$over" | grep -q leave_balance && ok "annual leave beyond balance refused" || bad "over-balance: $over"
ovl=$(call POST /api/v1/hr/leaves "{\"employeeId\":$AID,\"type\":\"SICK\",\"startDate\":\"$Y-03-05\",\"endDate\":\"$Y-03-06\"}")
echo "$ovl" | grep -q leave_overlap && ok "overlapping leave refused" || bad "overlap: $ovl"
call POST /api/v1/hr/leaves "{\"employeeId\":$AID,\"type\":\"UNPAID\",\"startDate\":\"$Y-11-10\",\"endDate\":\"$Y-11-11\"}" >/dev/null
call POST /api/v1/hr/advances "{\"employeeId\":$AID,\"date\":\"$Y-10-01\",\"amount\":3000,\"installment\":1000}" | j 'float(d["outstanding"])' | grep -q '^3000.0$' && ok "salary advance 3000 (1000/month)" || bad "advance"

R=$(call POST /api/v1/payroll/runs "{\"year\":$Y,\"month\":11}")
RID=$(echo "$R" | j 'd["id"]'); echo "$R" | j 'd["status"]' | grep -q DRAFT && ok "payroll $(echo "$R" | j 'd["number"]') DRAFT" || bad "run $R"
line() { echo "$1" | python -c "import sys,json; d=json.load(sys.stdin); print(json.dumps([l for l in d['lines'] if l['employeeId']==$2][0]))"; }
LA=$(line "$R" $AID); LB=$(line "$R" $BID)
near "$(echo "$LA" | j 'd["gosi"]')" 731.25 && ok "A: GOSI 731.25" || bad "A gosi $(echo "$LA" | j 'd["gosi"]')"
near "$(echo "$LA" | j 'd["unpaidLeaveDeduction"]')" 533.33 && ok "A: 2 unpaid days = 533.33" || bad "A unpaid $(echo "$LA" | j 'd["unpaidLeaveDeduction"]')"
near "$(echo "$LA" | j 'd["advanceDeduction"]')" 1000 && ok "A: advance installment 1000" || bad "A advance"
near "$(echo "$LA" | j 'd["net"]')" 5735.42 && ok "A: net 5735.42" || bad "A net $(echo "$LA" | j 'd["net"]')"
near "$(echo "$LB" | j 'd["daysWorked"]')" 15 && near "$(echo "$LB" | j 'd["net"]')" 1875 && ok "B: joined mid-month → 15 days, net 1875.00" || bad "B $LB"
LAID=$(echo "$LA" | j 'd["id"]')
near "$(call PUT "/api/v1/payroll/runs/$RID/lines/$LAID" '{"overtime":200,"notes":"Weekend shift"}' | python -c "import sys,json; d=json.load(sys.stdin); print([l for l in d['lines'] if l['employeeId']==$AID][0]['net'])")" 5935.42 && ok "overtime 200 → net 5935.42" || bad "overtime"
dup=$(call POST /api/v1/payroll/runs "{\"year\":$Y,\"month\":11}")
echo "$dup" | grep -q payroll_exists && ok "second payroll for the same month refused" || bad "dup: $dup"
call POST "/api/v1/payroll/runs/$RID/approve" | j 'd["status"]' | grep -q APPROVED && ok "approved" || bad "approve"
lk=$(call PUT "/api/v1/payroll/runs/$RID/lines/$LAID" '{"overtime":999}')
echo "$lk" | grep -q payroll_locked && ok "approved payroll is locked" || bad "lock: $lk"
call GET /api/v1/hr/employees | python -c "import sys,json; d=[e for e in json.load(sys.stdin) if e['id']==$AID][0]; sys.exit(0 if abs(float(d['advanceOutstanding'])-2000)<0.01 else 1)" && ok "advance outstanding 2000 after approval" || bad "outstanding"
call POST "/api/v1/payroll/runs/$RID/pay" '{"method":"BANK_TRANSFER"}' | j 'd["status"]' | grep -q PAID && ok "paid (salary expense posted)" || bad "pay"
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/api/v1/payroll/runs/$RID/payslip/$LAID"); [ "$code" = 200 ] && ok "payslip PDF" || bad "payslip $code"
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/api/v1/payroll/runs/$RID/report.xlsx"); [ "$code" = 200 ] && ok "payroll & leave Excel" || bad "report $code"
R2=$(call POST /api/v1/payroll/runs "{\"year\":$Y,\"month\":12}")
near "$(line "$R2" $AID | j 'd["advanceDeduction"]')" 1000 && ok "December deducts the next 1000" || bad "dec advance"
near "$(line "$R2" $AID | j 'd["unpaidLeaveDeduction"]')" 0 && ok "November's unpaid leave not repeated" || bad "dec unpaid"
call DELETE "/api/v1/payroll/runs/$(echo "$R2" | j 'd["id"]')" >/dev/null && ok "draft payroll deleted"

echo "== $pass passed, $fail failed"
[ "$fail" = 0 ]
