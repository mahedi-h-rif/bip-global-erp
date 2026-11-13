"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import { FileText, Plus, Truck } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { SalesOrder } from "@/lib/types";
import { date, money } from "@/lib/format";
import { Alert, Button, ButtonLink, Card, Empty, PageHeader, Select, Spinner } from "@/components/ui";
import { salesOrderStatusLabel, salesOrderStatusTone } from "@/components/sales-order-editor";
import { useT } from "@/lib/i18n";
import { statusText, useListTools } from "@/components/list-tools";

export default function SalesOrdersPage() {
  const t = useT();
  const [status, setStatus] = useState("");
  const { data, error } = useSWR<SalesOrder[]>(`/sales-orders${status ? `?status=${status}` : ""}`, fetcher, { keepPreviousData: true });
  const { toolbar, shown } = useListTools<SalesOrder>(data, { filters: [{ label: "Customer", get: (r) => r.customerName ?? r.clientName }], date: (r) => r.orderDate });
  const router = useRouter();
  const [picked, setPicked] = useState<number[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  const isOpen = (o: SalesOrder) => o.status === "OPEN" || o.status === "PARTIALLY_DELIVERED";
  const chosen = (data ?? []).filter((o) => picked.includes(o.id));
  const oneCustomer = chosen.length > 0 && chosen.every((o) => o.customerId && o.customerId === chosen[0].customerId);
  return (
    <>
      <PageHeader
        title={t("Sales orders")}
        subtitle={t("Quotation → Sales order → Delivery → Invoice → Payment. Open an order to deliver it.")}
        actions={<ButtonLink href="/sales-orders/new"><Plus className="size-4" aria-hidden /> {t("New sales order")}</ButtonLink>}
      />
      <Card>
        <div className="flex flex-wrap items-center gap-3 border-b border-line p-4">
          {problem && <div className="w-full"><Alert tone="bad">{problem}</Alert></div>}
          {chosen.length > 0 && (
            <span className="flex flex-wrap items-center gap-2 text-sm">
              <span className="text-muted">{t("{n} selected", { n: String(chosen.length) })}</span>
              <Button variant="secondary" disabled={!oneCustomer} title={oneCustomer ? undefined : t("Choose open orders of one customer")}
                onClick={() => router.push(`/delivery-notes/new?salesOrderIds=${picked.join(",")}`)}><Truck className="size-4" aria-hidden /> {t("Deliver together")}</Button>
              <Button variant="secondary" disabled={!oneCustomer} onClick={async () => {
                try { const r = await api<{ invoiceId: number }>("POST", "/sales-orders/invoice", { salesOrderIds: picked }); router.push(`/invoices/${r.invoiceId}`); }
                catch (e) { setProblem(e instanceof ApiError ? e.message : t("Could not reach the server")); }
              }}><FileText className="size-4" aria-hidden /> {t("Invoice together")}</Button>
            </span>
          )}
          <Select className="w-56" value={status} onChange={(e) => setStatus(e.target.value)} aria-label={t("Status")}>
            <option value="">{t("All statuses")}</option>
            {Object.entries(salesOrderStatusLabel).map(([k, v]) => <option key={k} value={k}>{t(v)}</option>)}
          </Select>
        </div>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("No sales orders yet")}>{t("Open a quotation and use “Copy to sales order”, or start a new one.")}</Empty>}
        {data && data.length > 0 && toolbar}
        {data && data.length > 0 && shown?.length === 0 && <Empty title={t("Nothing matches")} />}
        {shown && shown.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-xs text-muted">
                  <th className="w-8 px-3 py-2.5"><span className="sr-only">{t("Select")}</span></th>
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
                {shown.map((o) => (
                  <tr key={o.id} className="hover:bg-slate-50">
                    <td className="px-3 py-3">{isOpen(o) && <input type="checkbox" aria-label={t("Select {number}", { number: o.number })} checked={picked.includes(o.id)}
                      onChange={(e) => setPicked(e.target.checked ? [...picked, o.id] : picked.filter((x) => x !== o.id))} />}</td>
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
