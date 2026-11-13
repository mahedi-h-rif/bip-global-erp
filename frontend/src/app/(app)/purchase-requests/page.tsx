"use client";

import Link from "next/link";
import useSWR from "swr";
import { FileDown, Plus } from "lucide-react";
import { fetcher, openFile } from "@/lib/api";
import type { PurchaseRequest } from "@/lib/types";
import { date } from "@/lib/format";
import { Alert, ButtonLink, Card, Empty, PageHeader, Spinner } from "@/components/ui";
import { StatusPill } from "@/components/purchase";
import { useT } from "@/lib/i18n";
import { statusText, useListTools } from "@/components/list-tools";

/** Purchase requests (SAP B1 "Purchase Request"): a department asks for items; purchasing turns it into an order. */
export default function PurchaseRequestsPage() {
  const t = useT();
  const { data, error } = useSWR<PurchaseRequest[]>("/purchase-requests", fetcher);
  const { toolbar, shown } = useListTools<PurchaseRequest>(data, { filters: [{ label: "Status", get: (r) => r.status, format: statusText }, { label: "Department", get: (r) => r.department }, { label: "Requested by", get: (r) => r.requester }], date: (r) => r.requestDate });
  return (
    <>
      <PageHeader title={t("Purchase requests")} subtitle={t("Departments ask for items here; purchasing copies a request to a purchase order.")}
                  actions={<ButtonLink href="/purchase-requests/new"><Plus className="size-4" aria-hidden /> {t("New request")}</ButtonLink>} />
      <Card>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("No purchase requests yet")} />}
        {data && data.length > 0 && toolbar}
        {data && data.length > 0 && shown?.length === 0 && <Empty title={t("Nothing matches")} />}
        {shown && shown.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead><tr className="border-b border-line text-xs text-muted">
                <th className="px-4 py-2.5 text-start font-medium">{t("Number")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Requested by")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Department")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Required by")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Lines")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Purchase order")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Status")}</th><th className="w-12" />
              </tr></thead>
              <tbody className="divide-y divide-line">
                {shown.map((r) => (
                  <tr key={r.id} className="hover:bg-slate-50">
                    <td className="tabular px-4 py-3 font-medium"><Link className="text-brand hover:underline" href={`/purchase-requests/${r.id}`}>{r.number}</Link></td>
                    <td className="tabular px-4 py-3">{date(r.requestDate)}</td>
                    <td className="px-4 py-3" dir="auto">{r.requester}</td>
                    <td className="px-4 py-3">{r.department ?? "—"}</td>
                    <td className="tabular px-4 py-3">{date(r.requiredDate)}</td>
                    <td className="tabular px-4 py-3 text-end">{r.lines.length}</td>
                    <td className="tabular px-4 py-3">{r.purchaseOrders.map((p) => <Link key={p.id} className="text-brand hover:underline" href={`/purchase-orders/${p.id}`}>{p.number}</Link>)}{r.purchaseOrders.length === 0 && "—"}</td>
                    <td className="px-4 py-3"><StatusPill status={r.status} /></td>
                    <td className="px-2 py-3 text-end"><button type="button" title={t("Download PDF")} aria-label={t("Download PDF")} onClick={() => openFile(`/purchase-requests/${r.id}/pdf`)} className="rounded-md p-1.5 text-muted hover:bg-canvas hover:text-ink"><FileDown className="size-4" /></button></td>
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
