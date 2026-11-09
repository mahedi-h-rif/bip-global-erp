"use client";

import { useMemo, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import { Plus, Save, Trash2 } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { Customer, Product, SalesOrder, SalesOrderLine, SalesOrderRequest, Warehouse } from "@/lib/types";
import { money, todayKsa, unitCodes } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Field, Input, Select, Textarea } from "@/components/ui";
import { UnitSelect, defaultUnit, useItemUnits } from "@/components/units";
import { useT } from "@/lib/i18n";

export const salesOrderStatusLabel: Record<SalesOrder["status"], string> = {
  OPEN: "Open",
  PARTIALLY_DELIVERED: "Partially delivered",
  CLOSED: "Closed",
  CANCELLED: "Cancelled",
};

export const salesOrderStatusTone: Record<SalesOrder["status"], string> = {
  OPEN: "bg-blue-50 text-blue-700",
  PARTIALLY_DELIVERED: "bg-amber-50 text-amber-700",
  CLOSED: "bg-emerald-50 text-emerald-700",
  CANCELLED: "bg-slate-100 text-slate-500",
};

const emptyLine = (): SalesOrderLine => ({ name: "", quantity: "", unitPrice: "", discountPct: 0, vatRate: 15, unitCode: "PCE" });

function lineNet(l: SalesOrderLine) {
  const gross = Number(l.quantity || 0) * Number(l.unitPrice || 0);
  return Math.round((gross - (gross * Number(l.discountPct || 0)) / 100) * 100) / 100;
}

