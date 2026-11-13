"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import { FileText, Plus } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { Customer, DeliveryNote } from "@/lib/types";
import { date } from "@/lib/format";
import { Alert, Button, ButtonLink, Card, CardHeader, Empty, PageHeader, Select, Spinner } from "@/components/ui";
import { deliveryStatusLabel } from "@/components/delivery-note-editor";
import { InvoiceQuantities, invoicePicks, picksValid, type QuantityInput } from "@/components/invoice-quantities";
import { useT } from "@/lib/i18n";
import { statusText, useListTools } from "@/components/list-tools";
import { PendingDeliveries } from "@/components/pending-work";

const tone: Record<DeliveryNote["status"], string> = {
  DRAFT: "bg-amber-50 text-amber-700",
  DELIVERED: "bg-emerald-50 text-emerald-700",
  CANCELLED: "bg-slate-100 text-slate-500",
};

/** Copy To Invoice from several delivered notes of one customer. */
function InvoiceDeliveries() {
  const t = useT();
  const router = useRouter();
  const { data: customers } = useSWR<Customer[]>("/customers", fetcher);
  const [customerId, setCustomerId] = useState("");
  const { data: notes } = useSWR<DeliveryNote[]>(customerId ? `/delivery-notes/to-invoice?customerId=${customerId}` : null, fetcher);
  const [picked, setPicked] = useState<number[]>([]);
  const [qty, setQty] = useState<QuantityInput>({});
  const chosen = (notes ?? []).filter((n) => picked.includes(n.id));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function create() {
    setBusy(true);
    setError(null);
    try {
      const r = await api<{ invoiceId: number }>("POST", "/delivery-notes/invoice", { deliveryNoteIds: picked, quantities: invoicePicks(chosen, qty) });
      router.push(`/invoices/${r.invoiceId}`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t("Could not reach the server"));
      setBusy(false);
    }
  }

  return (
    <Card className="mb-6">
      <CardHeader title={t("Invoice deliveries")} subtitle={t("Pick delivered notes of one customer and how much of each line to invoice; one draft invoice is made. Anything left out stays open for another invoice.")} />
      <div className="space-y-3 p-5">
        {error && <Alert tone="bad">{error}</Alert>}
        <Select className="w-80" value={customerId} onChange={(e) => { setCustomerId(e.target.value); setPicked([]); setQty({}); }} aria-label={t("Customer")}>
          <option value="">{t("Select a customer…")}</option>
          {customers?.map((c) => <option key={c.id} value={c.id}>{c.nameEn || c.nameAr}</option>)}
        </Select>
        {customerId && notes?.length === 0 && <p className="text-sm text-muted">{t("No delivered notes waiting for an invoice.")}</p>}
        {notes && notes.length > 0 && (
          <>
            <ul className="divide-y divide-line rounded-lg border border-line">
              {notes.map((n) => (
                <li key={n.id}>
                  <label className="flex cursor-pointer items-center gap-3 px-3 py-2 text-sm">
                    <input type="checkbox" className="size-4 accent-[var(--color-brand)]" checked={picked.includes(n.id)}
                           onChange={(e) => setPicked(e.target.checked ? [...picked, n.id] : picked.filter((x) => x !== n.id))} />
                    <span className="tabular font-medium">{n.number}</span>
                    <span className="tabular text-muted">{date(n.deliveryDate)}</span>
                    {n.salesOrderNumber && <span className="tabular text-muted">{n.salesOrderNumber}</span>}
                    <span className="truncate text-muted">{n.lines.map((l) => `${l.name} × ${Number(l.quantity) - Number(l.invoiced ?? 0)}`).join(", ")}</span>
                  </label>
                </li>
              ))}
            </ul>
            {chosen.length > 0 && <InvoiceQuantities notes={chosen} value={qty} onChange={setQty} />}
            <Button disabled={picked.length === 0 || !picksValid(chosen, qty)} loading={busy} onClick={create}><FileText className="size-4" aria-hidden /> {t("Create invoice from {n} deliveries", { n: String(picked.length) })}</Button>
          </>
        )}
      </div>
    </Card>
  );
}

export default function DeliveryNotesPage() {
  const t = useT();
  const [status, setStatus] = useState("");
  const { data, error } = useSWR<DeliveryNote[]>(`/delivery-notes${status ? `?status=${status}` : ""}`, fetcher, { keepPreviousData: true });
  const { toolbar, shown } = useListTools<DeliveryNote>(data, { filters: [{ label: "Customer", get: (r) => r.customerName ?? r.clientName }, { label: "Warehouse", get: (r) => r.warehouseName }], date: (r) => r.deliveryDate });
  return (
    <>
      <PageHeader
        title={t("Delivery notes")}
        subtitle={t("Create them from an issued invoice (open the invoice) or on their own.")}
        actions={<ButtonLink href="/delivery-notes/new"><Plus className="size-4" aria-hidden /> {t("New delivery note")}</ButtonLink>}
      />
      <PendingDeliveries />
      <InvoiceDeliveries />
      <Card>
        <div className="border-b border-line p-4">
          <Select className="w-48" value={status} onChange={(e) => setStatus(e.target.value)} aria-label={t("Status")}>
            <option value="">{t("All statuses")}</option>
            {Object.entries(deliveryStatusLabel).map(([k, v]) => <option key={k} value={k}>{t(v)}</option>)}
          </Select>
        </div>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("No delivery notes yet")} />}
        {data && data.length > 0 && toolbar}
        {data && data.length > 0 && shown?.length === 0 && <Empty title={t("Nothing matches")} />}
        {shown && shown.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-xs text-muted">
                  <th className="px-4 py-2.5 text-start font-medium">{t("Number")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Client")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Invoice")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Driver / vehicle")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Status")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {shown.map((n) => (
                  <tr key={n.id} className="hover:bg-slate-50">
                    <td className="tabular px-4 py-3 font-medium"><Link className="text-brand hover:underline" href={`/delivery-notes/${n.id}`}>{n.number}</Link></td>
                    <td className="tabular px-4 py-3">{date(n.deliveryDate)}</td>
                    <td className="px-4 py-3" dir="auto">{n.customerName ?? n.clientName ?? "—"}</td>
                    <td className="tabular px-4 py-3">{n.invoiceNumber ?? "—"}</td>
                    <td className="px-4 py-3 text-muted">{[n.driverName, n.vehicleNo].filter(Boolean).join(" · ") || "—"}</td>
                    <td className="px-4 py-3"><span className={`rounded px-2 py-0.5 text-xs font-medium ${tone[n.status]}`}>{t(deliveryStatusLabel[n.status])}</span></td>
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
