# Requirements analysis — BIP Gulf ERP

Sources:
1. **Video** `Screen Recording 2026-11-07 (Recovered).mp4` (32 min, Google Meet, 7 Nov 2026 ~21:48–22:23).
   Part A (≈21:48–22:15): Shafiqul (Gulf Season Trading / GS Packaging) demonstrates **SAP Business One** as the reference for
   how documents must relate. Part B (≈22:19–22:23): Anwar (BIP) shows BIP's **current system, "Digi POS v7.1"**
   (ksa.diginextit.com), which this ERP replaces. Narration is Bangla; translated transcript appended below when ready.
2. **Quotation** QT-2026-000002 v3 (SoudaCore ERP Lite, 7 items).

---

## 1. What the video shows (A → Z)

### 1.1 Sales document chain (SAP B1) — the core requirement: "relations like that"
| Step | Screen | What matters |
|---|---|---|
| 1 | **Sales Quotation** | Customer + contact person, posting/valid-until/document dates, item lines (item no., description, qty, unit price, discount %, tax code, warehouse, UoM), **sales employee**, owner, **remarks** (delivery 2–3 days, payment 50 % advance 50 % on 30 days, validity 5 days). Totals before discount, discount, tax, total. Printed bilingual quotation with logo. |
| 2 | **Copy To → Sales Order** | The order is created *from* the quotation; lines, prices, sales employee and remarks are carried over, remarks gain "Based on Sales Quotations 2610740". |
| 3 | **Copy To → Delivery** | Created from the order; **partial deliveries** allowed (one order → 3 deliveries). Delivery prints as a Delivery Note (customer, salesman, goods, qty, UoM, "Received in good condition" / "For Gulf Season Trading"). Stock leaves the warehouse here. |
| 4 | **Copy To → A/R Invoice** | Created from one or more deliveries; remarks "Based on Sales Orders X. Based on Deliveries Y". Shows GP value / GP %. |
| 5 | **Incoming Payment** | Selects open invoices of the customer (document no., installment, customer PO ref, total, balance due, overdue days) — **one payment can settle several invoices**, and an invoice can be paid by several payments. |
| 6 | **Relationship Map** | Graph of every linked document: Business Partner → Sales Order → Deliveries → A/R Invoices → Incoming Payments, each node showing number, date, **customer PO ref (PO-0000004114)** and amount; open (unpaid) invoices underlined red. |

### 1.2 Tax invoice print (ZATCA)
QR code; bilingual title; invoice no., issue date, **payment due date**, date of supply, **customer ref (PO) no.**, **sales employee**, remarks with base documents; bilingual **Seller / Buyer national-address grid** (name, building no., street, district & city, country, postal code, additional no., VAT, other seller ID/CR); bilingual line items (nature of goods, unit price, qty, UoM, discount, taxable amount, tax rate, tax amount, total incl. VAT); **bank details** (account name, account no., IBAN, bank name); totals in SAR; "system-generated e-invoice, no signature required"; **branch offices footer**. Two print templates (with / without logo).

### 1.3 Purchasing chain (SAP B1)
Purchasing menu: Purchase Request, Purchase Quotation, **Purchase Order**, **Goods Receipt PO**, Goods Return, **A/P Invoice**, A/P Credit Memo, Landed Costs.
- **Purchase Order** (approved), vendor + vendor ref no., **foreign currency** (BHD, rate 9.92), warehouse, printed PO with amount in words.
- **Goods Receipt PO** created from the PO, **partial** (one PO → 3 GRPOs), stock comes in at cost; "Based on Purchase Orders 32620128".
- **A/P Invoice** created from one or more GRPOs (many-to-many).
- Relationship map for the vendor: PO → GRPOs → A/P invoices.

### 1.4 Item master & inventory valuation
- **Item Master Data**: item no., description, **foreign (Arabic) name**, item type, **item group**, **UoM group**, **barcode**, price list / unit price, flags *inventory / sales / purchasing item*, active/inactive, manufacturer, remarks, attachments.
- **Inventory Audit Report** (per item, all warehouses): system date, posting date, **document** (DN/IN/PD/IF…), quantity, **cost**, transaction value, **cumulative qty**, **cumulative value** — i.e. **moving-average costing** with a full valuation ledger.

### 1.5 BIP's current system (Digi POS) — features BIP already uses and expects
- **Products**: list, add (name, SKU, **barcode type**, unit, **brand**, **category**, business location, manage stock, **alert quantity**, description, brochure), update price, **print labels**, variations, **import products**, **import opening stock**, selling price groups, units, categories, brands, warranties.
- **Sell**: all sales, add sale, **POS**, drafts, quotations, **sell return**, shipments, discounts, import sales.
- **Quotation print** on BIP letterhead (bilingual header with VAT + CR, customer tax no., code, item, qty, unit price, VAT 15 %, tax amount, subtotal, total with tax, **amount in words**, discount, delivery/payment terms, *Accountant* / *Client sign*).
- **Draft/quotation actions**: view, edit, print, **convert to proforma invoice**, delete, copy quotation, **notify customer**, **shareable quote URL**.
- **Sell details**: status, payment status, delivered-to + delivery person, discount, order tax, line taxes, shipping, round-off, total payable / paid / remaining, sell note, staff note, **activity log (who changed what, before → after)**, **Packing slip**, Print invoice.
- Other menus: Purchases, Stock transfers, Stock adjustment, Expenses, Accounting, ZATCA, Reports, Notification templates, Settings, **HRM**, User management, Contacts (customers + suppliers).

