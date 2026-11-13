"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import { PackageCheck, Plus } from "lucide-react";
import { fetcher } from "@/lib/api";
import type { PurchaseOrder } from "@/lib/types";
import { date } from "@/lib/format";
import { Alert, Button, ButtonLink, Card, Empty, PageHeader, Select, Spinner } from "@/components/ui";
import { fmtCur, poStatus, StatusPill } from "@/components/purchase";
import { useT } from "@/lib/i18n";
import { statusText, useListTools } from "@/components/list-tools";
import { PendingPurchaseRequests } from "@/components/pending-work";

export default function PurchaseOrdersPage() {
  const t = useT();
  const [status, setStatus] = useState("");
  const { data, error } = useSWR<PurchaseOrder[]>(`/purchase-orders${status ? `?status=${status}` : ""}`, fetcher, { keepPreviousData: true });
  const { toolbar, shown } = useListTools<PurchaseOrder>(data, { filters: [{ label: "Supplier", get: (r) => r.supplierName }, { label: "Warehouse", get: (r) => r.warehouseName }, { label: "Currency", get: (r) => r.currency }], date: (r) => r.orderDate });
  const router = useRouter();
  const [picked, setPicked] = useState<number[]>([]);
  const open = (p: PurchaseOrder) => p.status === "APPROVED" || p.status === "PARTIALLY_RECEIVED";
  const chosen = (data ?? []).filter((p) => picked.includes(p.id));
  const same = chosen.length > 0 && chosen.every((p) => p.supplierId === chosen[0].supplierId && p.currency === chosen[0].currency);
  return (
    <>
      <PageHeader title={t("Purchase orders")} subtitle={t("Requisition → approval → goods receipt → supplier invoice → payment.")}
                  actions={<ButtonLink href="/purchase-orders/new"><Plus className="size-4" aria-hidden /> {t("New requisition")}</ButtonLink>} />
      <PendingPurchaseRequests />
      <Card>
        <div className="flex flex-wrap items-center gap-3 border-b border-line p-4">
          {chosen.length > 0 && (
            <span className="flex items-center gap-2 text-sm"><span className="text-muted">{t("{n} selected", { n: String(chosen.length) })}</span>
              <Button variant="secondary" disabled={!same} title={same ? undefined : t("Choose approved orders of one supplier and currency")}
                onClick={() => router.push(`/goods-receipts/new?purchaseOrderIds=${picked.join(",")}`)}><PackageCheck className="size-4" aria-hidden /> {t("Receive together")}</Button></span>
          )}
          <Select className="w-56" value={status} onChange={(e) => setStatus(e.target.value)} aria-label={t("Status")}>
            <option value="">{t("All statuses")}</option>
            {["PENDING", "APPROVED", "PARTIALLY_RECEIVED", "RECEIVED", "CLOSED", "CANCELLED"].map((k) => <option key={k} value={k}>{t(poStatus[k].label)}</option>)}
          </Select>
        </div>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("No purchase orders yet")} />}
        {data && data.length > 0 && toolbar}
        {data && data.length > 0 && shown?.length === 0 && <Empty title={t("Nothing matches")} />}
        {shown && shown.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead><tr className="border-b border-line text-xs text-muted">
                <th className="w-8 px-3 py-2.5"><span className="sr-only">{t("Select")}</span></th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Number")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Supplier")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Supplier ref.")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Total")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Status")}</th>
              </tr></thead>
              <tbody className="divide-y divide-line">
                {shown.map((p) => (
                  <tr key={p.id} className="hover:bg-slate-50">
                    <td className="px-3 py-3">{open(p) && <input type="checkbox" aria-label={t("Select {number}", { number: p.number })} checked={picked.includes(p.id)}
                      onChange={(e) => setPicked(e.target.checked ? [...picked, p.id] : picked.filter((x) => x !== p.id))} />}</td>
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
