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

Backend unit tests: 115 passed, 9 skipped (live-only). Empty-database crawl of every list/report endpoint on UAT: all 200.

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
- [x] **UAT** http://2.24.60.124:8088 on `bip_uat`, ZATCA SIMULATION, nightly backups, firewall, fail2ban

## Waiting on the client

- [ ] ZATCA **Simulation** device on UAT: OTP from the Fatoora simulation portal (Mr. Umer Wahab)
- [ ] Go-live: `DB_NAME=bip_prod`, `ORG_ZATCA_ENVIRONMENT=PRODUCTION`, production OTP, domain + HTTPS (then `FATOORA_COOKIE_SECURE=true`)
- [ ] Bank details for invoices (Settings → Bank details) and department roles for staff

## Recommended next phase (outside the signed quotation)

Double-entry general ledger: chart of accounts, automatic journals from all documents, trial balance, balance sheet,
bank reconciliation, fixed assets (see `docs/ERP-BENCHMARK.md`).
