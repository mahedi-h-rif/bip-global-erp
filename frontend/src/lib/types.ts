export type InvoiceKind = "STANDARD" | "SIMPLIFIED";
export type DocumentType = "INVOICE" | "CREDIT_NOTE" | "DEBIT_NOTE";
export type SendMode = "NOW" | "LATER";
export type VatCategory = "S" | "Z" | "E" | "O";
export type InvoiceStatus =
  | "DRAFT"
  | "SIGNED"
  | "CLEARED"
  | "CLEARED_WITH_WARNINGS"
  | "REPORTED"
  | "REPORTED_WITH_WARNINGS"
  | "REJECTED";
export type ZatcaEnvironment = "SANDBOX" | "SIMULATION" | "PRODUCTION";
export type PaymentStatus = "UNPAID" | "PARTIAL" | "PAID" | "CLOSED";
export type PaymentMethod = "CASH" | "BANK_TRANSFER" | "CARD" | "CHEQUE" | "OTHER";

export interface Me {
  id: number;
  email: string;
  fullName: string;
  /** OPERATOR = the e-invoicing provider; the others are the client's own staff. ACCOUNTANT = normal staff user. */
  /** BRANCH: a division login that sees only its own invoices, quotations, agreements and cash-book entries. */
  role: "OPERATOR" | "ADMIN" | "ACCOUNTANT" | "VIEWER" | "BRANCH";
  organizationId: number;
}

export interface Line {
  name: string;
  quantity: number | string;
  unitCode: string;
  unitPrice: number | string;
  vatCategory: VatCategory;
  vatRate: number | string;
  exemptionCode?: string | null;
  exemptionReason?: string | null;
  /** The product the line was picked from; name and price stay editable. */
  productId?: number | null;
  /** Copied from a delivery line / a sales-order line (unit then fixed by that line). */
  deliveryLineId?: number | null;
  salesOrderLineId?: number | null;
  /** Read-only: base units in one unit of this line. */
  uomFactor?: number | null;
}

export interface InvoiceSummary {
  id: number;
  number: string | null;
  kind: InvoiceKind;
  type: DocumentType;
  status: InvoiceStatus;
  sendMode: SendMode;
  customerName: string | null;
  totalInclVat: number;
  vatTotal: number;
  issuedAt: string | null;
  reportDeadlineAt: string | null;
  createdAt: string;
  dueDate: string | null;
  /** Null for drafts, rejected documents and credit/debit notes. */
  paidAmount: number | null;
  dueAmount: number | null;
  paymentStatus: PaymentStatus | null;
  /** PDF/XML may be shared: simplified once issued, standard once cleared. */
  downloadable: boolean;
  /** Signed by a sandbox or simulation device: not a legal invoice, so it can be deleted. */
  test: boolean;
  lifecycle?: "CANCELLED" | "CLOSED" | null;
}

export interface Submission {
  endpoint: string;
  httpStatus: number | null;
  outcome: string;
  response: string | null;
  at: string;
}

export interface InvoiceDetail {
  id: number;
  warehouseId?: number | null;
  number: string | null;
  uuid: string | null;
  kind: InvoiceKind;
  type: DocumentType;
  status: InvoiceStatus;
  sendMode: SendMode;
  customerId: number | null;
  customerName: string | null;
  supplyDate: string | null;
  paymentMeans: string;
  originalInvoiceId: number | null;
  billingReference: string | null;
  reason: string | null;
  internalNote: string | null;
  totalExclVat: number;
  vatTotal: number;
  totalInclVat: number;
  icv: number | null;
  invoiceHash: string | null;
  qrCode: string | null;
  issuedAt: string | null;
  scheduledSubmitAt: string | null;
  reportDeadlineAt: string | null;
  submittedAt: string | null;
  submitAttempts: number;
  zatcaWarnings: string | null;
  zatcaErrors: string | null;
  lastError: string | null;
  downloadable: boolean;
  lines: Line[];
  submissions: Submission[];
  createdAt: string;
  createdBy: string | null;
  /** Only drafts can be edited or deleted; issued documents are corrected with credit/debit notes. */
  editable: boolean;
  dueDate: string | null;
  notesTotal: number | null;
  adjustedTotal: number | null;
  paidAmount: number | null;
  dueAmount: number | null;
  overpaidAmount: number | null;
  paymentStatus: PaymentStatus | null;
  payments: Payment[];
  /** Signed by a sandbox or simulation device: not a legal invoice, so it can be deleted. */
  test: boolean;
  /** CANCELLED (full credit note issued) or CLOSED (unpaid rest written off). */
  lifecycle?: "CANCELLED" | "CLOSED" | null;
  lifecycleReason?: string | null;
  lifecycleAt?: string | null;
  cancelNoteId?: number | null;
  cancelNoteNumber?: string | null;
}

