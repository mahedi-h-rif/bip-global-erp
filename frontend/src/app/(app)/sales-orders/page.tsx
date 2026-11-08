"use client";

import Link from "next/link";
import { useState } from "react";
import useSWR from "swr";
import { Plus } from "lucide-react";
import { fetcher } from "@/lib/api";
import type { SalesOrder } from "@/lib/types";
import { date, money } from "@/lib/format";
import { Alert, ButtonLink, Card, Empty, PageHeader, Select, Spinner } from "@/components/ui";
import { salesOrderStatusLabel, salesOrderStatusTone } from "@/components/sales-order-editor";
import { useT } from "@/lib/i18n";

export default function SalesOrdersPage() {
  const t = useT();
  const [status, setStatus] = useState("");
  const { data, error } = useSWR<SalesOrder[]>(`/sales-orders${status ? `?status=${status}` : ""}`, fetcher, { keepPreviousData: true });
  return (
    <>
      <PageHeader
        title={t("Sales orders")}
        subtitle={t("Quotation → Sales order → Delivery → Invoice → Payment. Open an order to deliver it.")}
        actions={<ButtonLink href="/sales-orders/new"><Plus className="size-4" aria-hidden /> {t("New sales order")}</ButtonLink>}
      />
      <Card>
        <div className="border-b border-line p-4">
          <Select className="w-56" value={status} onChange={(e) => setStatus(e.target.value)} aria-label={t("Status")}>
            <option value="">{t("All statuses")}</option>
            {Object.entries(salesOrderStatusLabel).map(([k, v]) => <option key={k} value={k}>{t(v)}</option>)}
          </Select>
        </div>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("No sales orders yet")}>{t("Open a quotation and use “Copy to sales order”, or start a new one.")}</Empty>}
        {data && data.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-xs text-muted">
                  <th className="px-4 py-2.5 text-start font-medium">{t("Number")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Customer")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Customer PO")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Quotation")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Total")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Status")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {data.map((o) => (
                  <tr key={o.id} className="hover:bg-slate-50">
                    <td className="tabular px-4 py-3 font-medium"><Link className="text-brand hover:underline" href={`/sales-orders/${o.id}`}>{o.number}</Link></td>
                    <td className="tabular px-4 py-3">{date(o.orderDate)}</td>
                    <td className="px-4 py-3" dir="auto">{o.customerName ?? o.clientName}</td>
                    <td className="tabular px-4 py-3 text-muted">{o.customerRef ?? "—"}</td>
                    <td className="tabular px-4 py-3 text-muted">{o.quotationNumber ?? "—"}</td>
                    <td className="tabular px-4 py-3 text-end">{money(o.totalInclVat)}</td>
                    <td className="px-4 py-3"><span className={`rounded px-2 py-0.5 text-xs font-medium ${salesOrderStatusTone[o.status]}`}>{t(salesOrderStatusLabel[o.status])}</span></td>
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
