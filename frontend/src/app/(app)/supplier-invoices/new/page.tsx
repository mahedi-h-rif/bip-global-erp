"use client";

import { Suspense, useState, type FormEvent } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import useSWR from "swr";
import { Save } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { Supplier, SupplierInvoice, SupplierInvoiceLine, SupplierInvoiceRequest } from "@/lib/types";
import { todayKsa } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Field, Input, PageHeader, Select, Spinner, Textarea } from "@/components/ui";
import { CostLines, currencies } from "@/components/purchase";
import { useT } from "@/lib/i18n";

const blank = (): SupplierInvoiceLine => ({ name: "", quantity: "", unitCost: "", vatRate: 15 });

function InvForm({ initial }: { initial: SupplierInvoiceRequest }) {
  const t = useT();
  const router = useRouter();
  const { data: suppliers } = useSWR<Supplier[]>("/suppliers", fetcher);
  const [v, setV] = useState<SupplierInvoiceRequest>({ ...initial, invoiceDate: initial.invoiceDate ?? todayKsa() });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const fromGrn = initial.lines.some((l) => l.goodsReceiptLineId);
  const currency = v.currency ?? "SAR";

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const r = await api<SupplierInvoice>("POST", "/supplier-invoices", {
        ...v, supplierId: Number(v.supplierId), exchangeRate: Number(v.exchangeRate || 1), dueDate: v.dueDate || null,
        lines: v.lines.map((l) => ({ ...l, quantity: Number(l.quantity), unitCost: Number(l.unitCost), vatRate: Number(l.vatRate ?? 15) })),
      });
      router.push(`/supplier-invoices/${r.id}`);
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
            <Select required disabled={fromGrn} value={v.supplierId ?? ""} onChange={(e) => {
              const s = suppliers?.find((x) => String(x.id) === e.target.value);
              setV({ ...v, supplierId: e.target.value ? Number(e.target.value) : null, currency: s?.currency ?? v.currency });
            }}>
              <option value="">{t("Select…")}</option>
              {suppliers?.map((s) => <option key={s.id} value={s.id}>{s.code} · {s.name}</option>)}
            </Select>
          </Field>
          <Field label={t("Supplier's invoice no.")}><Input required value={v.supplierInvoiceNo ?? ""} onChange={(e) => setV({ ...v, supplierInvoiceNo: e.target.value })} /></Field>
          <Field label={t("Invoice date")}><Input type="date" value={v.invoiceDate ?? ""} onChange={(e) => setV({ ...v, invoiceDate: e.target.value })} /></Field>
          <Field label={t("Due date")} optional hint={t("Default: supplier's payment terms")}><Input type="date" value={v.dueDate ?? ""} onChange={(e) => setV({ ...v, dueDate: e.target.value })} /></Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label={t("Currency")}>
              <Select disabled={fromGrn} value={currency} onChange={(e) => setV({ ...v, currency: e.target.value, exchangeRate: e.target.value === "SAR" ? 1 : v.exchangeRate })}>{currencies.map((c) => <option key={c}>{c}</option>)}</Select>
            </Field>
            <Field label={t("SAR per 1 {c}", { c: currency })}><Input type="number" min="0" step="any" disabled={currency === "SAR"} value={v.exchangeRate ?? 1} onChange={(e) => setV({ ...v, exchangeRate: e.target.value })} /></Field>
          </div>
        </div>
      </Card>
      <Card>
        <CardHeader title={t("Lines")} subtitle={fromGrn ? t("From goods receipts; adjust prices to match the supplier's invoice.") : undefined} />
        <div className="p-5"><CostLines lines={v.lines.map((l) => ({ ...l, locked: !!l.goodsReceiptLineId }))} onChange={(ls) => setV({ ...v, lines: ls })} currency={currency} blank={blank} /></div>
      </Card>
      <Card><div className="p-5"><Field label={t("Remarks")} optional><Textarea rows={2} value={v.remarks ?? ""} onChange={(e) => setV({ ...v, remarks: e.target.value })} /></Field></div></Card>
      <div className="flex gap-2">
        <Button type="submit" loading={busy}><Save className="size-4" aria-hidden /> {t("Save supplier invoice")}</Button>
        <Button type="button" variant="ghost" onClick={() => router.back()}>{t("Cancel")}</Button>
      </div>
    </form>
  );
}

function NewInv() {
  const ids = useSearchParams().get("grnIds");
  const { data, error } = useSWR<SupplierInvoiceRequest>(ids ? `/supplier-invoices/from-goods-receipts?ids=${ids}` : null, fetcher);
  if (!ids) return <InvForm initial={{ supplierId: null, currency: "SAR", exchangeRate: 1, lines: [blank()] }} />;
  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!data) return <Spinner />;
  return <InvForm initial={data} />;
}

export default function NewSupplierInvoicePage() {
  const t = useT();
  return (<><PageHeader title={t("Supplier invoice")} subtitle={t("فاتورة مورد · records what we owe")} /><Suspense fallback={<Spinner />}><NewInv /></Suspense></>);
}
