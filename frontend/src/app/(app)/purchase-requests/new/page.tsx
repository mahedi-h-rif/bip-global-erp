"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import { Plus, Trash2 } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { Product, PurchaseRequest, Warehouse } from "@/lib/types";
import { unitCodes } from "@/lib/format";
import { Alert, Button, Card, Field, Input, PageHeader, Select, Textarea } from "@/components/ui";
import { useT } from "@/lib/i18n";

interface Line { productId: string; name: string; quantity: string; unitCode: string }
const blank = (): Line => ({ productId: "", name: "", quantity: "1", unitCode: "PCE" });

export default function NewPurchaseRequestPage() {
  const t = useT();
  const router = useRouter();
  const { data: products } = useSWR<Product[]>("/products", fetcher);
  const { data: warehouses } = useSWR<Warehouse[]>("/warehouses", fetcher);
  const [requester, setRequester] = useState("");
  const [department, setDepartment] = useState("");
  const [requiredDate, setRequiredDate] = useState("");
  const [warehouseId, setWarehouseId] = useState("");
  const [remarks, setRemarks] = useState("");
  const [lines, setLines] = useState<Line[]>([blank()]);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (i: number, patch: Partial<Line>) => setLines(lines.map((l, k) => (k === i ? { ...l, ...patch } : l)));

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true); setProblem(null);
    try {
      const r = await api<PurchaseRequest>("POST", "/purchase-requests", {
        requester, department: department || null, requiredDate: requiredDate || null, warehouseId: warehouseId ? Number(warehouseId) : null,
        remarks: remarks || null,
        lines: lines.filter((l) => l.name.trim()).map((l) => ({ productId: l.productId ? Number(l.productId) : null, name: l.name, quantity: Number(l.quantity), unitCode: l.unitCode })),
      });
      router.push(`/purchase-requests/${r.id}`);
    } catch (err) { setProblem(err instanceof ApiError ? err.message : t("Could not reach the server")); setBusy(false); }
  }

  return (
    <>
      <PageHeader title={t("New purchase request")} subtitle={t("طلب شراء · what your department needs; purchasing chooses the supplier")} />
      {problem && <div className="mb-4"><Alert tone="bad">{problem}</Alert></div>}
      <form onSubmit={submit}>
        <Card className="mb-4">
          <div className="grid gap-4 p-5 md:grid-cols-4">
            <Field label={t("Requested by")} required><Input value={requester} onChange={(e) => setRequester(e.target.value)} required /></Field>
            <Field label={t("Department")}><Input value={department} onChange={(e) => setDepartment(e.target.value)} placeholder={t("e.g. Warehouse, Sales")} /></Field>
            <Field label={t("Required by")}><Input type="date" value={requiredDate} onChange={(e) => setRequiredDate(e.target.value)} /></Field>
            <Field label={t("Deliver to")}><Select value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)}><option value="">{t("Default warehouse")}</option>{warehouses?.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}</Select></Field>
            <Field label={t("Remarks")} className="md:col-span-4"><Textarea rows={2} value={remarks} onChange={(e) => setRemarks(e.target.value)} /></Field>
          </div>
        </Card>
        <Card className="mb-4">
          <table className="w-full text-sm">
            <thead><tr className="border-b border-line text-xs text-muted"><th className="px-4 py-2 text-start font-medium">{t("Item")}</th><th className="px-4 py-2 text-start font-medium">{t("Description")}</th><th className="w-28 px-4 py-2 text-end font-medium">{t("Quantity")}</th><th className="w-28 px-4 py-2 text-start font-medium">{t("Unit")}</th><th className="w-10" /></tr></thead>
            <tbody className="divide-y divide-line">
              {lines.map((l, i) => (
                <tr key={i}>
                  <td className="px-4 py-2"><Select value={l.productId} aria-label={t("Item")} onChange={(e) => {
                    const p = products?.find((x) => String(x.id) === e.target.value);
                    set(i, { productId: e.target.value, name: p?.name ?? l.name, unitCode: p?.unitCode ?? l.unitCode });
                  }}><option value="">{t("Free text")}</option>{products?.filter((p) => p.active !== false).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</Select></td>
                  <td className="px-4 py-2"><Input value={l.name} aria-label={t("Description")} onChange={(e) => set(i, { name: e.target.value })} required dir="auto" /></td>
                  <td className="px-4 py-2"><Input type="number" min="0" step="any" className="text-end" aria-label={t("Quantity")} value={l.quantity} onChange={(e) => set(i, { quantity: e.target.value })} required /></td>
                  <td className="px-4 py-2"><Select value={l.unitCode} aria-label={t("Unit")} onChange={(e) => set(i, { unitCode: e.target.value })}>{unitCodes.map((u) => <option key={u.code} value={u.code}>{u.code}</option>)}</Select></td>
                  <td className="px-2 py-2">{lines.length > 1 && <Button type="button" variant="ghost" aria-label={t("Remove line")} onClick={() => setLines(lines.filter((_, k) => k !== i))}><Trash2 className="size-4" aria-hidden /></Button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="border-t border-line p-3"><Button type="button" variant="secondary" onClick={() => setLines([...lines, blank()])}><Plus className="size-4" aria-hidden /> {t("Add line")}</Button></div>
        </Card>
        <Button type="submit" loading={busy}>{t("Submit request")}</Button>
      </form>
    </>
  );
}
