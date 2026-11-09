"use client";

import { Suspense, useState, type FormEvent } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import useSWR from "swr";
import { Trash2 } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { CreditRequest, SupplierCreditNote } from "@/lib/types";
import { money } from "@/lib/format";
import { Alert, Button, Card, Field, Input, PageHeader, Spinner, Textarea } from "@/components/ui";
import { useT } from "@/lib/i18n";

function NewCredit() {
  const t = useT();
  const sp = useSearchParams();
  const returnId = sp.get("returnId"), invoiceId = sp.get("invoiceId");
  const src = returnId ? `/supplier-credit-notes/from-goods-return/${returnId}` : invoiceId ? `/supplier-credit-notes/from-supplier-invoice/${invoiceId}` : null;
  const { data, error } = useSWR<CreditRequest>(src, fetcher, { revalidateOnFocus: false });
  if (!src) return <Alert tone="info">{t("Open a supplier invoice or a goods return and choose “Credit memo”.")}</Alert>;
  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!data) return <Spinner />;
  return <CreditEditor initial={data} />;
}

function CreditEditor({ initial }: { initial: CreditRequest }) {
  const t = useT();
  const router = useRouter();
  const [req, setReq] = useState<CreditRequest>(initial);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const setLine = (i: number, patch: Partial<CreditRequest["lines"][number]>) => setReq({ ...req, lines: req.lines.map((l, k) => (k === i ? { ...l, ...patch } : l)) });
  const net = req.lines.reduce((s, l) => s + Number(l.quantity) * Number(l.unitCost), 0);
  const vat = req.lines.reduce((s, l) => s + Number(l.quantity) * Number(l.unitCost) * Number(l.vatRate) / 100, 0);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true); setProblem(null);
    try {
      const c = await api<SupplierCreditNote>("POST", "/supplier-credit-notes", req);
      router.push(`/supplier-credit-notes/${c.id}`);
    } catch (err) { setProblem(err instanceof ApiError ? err.message : t("Could not reach the server")); setBusy(false); }
  }

  return (
    <form onSubmit={submit}>
      {problem && <div className="mb-4"><Alert tone="bad">{problem}</Alert></div>}
      <Card className="mb-4">
        <div className="grid gap-4 p-5 md:grid-cols-3">
          <Field label={t("Supplier's credit note no.")}><Input value={req.supplierRef ?? ""} onChange={(e) => setReq({ ...req, supplierRef: e.target.value })} /></Field>
          <Field label={t("Date")}><Input type="date" value={req.creditDate ?? ""} onChange={(e) => setReq({ ...req, creditDate: e.target.value })} /></Field>
          <Field label={t("Remarks")} className="md:col-span-3"><Textarea rows={2} value={req.remarks ?? ""} onChange={(e) => setReq({ ...req, remarks: e.target.value })} /></Field>
        </div>
      </Card>
      <Card className="mb-4">
        <table className="w-full text-sm">
          <thead><tr className="border-b border-line text-xs text-muted">
            <th className="px-4 py-2 text-start font-medium">{t("Item")}</th><th className="w-28 px-4 py-2 text-end font-medium">{t("Quantity")}</th>
            <th className="w-32 px-4 py-2 text-end font-medium">{t("Unit cost")}</th><th className="w-24 px-4 py-2 text-end font-medium">{t("VAT %")}</th><th className="w-10" />
          </tr></thead>
          <tbody className="divide-y divide-line">
            {req.lines.map((l, i) => (
              <tr key={i}>
                <td className="px-4 py-2"><Input value={l.name} aria-label={t("Item")} onChange={(e) => setLine(i, { name: e.target.value })} dir="auto" required /></td>
                <td className="px-4 py-2"><Input type="number" min="0" step="any" className="text-end" aria-label={t("Quantity")} value={l.quantity} onChange={(e) => setLine(i, { quantity: e.target.value as unknown as number })} required /></td>
                <td className="px-4 py-2"><Input type="number" min="0" step="any" className="text-end" aria-label={t("Unit cost")} value={l.unitCost} onChange={(e) => setLine(i, { unitCost: e.target.value as unknown as number })} required /></td>
                <td className="px-4 py-2"><Input type="number" min="0" step="any" className="text-end" aria-label={t("VAT %")} value={l.vatRate} onChange={(e) => setLine(i, { vatRate: e.target.value as unknown as number })} /></td>
                <td className="px-2 py-2">{req.lines.length > 1 && <Button type="button" variant="ghost" aria-label={t("Remove line")} onClick={() => setReq({ ...req, lines: req.lines.filter((_, k) => k !== i) })}><Trash2 className="size-4" aria-hidden /></Button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="flex justify-end gap-6 border-t border-line p-4 text-sm">
          <span>{t("Net")} <span className="tabular font-medium">{money(net)}</span></span>
          <span>{t("VAT")} <span className="tabular font-medium">{money(vat)}</span></span>
          <span>{t("Total")} <span className="tabular font-semibold">{money(net + vat)}</span></span>
        </div>
      </Card>
      <p className="mb-4 text-xs text-muted">{t("Amounts are in the supplier invoice's currency. The credit lowers what is owed on that invoice and reverses its input VAT.")}</p>
      <Button type="submit" loading={busy}>{t("Post credit memo")}</Button>
    </form>
  );
}

export default function NewSupplierCreditNotePage() {
  const t = useT();
  return (<><PageHeader title={t("A/P credit memo")} subtitle={t("إشعار دائن من المورد · supplier credit against an invoice")} /><Suspense fallback={<Spinner />}><NewCredit /></Suspense></>);
}
