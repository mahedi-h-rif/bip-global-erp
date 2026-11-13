"use client";

import Link from "next/link";
import { useState, type ReactNode } from "react";
import useSWR from "swr";
import { ChevronDown, Clock } from "lucide-react";
import { fetcher } from "@/lib/api";
import type { DeliveryNote, DueInvoice, GoodsReceipt, PurchaseOrder, PurchaseRequest, Quotation, SalesOrder, SupplierInvoice } from "@/lib/types";
import { date, money } from "@/lib/format";
import { Card } from "@/components/ui";
import { useT } from "@/lib/i18n";

interface Column<T> { label: string; cell: (row: T) => ReactNode; end?: boolean }

/**
 * "Waiting for you": the documents of the previous step that still need this page's action (orders to deliver,
 * deliveries to invoice, invoices to collect…), each with a button that starts the next document from it.
 */
function PendingCard<T>({ title, rows, columns, action, keyOf }: {
  title: string; rows: T[] | undefined; columns: Column<T>[]; keyOf: (r: T) => string | number;
  action: (r: T) => { href: string; label: string };
}) {
  const t = useT();
  const [open, setOpen] = useState(true);
  const [all, setAll] = useState(false);
  if (!rows || rows.length === 0) return null;
  const shown = all ? rows : rows.slice(0, 8);
  return (
    <Card className="mb-6 border-amber-200">
      <button type="button" onClick={() => setOpen(!open)} aria-expanded={open}
        className="flex w-full items-center gap-2 px-5 py-3 text-start">
        <Clock className="size-4 text-amber-600" aria-hidden />
        <span className="font-semibold">{t(title)}</span>
        <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-semibold text-amber-800">{rows.length}</span>
        <ChevronDown className={`ms-auto size-4 text-muted transition-transform ${open ? "" : "-rotate-90 rtl:rotate-90"}`} aria-hidden />
      </button>
      {open && (
        <div className="overflow-x-auto border-t border-line">
          <table className="w-full text-sm">
            <thead><tr className="border-b border-line text-xs text-muted">
              {columns.map((c) => <th key={c.label} className={`px-4 py-2 font-medium ${c.end ? "text-end" : "text-start"}`}>{t(c.label)}</th>)}
              <th />
            </tr></thead>
            <tbody className="divide-y divide-line">
              {shown.map((r) => {
                const a = action(r);
                return (
                  <tr key={keyOf(r)}>
                    {columns.map((c) => <td key={c.label} className={`px-4 py-2 ${c.end ? "tabular text-end" : ""}`}>{c.cell(r)}</td>)}
                    <td className="px-4 py-2 text-end">
                      <Link href={a.href} className="inline-flex rounded-md bg-brand px-2.5 py-1 text-xs font-medium text-white hover:brightness-110">{t(a.label)}</Link>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {rows.length > shown.length && (
            <button type="button" onClick={() => setAll(true)} className="w-full border-t border-line px-4 py-2 text-xs font-medium text-brand hover:bg-canvas">
              {t("Show all {n}", { n: String(rows.length) })}
            </button>
          )}
        </div>
      )}
    </Card>
  );
}

const link = (href: string, text: ReactNode) => <Link className="tabular font-medium text-brand hover:underline" href={href}>{text}</Link>;

/** Sales orders page: quotations sent or accepted that have no sales order yet. */
export function PendingQuotations() {
  const { data: quotes } = useSWR<Quotation[]>("/quotations", fetcher);
  const { data: orders } = useSWR<SalesOrder[]>("/sales-orders", fetcher);
  const ordered = new Set((orders ?? []).map((o) => o.quotationId).filter(Boolean));
  const rows = quotes && orders ? quotes.filter((q) => (q.status === "SENT" || q.status === "ACCEPTED") && !ordered.has(q.id)) : undefined;
  return <PendingCard title="Quotations waiting for a sales order" rows={rows} keyOf={(q) => q.id}
    columns={[{ label: "Quotation", cell: (q) => link(`/quotations/${q.id}`, q.number) }, { label: "Client", cell: (q) => q.customerName ?? q.clientName },
      { label: "Date", cell: (q) => date(q.issueDate) }, { label: "Total", end: true, cell: (q) => money(q.totalInclVat) }]}
    action={(q) => ({ href: `/sales-orders/new?quotationId=${q.id}`, label: "Make sales order" })} />;
}

/** Delivery notes page: open sales orders with items still to deliver. */
export function PendingDeliveries() {
  const { data } = useSWR<SalesOrder[]>("/sales-orders", fetcher);
  const rows = data?.filter((o) => (o.status === "OPEN" || o.status === "PARTIALLY_DELIVERED") && o.deliveryStatus !== "FULL");
  return <PendingCard title="Sales orders waiting for delivery" rows={rows} keyOf={(o) => o.id}
    columns={[{ label: "Sales order", cell: (o) => link(`/sales-orders/${o.id}`, o.number) }, { label: "Customer", cell: (o) => o.customerName ?? o.clientName },
      { label: "Delivered", cell: (o) => (o.deliveryStatus === "PARTIAL" ? "Partly" : "Not yet") }, { label: "Delivery date", cell: (o) => (o.deliveryDate ? date(o.deliveryDate) : "—") }]}
    action={(o) => ({ href: `/delivery-notes/new?salesOrderId=${o.id}`, label: "Deliver" })} />;
}

/** Invoices page: delivered notes not yet (fully) invoiced. */
export function PendingInvoicing() {
  const { data } = useSWR<DeliveryNote[]>("/delivery-notes?status=DELIVERED", fetcher);
  const rows = data?.filter((n) => n.invoiceId == null && n.customerId != null && n.invoicing !== "FULL");
  return <PendingCard title="Deliveries waiting for an invoice" rows={rows} keyOf={(n) => n.id}
    columns={[{ label: "Delivery note", cell: (n) => link(`/delivery-notes/${n.id}`, n.number) }, { label: "Customer", cell: (n) => n.customerName ?? n.clientName },
      { label: "Delivered", cell: (n) => (n.deliveryDate ? date(n.deliveryDate) : "—") }, { label: "Invoiced", cell: (n) => (n.invoicing === "PARTIAL" ? "Partly" : "Not yet") }]}
    action={(n) => ({ href: `/delivery-notes/${n.id}`, label: "Invoice" })} />;
}

/** Incoming payments page: issued invoices with money still due. */
export function PendingCollections() {
  const { data } = useSWR<DueInvoice[]>("/dues", fetcher);
  return <PendingCard title="Invoices waiting for payment" rows={data} keyOf={(d) => d.invoiceId}
    columns={[{ label: "Invoice", cell: (d) => link(`/invoices/${d.invoiceId}`, d.number) }, { label: "Customer", cell: (d) => d.customerName ?? "—" },
      { label: "Due date", cell: (d) => (d.dueDate ? date(d.dueDate) : "—") }, { label: "Total", end: true, cell: (d) => money(d.total) },
      { label: "Due", end: true, cell: (d) => <b className={d.daysOverdue && d.daysOverdue > 0 ? "text-bad" : ""}>{money(d.due)}</b> }]}
    action={(d) => (d.customerId ? { href: `/receipts/new?customerId=${d.customerId}`, label: "Receive payment" } : { href: `/invoices/${d.invoiceId}`, label: "Receive payment" })} />;
}

/** Purchase orders page: purchase requests not turned into an order yet. */
export function PendingPurchaseRequests() {
  const { data } = useSWR<PurchaseRequest[]>("/purchase-requests", fetcher);
  const rows = data?.filter((r) => r.purchaseOrders.length === 0 && !["CANCELLED", "CLOSED", "ORDERED"].includes(r.status));
  return <PendingCard title="Purchase requests waiting for an order" rows={rows} keyOf={(r) => r.id}
    columns={[{ label: "Request", cell: (r) => link(`/purchase-requests/${r.id}`, r.number) }, { label: "Requested by", cell: (r) => r.requester },
      { label: "Required by", cell: (r) => (r.requiredDate ? date(r.requiredDate) : "—") }, { label: "Lines", end: true, cell: (r) => r.lines.length }]}
    action={(r) => ({ href: `/purchase-requests/${r.id}`, label: "Make purchase order" })} />;
}

/** Goods receipts page: approved purchase orders with goods still to receive. */
export function PendingReceiving() {
  const { data } = useSWR<PurchaseOrder[]>("/purchase-orders", fetcher);
  const rows = data?.filter((p) => p.status === "APPROVED" || p.status === "PARTIALLY_RECEIVED");
  return <PendingCard title="Purchase orders waiting to be received" rows={rows} keyOf={(p) => p.id}
    columns={[{ label: "Purchase order", cell: (p) => link(`/purchase-orders/${p.id}`, p.number) }, { label: "Supplier", cell: (p) => p.supplierName },
      { label: "Delivery date", cell: (p) => (p.deliveryDate ? date(p.deliveryDate) : "—") }, { label: "Received", cell: (p) => (p.status === "PARTIALLY_RECEIVED" ? "Partly" : "Not yet") }]}
    action={(p) => ({ href: `/goods-receipts/new?purchaseOrderId=${p.id}`, label: "Receive goods" })} />;
}

/** Supplier invoices page: goods received that the supplier has not billed yet. */
export function PendingBilling() {
  const { data } = useSWR<GoodsReceipt[]>("/goods-receipts", fetcher);
  const rows = data?.filter((g) => g.status === "POSTED" && g.lines.some((l) => Number(l.remaining ?? 0) > 0));
  return <PendingCard title="Goods received, not yet billed" rows={rows} keyOf={(g) => g.id}
    columns={[{ label: "Goods receipt", cell: (g) => link(`/goods-receipts/${g.id}`, g.number) }, { label: "Supplier", cell: (g) => g.supplierName },
      { label: "Received", cell: (g) => (g.receiptDate ? date(g.receiptDate) : "—") }, { label: "Total", end: true, cell: (g) => money(g.totalInclVat) }]}
    action={(g) => ({ href: `/supplier-invoices/new?grnIds=${g.id}`, label: "Enter supplier invoice" })} />;
}

/** Supplier payments page: supplier invoices with money still to pay. */
export function PendingSupplierPayments() {
  const { data } = useSWR<SupplierInvoice[]>("/supplier-invoices?openOnly=true", fetcher);
  const rows = data?.filter((v) => Number(v.due) > 0);
  return <PendingCard title="Supplier invoices waiting for payment" rows={rows} keyOf={(v) => v.id}
    columns={[{ label: "Supplier invoice", cell: (v) => link(`/supplier-invoices/${v.id}`, v.number) }, { label: "Supplier", cell: (v) => v.supplierName },
      { label: "Due date", cell: (v) => (v.dueDate ? date(v.dueDate) : "—") }, { label: "Due", end: true, cell: (v) => <b className={v.overdueDays > 0 ? "text-bad" : ""}>{money(v.due)}</b> }]}
    action={(v) => ({ href: `/supplier-payments/new?supplierId=${v.supplierId}`, label: "Pay" })} />;
}
