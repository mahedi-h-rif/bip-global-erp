"use client";

import Link from "next/link";
import { use, useState } from "react";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import { CheckCircle2, FileDown, FileText, Pencil, Printer, XCircle } from "lucide-react";
import { api, ApiError, fetcher, openFile, printFile } from "@/lib/api";
import type { DeliveryNote } from "@/lib/types";
import { date, dateTime } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Field, Input, PageHeader, Spinner } from "@/components/ui";
import { DeliveryNoteEditor, deliveryStatusLabel } from "@/components/delivery-note-editor";
import { RelationshipMapButton } from "@/components/relationship-map";
import { useT } from "@/lib/i18n";

export default function DeliveryNotePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const t = useT();
  const router = useRouter();
  const { data: n, error, mutate } = useSWR<DeliveryNote>(`/delivery-notes/${id}`, fetcher);
  const [editing, setEditing] = useState(false);
  const [receivedBy, setReceivedBy] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  async function act(name: string, run: () => Promise<DeliveryNote>) {
    setBusy(name);
    setProblem(null);
    try {
      await mutate(await run(), { revalidate: false });
    } catch (e) {
      setProblem(e instanceof ApiError ? e.message : t("Could not reach the server"));
    } finally {
      setBusy(null);
    }
  }

  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!n) return <Spinner />;
  if (editing) {
    return (
      <>
        <PageHeader title={t("Edit {number}", { number: n.number })} />
        <DeliveryNoteEditor initial={n} invoiceNumber={n.invoiceNumber} />
      </>
    );
  }

  return (
    <>
      <PageHeader
        title={t("Delivery note {number}", { number: n.number })}
        subtitle={`${t(deliveryStatusLabel[n.status])} · ${n.customerName ?? n.clientName ?? ""}`}
        actions={
          <span className="flex flex-wrap gap-2">
            {n.status === "DELIVERED" && n.invoiceId == null && n.customerId != null && n.invoicing !== "FULL" && (
              <Button loading={busy === "invoice"} onClick={() => {
                setBusy("invoice");
                setProblem(null);
                api<{ invoiceId: number }>("POST", "/delivery-notes/invoice", { deliveryNoteIds: [n.id] })
                  .then((r) => router.push(`/invoices/${r.invoiceId}`))
                  .catch((e) => { setProblem(e instanceof ApiError ? e.message : t("Could not reach the server")); setBusy(null); });
              }}><FileText className="size-4" aria-hidden /> {t("Copy to invoice")}</Button>
            )}
            <RelationshipMapButton type="DELIVERY_NOTE" id={n.id} />
            <Button variant="secondary" onClick={() => printFile(`/delivery-notes/${n.id}/pdf`)}><Printer className="size-4" aria-hidden /> {t("Print")}</Button>
            <Button variant="secondary" onClick={() => openFile(`/delivery-notes/${n.id}/pdf`)}><FileDown className="size-4" aria-hidden /> PDF</Button>
            {n.status === "DRAFT" && <Button variant="secondary" onClick={() => setEditing(true)}><Pencil className="size-4" aria-hidden /> {t("Edit")}</Button>}
          </span>
        }
      />
      {problem && <div className="mb-4"><Alert tone="bad">{problem}</Alert></div>}

      {n.status === "DRAFT" && (
        <Card className="mb-6">
          <CardHeader title={t("Mark as delivered")} subtitle={n.invoiceId ? undefined : t("Stock items leave the warehouse now.")} />
          <form
            className="flex flex-wrap items-end gap-3 p-5"
            onSubmit={(e) => { e.preventDefault(); void act("deliver", () => api("POST", `/delivery-notes/${n.id}/deliver`, { receivedBy })); }}
          >
            <Field label={t("Received by")} className="min-w-64 flex-1">
              <Input required dir="auto" value={receivedBy} onChange={(e) => setReceivedBy(e.target.value)} placeholder={t("Name of the person who signed")} />
            </Field>
            <Button type="submit" loading={busy === "deliver"}><CheckCircle2 className="size-4" aria-hidden /> {t("Delivered")}</Button>
          </form>
        </Card>
      )}

      <Card className="mb-6">
        <dl className="grid gap-x-6 gap-y-3 p-5 text-sm md:grid-cols-3">
          <div><dt className="text-xs text-muted">{t("Date")}</dt><dd>{date(n.deliveryDate)}</dd></div>
          <div><dt className="text-xs text-muted">{t("Invoice")}</dt><dd>{n.invoiceId ? <Link className="text-brand hover:underline" href={`/invoices/${n.invoiceId}`}>{n.invoiceNumber}</Link> : "—"}</dd></div>
          <div><dt className="text-xs text-muted">{t("Warehouse")}</dt><dd>{n.warehouseName ?? "—"}</dd></div>
          {n.salesOrderId && <div><dt className="text-xs text-muted">{t("Sales order")}</dt><dd><Link className="text-brand hover:underline" href={`/sales-orders/${n.salesOrderId}`}>{n.salesOrderNumber}</Link></dd></div>}
          {n.customerRef && <div><dt className="text-xs text-muted">{t("Customer PO")}</dt><dd className="tabular">{n.customerRef}</dd></div>}
          {n.status === "DELIVERED" && n.invoiceId == null && <div><dt className="text-xs text-muted">{t("Invoiced")}</dt><dd>{t(n.invoicing === "FULL" ? "Fully invoiced" : n.invoicing === "PARTIAL" ? "Partly invoiced" : "Not invoiced yet")}</dd></div>}
          <div><dt className="text-xs text-muted">{t("Delivery address")}</dt><dd dir="auto">{n.deliveryAddress ?? "—"}</dd></div>
          <div><dt className="text-xs text-muted">{t("Driver")}</dt><dd dir="auto">{[n.driverName, n.driverPhone].filter(Boolean).join(" · ") || "—"}</dd></div>
          <div><dt className="text-xs text-muted">{t("Vehicle")}</dt><dd dir="auto">{n.vehicleNo ?? "—"}</dd></div>
          {n.receivedBy && <div><dt className="text-xs text-muted">{t("Received by")}</dt><dd dir="auto">{n.receivedBy} · {dateTime(n.receivedAt)}</dd></div>}
          {n.note && <div className="md:col-span-3"><dt className="text-xs text-muted">{t("Notes")}</dt><dd className="whitespace-pre-wrap" dir="auto">{n.note}</dd></div>}
        </dl>
        <table className="w-full border-t border-line text-sm">
          <thead>
            <tr className="border-b border-line text-xs text-muted">
              <th className="w-10 px-4 py-2.5 text-start font-medium">#</th>
              <th className="px-4 py-2.5 text-start font-medium">{t("Item")}</th>
              <th className="px-4 py-2.5 text-end font-medium">{t("Quantity")}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {n.lines.map((l, i) => (
              <tr key={i}>
                <td className="px-4 py-3 text-muted">{i + 1}</td>
                <td className="px-4 py-3" dir="auto">{l.name}</td>
                <td className="tabular px-4 py-3 text-end">{Number(l.quantity)} {l.unitCode}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>

      {n.status !== "CANCELLED" && (
        <Button
          variant="ghost"
          loading={busy === "cancel"}
          onClick={() => { if (window.confirm(t("Cancel delivery note {number}?", { number: n.number }))) void act("cancel", () => api("POST", `/delivery-notes/${n.id}/cancel`)); }}
        >
          <XCircle className="size-4" aria-hidden /> {t("Cancel delivery note")}
        </Button>
      )}
    </>
  );
}
