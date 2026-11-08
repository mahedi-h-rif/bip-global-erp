"use client";

import { Suspense, useState, type FormEvent } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import useSWR from "swr";
import { Save } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { PaymentMethod, Supplier, SupplierInvoice, SupplierPayment } from "@/lib/types";
import { date, money, paymentMethodLabel, todayKsa } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Field, Input, PageHeader, Select, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

function PayForm() {
  const t = useT();
  const router = useRouter();
  const { data: suppliers } = useSWR<Supplier[]>("/suppliers", fetcher);
  const [supplierId, setSupplierId] = useState(useSearchParams().get("supplierId") ?? "");
  const { data: open } = useSWR<SupplierInvoice[]>(supplierId ? `/supplier-invoices?supplierId=${supplierId}&openOnly=true` : null, fetcher);
  const [alloc, setAlloc] = useState<Record<number, string>>({});
  const [method, setMethod] = useState<PaymentMethod>("BANK_TRANSFER");
  const [paymentDate, setPaymentDate] = useState(todayKsa());
  const [reference, setReference] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const total = Object.values(alloc).reduce((s, v) => s + (Number(v) || 0), 0);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const p = await api<SupplierPayment>("POST", "/supplier-payments", {
        supplierId: Number(supplierId), paymentDate, method, reference: reference || null,
        allocations: Object.entries(alloc).filter(([, v]) => Number(v) > 0).map(([id, v]) => ({ supplierInvoiceId: Number(id), amount: Number(v) })),
      });
      router.push(`/supplier-payments/${p.id}`);
    } catch (err) {
      setError(err instanceof ApiError ? (err.fields ? Object.values(err.fields).join(", ") : err.message) : t("Could not reach the server"));
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-6">
      {error && <Alert tone="bad" title={t("Not saved")}>{error}</Alert>}
      <Card>
        <div className="grid gap-4 p-5 md:grid-cols-4">
          <Field label={t("Supplier")}>
            <Select required value={supplierId} onChange={(e) => { setSupplierId(e.target.value); setAlloc({}); }}>
              <option value="">{t("Select…")}</option>
              {suppliers?.map((s) => <option key={s.id} value={s.id}>{s.code} · {s.name}</option>)}
            </Select>
          </Field>
          <Field label={t("Date")}><Input type="date" value={paymentDate} onChange={(e) => setPaymentDate(e.target.value)} /></Field>
          <Field label={t("Method")}><Select value={method} onChange={(e) => setMethod(e.target.value as PaymentMethod)}>{Object.entries(paymentMethodLabel).map(([k, v]) => <option key={k} value={k}>{t(v)}</option>)}</Select></Field>
          <Field label={t("Reference")} optional><Input value={reference} onChange={(e) => setReference(e.target.value)} /></Field>
        </div>
      </Card>
      {supplierId && (
        <Card>
          <CardHeader title={t("Unpaid supplier invoices")} />
          {!open ? <Spinner /> : open.length === 0 ? <p className="p-5 text-sm text-muted">{t("Nothing to pay this supplier.")}</p> : (
            <table className="w-full text-sm">
              <thead><tr className="border-b border-line text-xs text-muted">
                <th className="px-4 py-2.5 text-start font-medium">{t("Invoice")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Due date")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Due (SAR)")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Overdue days")}</th>
                <th className="w-40 px-4 py-2.5 text-end font-medium">{t("Pay now")}</th>
              </tr></thead>
              <tbody className="divide-y divide-line">
                {open.map((v) => (
                  <tr key={v.id}>
                    <td className="tabular px-4 py-2">{v.number} <span className="text-xs text-muted">{v.supplierInvoiceNo}</span></td>
                    <td className="tabular px-4 py-2">{date(v.dueDate)}</td>
                    <td className="tabular px-4 py-2 text-end font-medium">{money(v.due)}</td>
                    <td className={`tabular px-4 py-2 text-end ${v.overdueDays > 0 ? "text-bad" : "text-muted"}`}>{v.overdueDays}</td>
                    <td className="px-4 py-2"><Input type="number" min="0" max={v.due} step="0.01" className="text-end" value={alloc[v.id] ?? ""} onChange={(e) => setAlloc({ ...alloc, [v.id]: e.target.value })} aria-label={t("Pay now")} /></td>
                  </tr>
                ))}
              </tbody>
              <tfoot><tr className="border-t border-line font-semibold"><td colSpan={4} className="px-4 py-3 text-end">{t("Total payment")}</td><td className="tabular px-4 py-3 text-end">{money(total)}</td></tr></tfoot>
            </table>
          )}
        </Card>
      )}
      <div className="flex gap-2">
        <Button type="submit" loading={busy} disabled={total <= 0}><Save className="size-4" aria-hidden /> {t("Save payment")}</Button>
        <Button type="button" variant="ghost" onClick={() => router.back()}>{t("Cancel")}</Button>
      </div>
    </form>
  );
}

export default function NewSupplierPaymentPage() {
  const t = useT();
  return (<><PageHeader title={t("Pay supplier")} subtitle={t("سند صرف · settles one or more supplier invoices")} /><Suspense fallback={<Spinner />}><PayForm /></Suspense></>);
}
