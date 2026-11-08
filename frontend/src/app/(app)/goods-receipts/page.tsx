"use client";

import Link from "next/link";
import useSWR from "swr";
import { Plus } from "lucide-react";
import { fetcher } from "@/lib/api";
import type { GoodsReceipt } from "@/lib/types";
import { date, money } from "@/lib/format";
import { Alert, ButtonLink, Card, Empty, PageHeader, Spinner } from "@/components/ui";
import { fmtCur, StatusPill } from "@/components/purchase";
import { useT } from "@/lib/i18n";

export default function GoodsReceiptsPage() {
  const t = useT();
  const { data, error } = useSWR<GoodsReceipt[]>("/goods-receipts", fetcher);
  return (
    <>
      <PageHeader title={t("Goods receipts")} subtitle={t("Stock comes in here, at its landed cost. Open a purchase order to receive against it.")}
                  actions={<ButtonLink href="/goods-receipts/new"><Plus className="size-4" aria-hidden /> {t("Receive without PO")}</ButtonLink>} />
      <Card>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("No goods received yet")} />}
        {data && data.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead><tr className="border-b border-line text-xs text-muted">
                <th className="px-4 py-2.5 text-start font-medium">{t("Number")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Supplier")}</th><th className="px-4 py-2.5 text-start font-medium">{t("PO")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Warehouse")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Landed costs")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Total")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Status")}</th>
              </tr></thead>
              <tbody className="divide-y divide-line">
                {data.map((g) => (
                  <tr key={g.id} className="hover:bg-slate-50">
                    <td className="tabular px-4 py-3 font-medium"><Link className="text-brand hover:underline" href={`/goods-receipts/${g.id}`}>{g.number}</Link></td>
                    <td className="tabular px-4 py-3">{date(g.receiptDate)}</td>
                    <td className="px-4 py-3" dir="auto">{g.supplierName}</td>
                    <td className="tabular px-4 py-3 text-muted">{g.purchaseOrderNumber ?? "—"}</td>
                    <td className="px-4 py-3">{g.warehouseName}</td>
                    <td className="tabular px-4 py-3 text-end">{money(g.landedCostTotal)}</td>
                    <td className="tabular px-4 py-3 text-end">{fmtCur(g.totalInclVat, g.currency ?? "SAR")}</td>
                    <td className="px-4 py-3"><StatusPill status={g.status} /></td>
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