export interface Payment {
  id: number;
  invoiceId: number;
  amount: number;
  paidOn: string;
  method: PaymentMethod;
  reference: string | null;
  note: string | null;
  createdBy: string | null;
  createdAt: string;
  updatedBy?: string | null;
  updatedAt?: string | null;
}

export interface Product {
  id?: number;
  /** Incremental item number, e.g. ITEM-000001 (assigned by the server). */
  number?: string | null;
  name: string;
  unitPrice: number | string;
  active?: boolean;
  updatedAt?: string;
  trackStock?: boolean;
  costPrice?: number | string | null;
  lowStockThreshold?: number | string | null;
  unitCode?: string;
  /** Total on hand across warehouses; null for services. */
  onHand?: number | null;
  nameAr?: string | null;
  category?: string | null;
  brand?: string | null;
  barcode?: string | null;
  salesItem?: boolean;
  purchaseItem?: boolean;
  avgCost?: number | null;
}

export interface DueInvoice {
  invoiceId: number;
  number: string;
  kind: InvoiceKind;
  customerId: number | null;
  customerName: string | null;
  issuedAt: string | null;
  dueDate: string | null;
  ageDays: number;
  /** Null when the invoice has no due date. */
  daysOverdue: number | null;
  total: number;
  adjustedTotal: number;
  paid: number;
  due: number;
  paymentStatus: PaymentStatus;
  shareable: boolean;
}

export interface CustomerDue {
  /** Null groups the walk-in (no customer) invoices. */
  customerId: number | null;
  customerName: string;
  email: string | null;
  phone: string | null;
  remindersEnabled: boolean;
  lastReminderAt: string | null;
  invoiceCount: number;
  totalInvoiced: number;
  paid: number;
  due: number;
  oldestIssuedAt: string | null;
  maxDaysOverdue: number;
}

export interface NotificationEntry {
  id: number;
  customerId: number | null;
  customerName: string | null;
  channel: "EMAIL" | "SMS" | "WHATSAPP";
  trigger: "AUTO" | "MANUAL";
  recipient: string | null;
  /** LOGGED = channel not configured yet, the message only went to the server log. */
  status: "SENT" | "LOGGED" | "FAILED" | "OPENED";
  dueAmount: number | null;
  message: string | null;
  error: string | null;
  createdBy: string | null;
  createdAt: string;
}

export interface SalesRow {
  key: string;
  label: string;
  invoices: number;
  gross: number;
  notes: number;
  net: number;
  vat: number;
  collected: number;
  due: number;
}

