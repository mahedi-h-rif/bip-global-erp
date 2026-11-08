"use client";

import { use, useState } from "react";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import { Banknote, CheckCircle2, FileSpreadsheet, FileText, RefreshCw, Trash2 } from "lucide-react";
import { api, ApiError, download, fetcher, openFile } from "@/lib/api";
import type { Me, PaymentMethod, PayrollLine, PayrollRun } from "@/lib/types";
import { dateTime, money, paymentMethodLabel } from "@/lib/format";
import { Alert, Button, Card, Input, PageHeader, Select, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

function EditCell({ value, disabled, onSave }: { value: number; disabled: boolean; onSave: (v: number) => void }) {
  const [v, setV] = useState(String(Number(value)));
  if (disabled) return <span className="tabular">{Number(value) ? money(value) : "—"}</span>;
  return <Input type="number" min="0" step="0.01" className="w-24 text-end" value={v} onChange={(e) => setV(e.target.value)}
                onBlur={() => { if (Number(v) !== Number(value)) onSave(Number(v) || 0); }} />;
}

export default function PayrollRunPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const t = useT();
  const router = useRouter();
  const { data: me } = useSWR<Me>("/auth/me", fetcher);
  const { data: r, error, mutate } = useSWR<PayrollRun>(`/payroll/runs/${id}`, fetcher);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [method, setMethod] = useState<PaymentMethod>("BANK_TRANSFER");
  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!r) return <Spinner />;
  const draft = r.status === "DRAFT";
  const admin = me?.role === "ADMIN";

  async function act(name: string, run: () => Promise<PayrollRun | void>) {
    setBusy(name);
    setProblem(null);
    try { const x = await run(); if (x) await mutate(x, { revalidate: false }); }
    catch (e) { setProblem(e instanceof ApiError ? (e.fields ? Object.values(e.fields).join(", ") : e.message) : t("Could not reach the server")); }
    finally { setBusy(null); }
  }
  const save = (l: PayrollLine, patch: Record<string, number>) => act("line", () => api<PayrollRun>("PUT", `/payroll/runs/${r.id}/lines/${l.id}`, patch));

  return (
    <>
      <PageHeader title={t("Payroll {number}", { number: r.number })}
        subtitle={`${t(r.status.charAt(0) + r.status.slice(1).toLowerCase())} · ${r.lines.length} ${t("employees")}${r.approvedBy ? ` · ${t("approved by")} ${r.approvedBy}` : ""}${r.paidAt ? ` · ${t("paid")} ${dateTime(r.paidAt)}` : ""}`}
        actions={<span className="flex flex-wrap gap-2">
          {draft && <Button variant="secondary" loading={busy === "recalc"} onClick={() => act("recalc", () => api<PayrollRun>("POST", `/payroll/runs/${r.id}/recalculate`))}><RefreshCw className="size-4" aria-hidden /> {t("Recalculate")}</Button>}
          {draft && admin && <Button loading={busy === "approve"} onClick={() => act("approve", () => api<PayrollRun>("POST", `/payroll/runs/${r.id}/approve`))}><CheckCircle2 className="size-4" aria-hidden /> {t("Approve")}</Button>}
          <Button variant="secondary" onClick={() => download(`/payroll/runs/${r.id}/report.xlsx`, `${r.number}.xlsx`)}><FileSpreadsheet className="size-4" aria-hidden /> {t("Payroll & leave report")}</Button>
        </span>} />
      {problem && <div className="mb-4"><Alert tone="bad">{problem}</Alert></div>}
      {draft && !admin && <div className="mb-4"><Alert tone="info">{t("An admin approves the payroll before it is paid.")}</Alert></div>}
      {r.status === "APPROVED" && admin && (
        <Card className="mb-4"><div className="flex flex-wrap items-end gap-3 p-5">
          <span className="text-sm">{t("Pay {amount} and post it as a salary expense in Accounts:", { amount: money(r.totalNet) })}</span>
          <Select className="w-48" value={method} onChange={(e) => setMethod(e.target.value as PaymentMethod)}>{Object.entries(paymentMethodLabel).map(([k, v]) => <option key={k} value={k}>{t(v)}</option>)}</Select>
          <Button loading={busy === "pay"} onClick={() => act("pay", () => api<PayrollRun>("POST", `/payroll/runs/${r.id}/pay`, { method }))}><Banknote className="size-4" aria-hidden /> {t("Mark as paid")}</Button>
        </div></Card>
      )}
      <Card className="mb-4">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[70rem] text-sm">
            <thead><tr className="border-b border-line text-xs text-muted">
              <th className="px-3 py-2.5 text-start font-medium">{t("Employee")}</th><th className="px-3 py-2.5 text-end font-medium">{t("Days")}</th>
              <th className="px-3 py-2.5 text-end font-medium">{t("Salary + allowances")}</th><th className="px-3 py-2.5 text-end font-medium">{t("Overtime")}</th>
              <th className="px-3 py-2.5 text-end font-medium">{t("Additions")}</th><th className="px-3 py-2.5 text-end font-medium">{t("GOSI")}</th>
              <th className="px-3 py-2.5 text-end font-medium">{t("Advance")}</th><th className="px-3 py-2.5 text-end font-medium">{t("Unpaid leave")}</th>
              <th className="px-3 py-2.5 text-end font-medium">{t("Other ded.")}</th><th className="px-3 py-2.5 text-end font-medium">{t("Net")}</th><th className="w-12" />
            </tr></thead>
            <tbody className="divide-y divide-line">
              {r.lines.map((l) => (
                <tr key={l.id}>
                  <td className="px-3 py-2"><span className="font-medium" dir="auto">{l.employeeName}</span> <span className="text-xs text-muted">{l.employeeCode}</span></td>
                  <td className="tabular px-3 py-2 text-end">{Number(l.daysWorked)}</td>
                  <td className="tabular px-3 py-2 text-end">{money(Number(l.basic) + Number(l.housing) + Number(l.transport) + Number(l.otherAllowance))}</td>
                  <td className="px-3 py-2 text-end"><EditCell value={l.overtime} disabled={!draft} onSave={(v) => save(l, { overtime: v })} /></td>
                  <td className="px-3 py-2 text-end"><EditCell value={l.additions} disabled={!draft} onSave={(v) => save(l, { additions: v })} /></td>
                  <td className="px-3 py-2 text-end"><EditCell value={l.gosi} disabled={!draft} onSave={(v) => save(l, { gosi: v })} /></td>
                  <td className="px-3 py-2 text-end"><EditCell value={l.advanceDeduction} disabled={!draft} onSave={(v) => save(l, { advanceDeduction: v })} /></td>
                  <td className="tabular px-3 py-2 text-end">{Number(l.unpaidLeaveDays) ? `${money(l.unpaidLeaveDeduction)} (${Number(l.unpaidLeaveDays)}d)` : "—"}</td>
                  <td className="px-3 py-2 text-end"><EditCell value={l.otherDeductions} disabled={!draft} onSave={(v) => save(l, { otherDeductions: v })} /></td>
                  <td className={`tabular px-3 py-2 text-end font-semibold ${Number(l.net) < 0 ? "text-bad" : ""}`}>{money(l.net)}</td>
                  <td className="px-2"><Button variant="ghost" onClick={() => openFile(`/payroll/runs/${r.id}/payslip/${l.id}`)} aria-label={t("Payslip")} title={t("Payslip")}><FileText className="size-4" aria-hidden /></Button></td>
                </tr>
              ))}
            </tbody>
            <tfoot><tr className="border-t border-line font-semibold">
              <td className="px-3 py-3" colSpan={2}>{t("Total")}</td><td className="tabular px-3 py-3 text-end" colSpan={3}>{t("Gross")} {money(r.totalGross)}</td>
              <td className="tabular px-3 py-3 text-end" colSpan={4}>{t("Deductions")} {money(r.totalDeductions)}</td><td className="tabular px-3 py-3 text-end">{money(r.totalNet)}</td><td />
            </tr></tfoot>
          </table>
        </div>
      </Card>
      {draft && <Button variant="ghost" onClick={() => { if (window.confirm(t("Delete draft payroll {number}?", { number: r.number }))) void act("delete", async () => { await api("DELETE", `/payroll/runs/${r.id}`); router.push("/payroll"); }); }}><Trash2 className="size-4" aria-hidden /> {t("Delete draft")}</Button>}
    </>
  );
}
