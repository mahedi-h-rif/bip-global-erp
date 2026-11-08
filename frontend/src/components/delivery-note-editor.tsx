"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import { Plus, Save, Trash2 } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { Customer, DeliveryLine, DeliveryNote, DeliveryNoteRequest, Product, Warehouse } from "@/lib/types";
import { todayKsa, unitCodes } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Field, Input, Select, Textarea } from "@/components/ui";
import { useT } from "@/lib/i18n";

export const deliveryStatusLabel: Record<DeliveryNote["status"], string> = {
  DRAFT: "Draft",
  DELIVERED: "Delivered",
  CANCELLED: "Cancelled",
};

/** New or draft delivery note. Lines from an invoice keep their invoice link and are capped at what is left to deliver. */
export function DeliveryNoteEditor({ initial, invoiceNumber, orderNumber }: { initial: DeliveryNoteRequest & { id?: number }; invoiceNumber?: string | null; orderNumber?: string | null }) {
  const t = useT();
  const router = useRouter();
  const fromInvoice = initial.invoiceId != null || initial.salesOrderId != null;
  const { data: customers } = useSWR<Customer[]>(fromInvoice ? null : "/customers", fetcher);
  const { data: products } = useSWR<Product[]>(fromInvoice ? null : "/products", fetcher);
  const { data: warehouses } = useSWR<Warehouse[]>("/warehouses", fetcher);
  const [d, setD] = useState<DeliveryNoteRequest>({ ...initial, deliveryDate: initial.deliveryDate ?? todayKsa() });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const setLine = (i: number, patch: Partial<DeliveryLine>) => setD({ ...d, lines: d.lines.map((l, j) => (j === i ? { ...l, ...patch } : l)) });

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const body = {
      ...d,
      customerId: d.customerId ? Number(d.customerId) : null,
      warehouseId: d.warehouseId ? Number(d.warehouseId) : null,
      lines: d.lines.filter((l) => Number(l.quantity) > 0).map((l) => ({ ...l, quantity: Number(l.quantity) })),
    };
    try {
      const saved = initial.id
        ? await api<DeliveryNote>("PUT", `/delivery-notes/${initial.id}`, body)
        : await api<DeliveryNote>("POST", "/delivery-notes", body);
      router.push(`/delivery-notes/${saved.id}`);
      router.refresh();
    } catch (err) {
      setError(err instanceof ApiError ? (err.fields ? Object.values(err.fields).join(", ") : err.message) : t("Could not reach the server"));
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-6">
      {error && <Alert tone="bad" title={t("Not saved")}>{error}</Alert>}
      <Card>
        <CardHeader title={t("Delivery")} subtitle={initial.salesOrderId != null ? t("From sales order {number}", { number: orderNumber ?? "" }) : fromInvoice ? t("From invoice {number}", { number: invoiceNumber ?? "" }) : t("Standalone delivery: stock items leave the warehouse when marked delivered.")} />
        <div className="grid gap-4 p-5 md:grid-cols-2">
          {!fromInvoice && (
            <>
              <Field label={t("Customer")} optional>
                <Select value={d.customerId ?? ""} onChange={(e) => setD({ ...d, customerId: e.target.value ? Number(e.target.value) : null })}>
                  <option value="">{t("Not in the customer list")}</option>
                  {customers?.map((c) => <option key={c.id} value={c.id}>{c.nameEn || c.nameAr}</option>)}
                </Select>
              </Field>
              {!d.customerId && (
                <Field label={t("Client name")}>
                  <Input required dir="auto" value={d.clientName ?? ""} onChange={(e) => setD({ ...d, clientName: e.target.value })} />
                </Field>
              )}
            </>
          )}
          <Field label={t("Delivery address")} optional>
            <Input dir="auto" value={d.deliveryAddress ?? ""} onChange={(e) => setD({ ...d, deliveryAddress: e.target.value })} />
          </Field>
          <Field label={t("Delivery date")}>
            <Input type="date" value={d.deliveryDate ?? ""} onChange={(e) => setD({ ...d, deliveryDate: e.target.value })} />
          </Field>
          {!fromInvoice && (
            <Field label={t("Warehouse")} optional>
              <Select value={d.warehouseId ?? ""} onChange={(e) => setD({ ...d, warehouseId: e.target.value ? Number(e.target.value) : null })}>
                <option value="">{t("Default warehouse")}</option>
                {warehouses?.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
              </Select>
            </Field>
          )}
          <Field label={t("Driver name")} optional>
            <Input dir="auto" value={d.driverName ?? ""} onChange={(e) => setD({ ...d, driverName: e.target.value })} />
          </Field>
          <Field label={t("Driver mobile")} optional>
            <Input dir="ltr" value={d.driverPhone ?? ""} onChange={(e) => setD({ ...d, driverPhone: e.target.value })} />
          </Field>
          <Field label={t("Vehicle / plate no.")} optional>
            <Input dir="auto" value={d.vehicleNo ?? ""} onChange={(e) => setD({ ...d, vehicleNo: e.target.value })} />
          </Field>
        </div>
      </Card>

      <Card>
        <CardHeader title={t("Items")} subtitle={fromInvoice ? t("Lower a quantity for a partial delivery; the rest can go on another note.") : undefined} />
        <div className="space-y-3 p-5">
          {d.lines.map((l, i) => (
            <div key={i} className="grid gap-3 md:grid-cols-[1fr_9rem_8rem_auto] md:items-end">
              <Field label={i === 0 ? t("Item") : ""}>
                {fromInvoice ? (
                  <Input readOnly dir="auto" value={l.name} />
                ) : (
                  <div className="flex gap-2">
                    <Select className="w-44 shrink-0" value={l.productId ?? ""} onChange={(e) => {
                      const p = products?.find((x) => String(x.id) === e.target.value);
                      setLine(i, { productId: p?.id ?? null, name: p ? p.name : l.name, unitCode: p?.unitCode ?? l.unitCode });
                    }} aria-label={t("Product")}>
                      <option value="">{t("Free text")}</option>
                      {products?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
                    </Select>
                    <Input required dir="auto" value={l.name} onChange={(e) => setLine(i, { name: e.target.value })} />
                  </div>
                )}
              </Field>
              <Field label={i === 0 ? t("Quantity") : ""} hint={l.remaining != null ? t("{n} left to deliver", { n: String(l.remaining) }) : undefined}>
                <Input required type="number" min="0" step="any" max={l.remaining ?? undefined} value={l.quantity} onChange={(e) => setLine(i, { quantity: e.target.value })} />
              </Field>
              <Field label={i === 0 ? t("Unit") : ""}>
                <Select disabled={fromInvoice} value={l.unitCode ?? "PCE"} onChange={(e) => setLine(i, { unitCode: e.target.value })}>
                  {unitCodes.map((u) => <option key={u.code} value={u.code}>{t(u.label)}</option>)}
                </Select>
              </Field>
              <Button type="button" variant="ghost" disabled={d.lines.length === 1} onClick={() => setD({ ...d, lines: d.lines.filter((_, j) => j !== i) })} aria-label={t("Remove line")}>
                <Trash2 className="size-4" aria-hidden />
              </Button>
            </div>
          ))}
          {!fromInvoice && (
            <Button type="button" variant="secondary" onClick={() => setD({ ...d, lines: [...d.lines, { name: "", quantity: "", unitCode: "PCE" }] })}>
              <Plus className="size-4" aria-hidden /> {t("Add line")}
            </Button>
          )}
          <Field label={t("Notes")} optional>
            <Textarea rows={2} value={d.note ?? ""} onChange={(e) => setD({ ...d, note: e.target.value })} />
          </Field>
        </div>
      </Card>

      <div className="flex gap-2">
        <Button type="submit" loading={busy}><Save className="size-4" aria-hidden /> {t("Save delivery note")}</Button>
        <Button type="button" variant="ghost" onClick={() => router.back()}>{t("Cancel")}</Button>
      </div>
    </form>
  );
}
