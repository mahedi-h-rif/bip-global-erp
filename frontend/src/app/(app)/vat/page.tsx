"use client";

import { useState } from "react";
import useSWR from "swr";
import { CheckCircle2, Pencil, Trash2 } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import { date, money, todayKsa } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Field, Input, PageHeader, Select, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

export interface VatSummary {
  from: string; to: string;
  salesNet: number; salesVat: number; creditNotesVat: number; debitNotesVat: number; outputVat: number;
  purchasesNet: number; purchasesVat: number; supplierCreditsVat: number; inputVat: number;
  payable: number; salesDocuments: number; purchaseDocuments: number;
}
interface Filing { filedOn: string | null; reference: string | null; paidAmount: number | null; paidOn: string | null; notes: string | null; updatedBy: string | null; updatedAt: string }
interface Quarter { year: number; quarter: number; from: string; to: string; deadline: string; vat: VatSummary; filing: Filing | null; state: "NOT_DUE" | "DUE" | "OVERDUE" | "FILED" | "PAID" }

const stateStyle: Record<Quarter["state"], { label: string; tone: string }> = {
  NOT_DUE: { label: "Not due yet", tone: "bg-slate-100 text-slate-600" },
  DUE: { label: "To file", tone: "bg-amber-50 text-amber-800" },
  OVERDUE: { label: "Overdue", tone: "bg-red-50 text-bad" },
  FILED: { label: "Filed, not paid", tone: "bg-blue-50 text-blue-700" },
  PAID: { label: "Filed and paid", tone: "bg-emerald-50 text-emerald-800" },
};

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
function quarterRange(offset: number) {
  const [y, m] = todayKsa().split("-").map(Number);
  const start = new Date(y, Math.floor((m - 1) / 3) * 3 + offset * 3, 1);
  return [iso(start), iso(new Date(start.getFullYear(), start.getMonth() + 3, 0))];
}

