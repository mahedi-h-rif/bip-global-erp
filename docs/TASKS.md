# SoudaCore ERP Lite — BIP Gulf Logistics Services Company

Scope source: quotation QT-2026-000002 (v3, with details). Base code: the Fatoora invoice platform
(Spring Boot 4 + PostgreSQL + Next.js), already in production for Nazmul Global and Astha Gulf.

## Client master data (from the WhatsApp group "Souda Core IT & BIP GULF")

| Field | Value |
|---|---|
| Legal name (AR) | شركة بي آي بي الخليج للخدمات اللوجستية |
| Legal name (EN) | BIP Gulf Logistics Services Company |
| VAT number | 312798399100003 (TIN 3127983991) |
| CR (unified national number) | 7043504286 (old CR on VAT certificate: 1009179121) |
| National address | Bldg 3965, Ibn Mufleh (ابن مفلح), secondary 6745, Al Malaz (حي الملز), Riyadh 12836 — short address REMA3965 |
| Contact | Anwar +966 50 462 8404 · ZATCA OTP: Umer Wahab +966 57 694 7430 |
| Documents | `docs/company/` (CR, VAT certificate, national address, logo) |

## Environments

| Env | Database | ZATCA | Purpose |
|---|---|---|---|
| Local | local Postgres | SANDBOX (OTP 123345) | development, tests |
| UAT | `bip_uat` on 2.24.60.124 | SIMULATION | client testing, not legally binding |
| Production | `bip_prod` on 2.24.60.124 | PRODUCTION | go-live; swap = point `DB_NAME` at `bip_prod` and onboard a production device (OTP) |

UAT must never use a PRODUCTION device: every invoice it clears would be legally binding.

## Phase 0 — Organisation
- [x] T0.1 Collect client data (CR, VAT, address, logo, ZATCA contact, server)
- [x] T0.2 Gap analysis quotation ↔ existing platform
- [x] T0.3 Task breakdown (this file)

## Phase 1 — Foundation + invoicing (week 1, 50 % milestone)
- [ ] T1.1 Fork the platform as `erp`, rebrand to "SoudaCore ERP", BIP seller profile and logo
- [ ] T1.2 Client env file (`deploy/clients/bip-gulf.env`), secrets generated, UAT/prod DB split
- [ ] T1.3 Invoicing: standard/simplified, credit/debit notes, quote → invoice, payments, dues (existing; verify)
- [ ] T1.4 ZATCA Phase 2 in SANDBOX end-to-end (onboard, clear, report, PDF/A-3)
- [ ] T1.5 Local build + unit tests green

## Phase 2 — ERP modules (week 2, 30 % milestone)
- [ ] T2.1 Warehouses (CRUD, default warehouse)
- [ ] T2.2 Per-warehouse stock + stock movement ledger (IN / OUT / TRANSFER / ADJUST / RETURN)
- [ ] T2.3 Stock transfers between warehouses with history
- [ ] T2.4 Issued invoices deduct stock; credit notes return stock
- [ ] T2.5 Low-stock thresholds + stock value by warehouse (dashboard)
- [ ] T2.6 Delivery notes (from invoice or standalone, partial, driver/vehicle, received-by, bilingual A4)
- [ ] T2.7 Suppliers + requisitions (Pending → Received, stock receipt, paid/due)
- [ ] T2.8 POS screen (product grid by warehouse, stock-aware cart, cash/credit sale, QR receipt 80 mm)
- [ ] T2.9 HR payroll runs, payslips, salary advances, holidays
- [ ] T2.10 Custom roles with permission matrix
- [ ] T2.11 Reports: stock, purchase, VAT summary, Excel + PDF

## Phase 3 — Server, security, UAT
- [ ] T3.1 SSH key access to 2.24.60.124 (one-time, client side)
- [ ] T3.2 Server bootstrap script: Docker, firewall (ufw 22/80/443/UAT port), fail2ban, swap
- [ ] T3.3 Postgres with `bip_uat` + `bip_prod` databases, nightly backups
- [ ] T3.4 One-command deploy (`deploy/deploy.sh uat|prod`): rsync, build, migrate, restart, health check
- [ ] T3.5 UAT up on `http://2.24.60.124:<port>`; HTTPS once a domain is mapped
- [ ] T3.6 ZATCA SIMULATION device on UAT (OTP from Fatoora simulation portal)

## Phase 4 — Testing
- [ ] T4.1 Backend unit tests + ZATCA sandbox live suite
- [ ] T4.2 API smoke tests against UAT (login, customer, product, invoice, PDF, stock)
- [ ] T4.3 Browser walk-through of main flows

## Phase 5 — Go-live (week 3, 20 % milestone)
- [ ] T5.1 Switch stack to `bip_prod`, ZATCA PRODUCTION device onboarding (OTP from Umer Wahab)
- [ ] T5.2 Domain + HTTPS (Caddy), first real invoice, handover
