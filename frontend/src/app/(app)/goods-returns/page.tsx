"use client";

import Link from "next/link";
import useSWR from "swr";
import { fetcher } from "@/lib/api";
import type { GoodsReturn } from "@/lib/types";
import { date } from "@/lib/format";
import { Alert, Card, Empty, PageHeader, Spinner } from "@/components/ui";
import { fmtCur, StatusPill } from "@/components/purchase";
import { useT } from "@/lib/i18n";
import { statusText, useListTools } from "@/components/list-tools";

/** Goods returns (SAP B1 "Goods Return"): stock sent back to the supplier from a goods receipt. */
export default function GoodsReturnsPage() {
  const t = useT();
  const { data, error } = useSWR<GoodsReturn[]>("/goods-returns", fetcher);
  const { toolbar, shown } = useListTools<GoodsReturn>(data, { filters: [{ label: "Supplier", get: (r) => r.supplierName }, { label: "Status", get: (r) => r.status, format: statusText }], date: (r) => r.returnDate });
  return (
    <>
      <PageHeader title={t("Goods returns")} subtitle={t("Items sent back to a supplier. Open a goods receipt and choose “Return to supplier”.")} />
      <Card>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("No goods returned yet")} />}
        {data && data.length > 0 && toolbar}
        {data && data.length > 0 && shown?.length === 0 && <Empty title={t("Nothing matches")} />}
        {shown && shown.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead><tr className="border-b border-line text-xs text-muted">
                <th className="px-4 py-2.5 text-start font-medium">{t("Number")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Supplier")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Goods receipt")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Reason")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Total")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Credit memo")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Status")}</th>
              </tr></thead>
              <tbody className="divide-y divide-line">
                {shown.map((r) => (
                  <tr key={r.id} className="hover:bg-slate-50">
                    <td className="tabular px-4 py-3 font-medium"><Link className="text-brand hover:underline" href={`/goods-returns/${r.id}`}>{r.number}</Link></td>
                    <td className="tabular px-4 py-3">{date(r.returnDate)}</td>
                    <td className="px-4 py-3" dir="auto">{r.supplierName}</td>
                    <td className="tabular px-4 py-3"><Link className="text-brand hover:underline" href={`/goods-receipts/${r.goodsReceiptId}`}>{r.goodsReceiptNumber}</Link></td>
                    <td className="px-4 py-3 text-muted" dir="auto">{r.reason ?? "—"}</td>
                    <td className="tabular px-4 py-3 text-end">{fmtCur(r.totalInclVat, r.currency)}</td>
                    <td className="tabular px-4 py-3">{r.creditNoteId ? <Link className="text-brand hover:underline" href={`/supplier-credit-notes/${r.creditNoteId}`}>{r.creditNoteNumber}</Link> : "—"}</td>
                    <td className="px-4 py-3"><StatusPill status={r.status} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
