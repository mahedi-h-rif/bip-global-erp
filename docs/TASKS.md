# SoudaCore ERP — BIP Gulf Logistics · task plan & status

Inputs: `docs/ANALYSIS.md` (video A→Z + quotation gap analysis), `docs/ERP-BENCHMARK.md` (Odoo / Oracle comparison),
quotation QT-2026-000002 v3, `docs/company/`.

Every phase is verified by an acceptance script against a running system (ZATCA developer sandbox for issuing):

| Script | Covers | Result |
|---|---|---|
| `deploy/smoke.sh` | warehouses, stock, transfers, delivery notes, invoice + credit note stock | 27/27 |
| `deploy/smoke-flow.sh` | quotation → sales order → partial deliveries → invoice → receipts, cancel/repost, relationship map | 42/42 |
| `deploy/smoke-purchase.sh` | suppliers, PO approval (BHD), GRN + landed cost, moving average, inventory audit, supplier invoice/payment | 30/30 |
| `deploy/smoke-hr.sh` | employees, leave in working days, holidays, advances, payroll run, payslip, report | 24/24 |
| `deploy/smoke-erp.sh` | POS + 80 mm receipt, returns, invoice bank details, department permissions, 8 reports, dashboard, search | 34/34 |
| `deploy/smoke-ops.sh` | fleet, trips & profit, credit limit, payment terms, reorder → PO, CRM, recurring invoices | 25/25 |
| `deploy/smoke-lifecycle.sh` | units (piece/dozen/carton), delivery from 2 orders, invoice from order, close order/PO, receipt from 2 POs, statuses | 37/37 |
| `deploy/smoke-video.sh` | purchase request → PO, goods return, A/P credit memo, gross profit, partner maps, dashboard charts, print without logo, proforma | 41/41 |

Backend unit tests: 130 run, 10 skipped (live-only), 0 failures. Empty-database crawl of every list/report endpoint on UAT: all 200.

## Phases

- [x] **R1** Foundation: fork, rebrand, BIP seller profile, logo, warehouses, stock, transfers, delivery notes, deploy pipeline
- [x] **A** Document flow (SAP style): sales orders, deliveries from orders, invoice from deliveries, receipts, relationship map;
      one quotation → one order; receipts cancelled not deleted (meeting rules)
- [x] **B** Purchasing / requisition: suppliers, PO with approval + attachments + currency, GRN, landed costs, supplier invoices, payments
- [x] **C** Inventory valuation: moving-average cost, inventory audit report (+Excel), item master (Arabic name, brand, category, barcode)
- [x] **D** HR & payroll: employees with ID/passport expiry, leave ledger, holidays, salary advances, payroll runs, payslips, report
- [x] **E** Point of sale: barcode grid per warehouse, stock-aware cart, split payment, credit sales, ZATCA simplified invoice, 80 mm receipt
- [x] **F** Returns & print: partial/full returns → credit note + stock; invoice PDF with due date, PO, sales employee, amount in words, bank details
- [x] **G** Settings, reports, dashboard: department roles + permission matrix, sales/stock/purchase/expense/VAT/P&L/aging reports (Excel+PDF), ERP dashboard, quick search
- [x] **H** Benchmark additions: fleet (Istimara/insurance/Fahas expiry, fuel, maintenance), trips with costs and profit, customer credit limits
      and payment terms, reorder suggestions → PO, CRM pipeline, recurring contract invoices
- [x] **I** Video integration (SAP B1 parity): purchase requests → PO, goods returns from receipts, A/P credit memos (lower payables
      and input VAT), gross profit on orders/invoices + GP report, business-partner relationship maps, dashboard charts
      (12-month sales/purchases/GP, top customers/items, aging), inventory audit chart, print without logo, proforma invoice
- [x] **UAT** http://2.24.60.124:8088 on `bip_uat`; **Production** http://2.24.60.124:8089 on `bip_prod`; both ZATCA PRODUCTION
      (devices BIP-UAT-2 / BIP-PROD-2, Industry "Trading", certificates to 08 Nov 2031); nightly backups, firewall, fail2ban

## Phase J — document lifecycle to market standard + units of measure (in progress)

Rules (SAP Business One / Odoo): a document is OPEN until fully copied to its target, then CLOSED; it can be closed by hand
(remaining quantity dropped) or CANCELLED only while nothing was copied from it. Closed/cancelled documents can't be edited or
copied. Targets may combine several base documents of the same business partner; every line keeps its base line, so remaining
quantities, statuses and the relationship map follow the data. One quotation → one sales order (BIP rule).

- [x] J1 Units of measure: item base unit + alternative units (dozen, carton…) with factor, own price and barcode, default sales /
      purchase unit; every document line keeps unit + factor; stock always posts in the base unit (qty × factor)
- [x] J2 Sales: close order by hand, one delivery from several orders of the same customer, invoice straight from an order
      (services / no delivery), delivery- and invoice-status on orders, "Based on" text, map edges per line
- [x] J3 Purchasing: close PO by hand, one goods receipt from several POs of the same supplier, receiving-/billing-status on POs
- [x] J4 Full web test on UAT (CRUD of every module, edge cases, security), fix + retest until clean
      (ZATCA: only 2 real invoices + 2 full credit notes on customer "TEST – SoudaCore QA")
- [x] J5 Report (web page): screenshots, module connectivity, database relations, data effects, business flow

Result: 260 business checks + 33 security checks + 80 browser checks + 130 unit tests pass locally on the final code;
on UAT 217/217 business checks (no ZATCA submission), 79/80 browser, 29/31 security before the fixes (fixed, deploy pending approval).
Report: https://claude.ai/artifact/5cDsoHxSTFQP42W3qL276t

## Waiting on the client

- [ ] Revoke the retired "Logistics" devices BIP-PROD-1 / BIP-UAT-1 in the Fatoora portal (client to confirm)
- [ ] Domain + HTTPS for production (then `FATOORA_COOKIE_SECURE=true`)
- [ ] Bank details for invoices (Settings → Bank details) and department roles for staff

## Recommended next phase (outside the signed quotation)

Double-entry general ledger: chart of accounts, automatic journals from all documents, trial balance, balance sheet,
bank reconciliation, fixed assets (see `docs/ERP-BENCHMARK.md`).
