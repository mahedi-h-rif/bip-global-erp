"use client";

import Link from "next/link";
import { useState } from "react";
import useSWR from "swr";
import { Plus } from "lucide-react";
import { fetcher } from "@/lib/api";
import type { PurchaseOrder } from "@/lib/types";
import { date } from "@/lib/format";
import { Alert, ButtonLink, Card, Empty, PageHeader, Select, Spinner } from "@/components/ui";
import { fmtCur, poStatus, StatusPill } from "@/components/purchase";
import { useT } from "@/lib/i18n";

export default function PurchaseOrdersPage() {
  const t = useT();
  const [status, setStatus] = useState("");
  const { data, error } = useSWR<PurchaseOrder[]>(`/purchase-orders${status ? `?status=${status}` : ""}`, fetcher, { keepPreviousData: true });
  return (
    <>
      <PageHeader title={t("Purchase orders")} subtitle={t("Requisition → approval → goods receipt → supplier invoice → payment.")}
                  actions={<ButtonLink href="/purchase-orders/new"><Plus className="size-4" aria-hidden /> {t("New requisition")}</ButtonLink>} />
      <Card>
        <div className="border-b border-line p-4">
          <Select className="w-56" value={status} onChange={(e) => setStatus(e.target.value)} aria-label={t("Status")}>
            <option value="">{t("All statuses")}</option>
            {["PENDING", "APPROVED", "PARTIALLY_RECEIVED", "RECEIVED", "CANCELLED"].map((k) => <option key={k} value={k}>{t(poStatus[k].label)}</option>)}
          </Select>
        </div>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("No purchase orders yet")} />}
        {data && data.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead><tr className="border-b border-line text-xs text-muted">
                <th className="px-4 py-2.5 text-start font-medium">{t("Number")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Supplier")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Supplier ref.")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Total")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Status")}</th>
              </tr></thead>
              <tbody className="divide-y divide-line">
                {data.map((p) => (
                  <tr key={p.id} className="hover:bg-slate-50">
                    <td className="tabular px-4 py-3 font-medium"><Link className="text-brand hover:underline" href={`/purchase-orders/${p.id}`}>{p.number}</Link></td>
                    <td className="tabular px-4 py-3">{date(p.orderDate)}</td>
                    <td className="px-4 py-3" dir="auto">{p.supplierName}</td>
                    <td className="tabular px-4 py-3 text-muted">{p.supplierRef ?? "—"}</td>
                    <td className="tabular px-4 py-3 text-end">{fmtCur(p.totalInclVat, p.currency)}</td>
                    <td className="px-4 py-3"><StatusPill status={p.status} /></td>
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
