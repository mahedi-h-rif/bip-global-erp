"use client";

import Link from "next/link";
import { use, useState } from "react";
import useSWR from "swr";
import { useRouter } from "next/navigation";
import { FileDown, FileText, Lock, Pencil, Printer, Truck, XCircle } from "lucide-react";
import { api, ApiError, fetcher, openFile, printFile } from "@/lib/api";
import type { DeliveryNote, SalesOrder } from "@/lib/types";
import { date, money } from "@/lib/format";
import { Alert, Button, ButtonLink, Card, CardHeader, PageHeader, Spinner } from "@/components/ui";
import { SalesOrderEditor, salesOrderStatusLabel, salesOrderStatusTone } from "@/components/sales-order-editor";
import { deliveryStatusLabel } from "@/components/delivery-note-editor";
import { RelationshipMapButton } from "@/components/relationship-map";
import { GrossProfitCard } from "@/components/gross-profit";
import { useT } from "@/lib/i18n";

export default function SalesOrderPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const t = useT();
  const { data: o, error, mutate } = useSWR<SalesOrder>(`/sales-orders/${id}`, fetcher);
  const { data: notes } = useSWR<DeliveryNote[]>(o ? `/delivery-notes?salesOrderId=${o.id}` : null, fetcher);
  const [editing, setEditing] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const router = useRouter();

  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!o) return <Spinner />;
  if (editing) return (<><PageHeader title={t("Edit {number}", { number: o.number })} /><SalesOrderEditor initial={o} quotationNumber={o.quotationNumber} /></>);

  const open = o.status === "OPEN" || o.status === "PARTIALLY_DELIVERED";
  const leftToDeliver = o.lines.some((l) => Number(l.remaining) > 0);
  const myNotes = (notes ?? []).filter((n) => n.salesOrderId === o.id || (n.salesOrderNumbers ?? []).includes(o.number));
  const levelLabel: Record<string, string> = { NONE: "Not yet", PARTIAL: "Partly", FULL: "Fully" };

  async function close() {
    const reason = window.prompt(t("Close {number}? What is delivered or invoiced stays; the rest will not be delivered. Reason:", { number: o!.number }));
    if (!reason) return;
    try { await mutate(await api<SalesOrder>("POST", `/sales-orders/${o!.id}/close`, { reason }), { revalidate: false }); }
    catch (e) { setProblem(e instanceof ApiError ? e.message : t("Could not reach the server")); }
  }

  async function invoice() {
    try {
      const r = await api<{ invoiceId: number }>("POST", "/sales-orders/invoice", { salesOrderIds: [o!.id] });
      router.push(`/invoices/${r.invoiceId}`);
    } catch (e) { setProblem(e instanceof ApiError ? e.message : t("Could not reach the server")); }
  }

  async function cancel() {
    if (!window.confirm(t("Cancel sales order {number}?", { number: o!.number }))) return;
    try {
      await mutate(await api<SalesOrder>("POST", `/sales-orders/${o!.id}/cancel`), { revalidate: false });
    } catch (e) {
      setProblem(e instanceof ApiError ? e.message : t("Could not reach the server"));
    }
  }

  return (
    <>
      <PageHeader
        title={t("Sales order {number}", { number: o.number })}
        subtitle={<span className="flex flex-wrap items-center gap-2"><span className={`rounded px-2 py-0.5 text-xs font-medium ${salesOrderStatusTone[o.status]}`}>{t(salesOrderStatusLabel[o.status])}</span><span className="rounded bg-slate-100 px-2 py-0.5 text-xs">{t("Delivered")}: {t(levelLabel[o.deliveryStatus] ?? o.deliveryStatus)}</span><span className="rounded bg-slate-100 px-2 py-0.5 text-xs">{t("Invoiced")}: {t(levelLabel[o.invoiceStatus] ?? o.invoiceStatus)}</span><span dir="auto">{o.customerName ?? o.clientName}</span></span>}
        actions={
          <span className="flex flex-wrap gap-2">
            {open && leftToDeliver && <ButtonLink href={`/delivery-notes/new?salesOrderId=${o.id}`}><Truck className="size-4" aria-hidden /> {t("Copy to delivery")}</ButtonLink>}
            {open && leftToDeliver && o.customerId && <Button variant="secondary" title={t("Invoice without a delivery (services, goods collected at the counter)")} onClick={invoice}><FileText className="size-4" aria-hidden /> {t("Copy to invoice")}</Button>}
            {open && <Button variant="secondary" onClick={close}><Lock className="size-4" aria-hidden /> {t("Close order")}</Button>}
            <RelationshipMapButton type="SALES_ORDER" id={o.id} />
            <Button variant="secondary" onClick={() => printFile(`/sales-orders/${o.id}/pdf`)}><Printer className="size-4" aria-hidden /> {t("Print")}</Button>
            <Button variant="secondary" onClick={() => openFile(`/sales-orders/${o.id}/pdf`)}><FileDown className="size-4" aria-hidden /> PDF</Button>
            {o.status === "OPEN" && myNotes.every((n) => n.status === "CANCELLED") && <Button variant="secondary" onClick={() => setEditing(true)}><Pencil className="size-4" aria-hidden /> {t("Edit")}</Button>}
          </span>
        }
      />
      {problem && <div className="mb-4"><Alert tone="bad">{problem}</Alert></div>}
      {o.closedReason && <div className="mb-4"><Alert tone="info">{t("Closed by hand by {who} on {date}: {reason}", { who: o.closedBy ?? "", date: date(o.closedAt), reason: o.closedReason })}</Alert></div>}

      <Card className="mb-6">
        <dl className="grid gap-x-6 gap-y-3 p-5 text-sm md:grid-cols-4">
          <div><dt className="text-xs text-muted">{t("Order date")}</dt><dd>{date(o.orderDate)}</dd></div>
          <div><dt className="text-xs text-muted">{t("Delivery date")}</dt><dd>{date(o.deliveryDate) || "—"}</dd></div>
          <div><dt className="text-xs text-muted">{t("Customer PO")}</dt><dd className="tabular">{o.customerRef ?? "—"}</dd></div>
          <div><dt className="text-xs text-muted">{t("Sales employee")}</dt><dd dir="auto">{o.salesEmployee ?? "—"}</dd></div>
          <div><dt className="text-xs text-muted">{t("Quotation")}</dt><dd>{o.quotationId ? <Link className="text-brand hover:underline" href={`/quotations/${o.quotationId}`}>{o.quotationNumber}</Link> : "—"}</dd></div>
          {o.remarks && <div className="md:col-span-3"><dt className="text-xs text-muted">{t("Remarks")}</dt><dd className="whitespace-pre-wrap" dir="auto">{o.remarks}</dd></div>}
        </dl>
        <div className="overflow-x-auto border-t border-line">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-xs text-muted">
                <th className="w-10 px-4 py-2.5 text-start font-medium">#</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Item")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Ordered")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Delivered")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Open")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Unit price")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Disc. %")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {o.lines.map((l, i) => (
                <tr key={l.id ?? i}>
                  <td className="px-4 py-3 text-muted">{i + 1}</td>
                  <td className="px-4 py-3" dir="auto">{l.name}</td>
                  <td className="tabular px-4 py-3 text-end">{Number(l.quantity)} {l.unitCode}</td>
                  <td className="tabular px-4 py-3 text-end">{Number(l.delivered ?? 0)}</td>
                  <td className={`tabular px-4 py-3 text-end ${Number(l.remaining) > 0 ? "font-semibold text-amber-700" : "text-muted"}`}>{Number(l.remaining ?? 0)}</td>
                  <td className="tabular px-4 py-3 text-end">{money(l.unitPrice)}</td>
                  <td className="tabular px-4 py-3 text-end">{Number(l.discountPct ?? 0) || ""}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr className="border-t border-line font-semibold">
                <td colSpan={6} className="px-4 py-3 text-end">{t("Total incl. VAT")}</td>
                <td className="tabular px-4 py-3 text-end">{money(o.totalInclVat)}</td>
              </tr>
            </tfoot>
          </table>
        </div>
      </Card>

      <GrossProfitCard path={`/sales-orders/${o.id}/gross-profit`} />
      <Card className="mb-6">
        <CardHeader title={t("Deliveries")} />
        {myNotes.length === 0 ? <p className="p-5 text-sm text-muted">{t("Nothing delivered yet.")}</p> : (
          <table className="w-full text-sm">
            <tbody className="divide-y divide-line">
              {myNotes.map((n) => (
                <tr key={n.id}>
                  <td className="tabular px-4 py-3 font-medium"><Link className="text-brand hover:underline" href={`/delivery-notes/${n.id}`}>{n.number}</Link></td>
                  <td className="tabular px-4 py-3">{date(n.deliveryDate)}</td>
                  <td className="px-4 py-3">{t(deliveryStatusLabel[n.status])}</td>
                  <td className="px-4 py-3 text-muted">{n.lines.filter((l) => !l.salesOrderLineId || o.lines.some((x) => x.id === l.salesOrderLineId)).map((l) => `${l.name} × ${Number(l.quantity)} ${l.unitCode ?? ""}`.trim()).join(", ")}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>

      {o.status === "OPEN" && <Button variant="ghost" onClick={cancel}><XCircle className="size-4" aria-hidden /> {t("Cancel sales order")}</Button>}
    </>
  );
}
