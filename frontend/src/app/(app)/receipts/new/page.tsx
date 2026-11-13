"use client";

import { Suspense, useState, type FormEvent } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import useSWR from "swr";
import { Save } from "lucide-react";
import { api, ApiError, fetcher, uploadFile } from "@/lib/api";
import type { Customer, OpenInvoice, PaymentMethod, Receipt } from "@/lib/types";
import { date, money, paymentMethodLabel, todayKsa } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Field, Input, PageHeader, Select, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

function ReceiptForm() {
  const t = useT();
  const router = useRouter();
  const params = useSearchParams();
  const { data: customers } = useSWR<Customer[]>("/customers", fetcher);
  const [customerId, setCustomerId] = useState(params.get("customerId") ?? "");
  const { data: open } = useSWR<OpenInvoice[]>(customerId ? `/receipts/open-invoices?customerId=${customerId}` : null, fetcher);
  const [alloc, setAlloc] = useState<Record<number, string>>({});
  const [amount, setAmount] = useState("");
  const [method, setMethod] = useState<PaymentMethod>("BANK_TRANSFER");
  const [receiptDate, setReceiptDate] = useState(todayKsa());
  const [reference, setReference] = useState("");
  const [note, setNote] = useState("");
  /** Optional photo or PDF of the payment slip. */
  const [proof, setProof] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  /** Spread an amount over the open invoices, oldest first. */
  function autoAllocate(total: string) {
    setAmount(total);
    let left = Number(total) || 0;
    const next: Record<number, string> = {};
    for (const inv of open ?? []) {
      if (left <= 0) break;
      const take = Math.min(left, Number(inv.due));
      next[inv.invoiceId] = take.toFixed(2);
      left = Math.round((left - take) * 100) / 100;
    }
    setAlloc(next);
  }

  const total = Object.values(alloc).reduce((s, v) => s + (Number(v) || 0), 0);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const r = await api<Receipt>("POST", "/receipts", {
        customerId: Number(customerId), receiptDate, method, reference: reference || null, note: note || null,
        allocations: Object.entries(alloc).filter(([, v]) => Number(v) > 0).map(([id, v]) => ({ invoiceId: Number(id), amount: Number(v) })),
      });
      if (proof) await uploadFile(`/receipts/${r.id}/proof?name=${encodeURIComponent(proof.name)}`, proof);
      router.push(`/receipts/${r.id}`);
    } catch (err) {
      setError(err instanceof ApiError ? (err.fields ? Object.values(err.fields).join(", ") : err.message) : t("Could not reach the server"));
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-6">
      {error && <Alert tone="bad" title={t("Not saved")}>{error}</Alert>}
      <Card>
        <div className="grid gap-4 p-5 md:grid-cols-3">
          <Field label={t("Customer")}>
            <Select required value={customerId} onChange={(e) => { setCustomerId(e.target.value); setAlloc({}); setAmount(""); }}>
              <option value="">{t("Select a customer…")}</option>
              {customers?.map((c) => <option key={c.id} value={c.id}>{c.nameEn || c.nameAr}</option>)}
            </Select>
          </Field>
          <Field label={t("Date")}><Input type="date" value={receiptDate} onChange={(e) => setReceiptDate(e.target.value)} /></Field>
          <Field label={t("Method")}>
            <Select value={method} onChange={(e) => setMethod(e.target.value as PaymentMethod)}>
              {Object.entries(paymentMethodLabel).map(([k, v]) => <option key={k} value={k}>{t(v)}</option>)}
            </Select>
          </Field>
          <Field label={t("Amount received (SAR)")} hint={t("Spread over the open invoices, oldest first; adjust below")}>
            <Input type="number" min="0" step="0.01" value={amount} onChange={(e) => autoAllocate(e.target.value)} disabled={!open} />
          </Field>
          <Field label={t("Reference")} optional><Input value={reference} onChange={(e) => setReference(e.target.value)} placeholder={t("Transfer / cheque no.")} /></Field>
          <Field label={t("Note")} optional><Input value={note} onChange={(e) => setNote(e.target.value)} /></Field>
          <Field label={t("Receipt (photo or PDF)")} optional hint={t("The bank slip, transfer screenshot or cash receipt. At most 10 MB.")}>
            <input type="file" accept="image/*,application/pdf" capture="environment"
              className="block w-full text-sm file:me-3 file:rounded-md file:border-0 file:bg-brand/10 file:px-3 file:py-1.5 file:text-brand"
              onChange={(e) => setProof(e.target.files?.[0] ?? null)} />
          </Field>
        </div>
      </Card>

      {customerId && (
        <Card>
          <CardHeader title={t("Open invoices")} />
          {!open ? <Spinner /> : open.length === 0 ? <p className="p-5 text-sm text-muted">{t("This customer has nothing to pay.")}</p> : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-line text-xs text-muted">
                    <th className="px-4 py-2.5 text-start font-medium">{t("Invoice")}</th>
                    <th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                    <th className="px-4 py-2.5 text-start font-medium">{t("Customer PO")}</th>
                    <th className="px-4 py-2.5 text-end font-medium">{t("Total")}</th>
                    <th className="px-4 py-2.5 text-end font-medium">{t("Balance due")}</th>
                    <th className="px-4 py-2.5 text-end font-medium">{t("Overdue days")}</th>
                    <th className="w-40 px-4 py-2.5 text-end font-medium">{t("Pay now")}</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {open.map((inv) => (
                    <tr key={inv.invoiceId}>
                      <td className="tabular px-4 py-2 font-medium">{inv.number}</td>
                      <td className="tabular px-4 py-2">{date(inv.issueDate)}</td>
                      <td className="tabular px-4 py-2 text-muted">{inv.customerRef ?? "—"}</td>
                      <td className="tabular px-4 py-2 text-end">{money(inv.total)}</td>
                      <td className="tabular px-4 py-2 text-end font-medium">{money(inv.due)}</td>
                      <td className={`tabular px-4 py-2 text-end ${inv.overdueDays > 0 ? "text-bad" : "text-muted"}`}>{inv.overdueDays}</td>
                      <td className="px-4 py-2">
                        <Input type="number" min="0" max={inv.due} step="0.01" className="text-end" value={alloc[inv.invoiceId] ?? ""}
                               onChange={(e) => setAlloc({ ...alloc, [inv.invoiceId]: e.target.value })} aria-label={t("Pay now")} />
                      </td>
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr className="border-t border-line font-semibold">
                    <td colSpan={6} className="px-4 py-3 text-end">{t("Total payment")}</td>
                    <td className="tabular px-4 py-3 text-end">{money(total)}</td>
                  </tr>
                </tfoot>
              </table>
            </div>
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

export default function NewReceiptPage() {
  const t = useT();
  return (
    <>
      <PageHeader title={t("Receive payment")} subtitle={t("سند قبض · settles one or more invoices")} />
      <Suspense fallback={<Spinner />}><ReceiptForm /></Suspense>
    </>
  );
}
