"use client";

import Link from "next/link";
import { use, useState } from "react";
import useSWR from "swr";
import { FileMinus, XCircle } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { GoodsReturn } from "@/lib/types";
import { date } from "@/lib/format";
import { Alert, Button, ButtonLink, Card, PageHeader, Spinner } from "@/components/ui";
import { fmtCur, StatusPill } from "@/components/purchase";
import { RelationshipMapButton } from "@/components/relationship-map";
import { useT } from "@/lib/i18n";

export default function GoodsReturnPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const t = useT();
  const { data: r, error, mutate } = useSWR<GoodsReturn>(`/goods-returns/${id}`, fetcher);
  const [problem, setProblem] = useState<string | null>(null);
  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!r) return <Spinner />;
  return (
    <>
      <PageHeader title={t("Goods return {number}", { number: r.number })}
        subtitle={<span className="flex items-center gap-2"><StatusPill status={r.status} /><span dir="auto">{r.supplierName}</span></span>}
        actions={<span className="flex flex-wrap gap-2">
          {r.status === "POSTED" && !r.creditNoteId && <ButtonLink href={`/supplier-credit-notes/new?returnId=${r.id}`}><FileMinus className="size-4" aria-hidden /> {t("Copy to A/P credit memo")}</ButtonLink>}
          <RelationshipMapButton type="GOODS_RETURN" id={r.id} />
        </span>} />
      {problem && <div className="mb-4"><Alert tone="bad">{problem}</Alert></div>}
      <Card className="mb-6">
        <dl className="grid gap-x-6 gap-y-3 p-5 text-sm md:grid-cols-4">
          <div><dt className="text-xs text-muted">{t("Date")}</dt><dd>{date(r.returnDate)}</dd></div>
          <div><dt className="text-xs text-muted">{t("Goods receipt")}</dt><dd><Link className="text-brand hover:underline" href={`/goods-receipts/${r.goodsReceiptId}`}>{r.goodsReceiptNumber}</Link></dd></div>
          <div><dt className="text-xs text-muted">{t("Warehouse")}</dt><dd>{r.warehouseName}</dd></div>
          <div><dt className="text-xs text-muted">{t("Total")}</dt><dd>{fmtCur(r.totalInclVat, r.currency)}</dd></div>
          {r.creditNoteId && <div><dt className="text-xs text-muted">{t("Credit memo")}</dt><dd><Link className="text-brand hover:underline" href={`/supplier-credit-notes/${r.creditNoteId}`}>{r.creditNoteNumber}</Link></dd></div>}
          {r.reason && <div className="md:col-span-3"><dt className="text-xs text-muted">{t("Reason")}</dt><dd dir="auto">{r.reason}</dd></div>}
        </dl>
        <table className="w-full border-t border-line text-sm">
          <thead><tr className="border-b border-line text-xs text-muted"><th className="px-4 py-2.5 text-start font-medium">{t("Item")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Returned")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Unit cost")}</th></tr></thead>
          <tbody className="divide-y divide-line">
            {r.lines.map((l, i) => <tr key={i}><td className="px-4 py-3" dir="auto">{l.name}</td><td className="tabular px-4 py-3 text-end">{Number(l.quantity)}</td><td className="tabular px-4 py-3 text-end">{fmtCur(l.unitCost, r.currency)}</td></tr>)}
          </tbody>
        </table>
      </Card>
      {r.status === "POSTED" && !r.creditNoteId && (
        <Button variant="ghost" onClick={async () => {
          if (!window.confirm(t("Cancel {number}? The items come back into stock.", { number: r.number }))) return;
          try { await mutate(await api<GoodsReturn>("POST", `/goods-returns/${r.id}/cancel`), { revalidate: false }); }
          catch (e) { setProblem(e instanceof ApiError ? e.message : t("Could not reach the server")); }
        }}><XCircle className="size-4" aria-hidden /> {t("Cancel goods return")}</Button>
      )}
    </>
  );
}
