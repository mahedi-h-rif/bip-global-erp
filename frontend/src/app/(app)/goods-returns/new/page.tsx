"use client";

import { Suspense, useState, type FormEvent } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import useSWR from "swr";
import { api, ApiError, fetcher } from "@/lib/api";
import type { GoodsReturn } from "@/lib/types";
import { Alert, Button, Card, Field, Input, PageHeader, Spinner, Textarea } from "@/components/ui";
import { useT } from "@/lib/i18n";

interface Returnable { goodsReceiptLineId: number; name: string; received: number; returnable: number; invoiced: number; unitCost: number; unitCode: string }

function NewReturn() {
  const t = useT();
  const router = useRouter();
  const grnId = useSearchParams().get("grnId");
  const { data, error } = useSWR<Returnable[]>(grnId ? `/goods-returns/from-goods-receipt/${grnId}` : null, fetcher);
  const [qty, setQty] = useState<Record<number, string>>({});
  const [reason, setReason] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  if (!grnId) return <Alert tone="info">{t("Open a goods receipt and choose “Return to supplier”.")}</Alert>;
  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!data) return <Spinner />;

  async function submit(e: FormEvent) {
    e.preventDefault();
    const lines = data!.map((l) => ({ goodsReceiptLineId: l.goodsReceiptLineId, quantity: Number(qty[l.goodsReceiptLineId] || 0) })).filter((l) => l.quantity > 0);
    if (lines.length === 0) { setProblem(t("Enter the quantity to return on at least one line")); return; }
    setBusy(true); setProblem(null);
    try {
      const r = await api<GoodsReturn>("POST", "/goods-returns", { goodsReceiptId: Number(grnId), reason: reason || null, lines });
      router.push(`/goods-returns/${r.id}`);
    } catch (err) { setProblem(err instanceof ApiError ? err.message : t("Could not reach the server")); setBusy(false); }
  }

  return (
    <form onSubmit={submit}>
      {problem && <div className="mb-4"><Alert tone="bad">{problem}</Alert></div>}
      <Card className="mb-4">
        <table className="w-full text-sm">
          <thead><tr className="border-b border-line text-xs text-muted">
            <th className="px-4 py-2 text-start font-medium">{t("Item")}</th><th className="px-4 py-2 text-end font-medium">{t("Received")}</th>
            <th className="px-4 py-2 text-end font-medium">{t("Invoiced")}</th><th className="px-4 py-2 text-end font-medium">{t("Can return")}</th>
            <th className="w-32 px-4 py-2 text-end font-medium">{t("Return now")}</th>
          </tr></thead>
          <tbody className="divide-y divide-line">
            {data.map((l) => (
              <tr key={l.goodsReceiptLineId}>
                <td className="px-4 py-2" dir="auto">{l.name}</td>
                <td className="tabular px-4 py-2 text-end">{Number(l.received)} {l.unitCode}</td>
                <td className="tabular px-4 py-2 text-end">{Number(l.invoiced)}</td>
                <td className="tabular px-4 py-2 text-end font-medium">{Number(l.returnable)}</td>
                <td className="px-4 py-2"><Input type="number" min="0" max={Number(l.returnable)} step="any" className="text-end" aria-label={t("Return now")}
                  disabled={Number(l.returnable) <= 0} value={qty[l.goodsReceiptLineId] ?? ""} onChange={(e) => setQty({ ...qty, [l.goodsReceiptLineId]: e.target.value })} /></td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="border-t border-line p-5"><Field label={t("Reason")}><Textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} placeholder={t("e.g. damaged rolls, wrong size")} /></Field></div>
      </Card>
      <p className="mb-4 text-xs text-muted">{t("Stock leaves the warehouse at its average cost. Items not yet billed simply won't be billed; for items already on a supplier invoice, make an A/P credit memo from the return.")}</p>
      <Button type="submit" loading={busy}>{t("Post goods return")}</Button>
    </form>
  );
}

export default function NewGoodsReturnPage() {
  const t = useT();
  return (<><PageHeader title={t("Return to supplier")} subtitle={t("مرتجع مشتريات · goods return from a goods receipt")} /><Suspense fallback={<Spinner />}><NewReturn /></Suspense></>);
}
