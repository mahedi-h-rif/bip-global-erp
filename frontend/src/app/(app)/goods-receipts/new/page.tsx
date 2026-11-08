"use client";

import { Suspense, useState, type FormEvent } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import useSWR from "swr";
import { Plus, Save, Trash2 } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { GoodsReceipt, GrnLine, GrnRequest, PurchaseOrder, Supplier, Warehouse } from "@/lib/types";
import { money, todayKsa } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Field, Input, PageHeader, Select, Spinner, Textarea } from "@/components/ui";
import { CostLines, currencies } from "@/components/purchase";
import { useT } from "@/lib/i18n";

const blank = (): GrnLine => ({ name: "", quantity: "", unitCost: "", vatRate: 15, unitCode: "PCE" });

function GrnForm({ initial, poNumber }: { initial: GrnRequest; poNumber?: string }) {
  const t = useT();
  const router = useRouter();
  const { data: suppliers } = useSWR<Supplier[]>(initial.purchaseOrderId ? null : "/suppliers", fetcher);
  const { data: warehouses } = useSWR<Warehouse[]>("/warehouses", fetcher);
  const [g, setG] = useState<GrnRequest>({ ...initial, receiptDate: initial.receiptDate ?? todayKsa(), costs: initial.costs ?? [] });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const fromPo = initial.purchaseOrderId != null;
  const currency = g.currency ?? "SAR";
  const lines = g.lines.map((l) => ({ ...l, locked: fromPo, hint: fromPo && l.remaining == null ? undefined : undefined }));
  const goodsSar = g.lines.reduce((s, l) => s + Number(l.quantity || 0) * Number(l.unitCost || 0) * Number(g.exchangeRate || 1), 0);
  const costs = g.costs.reduce((s, c) => s + Number(c.amount || 0), 0);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const r = await api<GoodsReceipt>("POST", "/goods-receipts", {
        ...g, supplierId: g.supplierId ? Number(g.supplierId) : null, warehouseId: Number(g.warehouseId), exchangeRate: Number(g.exchangeRate || 1),
        lines: g.lines.map((l) => ({ ...l, quantity: Number(l.quantity), unitCost: Number(l.unitCost), vatRate: Number(l.vatRate ?? 15) })),
        costs: g.costs.filter((c) => c.description.trim() && Number(c.amount) > 0).map((c) => ({ ...c, amount: Number(c.amount) })),
      });
      router.push(`/goods-receipts/${r.id}`);
    } catch (err) {
      setError(err instanceof ApiError ? (err.fields ? Object.values(err.fields).join(", ") : err.message) : t("Could not reach the server"));
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-6">
      {error && <Alert tone="bad" title={t("Not saved")}>{error}</Alert>}
      <Card>
        <CardHeader title={t("Receipt")} subtitle={fromPo ? t("Against purchase order {number}. Lower a quantity for a partial receipt.", { number: poNumber ?? "" }) : undefined} />
        <div className="grid gap-4 p-5 md:grid-cols-3">
          {!fromPo && (
            <Field label={t("Supplier")}>
              <Select required value={g.supplierId ?? ""} onChange={(e) => {
                const s = suppliers?.find((x) => String(x.id) === e.target.value);
                setG({ ...g, supplierId: e.target.value ? Number(e.target.value) : null, currency: s?.currency ?? g.currency });
              }}>
                <option value="">{t("Select…")}</option>
                {suppliers?.map((s) => <option key={s.id} value={s.id}>{s.code} · {s.name}</option>)}
              </Select>
            </Field>
          )}
          <Field label={t("Supplier delivery note no.")} optional><Input value={g.supplierRef ?? ""} onChange={(e) => setG({ ...g, supplierRef: e.target.value })} /></Field>
          <Field label={t("Receipt date")}><Input type="date" value={g.receiptDate ?? ""} onChange={(e) => setG({ ...g, receiptDate: e.target.value })} /></Field>
          <Field label={t("Into warehouse")}>
            <Select required value={g.warehouseId ?? ""} onChange={(e) => setG({ ...g, warehouseId: Number(e.target.value) })}>
              <option value="">{t("Select…")}</option>
              {warehouses?.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
            </Select>
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label={t("Currency")}>
              <Select disabled={fromPo} value={currency} onChange={(e) => setG({ ...g, currency: e.target.value, exchangeRate: e.target.value === "SAR" ? 1 : g.exchangeRate })}>
                {currencies.map((c) => <option key={c}>{c}</option>)}
              </Select>
            </Field>
            <Field label={t("SAR per 1 {c}", { c: currency })}>
              <Input type="number" min="0" step="any" disabled={currency === "SAR"} value={g.exchangeRate ?? 1} onChange={(e) => setG({ ...g, exchangeRate: e.target.value })} />
            </Field>
          </div>
        </div>
      </Card>
      <Card>
        <CardHeader title={t("Items received")} />
        <div className="p-5"><CostLines lines={lines} onChange={(ls) => setG({ ...g, lines: ls })} currency={currency} blank={blank} /></div>
      </Card>
      <Card>
        <CardHeader title={t("Landed costs (SAR, excl. VAT)")} subtitle={t("Transport, customs, clearing… spread over the items by value and added to their stock cost.")} />
        <div className="space-y-2 p-5">
          {g.costs.map((c, i) => (
            <div key={i} className="flex gap-2">
              <Input className="flex-1" required value={c.description} placeholder={t("e.g. Transport")} onChange={(e) => setG({ ...g, costs: g.costs.map((x, j) => (j === i ? { ...x, description: e.target.value } : x)) })} />
              <Input className="w-40" type="number" min="0" step="0.01" required value={c.amount} onChange={(e) => setG({ ...g, costs: g.costs.map((x, j) => (j === i ? { ...x, amount: e.target.value } : x)) })} />
              <Button type="button" variant="ghost" onClick={() => setG({ ...g, costs: g.costs.filter((_, j) => j !== i) })} aria-label={t("Remove")}><Trash2 className="size-4" aria-hidden /></Button>
            </div>
          ))}
          <Button type="button" variant="secondary" onClick={() => setG({ ...g, costs: [...g.costs, { description: "", amount: "" }] })}><Plus className="size-4" aria-hidden /> {t("Add cost")}</Button>
          <p className="pt-2 text-sm text-muted">{t("Goods {goods} + costs {costs} = stock value {total}", { goods: money(goodsSar), costs: money(costs), total: money(goodsSar + costs) })}</p>
        </div>
      </Card>
      <Card><div className="p-5"><Field label={t("Remarks")} optional><Textarea rows={2} value={g.remarks ?? ""} onChange={(e) => setG({ ...g, remarks: e.target.value })} /></Field></div></Card>
      <div className="flex gap-2">
        <Button type="submit" loading={busy}><Save className="size-4" aria-hidden /> {t("Post goods receipt")}</Button>
        <Button type="button" variant="ghost" onClick={() => router.back()}>{t("Cancel")}</Button>
      </div>
    </form>
  );
}

function NewGrn() {
  const t = useT();
  const poId = useSearchParams().get("purchaseOrderId");
  const { data, error } = useSWR<GrnRequest>(poId ? `/goods-receipts/from-purchase-order/${poId}` : null, fetcher);
  const { data: po } = useSWR<PurchaseOrder>(poId ? `/purchase-orders/${poId}` : null, fetcher);
  if (!poId) return <GrnForm initial={{ warehouseId: null, currency: "SAR", exchangeRate: 1, lines: [blank()], costs: [] }} />;
  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!data) return <Spinner />;
  if (data.lines.length === 0) return <Alert tone="ok">{t("Everything on this order has been received.")}</Alert>;
  return <GrnForm initial={data} poNumber={po?.number} />;
}

export default function NewGoodsReceiptPage() {
  const t = useT();
  return (<><PageHeader title={t("Goods receipt")} subtitle={t("استلام بضاعة · stock comes in at its landed cost")} />
    <Suspense fallback={<Spinner />}><NewGrn /></Suspense></>);
}
