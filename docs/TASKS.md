# SoudaCore ERP — BIP Gulf Logistics · task plan with acceptance criteria

Inputs: `docs/ANALYSIS.md` (video A→Z + quotation gap analysis), quotation QT-2026-000002 v3, `docs/company/`.
Order = dependency order. Each task is done only when every acceptance criterion (AC) is verified by an automated
test (unit/IT or `deploy/smoke.sh`) or, for UI-only criteria, a browser check.

## Done (round 1)
- [x] R1.1 Fork + rebrand, BIP seller profile, logo, env files, UAT/prod DB split
- [x] R1.2 Warehouses, per-warehouse stock ledger, adjustments, transfers; invoice/credit-note stock movements
- [x] R1.3 Delivery notes (from invoice / standalone, partial, driver/vehicle, received-by, bilingual A4)
- [x] R1.4 Deploy pipeline (`deploy/deploy.sh`), server bootstrap, nightly backups
- [x] R1.5 Tests: 115 unit tests pass (9 live/DB skipped), smoke 27/27 local incl. ZATCA sandbox, 14/14 in Docker

---

## Phase A — Document flow ("relations like SAP B1")  ← video §1.1, §1.3  ✅ DONE — `deploy/smoke-flow.sh` 38/38
**A1 Document link foundation** ✅
- AC1 Table `doc_link(from_type, from_id, to_type, to_id)`; line tables keep `base_type/base_line_id`.
- AC2 `GET /documents/{type}/{id}/relations` returns the whole connected graph (nodes: type, id, number, date, party, customer/vendor ref, total, open/closed/paid state; edges).
- AC3 Remaining quantity per base line = base qty − qty already copied to non-cancelled targets.

**A2 Sales Order** (new document SO-YYYY-NNNNNN) ✅
- AC1 Create from a quotation (*Copy To*) or blank; carries customer, lines, sales employee, customer PO ref, remarks; remarks get "Based on Quotation QT-…".
- AC2 Statuses OPEN → PARTIALLY_DELIVERED → CLOSED (auto), CANCELLED (only with nothing delivered).
- AC3 Quotation becomes ACCEPTED/ORDERED when copied; it cannot be copied beyond its quantities.
- AC4 Bilingual A4 print (BIP letterhead).

**A3 Delivery from Sales Order** ✅
- AC1 *Copy To Delivery* pre-fills open quantities; several partial deliveries per order; over-delivery refused.
- AC2 Stock leaves the warehouse when the delivery is DELIVERED (moving-average cost recorded).
- AC3 Delivery remarks "Based on Sales Order SO-…".

**A4 Invoice from Deliveries** ✅
- AC1 *Copy To Invoice* from one or more deliveries of the same customer; invoiced qty per delivery line tracked; no double invoicing.
- AC2 Invoice issued from deliveries does **not** move stock again (already moved on delivery).
- AC3 Invoice remarks "Based on Sales Orders … Based on Deliveries …"; customer PO ref and sales employee printed.

**A5 Incoming payments (receipts) across invoices** ✅
- AC1 Receipt RC-… for a customer allocates one amount across several open invoices; an invoice can have several receipts.
- AC2 Invoice paid/due/overdue days reflect allocations; existing per-invoice payments keep working.

**A6 Relationship Map UI** ✅
- AC1 Button on quotation, order, delivery, invoice, receipt, PO, GRN, supplier invoice opens the map; nodes clickable; unpaid invoices marked red.

## Phase B — Purchasing / Requisition  ← quotation "Requisition", video §1.3
**B1 Suppliers** (contacts): name AR/EN, VAT, CR, address, phone, email, payment terms. AC: CRUD, search, cannot delete one with documents.
**B2 Purchase Order / Requisition** PO-…: AC1 statuses PENDING → APPROVED → PARTIALLY_RECEIVED → RECEIVED / CANCELLED; AC2 lines with qty, unit cost, VAT; currency + exchange rate; AC3 attachments; AC4 A4 print with amount in words.
**B3 Goods Receipt** GRN-… from PO: AC1 partial receipts, over-receipt refused; AC2 stock IN at landed unit cost (SAR), updates moving average.
**B4 Supplier Invoice** SINV-… from one or more GRNs (or direct): AC1 no double billing of GRN lines; AC2 payable balance.
**B5 Supplier Payments** with allocation across supplier invoices: AC paid/due per PO/supplier invoice, settlement adjustments.

## Phase C — Inventory valuation  ← video §1.4
**C1 Moving-average cost**: AC1 each IN movement recomputes avg cost = (old value + in value)/(old qty + in qty); OUT movements valued at current avg; AC2 `stock_movement` stores unit_cost, value, balance_value.
**C2 Inventory Audit Report**: AC per item/warehouse: date, document, qty, cost, transaction value, cumulative qty, cumulative value; date filter; Excel + PDF.
**C3 Item master**: AC Arabic name, category, brand, barcode (+ barcode label print), item flags (inventory/sales/purchase), import products + opening stock from Excel.

## Phase D — HR & Payroll  ← quotation item 1 "HR and payroll"
**D1 Employees**: AC1 code, name AR/EN, nationality, Iqama/ID no. + **expiry** (dashboard warning 30 days before), passport + expiry, job title, department, join date, bank IBAN, basic, housing, transport, other allowances, GOSI flag; AC2 active/terminated.
**D2 Leave ledger + holidays**: AC1 leave types (annual/sick/unpaid/other), balance per employee (accrual 21 or 30 days/year configurable); AC2 company holidays calendar; unpaid leave deducted in payroll.
**D3 Salary advances**: AC advance with instalment amount; outstanding balance; deducted automatically by payroll runs until settled.
**D4 Payroll runs**: AC1 run per month: DRAFT → APPROVED → PAID; one line per active employee: basic + allowances + overtime + other additions − GOSI (simplified flat %) − advances − unpaid-leave − other deductions = net; AC2 editable while draft; AC3 approval locks; paying posts an expense to Accounts.
**D5 Payslips**: AC bilingual A4 payslip PDF per employee per run.
**D6 Payroll & leave report**: AC month/employee filters; Excel + PDF.

## Phase E — Point of sale  ← quotation "Point of sale"
AC1 product grid by warehouse with search/barcode; AC2 stock-aware cart (cannot exceed stock); AC3 live VAT + totals, discount; AC4 cash / card / credit (customer required) / partial; AC5 issues a simplified ZATCA invoice with QR; AC6 80 mm receipt print.

## Phase F — Sales returns & invoice print
F1 Partial / full return from an invoice (pick lines + qty) → credit note + stock back. F2 Invoice print: bank details (Settings), amount in words (EN/AR), customer PO ref, sales employee, branch footer; 80 mm template.

## Phase G — Settings, reports, dashboard
G1 Custom roles + permission matrix (module × view/create/edit/delete/approve), per-user override. G2 Reports (sales by day/month/product/customer/user, stock, purchase, expense, VAT summary) with Excel + PDF. G3 Dashboard tiles + 7-day sales trend + stock by warehouse + low-stock table + Iqama expiry. G4 Global quick search.

## Phase H — Server & go-live
- [ ] H1 SSH key on 2.24.60.124 (client side, once) → `deploy/deploy.sh bootstrap`
- [ ] H2 UAT on http://2.24.60.124:8088 with ZATCA SIMULATION device (OTP from Fatoora simulation portal)
- [ ] H3 Go-live: DB_NAME=bip_prod, ORG_ZATCA_ENVIRONMENT=PRODUCTION, production OTP, domain + HTTPS
