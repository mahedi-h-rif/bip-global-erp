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
export type PaymentStatus = "UNPAID" | "PARTIAL" | "PAID";
export type PaymentMethod = "CASH" | "BANK_TRANSFER" | "CARD" | "CHEQUE" | "OTHER";

export interface Me {
  id: number;
  email: string;
  fullName: string;
  /** OPERATOR = the e-invoicing provider; the others are the client's own staff. ACCOUNTANT = normal staff user. */
  role: "OPERATOR" | "ADMIN" | "ACCOUNTANT" | "VIEWER";
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
}

export interface Product {
  id?: number;
  name: string;
  unitPrice: number | string;
  active?: boolean;
  updatedAt?: string;
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
  legalNameAr: string;
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

export type WorkerCell = { month: number; work: string | null; paid: string | null; due: string | null };

export type WorkerRow = {
  id: number;
  no: number;
  name: string;
  phone: string | null;
  iqamaNumber: string | null;
  nationality: string | null;
  notes: string | null;
  active: boolean;
  months: WorkerCell[];
  work: string;
  paid: string;
  due: string;
};

export type WorkerTotals = { work: string; paid: string; due: string };

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

export type AccountIncome = {
  id: number; date: string; customerName: string | null; mobile: string | null; details: string | null;
  amount: string; paid: string; due: string; source: AccountSource;
};
/** id is null for the month's automatic worker-payments line (category "WORKERS"). */
export type AccountExpense = { id: number | null; date: string | null; description: string; category: ExpenseCategory | "WORKERS"; amount: string; source: AccountSource | null };
export type AccountCustomer = { name: string; mobile: string | null; billed: string; paid: string; due: string; jobs: number };
export type AccountCategory = { category: ExpenseCategory | "WORKERS"; amount: string };

export type AccountMonth = {
  year: number; month: number; income: AccountIncome[]; expenses: AccountExpense[];
  totals: { billed: string; received: string; due: string; expense: string; cashAfterExpense: string };
  customers: AccountCustomer[]; categories: AccountCategory[];
};

export type PartnerWithdrawal = { id: number; partnerId: number; date: string; amount: string; reference: string | null; approvedBy: string | null; notes: string | null; source: AccountSource };
export type PartnerRow = { id: number; name: string; active: boolean; entitlement: string | null; withdrawn: string; balance: string | null; withdrawals: PartnerWithdrawal[] };

export type AccountYear = {
  year: number;
  months: { month: number; billed: string; received: string; expense: string; net: string; withdrawals: Record<string, string> }[];
  totals: { billed: string; received: string; due: string; expense: string; net: string; drawings: string; retained: string };
  partners: PartnerRow[]; categories: AccountCategory[];
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
