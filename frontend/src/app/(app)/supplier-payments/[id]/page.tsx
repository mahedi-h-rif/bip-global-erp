"use client";

import Link from "next/link";
import { use, useState } from "react";
import useSWR from "swr";
import { XCircle } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { SupplierPayment } from "@/lib/types";
import { date, money, paymentMethodLabel } from "@/lib/format";
import { Alert, Button, Card, Field, Input, PageHeader, Spinner } from "@/components/ui";
import { StatusPill } from "@/components/purchase";
import { RelationshipMapButton } from "@/components/relationship-map";
import { useT } from "@/lib/i18n";

export default function SupplierPaymentPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const t = useT();
  const { data: p, error, mutate } = useSWR<SupplierPayment>(`/supplier-payments/${id}`, fetcher);
  const [reason, setReason] = useState("");
  const [cancelling, setCancelling] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!p) return <Spinner />;
  return (
    <>
      <PageHeader title={t("Supplier payment {number}", { number: p.number })}
        subtitle={<span className="flex items-center gap-2"><StatusPill status={p.status} /><span dir="auto">{p.supplierName}</span></span>}
        actions={<span className="flex gap-2"><RelationshipMapButton type="SUPPLIER_PAYMENT" id={p.id} />
          {p.status === "POSTED" && !cancelling && <Button variant="ghost" onClick={() => setCancelling(true)}><XCircle className="size-4" aria-hidden /> {t("Cancel payment")}</Button>}</span>} />
      {problem && <div className="mb-4"><Alert tone="bad">{problem}</Alert></div>}
      {p.status === "CANCELLED" && <div className="mb-4"><Alert tone="warn" title={t("Cancelled")}>{p.cancelReason}</Alert></div>}
      {cancelling && (
        <Card className="mb-4">
          <form className="flex flex-wrap items-end gap-3 p-5" onSubmit={async (e) => {
            e.preventDefault();
            try { await mutate(await api<SupplierPayment>("POST", `/supplier-payments/${p.id}/cancel`, { reason }), { revalidate: false }); setCancelling(false); }
            catch (err) { setProblem(err instanceof ApiError ? err.message : t("Could not reach the server")); }
          }}>
            <Field label={t("Reason")} className="min-w-72 flex-1"><Input required value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
            <Button type="submit" variant="danger">{t("Cancel payment")}</Button>
            <Button type="button" variant="ghost" onClick={() => setCancelling(false)}>{t("Keep it")}</Button>
          </form>
        </Card>
      )}
      <Card>
        <dl className="grid gap-x-6 gap-y-3 p-5 text-sm md:grid-cols-4">
          <div><dt className="text-xs text-muted">{t("Date")}</dt><dd>{date(p.paymentDate)}</dd></div>
          <div><dt className="text-xs text-muted">{t("Method")}</dt><dd>{t(paymentMethodLabel[p.method as keyof typeof paymentMethodLabel] ?? p.method)}</dd></div>
          <div><dt className="text-xs text-muted">{t("Reference")}</dt><dd>{p.reference ?? "—"}</dd></div>
          <div><dt className="text-xs text-muted">{t("Amount")}</dt><dd className="font-semibold">{money(p.amount)}</dd></div>
        </dl>
        <table className="w-full border-t border-line text-sm"><tbody className="divide-y divide-line">
          {p.allocations.map((a) => (
            <tr key={a.supplierInvoiceId}><td className="px-4 py-3"><Link className="text-brand hover:underline" href={`/supplier-invoices/${a.supplierInvoiceId}`}>{a.invoiceNumber}</Link></td><td className="tabular px-4 py-3 text-end">{money(a.amount)}</td></tr>
          ))}
        </tbody></table>
      </Card>
    </>
  );
}
