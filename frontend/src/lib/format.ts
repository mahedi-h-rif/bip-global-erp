import type { DocumentType, InvoiceKind, InvoiceStatus, Line, PaymentMethod, PaymentStatus } from "./types";

const KSA = "Asia/Riyadh";

/**
 * Wraps text in Unicode isolate marks (invisible) so "1,234.00 SAR" and "05 Oct 2026, 14:00" keep their
 * left-to-right order inside Arabic (right-to-left) text.
 */
const ltr = (s: string) => String.fromCharCode(0x2066) + s + String.fromCharCode(0x2069);

export function money(value: number | string | null | undefined, currency = "SAR") {
  const n = Number(value ?? 0);
  return ltr(`${n.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency}`);
}

export function dateTime(iso: string | null | undefined) {
  if (!iso) return "—";
  return ltr(
    new Date(iso).toLocaleString("en-GB", {
      timeZone: KSA,
      year: "numeric",
      month: "short",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
    }),
  );
}

export function date(iso: string | null | undefined) {
  if (!iso) return "—";
  return ltr(new Date(iso).toLocaleDateString("en-GB", { timeZone: KSA, year: "numeric", month: "short", day: "2-digit" }));
}

/** "in 5h 12m" / "overdue 1h 3m" relative to now; `time` is just "5h 12m", for translated sentences. */
export function countdown(iso: string | null | undefined) {
  if (!iso) return null;
  const ms = new Date(iso).getTime() - Date.now();
  const abs = Math.abs(ms);
  const h = Math.floor(abs / 3_600_000);
  const m = Math.floor((abs % 3_600_000) / 60_000);
  const time = ltr(`${h}h ${m}m`);
  return { overdue: ms < 0, time, label: `${ms < 0 ? "overdue " : ""}${time}` };
}

export function expiresWithinDays(iso: string | null | undefined, days: number) {
  return iso != null && new Date(iso).getTime() - Date.now() < days * 86_400_000;
}

export const kindLabel: Record<InvoiceKind, string> = {
  STANDARD: "Standard (B2B)",
  SIMPLIFIED: "Simplified (B2C)",
};

export const typeLabel: Record<DocumentType, string> = {
  INVOICE: "Invoice",
  CREDIT_NOTE: "Credit note",
  DEBIT_NOTE: "Debit note",
};

export const statusLabel: Record<InvoiceStatus, string> = {
  DRAFT: "Draft",
  SIGNED: "Awaiting ZATCA",
  CLEARED: "Cleared",
  CLEARED_WITH_WARNINGS: "Cleared · warnings",
  REPORTED: "Reported",
  REPORTED_WITH_WARNINGS: "Reported · warnings",
  REJECTED: "Rejected",
};

export const paymentStatusLabel: Record<PaymentStatus, string> = {
  UNPAID: "Unpaid",
  PARTIAL: "Partly paid",
  PAID: "Paid",
  CLOSED: "Closed",
};

export const paymentMethodLabel: Record<PaymentMethod, string> = {
  CASH: "Cash",
  BANK_TRANSFER: "Bank transfer",
  CARD: "Card",
  CHEQUE: "Cheque",
  OTHER: "Other",
};

/** Today in Saudi time as yyyy-mm-dd (for date inputs). */
export function todayKsa() {
  return new Date().toLocaleDateString("en-CA", { timeZone: KSA });
}

/** Mirrors the backend's ZATCA rounding so the editor shows exactly what will be issued. */
export function lineTotals(lines: Line[]) {
  const r2 = (n: number) => Math.round((n + Number.EPSILON) * 100) / 100;
  let net = 0;
  const byRate = new Map<string, { taxable: number; rate: number }>();
  const rows = lines.map((l) => {
    const lineNet = r2(Number(l.quantity || 0) * Number(l.unitPrice || 0));
    const rate = l.vatCategory === "S" ? Number(l.vatRate || 0) : 0;
    const vat = r2((lineNet * rate) / 100);
    net += lineNet;
    const key = `${l.vatCategory}|${rate}|${l.exemptionCode ?? ""}`;
    const g = byRate.get(key) ?? { taxable: 0, rate };
    g.taxable += lineNet;
    byRate.set(key, g);
    return { net: lineNet, vat, gross: r2(lineNet + vat) };
  });
  const vat = [...byRate.values()].reduce((s, g) => s + r2((g.taxable * g.rate) / 100), 0);
  return { rows, net: r2(net), vat: r2(vat), total: r2(net + vat) };
}

export const unitCodes = [
  { code: "PCE", label: "Piece" },
  { code: "LTR", label: "Litre" },
  { code: "KGM", label: "Kilogram" },
  { code: "MTR", label: "Metre" },
  { code: "HUR", label: "Hour" },
  { code: "DAY", label: "Day" },
  { code: "BX", label: "Box" },
  { code: "DZN", label: "Dozen" },
  { code: "CT", label: "Carton" },
  { code: "PK", label: "Pack" },
  { code: "RO", label: "Roll" },
  { code: "BG", label: "Bag" },
  { code: "BE", label: "Bundle" },
  { code: "PR", label: "Pair" },
  { code: "TNE", label: "Tonne" },
  { code: "SET", label: "Set" },
];

export const exemptionCodes = [
  { code: "VATEX-SA-32", label: "Export of goods", category: "Z" },
  { code: "VATEX-SA-33", label: "Export of services", category: "Z" },
  { code: "VATEX-SA-34-1", label: "International transport of goods", category: "Z" },
  { code: "VATEX-SA-35", label: "Medicines and medical equipment", category: "Z" },
  { code: "VATEX-SA-36", label: "Qualifying metals", category: "Z" },
  { code: "VATEX-SA-EDU", label: "Private education to citizen", category: "Z" },
  { code: "VATEX-SA-HEA", label: "Private healthcare to citizen", category: "Z" },
  { code: "VATEX-SA-29", label: "Financial services", category: "E" },
  { code: "VATEX-SA-30", label: "Real estate transactions", category: "E" },
  { code: "VATEX-SA-OOS", label: "Not subject to VAT", category: "O" },
] as const;