export interface SalesReport {
  from: string;
  to: string;
  totals: SalesRow;
  months: SalesRow[];
  days: SalesRow[];
  customers: SalesRow[];
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface Customer {
  id?: number;
  nameAr?: string | null;
  nameEn: string;
  vatNumber?: string | null;
  otherId?: string | null;
  otherIdScheme?: string | null;
  /** Free-text address; the structured fields below are the national address for B2B invoices. */
  address?: string | null;
  street?: string | null;
  buildingNumber?: string | null;
  additionalNumber?: string | null;
  district?: string | null;
  city?: string | null;
  postalCode?: string | null;
  countryCode?: string | null;
  email?: string | null;
  phone?: string | null;
  remindersEnabled?: boolean;
  lastReminderAt?: string | null;
}

export interface Organization {
  id: number;
  legalNameAr: string | null;
  legalNameEn: string | null;
  vatNumber: string;
  otherId: string;
  otherIdScheme: string;
  street: string;
  buildingNumber: string;
  additionalNumber: string | null;
  district: string;
  city: string;
  postalCode: string;
  phone: string | null;
  email: string | null;
  website: string | null;
  /** Who signs agreements and quotations for the company. */
  signatoryName: string | null;
  signatoryTitle: string | null;
  brandColor: string;
  letterheadStyle: "CLASSIC" | "MODERN" | "SKYLINE";
  /** Slogan under the company name on the Skyline letterhead. */
  tagline: string | null;
  logoUrl: string | null;
  invoicePrefix: string;
  creditNotePrefix: string;
  debitNotePrefix: string;
  zatcaEnvironment: ZatcaEnvironment;
}

export interface EgsUnit {
  id: number;
  name: string;
  serialNumber: string;
  branch: string;
  location: string;
  industry: string;
  invoiceTypes: string;
  environment: ZatcaEnvironment;
  status: "NEW" | "COMPLIANCE" | "ACTIVE" | "FAILED" | "RETIRED";
  icv: number;
  isDefault: boolean;
  activatedAt: string | null;
  certificateExpiresAt: string | null;
}

export interface OnboardingResult {
  egsUnitId: number;
  status: EgsUnit["status"];
  steps: { name: string; ok: boolean; detail: string; warning?: boolean }[];
}

export interface Dashboard {
  drafts: number;
  awaitingSubmission: number;
  rejected: number;
  issuedThisMonth: number;
  queue: InvoiceSummary[];
  certificates: { egsUnitId: number; name: string; expiresAt: string; daysLeft: number }[];
}

export type UserRole = "OPERATOR" | "ADMIN" | "ACCOUNTANT" | "VIEWER" | "BRANCH";

/** A login of the company, for the admin's Users page. */
export interface AppUserRow {
  id: number;
  email: string;
  fullName: string;
  role: UserRole;
  enabled: boolean;
  lastLoginAt: string | null;
  createdAt: string;
  /** Audit-log entries this month. */
  actionsThisMonth: number;
  lastActiveAt: string | null;
  /** Custom access: ticked feature keys; null = the role's full access (admins). */
  features: string[] | null;
  actions: string[] | null;
  ownDataOnly: boolean;
}

/** What the user form offers (GET /users/access-options). */
export interface AccessOptions {
  features: { key: string; label: string; section: string; pages: string[]; api: string[] }[];
  actions: string[];
}

/** One login's totals: records created by kind, and this month's invoices, value and quotations. */
export interface UserActivity {
  user: AppUserRow;
  created: Record<string, number>;
  invoicesThisMonth: number;
  issuedThisMonth: number;
  quotationsThisMonth: number;
}

/** One login's month on the admin dashboard. */
export interface UserMonth {
  id: number;
  fullName: string;
  email: string;
  role: UserRole;
  enabled: boolean;
  invoices: number;
  issued: number;
  quotations: number;
  actions: number;
  lastActiveAt: string | null;
}

export interface AuditEntry {
  id: number;
  actor: string;
  action: string;
  entityType: string | null;
  entityId: number | null;
  details: string | null;
  createdAt: string;
}

export type QuotationStatus = "DRAFT" | "SENT" | "ACCEPTED" | "DECLINED" | "INVOICED";

export interface QuotationLine {
  name: string;
  quantity: number | string;
  unitCode: string;
  unitPrice: number | string;
  vatRate: number | string;
  productId?: number | null;
}

/** A price offer. Internal only: never sent to ZATCA. */
export interface Quotation {
  id: number;
  number: string;
  customerId: number | null;
  customerName: string | null;
  /** For a prospect who isn't a saved customer. */
  clientName: string | null;
  issueDate: string;
  validUntil: string | null;
  status: QuotationStatus;
  notes: string | null;
  terms: string | null;
  totalExclVat: number;
  vatTotal: number;
  totalInclVat: number;
  invoiceId: number | null;
  lines: QuotationLine[];
  createdBy: string | null;
  createdAt: string;
}

export type AgreementStatus = "DRAFT" | "SENT" | "SIGNED" | "CANCELLED";

export interface Agreement {
  id: number;
  number: string;
  customerId: number | null;
  clientName: string;
  clientDetails: string | null;
  title: string;
  body: string;
  agreementDate: string;
  status: AgreementStatus;
  signedOn: string | null;
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
}

/** The standard agreement new agreements start from; standard = the built-in text, not yet edited. */
export interface AgreementTemplate {
  title: string;
  body: string;
  standard: boolean;
  updatedBy: string | null;
  updatedAt: string | null;
}

// --- workers -----------------------------------------------------------------------------------------

export type WorkerCell = { month: number; work: string | null; paid: string | null; due: string | null; days: string | null; rate: string | null; paidMethod: PaymentMethod | null };

export type WorkerRow = {
  id: number;
  no: number;
  name: string;
  phone: string | null;
  iqamaNumber: string | null;
  nationality: string | null;
  notes: string | null;
  active: boolean;
  daily: boolean;
  dailyRate: string | null;
  days: string | null;
  months: WorkerCell[];
  work: string;
  paid: string;
  due: string;
};

export type WorkerTotals = { work: string; paid: string; due: string };
/** One worker's figures on one day; they are already counted in that month's total. */
export type WorkerDayEntry = { id: number; workerId: number; workerName: string; date: string; work: string | null; paid: string | null; due: string; notes: string | null; method: PaymentMethod | null; createdBy: string | null; createdAt: string | null; updatedBy: string | null; updatedAt: string | null };

export type WorkerLedger = {
  year: number;
  years: number[];
  workers: WorkerRow[];
  months: WorkerTotals[];
  total: WorkerTotals;
  outstanding: string;
  advances: string;
  workerCount: number;
  activeCount: number;
};

export type WorkerLeaveType = "ANNUAL" | "SICK" | "UNPAID" | "OTHER";
/** One worker's leave over a date range; `days` is the inclusive day count, `deduction` an optional salary cut. */
export type WorkerLeave = {
  id: number; workerId: number; workerName: string | null; startDate: string; endDate: string;
  days: number; type: WorkerLeaveType; deduction: string | null; notes: string | null;
  createdBy: string | null; createdAt: string | null; updatedBy: string | null; updatedAt: string | null;
};

// --- documents ---------------------------------------------------------------------------------------

export type CompanyDocumentType =
  | "IQAMA" | "PASSPORT" | "WORK_PERMIT" | "DRIVING_LICENSE" | "CR" | "MUNICIPAL_LICENSE" | "VAT_CERTIFICATE" | "CHAMBER" | "INSURANCE" | "OTHER";

export type ExpiryStage = "NOT_YET" | "EARLY" | "FINAL" | "EXPIRED";

export type CompanyDocument = {
  id: number;
  type: CompanyDocumentType;
  workerId: number | null;
  workerName: string | null;
  workerPhone: string | null;
  title: string;
  number: string | null;
  holderName: string | null;
  nationality: string | null;
  issueDate: string | null;
  expiryDate: string | null;
  daysLeft: number | null;
  stage: ExpiryStage | null;
  remindersEnabled: boolean;
  remindAfterExpiry: boolean;
  lastReminderOn: string | null;
  nextReminderOn: string | null;
  notes: string | null;
  fileName: string | null;
  fileType: string | null;
  fileSize: number | null;
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
  message: string;
  whatsappUrl: string;
};

export type DocumentExtraction = {
  ocrAvailable: boolean;
  number: string | null;
  holderName: string | null;
  nationality: string | null;
  issueDate: string | null;
  expiryDate: string | null;
  dateOfBirth: string | null;
  warnings: string[];
  text: string | null;
};

export type DocumentAlert = {
  id: number;
  documentId: number;
  title: string | null;
  workerName: string | null;
  workerPhone: string | null;
  daysLeft: number;
  expiryDate: string;
  message: string;
  emailStatus: string | null;
  readAt: string | null;
  createdAt: string;
  whatsappUrl: string;
};

export type DocumentAlerts = { unread: number; items: DocumentAlert[] };

// --- accounts (cash book) ----------------------------------------------------------------------------

export type AccountSource = "MANUAL" | "IMPORT" | "IMPORT_YEAR";
export type ExpenseCategory = "MATERIALS" | "VEHICLE" | "FOOD" | "GOVERNMENT" | "SALARY" | "RENT_UTILITIES" | "OFFICE" | "OTHER";

/** Who created and who last edited an entry (display names), and when. */
export type EditedBy = { createdBy: string | null; createdAt: string | null; updatedBy: string | null; updatedAt: string | null };

export type AccountIncome = EditedBy & {
  id: number; date: string; customerName: string | null; mobile: string | null; details: string | null; receivedBy: string | null;
  amount: string; paid: string; due: string; source: AccountSource; method: PaymentMethod;
};
/** id is null for the month's automatic worker-payments line (category "WORKERS"). */
export type AccountExpense = EditedBy & { id: number | null; date: string | null; description: string; category: ExpenseCategory | "WORKERS"; paidBy: string | null; invoiceNumber: string | null; amount: string; source: AccountSource | null; method: PaymentMethod | null };
export type AccountCustomer = { name: string; mobile: string | null; billed: string; paid: string; due: string; jobs: number };
export type AccountCategory = { category: ExpenseCategory | "WORKERS"; amount: string };

export type AccountMonth = {
  year: number; month: number; income: AccountIncome[]; expenses: AccountExpense[];
  totals: { billed: string; received: string; due: string; expense: string; cashAfterExpense: string };
  customers: AccountCustomer[]; categories: AccountCategory[];
};

export type PartnerWithdrawal = EditedBy & { id: number; partnerId: number; date: string; amount: string; reference: string | null; approvedBy: string | null; notes: string | null; source: AccountSource };
export type PartnerRow = { id: number; name: string; active: boolean; entitlement: string | null; withdrawn: string; balance: string | null; withdrawals: PartnerWithdrawal[] };

export type AccountYear = {
  year: number;
  months: { month: number; billed: string; received: string; expense: string; net: string; withdrawals: Record<string, string> }[];
  totals: { billed: string; received: string; due: string; expense: string; net: string; drawings: string; retained: string };
  partners: PartnerRow[]; categories: AccountCategory[];
};

/** Names already used for "received by" / "paid by", for the form dropdowns. */
export type AccountPeople = { receivedBy: string[]; paidBy: string[] };

/** A person's income and expense totals for a year (person-wise view). */
export type AccountPerson = { name: string; income: string; incomeCount: number; expense: string; expenseCount: number; net: string };

/** One person's income and expense entries, shown independently. */
export type AccountPersonHistory = { name: string; income: AccountIncome[]; expenses: AccountExpense[]; incomeTotal: string; expenseTotal: string };

/** What reading an uploaded invoice recovered; source QR is exact, OCR is best-effort, NONE found nothing. */
export type InvoiceScan = {
  source: "QR" | "OCR" | "NONE";
  date: string | null; amount: number | string | null; vendor: string | null; vatNumber: string | null; invoiceNumber: string | null;
};

export type AccountImportResult = {
  months: { sheet: string; year: number; month: number; income: number; expenses: number; datesGuessed: number }[];
  memos: string[]; yearMonths: number[]; yearMonthsSkipped: number[]; withdrawals: number; partnersCreated: number;
};

export interface PadSummary {
  id: number;
  title: string;
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PadDocument extends PadSummary {
  /** Formatted text (HTML) written on the letterhead. */
  body: string;
}

/** The business audit of a period (see AuditReport.java). */
export type AuditSeverity = "HIGH" | "MEDIUM" | "LOW" | "INFO";

export interface AuditFinding {
  code: string;
  severity: AuditSeverity;
  area: string;
  /** English template with {placeholders}; doubles as the translation key. */
  text: string;
  textAr: string;
  action: string;
  actionAr: string;
  vars: Record<string, string>;
}

export interface AuditVatBox {
  box: string;
  label: string;
  labelAr: string;
  amount: number;
  adjustment: number;
  vat: number;
}

export interface AuditReportData {
  company: { nameEn: string; nameAr: string | null; vatNumber: string | null; crNumber: string | null; address: string; live: boolean; missing: string[] };
  from: string;
  to: string;
  generatedAt: string;
  preparedBy: string;
  score: { score: number; rating: string; ratingAr: string; high: number; medium: number; low: number };
  findings: AuditFinding[];
  vat: { boxes: AuditVatBox[]; total: AuditVatBox; outOfScope: number; filingDeadline: string };
  einvoicing: {
    invoices: number; creditNotes: number; debitNotes: number; standard: number; simplified: number;
    byStatus: Record<string, number>; pending: number; rejected: number; lateReports: number; overdueUnreported: number;
    standardWithoutBuyerId: number; chainProblems: number; oldDrafts: number; creditNotePct: number;
  };
  receivables: {
    sales: number; collected: number; collectionPct: number; totalDue: number;
    aging: { label: string; labelAr: string; amount: number; invoices: number }[];
    topDebtors: { name: string; due: number; invoices: number; oldestDays: number }[];
    topCustomer: string | null; topCustomerPct: number;
  };
  cash: { received: number; expenses: number; net: number; drawings: number; retained: number; categories: { category: string; amount: number }[] };
  documents: { type: string; label: string; labelAr: string; number: string | null; expiry: string | null; status: "VALID" | "EXPIRING" | "EXPIRED" | "MISSING"; daysLeft: number | null }[];
  workforce: { active: number; iqamaValid: number; iqamaExpiring: number; iqamaExpired: number; iqamaMissing: number };
  controls: { events: number; users: number; deletions: number; paymentEdits: number; failedLogins: number };
  calendar: { label: string; labelAr: string; date: string }[];
}

/** The company's WhatsApp number, linked by QR (see WhatsAppController.java). */
export interface WhatsAppStatus {
  /** False when the server has no WhatsApp service: WhatsApp buttons open a prefilled chat instead. */
  configured: boolean;
  status: "idle" | "connecting" | "qr" | "connected" | "logged_out" | "error" | "unavailable";
  /** PNG data URL to scan; admins only. */
  qr: string | null;
  phone: string | null;
  name: string | null;
  lastError: string | null;
  reminders: boolean;
  documentReminders: boolean;
}

export interface SendOutcome {
  status: "SENT" | "LOGGED" | "FAILED" | "OPENED";
  error: string | null;
}

// --- inventory -------------------------------------------------------------------------------

export interface Warehouse {
  id?: number;
  code: string;
  name: string;
  location?: string | null;
  isDefault?: boolean;
  active?: boolean;
  stockValue?: number;
}

export interface StockRow {
  productId: number;
  productNumber: string | null;
  productName: string;
  unitCode: string;
  warehouseId: number;
  warehouseName: string;
  quantity: number;
  costPrice: number | null;
  value: number;
  lowStockThreshold: number | null;
  low: boolean;
}

export interface StockMovement {
  id: number;
  at: string;
  productId: number;
  productName: string;
  warehouseId: number;
  warehouseName: string;
  quantity: number;
  balanceAfter: number;
  kind: string;
  refType: string | null;
  refId: number | null;
  refNumber: string | null;
  note: string | null;
  createdBy: string | null;
}

export interface TransferLine { productId: number; productName?: string; quantity: number | string }

export interface StockTransfer {
  id: number;
  number: string;
  fromWarehouseId: number;
  fromWarehouse: string;
  toWarehouseId: number;
  toWarehouse: string;
  transferDate: string;
  note: string | null;
  createdBy: string;
  createdAt: string;
  lines: TransferLine[];
}

export interface DeliveryLine {
  invoiceLineId?: number | null;
  productId?: number | null;
  name: string;
  quantity: number | string;
  unitCode?: string;
  remaining?: number | null;
  salesOrderLineId?: number | null;
  unitPrice?: number | null;
  vatRate?: number | null;
  invoiced?: number | null;
}

export interface DeliveryNoteRequest {
  invoiceId?: number | null;
  customerId?: number | null;
  clientName?: string | null;
  deliveryAddress?: string | null;
  warehouseId?: number | null;
  deliveryDate?: string | null;
  driverName?: string | null;
  driverPhone?: string | null;
  vehicleNo?: string | null;
  note?: string | null;
  lines: DeliveryLine[];
  salesOrderId?: number | null;
  customerRef?: string | null;
  salesEmployee?: string | null;
}

export interface DeliveryNote extends DeliveryNoteRequest {
  id: number;
  number: string;
  invoiceNumber: string | null;
  customerName: string | null;
  warehouseName: string | null;
  status: "DRAFT" | "DELIVERED" | "CANCELLED";
  receivedBy: string | null;
  receivedAt: string | null;
  createdBy: string;
  createdAt: string;
  salesOrderNumber: string | null;
  /** Every order the lines come from (a delivery may combine several). */
  salesOrderNumbers?: string[];
  invoicing: "NONE" | "PARTIAL" | "FULL";
}

// --- document flow ------------------------------------------------------------------------------

export interface SalesOrderLine {
  id?: number | null;
  quotationLineId?: number | null;
  productId?: number | null;
  name: string;
  quantity: number | string;
  unitCode?: string;
  unitPrice: number | string;
  discountPct?: number | string;
  vatRate?: number | string;
  delivered?: number | null;
  remaining?: number | null;
}

export interface SalesOrderRequest {
  quotationId?: number | null;
  customerId?: number | null;
  clientName?: string | null;
  customerRef?: string | null;
  salesEmployee?: string | null;
  warehouseId?: number | null;
  orderDate?: string | null;
  deliveryDate?: string | null;
  remarks?: string | null;
  lines: SalesOrderLine[];
}

export interface SalesOrder extends SalesOrderRequest {
  id: number;
  number: string;
  quotationNumber: string | null;
  customerName: string | null;
  status: "OPEN" | "PARTIALLY_DELIVERED" | "CLOSED" | "CANCELLED";
  totalExclVat: number;
  vatTotal: number;
  totalInclVat: number;
  createdBy: string;
  createdAt: string;
  /** NONE / PARTIAL / FULL (delivered or invoiced directly; invoiced in any way). */
  deliveryStatus: "NONE" | "PARTIAL" | "FULL";
  invoiceStatus: "NONE" | "PARTIAL" | "FULL";
  closedReason: string | null;
  closedBy: string | null;
  closedAt: string | null;
}

export type DocType =
  | "QUOTATION" | "SALES_ORDER" | "DELIVERY_NOTE" | "INVOICE" | "RECEIPT"
  | "PURCHASE_REQUEST" | "PURCHASE_ORDER" | "GOODS_RECEIPT" | "GOODS_RETURN" | "SUPPLIER_INVOICE" | "SUPPLIER_CREDIT" | "SUPPLIER_PAYMENT"
  | "CUSTOMER" | "SUPPLIER";

export interface PurchaseRequestLine { productId: number | null; name: string; quantity: number; unitCode: string }
export interface PurchaseRequest {
  id: number; number: string; requester: string; department: string | null; requestDate: string; requiredDate: string | null;
  warehouseId: number | null; warehouseName: string | null; status: string; remarks: string | null; createdBy: string; createdAt: string;
  lines: PurchaseRequestLine[]; purchaseOrders: { id: number; number: string; status: string }[];
}

export interface GoodsReturn {
  id: number; number: string; goodsReceiptId: number; goodsReceiptNumber: string; supplierId: number; supplierName: string;
  returnDate: string; warehouseId: number; warehouseName: string; currency: string; reason: string | null; status: string;
  totalExclVat: number; vatTotal: number; totalInclVat: number; createdBy: string; createdAt: string;
  lines: { goodsReceiptLineId: number; productId: number | null; name: string; quantity: number; unitCost: number; vatRate: number }[];
  creditNoteId: number | null; creditNoteNumber: string | null;
}

export interface CreditLine { productId: number | null; name: string; quantity: number; unitCost: number; vatRate: number }
export interface SupplierCreditNote {
  id: number; number: string; supplierId: number; supplierName: string; supplierInvoiceId: number; supplierInvoiceNumber: string;
  goodsReturnId: number | null; goodsReturnNumber: string | null; supplierRef: string | null; creditDate: string; currency: string;
  exchangeRate: number; status: string; remarks: string | null; totalExclVat: number; vatTotal: number; totalInclVat: number;
  totalSar: number; vatSar: number; createdBy: string; createdAt: string; lines: CreditLine[];
}
export interface CreditRequest {
  supplierInvoiceId: number; goodsReturnId: number | null; supplierRef: string | null; creditDate: string | null;
  remarks: string | null; lines: CreditLine[];
}

export interface GrossProfit {
  basis: string; revenue: number; cost: number; grossProfit: number; gpPct: number;
  lines: { name: string; quantity: number; revenue: number; unitCost: number; cost: number; grossProfit: number; gpPct: number }[];
}

export interface GraphNode {
  type: DocType;
  id: number;
  number: string;
  label: string;
  date: string | null;
  party: string | null;
  ref: string | null;
  total: number | null;
  status: string;
  open: boolean;
}

export interface DocGraph {
  rootType: DocType;
  rootId: number;
  nodes: GraphNode[];
  edges: { fromType: DocType; fromId: number; toType: DocType; toId: number }[];
}

export interface OpenInvoice {
  invoiceId: number;
  number: string;
  issueDate: string | null;
  dueDate: string | null;
  customerRef: string | null;
  total: number;
  due: number;
  overdueDays: number;
}

export interface Receipt {
  id: number;
  number: string;
  customerId: number;
  customerName: string;
  receiptDate: string;
  method: string;
  reference: string | null;
  amount: number;
  note: string | null;
  createdBy: string;
  createdAt: string;
  allocations: { invoiceId: number; amount: number; invoiceNumber: string }[];
  status: "POSTED" | "CANCELLED";
  cancelledAt: string | null;
  cancelledBy: string | null;
  cancelReason: string | null;
}

// --- purchasing ---------------------------------------------------------------------------------

export interface Supplier {
  id?: number;
  code?: string;
  name: string;
  nameAr?: string | null;
  vatNumber?: string | null;
  crNumber?: string | null;
  address?: string | null;
  city?: string | null;
  country?: string;
  phone?: string | null;
  email?: string | null;
  contactPerson?: string | null;
  currency?: string;
  paymentTermsDays?: number;
  active?: boolean;
  payable?: number;
}

export interface PoLine {
  id?: number | null;
  productId?: number | null;
  name: string;
  quantity: number | string;
  unitCode?: string;
  unitCost: number | string;
  vatRate?: number | string;
  received?: number;
  remaining?: number;
}

export interface PurchaseOrder {
  id: number;
  number: string;
  supplierId: number;
  supplierName: string;
  supplierRef: string | null;
  orderDate: string;
  deliveryDate: string | null;
  warehouseId: number | null;
  warehouseName: string | null;
  currency: string;
  exchangeRate: number;
  status: "PENDING" | "APPROVED" | "PARTIALLY_RECEIVED" | "RECEIVED" | "CLOSED" | "CANCELLED";
  receivingStatus?: "NONE" | "PARTIAL" | "FULL";
  billingStatus?: "NONE" | "PARTIAL" | "FULL";
  closedReason?: string | null;
  closedBy?: string | null;
  closedAt?: string | null;
  remarks: string | null;
  totalExclVat: number;
  vatTotal: number;
  totalInclVat: number;
  totalSar: number;
  approvedBy: string | null;
  approvedAt: string | null;
  createdBy: string;
  createdAt: string;
  lines: PoLine[];
  attachments: { id: number; fileName: string; contentType: string; createdBy: string; createdAt: string }[];
}

export interface GrnLine {
  id?: number | null;
  purchaseOrderLineId?: number | null;
  productId?: number | null;
  name: string;
  quantity: number | string;
  unitCode?: string;
  unitCost: number | string;
  vatRate?: number | string;
  landedUnitCost?: number | null;
  invoiced?: number | null;
  remaining?: number | null;
}

export interface GrnRequest {
  purchaseOrderId?: number | null;
  supplierId?: number | null;
  supplierRef?: string | null;
  receiptDate?: string | null;
  warehouseId: number | null;
  currency?: string;
  exchangeRate?: number | string;
  remarks?: string | null;
  lines: GrnLine[];
  costs: { description: string; amount: number | string }[];
}

export interface GoodsReceipt extends GrnRequest {
  id: number;
  number: string;
  purchaseOrderNumber: string | null;
  supplierName: string;
  warehouseName: string;
  status: "POSTED" | "CANCELLED";
  landedCostTotal: number;
  totalExclVat: number;
  vatTotal: number;
  totalInclVat: number;
  createdBy: string;
  createdAt: string;
}

export interface SupplierInvoiceLine {
  goodsReceiptLineId?: number | null;
  productId?: number | null;
  name: string;
  quantity: number | string;
  unitCost: number | string;
  vatRate?: number | string;
}

export interface SupplierInvoiceRequest {
  supplierId: number | null;
  supplierInvoiceNo?: string | null;
  invoiceDate?: string | null;
  dueDate?: string | null;
  currency?: string;
  exchangeRate?: number | string;
  remarks?: string | null;
  lines: SupplierInvoiceLine[];
}

export interface SupplierInvoice extends SupplierInvoiceRequest {
  id: number;
  number: string;
  supplierName: string;
  status: "OPEN" | "CANCELLED";
  totalExclVat: number;
  vatTotal: number;
  totalInclVat: number;
  totalSar: number;
  vatSar: number;
  paid: number;
  due: number;
  overdueDays: number;
  createdBy: string;
  createdAt: string;
  goodsReceipts: string[];
}

export interface SupplierPayment {
  id: number;
  number: string;
  supplierId: number;
  supplierName: string;
  paymentDate: string;
  method: string;
  reference: string | null;
  amount: number;
  note: string | null;
  status: "POSTED" | "CANCELLED";
  cancelReason: string | null;
  createdBy: string;
  createdAt: string;
  allocations: { supplierInvoiceId: number; amount: number; invoiceNumber: string }[];
}

export interface AuditRow {
  systemDate: string;
  postingDate: string;
  document: string;
  kind: string;
  warehouseName: string;
  quantity: number;
  cost: number | null;
  transValue: number;
  cumulativeQty: number;
  cumulativeValue: number;
}

export interface InventoryAudit {
  productId: number;
  productName: string;
  productNumber: string | null;
  openingQty: number;
  openingValue: number;
  rows: AuditRow[];
  closingQty: number;
  closingValue: number;
}

// --- HR & payroll -------------------------------------------------------------------------------

export interface Employee {
  id?: number;
  code?: string;
  name: string;
  nameAr?: string | null;
  nationality?: string;
  idNumber?: string | null;
  idExpiry?: string | null;
  passportNumber?: string | null;
  passportExpiry?: string | null;
  jobTitle?: string | null;
  department?: string | null;
  joinDate: string;
  endDate?: string | null;
  status?: "ACTIVE" | "TERMINATED";
  phone?: string | null;
  email?: string | null;
  bankName?: string | null;
  iban?: string | null;
  basic: number | string;
  housing?: number | string;
  transport?: number | string;
  otherAllowance?: number | string;
  gosiPct?: number | string | null;
  annualLeaveDays?: number | string;
  notes?: string | null;
  grossSalary?: number;
  leaveBalance?: number;
  advanceOutstanding?: number;
  idExpiresInDays?: number | null;
  passportExpiresInDays?: number | null;
}

export interface LeaveEntry { id?: number; employeeId: number; employeeName?: string; type: "ANNUAL" | "SICK" | "UNPAID" | "OTHER"; startDate: string; endDate: string; days?: number; notes?: string | null }
export interface Holiday { id?: number; date: string; name: string }
export interface SalaryAdvance { id?: number; employeeId: number; employeeName?: string; date?: string; amount: number | string; installment: number | string; notes?: string | null; repaid?: number; outstanding?: number }

export interface PayrollLine {
  id: number; employeeId: number; employeeCode: string; employeeName: string; jobTitle: string | null; iban: string | null;
  basic: number; housing: number; transport: number; otherAllowance: number; overtime: number; additions: number; gosi: number;
  advanceDeduction: number; unpaidLeaveDays: number; unpaidLeaveDeduction: number; otherDeductions: number; daysWorked: number;
  gross: number; deductions: number; net: number; notes: string | null;
}

export interface PayrollRun {
  id: number; number: string; year: number; month: number; status: "DRAFT" | "APPROVED" | "PAID"; paymentMethod: string | null;
  approvedBy: string | null; approvedAt: string | null; paidBy: string | null; paidAt: string | null; createdBy: string; createdAt: string;
  totalGross: number; totalDeductions: number; totalNet: number; lines: PayrollLine[];
}
