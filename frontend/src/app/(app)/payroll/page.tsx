"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import { Plus } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { PayrollRun } from "@/lib/types";
import { money } from "@/lib/format";
import { Alert, Button, Card, Empty, Field, Input, PageHeader, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";
import { statusText, useListTools } from "@/components/list-tools";

const payrollTone: Record<string, string> = { DRAFT: "bg-amber-50 text-amber-700", APPROVED: "bg-blue-50 text-blue-700", PAID: "bg-emerald-50 text-emerald-700" };

export default function PayrollPage() {
  const t = useT();
  const router = useRouter();
  const { data, error } = useSWR<PayrollRun[]>("/payroll/runs", fetcher);
  const { toolbar, shown } = useListTools<PayrollRun>(data, { filters: [{ label: "Status", get: (r) => r.status, format: statusText }], date: undefined });
  const now = new Date();
  const [ym, setYm] = useState(`${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  async function create() {
    setBusy(true);
    setProblem(null);
    const [y, m] = ym.split("-").map(Number);
    try { const r = await api<PayrollRun>("POST", "/payroll/runs", { year: y, month: m }); router.push(`/payroll/${r.id}`); }
    catch (e) { setProblem(e instanceof ApiError ? e.message : t("Could not reach the server")); setBusy(false); }
  }
  return (
    <>
      <PageHeader title={t("Payroll")} subtitle={t("Monthly salary runs: generate → review → approve → pay. Payslips and the payroll report come from each run.")} />
      <Card className="mb-6">
        <div className="flex flex-wrap items-end gap-3 p-5">
          {problem && <div className="w-full"><Alert tone="bad">{problem}</Alert></div>}
          <Field label={t("Month")}><Input type="month" value={ym} onChange={(e) => setYm(e.target.value)} /></Field>
          <Button loading={busy} onClick={create}><Plus className="size-4" aria-hidden /> {t("Generate payroll")}</Button>
        </div>
      </Card>
      <Card>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("No payroll runs yet")} />}
        {data && data.length > 0 && toolbar}
        {data && data.length > 0 && shown?.length === 0 && <Empty title={t("Nothing matches")} />}
        {shown && shown.length > 0 && (
          <table className="w-full text-sm">
            <thead><tr className="border-b border-line text-xs text-muted"><th className="px-4 py-2.5 text-start font-medium">{t("Payroll")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Employees")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Gross")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Deductions")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Net")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Status")}</th></tr></thead>
            <tbody className="divide-y divide-line">{shown.map((r) => (
              <tr key={r.id} className="hover:bg-slate-50">
                <td className="tabular px-4 py-3 font-medium"><Link className="text-brand hover:underline" href={`/payroll/${r.id}`}>{r.number}</Link></td>
                <td className="tabular px-4 py-3 text-end">{r.lines.length}</td><td className="tabular px-4 py-3 text-end">{money(r.totalGross)}</td>
                <td className="tabular px-4 py-3 text-end">{money(r.totalDeductions)}</td><td className="tabular px-4 py-3 text-end font-semibold">{money(r.totalNet)}</td>
                <td className="px-4 py-3"><span className={`rounded px-2 py-0.5 text-xs font-medium ${payrollTone[r.status]}`}>{t(r.status.charAt(0) + r.status.slice(1).toLowerCase())}</span></td>
              </tr>
            ))}</tbody>
          </table>
        )}
      </Card>
    </>
  );
}
