# Feature plan: products, payments & dues, reminders, sales report, normal user

Scope agreed for Astha Gulf Trading. Everything stays in the ZATCA **SANDBOX** environment while it is built.

## 1. Findings from the existing code

- Every business table carries `organization_id`; controllers scope with `CurrentUser.orgId()`.
- Roles: `OPERATOR > ADMIN > ACCOUNTANT > VIEWER` (Spring role hierarchy). Only ADMIN and OPERATOR exist today.
- Invoices (`invoice`, `invoice_line`) go `DRAFT → SIGNED → CLEARED/REPORTED (± warnings) | REJECTED`.
  `InvoiceService.requireDraft` already refuses edits and deletes of issued documents.
  Credit and debit notes are separate `invoice` rows with `original_invoice_id` pointing at the invoice.
- There is no payment, product or notification concept yet. `/dashboard` is open to every role.
- Customer create/update have no role check (a VIEWER could write); there is no customer delete.

## 2. Design per requirement

### 2.1 Products
- New table `product (id, organization_id, name, unit_price NUMERIC(18,6), active, created_at, updated_at)`,
  unique per organization on `lower(name)` among active products.
- `invoice_line.product_id` (nullable FK) records which product a line came from. The line still keeps its own
  name and price, so editing or removing a product never changes an issued invoice.
- Delete: a product never used on an invoice is deleted; one that is referenced is **deactivated** instead
  (hidden from the picker, kept for history, can be re-activated).

### 2.2 Invoice form ↔ products
- Each line's description input offers the active products (datalist). Choosing one fills name, unit price and
  `productId`; both stay editable.
- "Add product" panel under the lines: name + price → `POST /products` → appended as a new line and added to the list.

### 2.3 CRUD and the ZATCA rule
| Entity | Create | Read | Update | Delete |
|---|---|---|---|---|
| Product | yes | yes | yes | delete or deactivate |
| Customer | yes | yes | yes | only if it has no invoices (otherwise 409) |
| Invoice | draft | yes | **draft only** | **draft only** |
| Payment | yes | yes | yes | yes |

Issued invoices (SIGNED, CLEARED, REPORTED, REJECTED) are never edited or deleted. They are corrected with
credit/debit notes. The only exception is the internal **due date**, which is not part of the ZATCA XML.
The UI shows a lock notice on issued documents.

### 2.4 Normal user
- Reuse `ACCOUNTANT` ("Staff" in the UI). Seeded on boot from `FATOORA_USER_EMAIL / FATOORA_USER_PASSWORD /
  FATOORA_USER_NAME` (created once, like the operator).
- Backend: `/dashboard` and `/reports/**` require ADMIN. Frontend hides "Dashboard" and redirects `/` to `/invoices`.

### 2.5 Sales report (dashboard, admin only)
`GET /api/v1/reports/sales?from&to&customerId&q&hourFrom&hourTo`
- Counts issued documents (not DRAFT, not REJECTED) in the date range (KSA time), optionally within an hour-of-day
  window and for one customer / a customer-name search.
- Figures: invoice count, gross sales (invoices + debit notes), credit notes, **net sales**, VAT, **collected**
  (payments against those invoices), **due** (net − collected). Net sales = collected + due.
- Month-wise rows with the same columns, plus a day-wise list for the selected range.
- UI presets: today, this week, this month, last month, this year, custom range; time-of-day: all, morning,
  afternoon, evening, night.

### 2.6 Payments and dues
- New table `invoice_payment (id, organization_id, invoice_id, amount, paid_on, method, reference, note, created_by, …)`.
- New column `invoice.due_date` (optional; editable at any time).
- Balance of an invoice (type INVOICE, status not DRAFT/REJECTED):
  `adjusted total = total + Σ debit notes − Σ credit notes` (issued notes only),
  `paid = Σ payments`, `due = max(adjusted − paid, 0)`, status `UNPAID / PARTIAL / PAID`.
  A payment may not exceed the remaining due.
- Pure calculation lives in `BalanceCalculator` (unit tested); `BalanceService` loads figures with lightweight queries.
- Endpoints: `GET/POST /invoices/{id}/payments`, `PUT/DELETE /payments/{id}`, `PUT /invoices/{id}/due-date`,
  `GET /dues` (invoice-wise), `GET /dues/customers` (customer-wise aggregates).
- UI: "Due" menu with sub-items **All dues**, **Customer-wise due**, **Reminder log**.

### 2.7 Automatic reminders
- `customer.reminders_enabled` (default true) and `customer.last_reminder_at`.
- `ReminderScheduler` runs every `FATOORA_REMINDER_CHECK_INTERVAL` (default 6 h). A customer gets a reminder when
  it has a due balance, reminders are on (globally `FATOORA_REMINDERS_ENABLED` and per customer), it has an email or
  mobile, and `last_reminder_at` (or, if never reminded, the oldest due invoice's issue date) is at least
  `FATOORA_REMINDER_INTERVAL` (default 15 days) ago.
- Email through Spring Mail (`SPRING_MAIL_*`), SMS through `SmsSender` with a generic HTTP form-POST provider
  (works with Unifonic, Twilio, Msegat, Taqnyat… by configuration). Unconfigured → message is written to the log
  and stored with status `LOGGED`, never an error.
- Every attempt goes into `notification_log`, shown under Due → Reminder log.
- Messages are bilingual and include each due invoice with a **public PDF link**
  (`/api/v1/public/invoices/{id}/{token}`, HMAC token, only for invoices whose PDF may legally be shared).

### 2.8 Manual reminder and WhatsApp
- `POST /dues/customers/{id}/remind` sends email/SMS now (and resets the 15-day clock).
- `POST /dues/customers/{id}/whatsapp` returns `https://wa.me/<number>?text=<message>` with the system-generated
  bilingual message; the portal opens it. Saudi numbers are normalised (`05xxxxxxxx → 9665xxxxxxxx`). Logged as WHATSAPP.

## 3. Role matrix

| Capability | OPERATOR | ADMIN | ACCOUNTANT (staff) | VIEWER |
|---|---|---|---|---|
| Dashboard and sales report | ✓ | ✓ | – | – |
| Invoices, customers, products, payments, dues: read | ✓ | ✓ | ✓ | ✓ |
| … write, reminders, WhatsApp | ✓ | ✓ | ✓ | – |
| Company settings, audit log | ✓ | ✓ | – | – |
| ZATCA devices | ✓ | – | – | – |

## 4. Data model changes (migration `V2__products_payments_reminders.sql`)
- `product` (new), `invoice_payment` (new), `notification_log` (new)
- `invoice_line.product_id`, `invoice.due_date`, `customer.reminders_enabled`, `customer.last_reminder_at`

## 5. Ordered task list
1. Migration V2 + entities/repositories.
2. Normal user bootstrap + role checks (dashboard admin-only, write checks on customers).
3. Products API + Products page + nav.
4. Invoice lines ↔ products (backend `productId`, editor picker, inline add product), due date on drafts.
5. Customer delete + reminder toggle.
6. Payments + balance calculation + invoice detail payments card; due/payment status in invoice list.
7. Dues API + Due pages (all dues, customer-wise).
8. Sales report API + dashboard report UI.
9. Notifications: phone normaliser, message builder, public PDF link, email/SMS senders, scheduler,
   manual reminder, WhatsApp, reminder log page.
10. Unit tests, build, lint, local smoke test.