/** The VAT of a period: what was charged on sales, paid on purchases, and the difference owed to ZATCA. */
function PeriodSummary() {
  const t = useT();
  const year = Number(todayKsa().slice(0, 4));
  const presets: { id: string; label: string; range: () => string[] }[] = [
    { id: "q", label: "This quarter", range: () => quarterRange(0) },
    { id: "lq", label: "Last quarter", range: () => quarterRange(-1) },
    { id: "y", label: "This year", range: () => [`${year}-01-01`, `${year}-12-31`] },
    { id: "ly", label: "Last year", range: () => [`${year - 1}-01-01`, `${year - 1}-12-31`] },
  ];
  const [preset, setPreset] = useState("q");
  const [[from, to], setRange] = useState(quarterRange(0));
  const { data, error } = useSWR<VatSummary>(from && to ? `/vat/summary?from=${from}&to=${to}` : null, fetcher, { keepPreviousData: true });
  const refundable = data && Number(data.payable) < 0;

  return (
    <Card className="mb-6">
      <div className="flex flex-wrap items-end gap-2 border-b border-line p-4">
        {presets.map((p) => (
          <Button key={p.id} variant={preset === p.id ? "primary" : "secondary"} onClick={() => { setPreset(p.id); setRange(p.range()); }}>{t(p.label)}</Button>
        ))}
        <span className="ms-auto inline-flex items-end gap-2">
          <Field label={t("From")}><Input type="date" value={from} onChange={(e) => { setPreset(""); setRange([e.target.value, to]); }} /></Field>
          <Field label={t("To")}><Input type="date" value={to} onChange={(e) => { setPreset(""); setRange([from, e.target.value]); }} /></Field>
        </span>
      </div>
      {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
      {!data && !error && <Spinner />}
      {data && (
        <div className="space-y-5 p-5">
          <div className="grid gap-3 sm:grid-cols-3">
            <div className="rounded-lg border border-line p-4">
              <p className="text-xs text-muted">{t("Output VAT (on sales)")}</p>
              <p className="tabular mt-1 text-2xl font-semibold">{money(data.outputVat)}</p>
            </div>
            <div className="rounded-lg border border-line p-4">
              <p className="text-xs text-muted">{t("Input VAT (on purchases)")}</p>
              <p className="tabular mt-1 text-2xl font-semibold">{money(data.inputVat)}</p>
            </div>
            <div className={`rounded-lg border p-4 ${refundable ? "border-emerald-200 bg-emerald-50" : "border-amber-200 bg-amber-50"}`}>
              <p className="text-xs text-muted">{refundable ? t("VAT refundable from ZATCA") : t("VAT payable to ZATCA")}</p>
              <p className={`tabular mt-1 text-2xl font-semibold ${refundable ? "text-ok" : "text-amber-800"}`}>{money(Math.abs(Number(data.payable)))}</p>
            </div>
          </div>
          <div className="grid gap-4 md:grid-cols-2">
            <table className="w-full text-sm">
              <tbody className="divide-y divide-line">
                <tr><td className="py-1.5 text-muted">{t("Sales excl. VAT (after notes)")}</td><td className="tabular py-1.5 text-end">{money(data.salesNet)}</td></tr>
                <tr><td className="py-1.5 text-muted">{t("VAT on invoices")}</td><td className="tabular py-1.5 text-end">{money(data.salesVat)}</td></tr>
                <tr><td className="py-1.5 text-muted">{t("Less VAT on credit notes")}</td><td className="tabular py-1.5 text-end">− {money(data.creditNotesVat)}</td></tr>
                <tr><td className="py-1.5 text-muted">{t("Plus VAT on debit notes")}</td><td className="tabular py-1.5 text-end">{money(data.debitNotesVat)}</td></tr>
                <tr className="font-semibold"><td className="py-1.5">{t("Output VAT")}</td><td className="tabular py-1.5 text-end">{money(data.outputVat)}</td></tr>
              </tbody>
            </table>
            <table className="w-full text-sm">
              <tbody className="divide-y divide-line">
                <tr><td className="py-1.5 text-muted">{t("Purchases excl. VAT (after credit memos)")}</td><td className="tabular py-1.5 text-end">{money(data.purchasesNet)}</td></tr>
                <tr><td className="py-1.5 text-muted">{t("VAT on supplier invoices")}</td><td className="tabular py-1.5 text-end">{money(data.purchasesVat)}</td></tr>
                <tr><td className="py-1.5 text-muted">{t("Less VAT on supplier credit memos")}</td><td className="tabular py-1.5 text-end">− {money(data.supplierCreditsVat)}</td></tr>
                <tr className="font-semibold"><td className="py-1.5">{t("Input VAT")}</td><td className="tabular py-1.5 text-end">{money(data.inputVat)}</td></tr>
              </tbody>
            </table>
          </div>
          <p className="text-xs text-muted">
            {t("{a} sales documents and {b} supplier invoices from {from} to {to}. Sales count from their issue date; drafts and rejected documents are left out. Check these figures before filing your return on the ZATCA portal.",
              { a: String(data.salesDocuments), b: String(data.purchaseDocuments), from: date(data.from), to: date(data.to) })}
          </p>
        </div>
      )}
    </Card>
  );
}

function FilingForm({ q, onDone }: { q: Quarter; onDone: () => void }) {
  const t = useT();
  const [f, setF] = useState({
    filedOn: q.filing?.filedOn ?? todayKsa(), reference: q.filing?.reference ?? "",
    paidAmount: q.filing?.paidAmount != null ? String(q.filing.paidAmount) : String(Math.max(0, Number(q.vat.payable))),
    paidOn: q.filing?.paidOn ?? "", notes: q.filing?.notes ?? "",
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <form className="grid gap-3 bg-canvas/50 p-4 sm:grid-cols-2 lg:grid-cols-5" onSubmit={async (e) => {
      e.preventDefault();
      setBusy(true);
      setError(null);
      try {
        await api("PUT", `/vat/returns/${q.year}/${q.quarter}`, {
          filedOn: f.filedOn || null, reference: f.reference || null,
          paidAmount: f.paidOn && f.paidAmount ? Number(f.paidAmount) : null, paidOn: f.paidOn || null, notes: f.notes || null,
        });
        onDone();
      } catch (err) { setError(err instanceof ApiError ? err.message : t("Could not reach the server")); setBusy(false); }
    }}>
      {error && <div className="sm:col-span-2 lg:col-span-5"><Alert tone="bad">{error}</Alert></div>}
      <Field label={t("Filed with ZATCA on")}><Input type="date" value={f.filedOn} onChange={(e) => setF({ ...f, filedOn: e.target.value })} /></Field>
      <Field label={t("ZATCA reference")} optional><Input dir="ltr" value={f.reference} onChange={(e) => setF({ ...f, reference: e.target.value })} /></Field>
      <Field label={t("Paid on")} optional><Input type="date" value={f.paidOn} onChange={(e) => setF({ ...f, paidOn: e.target.value })} /></Field>
      <Field label={t("Amount paid (SAR)")} optional><Input type="number" min="0" step="0.01" value={f.paidAmount} onChange={(e) => setF({ ...f, paidAmount: e.target.value })} /></Field>
      <Field label={t("Notes")} optional><Input value={f.notes} onChange={(e) => setF({ ...f, notes: e.target.value })} /></Field>
      <div className="flex gap-2 sm:col-span-2 lg:col-span-5">
        <Button type="submit" loading={busy}><CheckCircle2 className="size-4" aria-hidden /> {t("Save")}</Button>
        <Button type="button" variant="ghost" onClick={onDone}>{t("Cancel")}</Button>
      </div>
    </form>
  );
}

/** The four quarters of a year: VAT, filing deadline and whether the return was filed and paid. */
function Quarters() {
  const t = useT();
  const thisYear = Number(todayKsa().slice(0, 4));
  const [year, setYear] = useState(thisYear);
  const { data, error, mutate } = useSWR<Quarter[]>(`/vat/quarters?year=${year}`, fetcher, { keepPreviousData: true });
  const [editing, setEditing] = useState<number | null>(null);
  const total = data?.reduce((a, q) => a + Number(q.vat.payable), 0) ?? 0;

  return (
    <Card>
      <CardHeader title={t("Quarterly VAT returns")} subtitle={t("Each quarter's return is due by the end of the following month. Record here when you filed it on the ZATCA portal and when you paid.")}
        action={<Select className="w-28" value={year} onChange={(e) => setYear(Number(e.target.value))} aria-label={t("Year")}>
          {[thisYear + 1, thisYear, thisYear - 1, thisYear - 2, thisYear - 3].map((y) => <option key={y} value={y}>{y}</option>)}
        </Select>} />
      {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
      {!data && !error && <Spinner />}
      {data && (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[820px] text-sm">
            <thead><tr className="border-b border-line text-xs text-muted">
              <th className="px-4 py-2.5 text-start font-medium">{t("Quarter")}</th>
              <th className="px-4 py-2.5 text-start font-medium">{t("Due by")}</th>
              <th className="px-4 py-2.5 text-end font-medium">{t("Output VAT")}</th>
              <th className="px-4 py-2.5 text-end font-medium">{t("Input VAT")}</th>
              <th className="px-4 py-2.5 text-end font-medium">{t("Payable")}</th>
              <th className="px-4 py-2.5 text-start font-medium">{t("Status")}</th>
              <th className="px-4 py-2.5" />
            </tr></thead>
            {data.map((q) => (
              <tbody key={q.quarter} className="border-b border-line">
                <tr>
                  <td className="px-4 py-3"><b>Q{q.quarter} {q.year}</b><span className="block text-xs text-muted">{date(q.from)} – {date(q.to)}</span></td>
                  <td className="tabular px-4 py-3">{date(q.deadline)}</td>
                  <td className="tabular px-4 py-3 text-end">{money(q.vat.outputVat)}</td>
                  <td className="tabular px-4 py-3 text-end">{money(q.vat.inputVat)}</td>
                  <td className={`tabular px-4 py-3 text-end font-semibold ${Number(q.vat.payable) < 0 ? "text-ok" : ""}`}>{money(q.vat.payable)}</td>
                  <td className="px-4 py-3">
                    <span className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ${stateStyle[q.state].tone}`}>{t(stateStyle[q.state].label)}</span>
                    {q.filing && (
                      <span className="mt-1 block text-[11px] text-muted">
                        {[q.filing.filedOn && `${t("Filed")} ${date(q.filing.filedOn)}`, q.filing.reference, q.filing.paidOn && `${t("Paid")} ${money(q.filing.paidAmount)} ${date(q.filing.paidOn)}`].filter(Boolean).join(" · ")}
                      </span>
                    )}
                  </td>
                  <td className="whitespace-nowrap px-4 py-3 text-end">
                    {q.state !== "NOT_DUE" && editing !== q.quarter && (
                      <Button variant="secondary" onClick={() => setEditing(q.quarter)}><Pencil className="size-4" aria-hidden /> {q.filing ? t("Edit") : t("Record filing")}</Button>
                    )}
                    {q.filing && editing !== q.quarter && (
                      <Button variant="ghost" aria-label={t("Clear")} onClick={async () => { await api("DELETE", `/vat/returns/${q.year}/${q.quarter}`); mutate(); }}><Trash2 className="size-4" aria-hidden /></Button>
                    )}
                  </td>
                </tr>
                {editing === q.quarter && <tr><td colSpan={7} className="p-0"><FilingForm q={q} onDone={() => { setEditing(null); mutate(); }} /></td></tr>}
              </tbody>
            ))}
            <tfoot><tr className="bg-canvas/60 font-semibold">
              <td className="px-4 py-2.5" colSpan={2}>{t("Year {y} total", { y: String(year) })}</td>
              <td className="tabular px-4 py-2.5 text-end">{money(data.reduce((a, q) => a + Number(q.vat.outputVat), 0))}</td>
              <td className="tabular px-4 py-2.5 text-end">{money(data.reduce((a, q) => a + Number(q.vat.inputVat), 0))}</td>
              <td className="tabular px-4 py-2.5 text-end">{money(total)}</td>
              <td colSpan={2} />
            </tr></tfoot>
          </table>
        </div>
      )}
    </Card>
  );
}

export default function VatPage() {
  const t = useT();
  return (
    <>
      <PageHeader title={t("VAT & ZATCA returns")} subtitle={t("VAT charged on sales, VAT paid on purchases, what is payable to ZATCA, and each quarter's return")} />
      <PeriodSummary />
      <Quarters />
    </>
  );
}