---

## 2. Quotation scope vs. what exists (after round 1)

| Quotation item / module | Status | Gap |
|---|---|---|
| Platform: online, setup wizard, safe-delete, print/PDF | ✅ | — |
| Dashboard: sales, invoice, stock, ZATCA, employee, payroll tiles; 7-day trend; stock value by warehouse; low-stock table | ⚠️ partial | stock/employee/payroll tiles, 7-day trend, stock-by-warehouse, low-stock table |
| **Point of sale** | ❌ | whole module |
| Inventory: products, SKU, cost, price, low-stock, per-warehouse stock, transfers | ✅ (round 1) | **moving-average cost**, audit report, item group/brand/barcode/Arabic name |
| Sales & invoices: bilingual simplified invoice, **80 mm + A4**, further payments, **full & partial returns** with stock + accounting | ⚠️ | 80 mm receipt, partial-return UI from invoice lines |
| Quotations: statuses, product/free-text lines, print | ✅ | convert to Sales Order, share URL |
| **Requisition**: Pending → Received, stock receipt, paid/due, settlements, attachments, A4 | ❌ | suppliers, PO, GRN, supplier invoice/payments |
| **HR & payroll**: employees with ID expiry, leave ledger, holidays, payroll runs, payslips, salary advances, payroll & leave report | ❌ (only a simple worker ledger) | whole module |
| Accounting & expenses: cash, bank, receivables, payables, monthly P&L, net VAT, balance adjustments, expense log with receipts | ⚠️ | payables, P&L, net VAT, bank vs cash |
| Settings: company, warehouses, users, **feature authorization per user, custom roles + permission matrix** | ⚠️ | permission matrix |
| ZATCA Phase 1 + 2 | ✅ | — |
| **Reports**: PDF + Excel; sales by day/month/product/customer/user; stock, purchase, expense; VAT summary; date + warehouse filters | ⚠️ | most reports + Excel/PDF export |
| Everyday tools: login/reset, activity log, logo on prints, quick search | ⚠️ | global quick search |
| Item 2 Sales & delivery: delivery notes (partial, driver, vehicle, signature, A4) ✅; quote→invoice ✅; credit/debit notes ✅ | ⚠️ | **Sales Order** step + document relations |
| Item 4 Dues & cash book | ✅ | — |
| Item 5 Agreements & letterhead | ✅ | — |
| Item 6 ZATCA Phase 2 | ✅ (sandbox verified) | simulation + production onboarding (OTP) |
| Item 7 Deployment | ⚠️ pipeline ready | SSH key (client side) |

## 3. Design decision: one document-flow model for sales and purchasing
Every business document (quotation, sales order, delivery note, invoice, credit note, receipt, purchase order, goods receipt,
supplier invoice, supplier payment) gets a row in **`doc_link`** (`from_type, from_id → to_type, to_id`, with per-line
quantities in the line tables via `base_line_id`). This gives, exactly like SAP B1:
- **Copy To / Copy From** with remaining quantities (partial),
- "Based on …" remarks generated automatically,
- a **Relationship Map** endpoint that walks the graph from any document,
- open-quantity status: an order is *Open* until fully delivered, *Closed* when done.

---

## 4. What was said (Bangla narration, machine-translated with Whisper medium)
Translation is rough; these points are consistent across the transcript and the screens.

| Time | Point | Effect on the build |
|---|---|---|
| 01:48–02:45 | "Our sequence is: first the sales order, then the delivery note, then the invoice." Copy To from a quotation can also go straight to delivery or invoice. | Phase A flow ✅ |
| 02:22–02:45, 07:23–08:11 | Long orders: one order of 500–550 units for six months, ~100 delivered per month; each delivery invoiced separately; the order shows *closed* once fully delivered. | Partial deliveries, per-delivery invoicing, auto-close ✅ |
| 08:11–08:39 | Once delivered, an order is locked — "we can't edit or change it, we have to make it new". | Orders with deliveries can't be edited ✅ |
| 10:24–10:52 | "We can't make one quotation and two sales orders … one quotation, one sales order, five deliveries." | **Rule enforced:** a quotation can have one active sales order (`quotation_ordered`) ✅ |
| 14:42–15:54 | Incoming payment for the first invoice shows on the map; a payment posted with the wrong date was *cancelled and reposted*, shown with a cross, not deleted. | **Receipts are cancelled, never deleted** (V25), kept on the map ✅ |
| 16:14–18:40 | Purchase orders to local or international vendors (e.g. China); goods receipt into a chosen warehouse (e.g. 36 rolls of stretch film). | Phase B (suppliers, PO with currency, GRN per warehouse) |
| 27:23–27:45 | Costs paid after purchase (transport account) are added to the purchase. | Phase B: **landed costs** on goods receipts → item cost |
| 20:10–22:31 | Separate sales, purchase, finance and cost departments, each with its own authority. | Phase G1: roles + permission matrix per department |
| 28:20–29:46 | Digi POS: products with brand and unit, add purchase, add sale, list with export, print with logo. | Item master (C3), reports with export (G2) |
| 29:48–31:52 | Quotation print shows name, logo and website; from the quotation view it converts (to proforma / invoice). | Quotation print ✅ (letterhead); proforma print → Phase F |
