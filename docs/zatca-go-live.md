# ZATCA go-live runbook

This is how a client moves from testing to legally binding e-invoices. It is the same for every client.

## 1. Before you start

- The client has a VAT registration certificate. The portal's **Company** page must match it exactly: Arabic legal name, VAT number, CR number, and national address (building no. 4 digits, postal code 5 digits).
- You (the provider) are logged in to the portal with the **OPERATOR** account; the steps below are not visible to the client's staff.
- You can generate OTPs from the **client's** ZATCA account at **fatoora.zatca.gov.sa**. ZATCA only accepts OTPs from the taxpayer's own account, so the client adds you as an authorized user once, or shares access for this step.

## 2. Environments

| Environment | Portal setting | OTP from | Invoices |
|---|---|---|---|
| Sandbox (developer portal) | `SANDBOX` | always `123345` | not real, for development |
| Simulation | `SIMULATION` | Fatoora **simulation** portal | validated by ZATCA, not legally binding |
| Production | `PRODUCTION` | Fatoora portal | legally binding |

Each environment needs its own device onboarding, because its certificate template differs. Invoices issued in one environment never carry over to another.

## 3. Onboarding a device (per environment)

1. In the portal: **Settings → Company**, set the environment and save.
2. **Settings → ZATCA devices → New device.**
   - Name: unique, e.g. `PORTAL-1`
   - Branch
   - Location: short national address
   - Industry: e.g. *Fuel retail*
   - Documents: *Standard and simplified*
3. On fatoora.zatca.gov.sa: **Onboard new solution unit/device**, choose the number of OTPs, and copy the 6-digit code. It is valid for 1 hour.
4. Back in the portal: **Register**, then paste the OTP. The portal then does the following automatically:
   - generates a secp256k1 key pair and a CSR
   - gets the compliance CSID
   - runs the 6 compliance documents (standard and simplified invoice, credit note and debit note)
   - gets the production CSID
5. The device shows **ACTIVE**, and the first active device becomes the default.

If a step fails, the portal shows ZATCA's exact message. Fix the data (usually the company address or VAT number), create a new device, and use a fresh OTP.

## 4. Go-live checklist

- [ ] Simulation: issue at least one standard and one simplified invoice. Both show *Cleared* or *Reported* with no warnings.
- [ ] Check the PDF: company details, QR code (scan it with the **ZATCA** mobile app), and the Arabic title.
- [ ] Switch to `PRODUCTION`, onboard a production device, and issue the first real invoice.
- [ ] Put a calendar reminder at the certificate expiry date. The dashboard warns 30 days before. Renew with **Renew** and a new OTP; the invoice chain continues.

## 5. Day-to-day rules the portal enforces

- **Standard invoices (B2B)** are only valid after clearance. Never send a draft or an uncleared PDF to a buyer; the portal blocks the download.
- **Simplified invoices (B2C)** must be reported within **24 hours**. "Send later" queues them, and the scheduler reports them automatically after 20 hours. The dashboard shows countdowns.
- **Issued documents can't be edited.** Raise a **credit note** (reduce) or **debit note** (increase) that references the original invoice and states a reason.
- **Rejected documents** stay in the chain. Use **Correct & reissue**, which creates a new number.
- **Record keeping:** the signed/cleared XML, PDF and ZATCA responses are stored in the database. Back up the database, and keep `FATOORA_MASTER_KEY` safe.

## 6. Technical reference

- API base URLs:
  - `https://gw-fatoora.zatca.gov.sa/e-invoicing/developer-portal`
  - `…/simulation`
  - `…/core`
- Endpoints (header `Accept-Version: V2`):
  - `/compliance`
  - `/compliance/invoices`
  - `/production/csids`: POST to issue, PATCH to renew
  - `/invoices/clearance/single`: `Clearance-Status: 1`
  - `/invoices/reporting/single`
- Hash: remove `ext:UBLExtensions`, `cac:Signature` and the QR `AdditionalDocumentReference`, then C14N 1.1 → SHA-256 → base64.
- QR tags:
  1. seller name
  2. VAT number
  3. timestamp
  4. total with VAT
  5. VAT
  6. hash
  7. signature
  8. public key
  9. ZATCA's certificate signature (simplified documents)

  All nine are TLV-encoded, then base64.
- ZATCA's official SDK and validator can be downloaded from the Fatoora developer portal, as a second check on generated XML.
