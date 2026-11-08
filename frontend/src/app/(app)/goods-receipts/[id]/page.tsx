"use client";

import Link from "next/link";
import { use, useState } from "react";
import useSWR from "swr";
import { FileText, XCircle } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { GoodsReceipt } from "@/lib/types";
import { date, money } from "@/lib/format";
import { Alert, Button, ButtonLink, Card, PageHeader, Spinner } from "@/components/ui";
import { fmtCur, StatusPill } from "@/components/purchase";
import { RelationshipMapButton } from "@/components/relationship-map";
import { useT } from "@/lib/i18n";

export default function GoodsReceiptPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const t = useT();
  const { data: g, error, mutate } = useSWR<GoodsReceipt>(`/goods-receipts/${id}`, fetcher);
  const [problem, setProblem] = useState<string | null>(null);
  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!g) return <Spinner />;
  const billable = g.status === "POSTED" && g.lines.some((l) => Number(l.remaining) > 0);
  return (
    <>
      <PageHeader title={t("Goods receipt {number}", { number: g.number })}
        subtitle={<span className="flex items-center gap-2"><StatusPill status={g.status} /><span dir="auto">{g.supplierName}</span></span>}
        actions={<span className="flex flex-wrap gap-2">
          {billable && <ButtonLink href={`/supplier-invoices/new?grnIds=${g.id}`}><FileText className="size-4" aria-hidden /> {t("Copy to supplier invoice")}</ButtonLink>}
          <RelationshipMapButton type="GOODS_RECEIPT" id={g.id} />
        </span>} />
      {problem && <div className="mb-4"><Alert tone="bad">{problem}</Alert></div>}
      <Card className="mb-6">
        <dl className="grid gap-x-6 gap-y-3 p-5 text-sm md:grid-cols-4">
          <div><dt className="text-xs text-muted">{t("Date")}</dt><dd>{date(g.receiptDate)}</dd></div>
          <div><dt className="text-xs text-muted">{t("Purchase order")}</dt><dd>{g.purchaseOrderId ? <Link className="text-brand hover:underline" href={`/purchase-orders/${g.purchaseOrderId}`}>{g.purchaseOrderNumber}</Link> : "—"}</dd></div>
          <div><dt className="text-xs text-muted">{t("Warehouse")}</dt><dd>{g.warehouseName}</dd></div>
          <div><dt className="text-xs text-muted">{t("Supplier delivery note")}</dt><dd>{g.supplierRef ?? "—"}</dd></div>
          <div><dt className="text-xs text-muted">{t("Total")}</dt><dd>{fmtCur(g.totalInclVat, g.currency ?? "SAR")}</dd></div>
          <div><dt className="text-xs text-muted">{t("Landed costs")}</dt><dd>{money(g.landedCostTotal)}{g.costs.length > 0 && <span className="ms-1 text-muted">({g.costs.map((c) => c.description).join(", ")})</span>}</dd></div>
          {g.remarks && <div className="md:col-span-2"><dt className="text-xs text-muted">{t("Remarks")}</dt><dd dir="auto">{g.remarks}</dd></div>}
        </dl>
        <table className="w-full border-t border-line text-sm">
          <thead><tr className="border-b border-line text-xs text-muted">
            <th className="px-4 py-2.5 text-start font-medium">{t("Item")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Received")}</th>
            <th className="px-4 py-2.5 text-end font-medium">{t("Unit cost")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Landed cost / unit (SAR)")}</th>
            <th className="px-4 py-2.5 text-end font-medium">{t("Invoiced")}</th>
          </tr></thead>
          <tbody className="divide-y divide-line">
            {g.lines.map((l) => (
              <tr key={l.id}>
                <td className="px-4 py-3" dir="auto">{l.name}</td>
                <td className="tabular px-4 py-3 text-end">{Number(l.quantity)} {l.unitCode}</td>
                <td className="tabular px-4 py-3 text-end">{fmtCur(l.unitCost, g.currency ?? "SAR")}</td>
                <td className="tabular px-4 py-3 text-end font-medium">{Number(l.landedUnitCost ?? 0).toFixed(4)}</td>
                <td className="tabular px-4 py-3 text-end">{Number(l.invoiced ?? 0)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
      {g.status === "POSTED" && (
        <Button variant="ghost" onClick={async () => {
          if (!window.confirm(t("Cancel {number}? Its stock goes back out of the warehouse.", { number: g.number }))) return;
          try { await mutate(await api<GoodsReceipt>("POST", `/goods-receipts/${g.id}/cancel`), { revalidate: false }); }
          catch (e) { setProblem(e instanceof ApiError ? e.message : t("Could not reach the server")); }
        }}><XCircle className="size-4" aria-hidden /> {t("Cancel goods receipt")}</Button>
      )}
    </>
  );
}
