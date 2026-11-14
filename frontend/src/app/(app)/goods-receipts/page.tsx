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
import { statusText, useListTools } from "@/components/list-tools";
import { PendingReceiving } from "@/components/pending-work";
import { PagePreview, PreviewDrawer, usePreview, ViewButton } from "@/components/preview";
import GoodsReceiptPage from "./[id]/page";

export default function GoodsReceiptsPage() {
  const t = useT();
  const { data, error } = useSWR<GoodsReceipt[]>("/goods-receipts", fetcher);
  const { toolbar, shown } = useListTools<GoodsReceipt>(data, { filters: [{ label: "Supplier", get: (r) => r.supplierName }, { label: "Warehouse", get: (r) => r.warehouseName }, { label: "Status", get: (r) => r.status, format: statusText }], date: (r) => r.receiptDate });
  const preview = usePreview<GoodsReceipt>();
  const pv = preview.current;
  return (
    <>
      <PageHeader title={t("Goods receipts")} subtitle={t("Stock comes in here, at its landed cost. Open a purchase order to receive against it.")}
                  actions={<ButtonLink href="/goods-receipts/new"><Plus className="size-4" aria-hidden /> {t("Receive without PO")}</ButtonLink>} />
      <PendingReceiving />
      <Card>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("No goods received yet")} />}
        {data && data.length > 0 && toolbar}
        {data && data.length > 0 && shown?.length === 0 && <Empty title={t("Nothing matches")} />}
        {shown && shown.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead><tr className="border-b border-line text-xs text-muted">
                <th className="px-4 py-2.5 text-start font-medium">{t("Number")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Supplier")}</th><th className="px-4 py-2.5 text-start font-medium">{t("PO")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Warehouse")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Landed costs")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Total")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Status")}</th>
              <th className="w-16" /></tr></thead>
              <tbody className="divide-y divide-line">
                {shown.map((g) => (
                  <tr key={g.id} {...preview.row(g, "hover:bg-slate-50")}>
                    <td className="tabular px-4 py-3 font-medium"><Link className="text-brand hover:underline" href={`/goods-receipts/${g.id}`}>{g.number}</Link></td>
                    <td className="tabular px-4 py-3">{date(g.receiptDate)}</td>
                    <td className="px-4 py-3" dir="auto">{g.supplierName}</td>
                    <td className="tabular px-4 py-3 text-muted">{g.purchaseOrderNumber ?? "—"}</td>
                    <td className="px-4 py-3">{g.warehouseName}</td>
                    <td className="tabular px-4 py-3 text-end">{money(g.landedCostTotal)}</td>
                    <td className="tabular px-4 py-3 text-end">{fmtCur(g.totalInclVat, g.currency ?? "SAR")}</td>
                    <td className="px-4 py-3"><StatusPill status={g.status} /></td>
  <td className="px-2 py-3 text-end"><ViewButton onClick={() => preview.open(g)} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      {pv && (
        <PreviewDrawer wide title={pv.number} subtitle={pv.supplierName} href={`/goods-receipts/${pv.id}`} onClose={preview.close}>
          <PagePreview page={GoodsReceiptPage} id={pv.id} />
        </PreviewDrawer>
      )}
    </>
  );
}
