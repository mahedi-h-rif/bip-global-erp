# Benchmark against Odoo and Oracle ERP — what BIP Gulf needs for complete operations

BIP Gulf Logistics Services is a **logistics / transport services** company that also trades packing goods (from the
meeting: quotations → orders delivered monthly, purchasing from local and foreign vendors, warehouses, drivers and
vehicles). The table compares the main Odoo apps and Oracle (NetSuite / Fusion Cloud ERP) process areas with what
SoudaCore ERP now has.

| Area | Odoo | Oracle | SoudaCore ERP (now) | Gap for BIP |
|---|---|---|---|---|
| Quote-to-cash | Sales: quotations, orders, deliveries, invoices, payments | Order-to-Cash | ✅ quotation → order → partial deliveries → invoice → receipts, relationship map | **Customer credit limit & payment terms** (both block orders over limit) |
| CRM | Leads, pipeline, activities | CX Sales | ❌ | **Lead / opportunity pipeline → quotation** |
| Recurring billing | Subscriptions | Subscription Management | ❌ | **Monthly contract invoices** (logistics contracts are billed monthly) |
| Price lists | Pricelists per customer | Pricing | ❌ (one price per item) | Customer-specific prices (Digi POS had "selling price groups") |
| Procure-to-pay | Purchase, RFQ, receipts, bills, 3-way match | Procure-to-Pay | ✅ PO (approval, FX) → GRN (landed cost) → supplier invoice → payment | **Reorder suggestions → PO** (Odoo replenishment) |
| Inventory | Multi-warehouse, valuation, reordering, lots/serials | Inventory Mgmt | ✅ warehouses, transfers, moving average, audit report | Reorder rules (above); lots/serials not needed for BIP's goods |
| **Fleet** | Fleet: vehicles, contracts, fuel, services, odometer | Fleet / Maintenance | ❌ (driver & plate typed as text) | **Vehicles with Istimara / insurance / MVPI expiry, fuel & maintenance log** |
| **Transport / trips** | (Odoo field service + delivery) | Transportation Management (OTM) | ❌ | **Trips: vehicle + driver + route, linked deliveries, trip costs, profitability per trip** |
| POS | Point of Sale | Retail POS | ✅ grid, barcode, split payment, 80 mm receipt, ZATCA simplified | — |
| HR & payroll | Employees, leave, payroll, expenses | HCM | ✅ employees, ID expiry, leave, holidays, advances, payroll, payslips | Attendance-driven overtime (optional) |
| Approvals | Approvals app, thresholds | Approval workflows (BPM) | ⚠️ PO and payroll approval (single step) | **Amount thresholds** (e.g. POs above X need admin) — covered by roles |
| Accounting | Full double entry, bank rec, assets, budgets | General Ledger, Cash Mgmt, Fixed Assets | ⚠️ cash book, receivables, payables, P&L, VAT summary | Double-entry GL is **excluded in the quotation**; recommended next phase |
| Reporting | Pivot / graph views, exports | OTBI / BI | ✅ 8 reports with Excel + PDF, dashboard | — |
| Documents & attachments | Documents app | Content | ✅ company documents with expiry; PO attachments | — |
| Saudi compliance | ZATCA Phase 2 (localization) | ZATCA via partners | ✅ native Phase 2, PDF/A-3, QR | — |

## Decision

Implemented now, because BIP's daily operations need them (Phase H):

1. **H1 Fleet** — vehicles (plate, type, capacity, Istimara/insurance/MVPI expiry with dashboard alerts), assigned driver
   (employee), fuel and maintenance log feeding expenses.
2. **H2 Trips** — a trip (TRP-) is one vehicle + driver run: route, date, status PLANNED → IN_TRANSIT → COMPLETED, the
   delivery notes it carries (vehicle/driver filled onto them), trip costs (fuel, tolls, driver allowance, other) posted to
   expenses, and revenue from the linked invoices → **profit per trip**.
3. **H3 Customer credit control** — credit limit and payment-terms days per customer; due date defaults from the terms;
   issuing an invoice or creating a sales order beyond the limit is refused (admin can still override by raising the limit).
4. **H4 Reorder suggestions** — items at or below their low-stock alert, with a suggested quantity, turned into a
   requisition (PO) for a chosen supplier in one click.
5. **H5 CRM pipeline** — leads/opportunities (NEW → QUALIFIED → PROPOSAL → WON / LOST) with expected value, that become a
   customer and a quotation.
6. **H6 Recurring invoices** — monthly contract billing: a template that the scheduler turns into a draft invoice each
   period (staff review and issue).

Recommended next phase (outside the signed quotation, needs an accountant's chart of accounts): **double-entry general
ledger** (automatic journal entries from invoices, receipts, supplier invoices, payments, payroll and stock), trial balance,
balance sheet, bank reconciliation, fixed assets.
