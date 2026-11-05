# Fatoora Platform

White-label, ZATCA Phase 2 compliant e-invoicing portal for Saudi businesses. First client: **Nazmul Global Company**.

Staff type invoices in the portal. The platform numbers, signs and chains them. It then clears standard (B2B) invoices with ZATCA, or reports simplified (B2C) invoices, either immediately or later within the 24-hour limit. Finally it produces the bilingual Arabic/English PDF (PDF/A-3 with the XML embedded).

| Part | Stack | What it does |
|---|---|---|
| `backend/zatca-core` | Plain Java 21 library (BouncyCastle, Santuario) | CSR, UBL 2.1 XML, invoice hash, XAdES signature, QR TLV, Fatoora API client. No Spring and no database, so it can be reused in any future product |
| `backend/invoice-api` | Spring Boot 4.1, PostgreSQL, Flyway | Auth, customers, ZATCA device onboarding, invoice lifecycle, retry scheduler, PDF, audit log |
| `frontend` | Next.js 16, Tailwind 4 | Staff portal: dashboard, invoice editor, customers, device wizard, settings |
| `deploy` | Docker Compose | One stack (db + api + web) per client |

## Who does what

| Role | Who | Sees |
|---|---|---|
| **OPERATOR** | You, the e-invoicing provider | Everything, plus ZATCA devices (OTP onboarding, renewal), ZATCA environment, the company's legal identity, and certificate-expiry warnings |
| **ADMIN** | Client's admin | Dashboard and sales report, invoices, customers, products, dues, branding, numbering, contact details, audit log |
| **ACCOUNTANT** ("Staff") | Client's normal user (`FATOORA_USER_*`) | Invoices, customers, products, payments, dues and reminders. No dashboard or sales report |
| **VIEWER** | Client's staff | Read-only |

Products, payments and dues, payment reminders (email/SMS every 15 days, WhatsApp on demand) and the sales report are described in [docs/feature-plan-products-dues.md](docs/feature-plan-products-dues.md).

The client's staff never see ZATCA setup. If setup is incomplete they get "E-invoicing is not fully set up, contact your provider", while the provider sees the technical reason.

The only step that needs the client's ZATCA account is the device OTP: once at go-live, and at each certificate renewal. Have the client add you as an authorized user in their ZATCA account, so you can generate OTPs yourself.

## Invoice lifecycle

```
DRAFT ──Issue──► SIGNED ──(send now | scheduler)──► CLEARED / REPORTED (± warnings)
                                         └──► REJECTED → "Correct & reissue" creates a new draft
```

- **Simplified (B2C):** signed immediately and valid for the customer at once.
  - **Send now** reports it straight away.
  - **Send later** queues it; the scheduler reports it automatically after `FATOORA_AUTO_REPORT_AFTER` (default 20 h). ZATCA's limit is 24 h.
- **Standard (B2B):** "later" means it stays a **draft**. Sending signs it and requests clearance in one step.
  - PDF and XML downloads stay locked until ZATCA returns `CLEARED`.
  - The legal copy is ZATCA's stamped XML.
- **Chain integrity:** every document gets `ICV = previous + 1` and `PIH = hash of previous document`.
  - These are assigned in the same transaction as the invoice number, under a row lock on the device.
  - A failure therefore never leaves gaps.
- **Transient failures** (network errors, 5xx, 401) keep the invoice `SIGNED` and retry with back-off (1 → 30 min). A 400 from ZATCA means `REJECTED`.

## Run locally

Requirements: Java 21, Node 24, PostgreSQL 17.

```bash
# database (this machine uses a project-local cluster on port 5434)
"C:/Program Files/PostgreSQL/17/bin/pg_ctl" -D .local/pgdata -l .local/postgres.log -o "-p 5434" start

# API: env vars from .local/dev.env (quote values that contain spaces)
cd backend && set -a && . ../.local/dev.env && set +a && ./gradlew :invoice-api:bootRun

# portal on http://localhost:3100 (proxies /api/v1 to :8080)
cd frontend && npm install && npm run dev
```

On first boot the API creates the organization and the admin from the `ORG_*` / `FATOORA_ADMIN_*` variables. Then go to **Settings → ZATCA devices → New device → Register**. In the sandbox the OTP is always `123345`.

## Tests

```bash
cd backend
./gradlew test                     # unit tests (offline)
./gradlew test -Dzatca.live=true   # + end-to-end against ZATCA's public developer sandbox and local Postgres
```

The live suite does the following:
- onboards a device (CSR → compliance CSID → 6 compliance documents → production CSID)
- clears a standard invoice and a credit note, and reports a queued simplified invoice
- checks the CSRF and login protections
- downloads the PDF/XML. The sample PDF is written to `backend/invoice-api/build/sample-standard-invoice.pdf`.

## Adding a new client (no code changes)

1. Copy `deploy/clients/nazmul-global.env.example` to `deploy/clients/<client>.env`.
2. Fill in the seller profile from the client's VAT certificate and national address, and generate **new** secrets (`FATOORA_MASTER_KEY`, `FATOORA_JWT_SECRET`, `DB_PASSWORD`, admin password).
3. `CLIENT=<client> docker compose -p <client> --env-file clients/<client>.env up -d --build` in `deploy/`, behind HTTPS (e.g. `invoice.<client-domain>`).
4. Log in, check **Company** settings and brand colour, then onboard a ZATCA device. Follow [docs/zatca-go-live.md](docs/zatca-go-live.md) to move from simulation to production.

Every table carries `organization_id`, so moving to a single multi-tenant deployment later needs no schema change.

## Security notes

- EGS private keys and CSID secrets are encrypted at rest with AES-256-GCM (`FATOORA_MASTER_KEY`). Losing that key means re-onboarding the devices.
- Sessions use an httpOnly, SameSite=Strict JWT cookie, with CSRF protection (double-submit `XSRF-TOKEN`) and a login lockout after 5 failures.
- Roles are ADMIN, ACCOUNTANT and VIEWER; only ADMIN is used today.
- Every ZATCA request and response is stored in `zatca_submission`, and user actions are stored in `audit_log`.