/** New order (blank or copied from a quotation) or edit of an order with no deliveries yet. */
export function SalesOrderEditor({ initial, quotationNumber }: { initial: SalesOrderRequest & { id?: number }; quotationNumber?: string | null }) {
  const t = useT();
  const itemUnits = useItemUnits();
  const router = useRouter();
  const { data: customers } = useSWR<Customer[]>("/customers", fetcher);
  const { data: products } = useSWR<Product[]>("/products", fetcher);
  const { data: warehouses } = useSWR<Warehouse[]>("/warehouses", fetcher);
  const [o, setO] = useState<SalesOrderRequest>({ ...initial, orderDate: initial.orderDate ?? todayKsa(), lines: initial.lines.length ? initial.lines : [emptyLine()] });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const setLine = (i: number, patch: Partial<SalesOrderLine>) => setO({ ...o, lines: o.lines.map((l, j) => (j === i ? { ...l, ...patch } : l)) });

  const totals = useMemo(() => {
    let net = 0, vat = 0;
    for (const l of o.lines) {
      const n = lineNet(l);
      net += n;
      vat += Math.round(n * Number(l.vatRate ?? 15)) / 100;
    }
    return { net, vat, total: net + vat };
  }, [o.lines]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const body = {
      ...o,
      customerId: o.customerId ? Number(o.customerId) : null,
      warehouseId: o.warehouseId ? Number(o.warehouseId) : null,
      lines: o.lines.filter((l) => l.name.trim() && Number(l.quantity) > 0).map((l) => ({
        ...l, quantity: Number(l.quantity), unitPrice: Number(l.unitPrice), discountPct: Number(l.discountPct || 0), vatRate: Number(l.vatRate ?? 15),
      })),
    };
    try {
      const saved = initial.id
        ? await api<SalesOrder>("PUT", `/sales-orders/${initial.id}`, body)
        : await api<SalesOrder>("POST", "/sales-orders", body);
      router.push(`/sales-orders/${saved.id}`);
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
        <CardHeader title={t("Order")} subtitle={quotationNumber ? t("Copied from quotation {number}", { number: quotationNumber }) : undefined} />
        <div className="grid gap-4 p-5 md:grid-cols-3">
          <Field label={t("Customer")}>
            <Select value={o.customerId ?? ""} disabled={!!o.quotationId && !!initial.customerId}
                    onChange={(e) => setO({ ...o, customerId: e.target.value ? Number(e.target.value) : null })}>
              <option value="">{t("Not in the customer list")}</option>
              {customers?.map((c) => <option key={c.id} value={c.id}>{c.nameEn || c.nameAr}</option>)}
            </Select>
          </Field>
          {!o.customerId && (
            <Field label={t("Client name")}>
              <Input required dir="auto" value={o.clientName ?? ""} onChange={(e) => setO({ ...o, clientName: e.target.value })} />
            </Field>
          )}
          <Field label={t("Customer PO no.")} optional hint={t("Printed on deliveries and invoices")}>
            <Input dir="ltr" value={o.customerRef ?? ""} onChange={(e) => setO({ ...o, customerRef: e.target.value })} placeholder="PO-0000004114" />
          </Field>
          <Field label={t("Sales employee")} optional>
            <Input dir="auto" value={o.salesEmployee ?? ""} onChange={(e) => setO({ ...o, salesEmployee: e.target.value })} />
          </Field>
          <Field label={t("Order date")}>
            <Input type="date" value={o.orderDate ?? ""} onChange={(e) => setO({ ...o, orderDate: e.target.value })} />
          </Field>
          <Field label={t("Delivery date")} optional>
            <Input type="date" value={o.deliveryDate ?? ""} onChange={(e) => setO({ ...o, deliveryDate: e.target.value || null })} />
          </Field>
          <Field label={t("Warehouse")} optional>
            <Select value={o.warehouseId ?? ""} onChange={(e) => setO({ ...o, warehouseId: e.target.value ? Number(e.target.value) : null })}>
              <option value="">{t("Default warehouse")}</option>
              {warehouses?.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
            </Select>
          </Field>
        </div>
      </Card>

      <Card>
        <CardHeader title={t("Items")} subtitle={o.quotationId ? t("Lower a quantity to order part of the quotation now.") : undefined} />
        <div className="overflow-x-auto p-5">
          <table className="w-full min-w-[56rem] text-sm">
            <thead>
              <tr className="text-xs text-muted">
                <th className="pb-2 text-start font-medium">{t("Item")}</th>
                <th className="w-28 pb-2 text-start font-medium">{t("Quantity")}</th>
                <th className="w-28 pb-2 text-start font-medium">{t("Unit")}</th>
                <th className="w-32 pb-2 text-start font-medium">{t("Unit price")}</th>
                <th className="w-24 pb-2 text-start font-medium">{t("Disc. %")}</th>
                <th className="w-20 pb-2 text-start font-medium">{t("VAT %")}</th>
                <th className="w-32 pb-2 text-end font-medium">{t("Total")}</th>
                <th className="w-10" />
              </tr>
            </thead>
            <tbody>
              {o.lines.map((l, i) => (
                <tr key={i} className="align-top">
                  <td className="py-1.5 pe-2">
                    {l.quotationLineId ? (
                      <Input readOnly dir="auto" value={l.name} />
                    ) : (
                      <div className="flex gap-2">
                        <Select className="w-40 shrink-0" value={l.productId ?? ""} aria-label={t("Product")} onChange={(e) => {
                          const p = products?.find((x) => String(x.id) === e.target.value);
                          const du = p ? defaultUnit(itemUnits, p.id) : undefined;
                          setLine(i, p ? { productId: p.id, name: p.name, unitPrice: du?.price ?? p.unitPrice, unitCode: du?.unitCode ?? p.unitCode ?? "PCE" } : { productId: null });
                        }}>
                          <option value="">{t("Free text")}</option>
                          {products?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
                        </Select>
                        <Input required dir="auto" value={l.name} onChange={(e) => setLine(i, { name: e.target.value })} />
                      </div>
                    )}
                    {l.remaining != null && l.quotationLineId && <span className="mt-1 block text-[11px] text-muted">{t("{n} still open on the quotation", { n: String(l.remaining) })}</span>}
                  </td>
                  <td className="py-1.5 pe-2"><Input required type="number" min="0" step="any" value={l.quantity} onChange={(e) => setLine(i, { quantity: e.target.value })} /></td>
                  <td className="py-1.5 pe-2">
                    <UnitSelect productId={l.productId} value={l.unitCode ?? "PCE"} disabled={!!l.quotationLineId} onPick={(u, price) => setLine(i, price == null ? { unitCode: u } : { unitCode: u, unitPrice: price })} />
                  </td>
                  <td className="py-1.5 pe-2"><Input required type="number" min="0" step="any" value={l.unitPrice} onChange={(e) => setLine(i, { unitPrice: e.target.value })} /></td>
                  <td className="py-1.5 pe-2"><Input type="number" min="0" max="100" step="any" value={l.discountPct ?? 0} onChange={(e) => setLine(i, { discountPct: e.target.value })} /></td>
                  <td className="py-1.5 pe-2"><Input type="number" min="0" step="any" value={l.vatRate ?? 15} onChange={(e) => setLine(i, { vatRate: e.target.value })} /></td>
                  <td className="tabular py-3 pe-2 text-end">{money(lineNet(l) * (1 + Number(l.vatRate ?? 15) / 100))}</td>
                  <td className="py-1.5">
                    <Button type="button" variant="ghost" disabled={o.lines.length === 1} onClick={() => setO({ ...o, lines: o.lines.filter((_, j) => j !== i) })} aria-label={t("Remove line")}>
                      <Trash2 className="size-4" aria-hidden />
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <Button type="button" variant="secondary" className="mt-2" onClick={() => setO({ ...o, lines: [...o.lines, emptyLine()] })}>
            <Plus className="size-4" aria-hidden /> {t("Add line")}
          </Button>
          <dl className="ms-auto mt-4 w-72 space-y-1 text-sm">
            <div className="flex justify-between"><dt className="text-muted">{t("Total excl. VAT")}</dt><dd className="tabular">{money(totals.net)}</dd></div>
            <div className="flex justify-between"><dt className="text-muted">{t("VAT")}</dt><dd className="tabular">{money(totals.vat)}</dd></div>
            <div className="flex justify-between border-t border-line pt-1 font-semibold"><dt>{t("Total incl. VAT")}</dt><dd className="tabular">{money(totals.total)}</dd></div>
          </dl>
        </div>
      </Card>

      <Card>
        <div className="p-5">
          <Field label={t("Remarks")} optional hint={t("Delivery and payment terms; printed on the order")}>
            <Textarea rows={3} value={o.remarks ?? ""} onChange={(e) => setO({ ...o, remarks: e.target.value })} />
          </Field>
        </div>
      </Card>

      <div className="flex gap-2">
        <Button type="submit" loading={busy}><Save className="size-4" aria-hidden /> {t("Save sales order")}</Button>
        <Button type="button" variant="ghost" onClick={() => router.back()}>{t("Cancel")}</Button>
      </div>
    </form>
  );
}
