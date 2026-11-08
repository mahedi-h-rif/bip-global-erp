"use client";

import { Suspense, useState, type FormEvent } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import useSWR from "swr";
import { Save } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { PoLine, PurchaseOrder, Supplier, Warehouse } from "@/lib/types";
import { todayKsa } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Field, Input, PageHeader, Select, Spinner, Textarea } from "@/components/ui";
import { CostLines, currencies } from "@/components/purchase";
import { useT } from "@/lib/i18n";

const blank = (): PoLine => ({ name: "", quantity: "", unitCost: "", vatRate: 15, unitCode: "PCE" });

function PoForm() {
  const t = useT();
  const router = useRouter();
  const params = useSearchParams();
  const { data: suppliers } = useSWR<Supplier[]>("/suppliers", fetcher);
  const { data: warehouses } = useSWR<Warehouse[]>("/warehouses", fetcher);
  const [supplierId, setSupplierId] = useState(params.get("supplierId") ?? "");
  const [supplierRef, setSupplierRef] = useState("");
  const [orderDate, setOrderDate] = useState(todayKsa());
  const [deliveryDate, setDeliveryDate] = useState("");
  const [warehouseId, setWarehouseId] = useState("");
  const [currency, setCurrency] = useState("SAR");
  const [rate, setRate] = useState("1");
  const [remarks, setRemarks] = useState("");
  const [lines, setLines] = useState<PoLine[]>([blank()]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function pickSupplier(id: string) {
    setSupplierId(id);
    const s = suppliers?.find((x) => String(x.id) === id);
    if (s?.currency) { setCurrency(s.currency); if (s.currency === "SAR") setRate("1"); }
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const po = await api<PurchaseOrder>("POST", "/purchase-orders", {
        supplierId: Number(supplierId), supplierRef: supplierRef || null, orderDate, deliveryDate: deliveryDate || null,
        warehouseId: warehouseId ? Number(warehouseId) : null, currency, exchangeRate: Number(rate) || 1, remarks: remarks || null,
        lines: lines.map((l) => ({ ...l, quantity: Number(l.quantity), unitCost: Number(l.unitCost), vatRate: Number(l.vatRate ?? 15) })),
      });
      router.push(`/purchase-orders/${po.id}`);
    } catch (err) {
      setError(err instanceof ApiError ? (err.fields ? Object.values(err.fields).join(", ") : err.message) : t("Could not reach the server"));
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-6">
      {error && <Alert tone="bad" title={t("Not saved")}>{error}</Alert>}
      <Card>
        <div className="grid gap-4 p-5 md:grid-cols-3">
          <Field label={t("Supplier")}>
            <Select required value={supplierId} onChange={(e) => pickSupplier(e.target.value)}>
              <option value="">{t("Select…")}</option>
              {suppliers?.map((s) => <option key={s.id} value={s.id}>{s.code} · {s.name}</option>)}
            </Select>
          </Field>
          <Field label={t("Supplier quotation / proforma no.")} optional><Input value={supplierRef} onChange={(e) => setSupplierRef(e.target.value)} /></Field>
          <Field label={t("Deliver to warehouse")} optional>
            <Select value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)}>
              <option value="">{t("Default warehouse")}</option>
              {warehouses?.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
            </Select>
          </Field>
          <Field label={t("Order date")}><Input type="date" value={orderDate} onChange={(e) => setOrderDate(e.target.value)} /></Field>
          <Field label={t("Delivery date")} optional><Input type="date" value={deliveryDate} onChange={(e) => setDeliveryDate(e.target.value)} /></Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label={t("Currency")}>
              <Select value={currency} onChange={(e) => { setCurrency(e.target.value); if (e.target.value === "SAR") setRate("1"); }}>{currencies.map((c) => <option key={c}>{c}</option>)}</Select>
            </Field>
            <Field label={t("SAR per 1 {c}", { c: currency })}>
              <Input type="number" min="0" step="any" disabled={currency === "SAR"} value={rate} onChange={(e) => setRate(e.target.value)} />
            </Field>
          </div>
        </div>
      </Card>
      <Card>
        <CardHeader title={t("Items")} />
        <div className="p-5"><CostLines lines={lines} onChange={setLines} currency={currency} blank={blank} /></div>
      </Card>
      <Card><div className="p-5"><Field label={t("Terms / remarks")} optional><Textarea rows={3} value={remarks} onChange={(e) => setRemarks(e.target.value)} /></Field></div></Card>
      <div className="flex gap-2">
        <Button type="submit" loading={busy}><Save className="size-4" aria-hidden /> {t("Save requisition")}</Button>
        <Button type="button" variant="ghost" onClick={() => router.back()}>{t("Cancel")}</Button>
      </div>
    </form>
  );
}

export default function NewPurchaseOrderPage() {
  const t = useT();
  return (<><PageHeader title={t("New requisition / purchase order")} subtitle={t("طلب شراء · needs an admin's approval before goods can be received")} />
    <Suspense fallback={<Spinner />}><PoForm /></Suspense></>);
}
