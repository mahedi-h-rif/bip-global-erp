"use client";

import Link from "next/link";
import { use, useState } from "react";
import useSWR from "swr";
import { Paperclip, XCircle } from "lucide-react";
import { api, ApiError, fetcher, openFile, uploadFile } from "@/lib/api";
import type { Receipt } from "@/lib/types";
import { date, dateTime, money, paymentMethodLabel } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Field, Input, PageHeader, Spinner } from "@/components/ui";
import { RelationshipMapButton } from "@/components/relationship-map";
import { useT } from "@/lib/i18n";

export default function ReceiptPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const t = useT();
  const { data: r, error, mutate } = useSWR<Receipt>(`/receipts/${id}`, fetcher);
  const { data: proofs, mutate: mutateProofs } = useSWR<{ fileName: string }[]>(`/receipts/${id}/proofs`, fetcher);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!r) return <Spinner />;

  async function cancel() {
    setBusy(true);
    setProblem(null);
    try {
      await mutate(await api<Receipt>("POST", `/receipts/${r!.id}/cancel`, { reason }), { revalidate: false });
      setCancelling(false);
    } catch (e) {
      setProblem(e instanceof ApiError ? (e.fields ? Object.values(e.fields).join(", ") : e.message) : t("Could not reach the server"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHeader
        title={t("Incoming payment {number}", { number: r.number })}
        subtitle={<span dir="auto">{r.customerName}</span>}
        actions={<span className="flex gap-2">
          {proofs && proofs.length > 0 ? (
            <Button variant="secondary" onClick={() => openFile(`/receipts/${r.id}/proof`)} title={proofs[0].fileName}><Paperclip className="size-4" aria-hidden /> {t("Receipt file")}</Button>
          ) : (
            <label className="inline-flex cursor-pointer items-center gap-2 rounded-lg border border-line bg-white px-3.5 py-2 text-sm font-medium hover:bg-canvas">
              <Paperclip className="size-4" aria-hidden /> {t("Attach receipt")}
              <input type="file" accept="image/*,application/pdf" className="sr-only" onChange={(e) => {
                const f = e.target.files?.[0];
                if (!f) return;
                setUploadError(null);
                uploadFile(`/receipts/${r.id}/proof?name=${encodeURIComponent(f.name)}`, f)
                  .then(() => mutateProofs()).catch((err) => setUploadError(err instanceof ApiError ? err.message : t("Could not reach the server")));
              }} />
            </label>
          )}
          <RelationshipMapButton type="RECEIPT" id={r.id} />
          {r.status === "POSTED" && !cancelling && <Button variant="ghost" onClick={() => setCancelling(true)}><XCircle className="size-4" aria-hidden /> {t("Cancel payment")}</Button>}</span>}
      />
      {problem && <div className="mb-4"><Alert tone="bad">{problem}</Alert></div>}
      {uploadError && <div className="mb-4"><Alert tone="bad">{uploadError}</Alert></div>}
      {r.status === "CANCELLED" && (
        <div className="mb-4"><Alert tone="warn" title={t("Cancelled")}>{t("Cancelled by {by} on {at}: {reason}. Its amounts were taken off the invoices; post the payment again if needed.", { by: r.cancelledBy ?? "", at: dateTime(r.cancelledAt), reason: r.cancelReason ?? "" })}</Alert></div>
      )}
      {cancelling && (
        <Card className="mb-4">
          <CardHeader title={t("Cancel payment {number}", { number: r.number })} subtitle={t("The payment stays on record, marked cancelled. Its amounts come off the invoices, which become due again.")} />
          <form className="flex flex-wrap items-end gap-3 p-5" onSubmit={(e) => { e.preventDefault(); void cancel(); }}>
            <Field label={t("Reason")} className="min-w-72 flex-1"><Input required value={reason} onChange={(e) => setReason(e.target.value)} placeholder={t("e.g. posted with the wrong date")} /></Field>
            <Button type="submit" variant="danger" loading={busy}>{t("Cancel payment")}</Button>
            <Button type="button" variant="ghost" onClick={() => setCancelling(false)}>{t("Keep it")}</Button>
          </form>
        </Card>
      )}
      <Card>
        <dl className="grid gap-x-6 gap-y-3 p-5 text-sm md:grid-cols-4">
          <div><dt className="text-xs text-muted">{t("Date")}</dt><dd>{date(r.receiptDate)}</dd></div>
          <div><dt className="text-xs text-muted">{t("Method")}</dt><dd>{t(paymentMethodLabel[r.method as keyof typeof paymentMethodLabel] ?? r.method)}</dd></div>
          <div><dt className="text-xs text-muted">{t("Reference")}</dt><dd>{r.reference ?? "—"}</dd></div>
          <div><dt className="text-xs text-muted">{t("Amount")}</dt><dd className="tabular font-semibold">{money(r.amount)}</dd></div>
          {r.note && <div className="md:col-span-4"><dt className="text-xs text-muted">{t("Note")}</dt><dd dir="auto">{r.note}</dd></div>}
        </dl>
        <table className="w-full border-t border-line text-sm">
          <thead><tr className="border-b border-line text-xs text-muted"><th className="px-4 py-2.5 text-start font-medium">{t("Invoice")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Paid")}</th></tr></thead>
          <tbody className="divide-y divide-line">
            {r.allocations.map((a) => (
              <tr key={a.invoiceId}>
                <td className="tabular px-4 py-3"><Link className="text-brand hover:underline" href={`/invoices/${a.invoiceId}`}>{a.invoiceNumber}</Link></td>
                <td className="tabular px-4 py-3 text-end">{money(a.amount)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </>
  );
}
