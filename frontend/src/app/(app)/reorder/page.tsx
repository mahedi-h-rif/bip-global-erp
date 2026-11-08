"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import { ShoppingCart } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { PurchaseOrder, Supplier, Warehouse } from "@/lib/types";
import { money } from "@/lib/format";
import { Alert, Button, Card, Empty, Field, Input, PageHeader, Select, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

interface Suggestion { productId: number; number: string | null; name: string; unitCode: string; onHand: number; onOrder: number; threshold: number; suggested: number; lastCost: number }

export default function ReorderPage() {
  const t = useT();
  const router = useRouter();
  const { data, error } = useSWR<Suggestion[]>("/reorder", fetcher);
  const { data: suppliers } = useSWR<Supplier[]>("/suppliers", fetcher);
  const { data: warehouses } = useSWR<Warehouse[]>("/warehouses", fetcher);
  const [qty, setQty] = useState<Record<number, string>>({});
  const [supplierId, setSupplierId] = useState("");
  const [warehouseId, setWarehouseId] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const q = (s: Suggestion) => qty[s.productId] ?? String(Number(s.suggested));
  return (
    <>
      <PageHeader title={t("Reorder suggestions")} subtitle={t("Items at or below their low-stock alert, net of what is already on order. Suggested quantity brings stock back to twice the alert level.")} />
      {problem && <div className="mb-4"><Alert tone="bad">{problem}</Alert></div>}
      <Card>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("Nothing to reorder")}>{t("Set a low-stock alert on products to get suggestions.")}</Empty>}
        {data && data.length > 0 && (
          <>
            <table className="w-full text-sm">
              <thead><tr className="border-b border-line text-xs text-muted"><th className="px-4 py-2 text-start font-medium">{t("Item")}</th><th className="px-4 py-2 text-end font-medium">{t("On hand")}</th><th className="px-4 py-2 text-end font-medium">{t("On order")}</th><th className="px-4 py-2 text-end font-medium">{t("Alert at")}</th><th className="px-4 py-2 text-end font-medium">{t("Avg cost")}</th><th className="w-32 px-4 py-2 text-end font-medium">{t("Order qty")}</th></tr></thead>
              <tbody className="divide-y divide-line">{data.map((s) => (
                <tr key={s.productId}><td className="px-4 py-2" dir="auto">{s.name} <span className="text-xs text-muted">{s.number}</span></td>
                  <td className="tabular px-4 py-2 text-end font-semibold text-bad">{Number(s.onHand)}</td><td className="tabular px-4 py-2 text-end">{Number(s.onOrder)}</td>
                  <td className="tabular px-4 py-2 text-end">{Number(s.threshold)}</td><td className="tabular px-4 py-2 text-end">{money(s.lastCost)}</td>
                  <td className="px-4 py-2"><Input type="number" min="0" step="any" className="text-end" value={q(s)} onChange={(e) => setQty({ ...qty, [s.productId]: e.target.value })} /></td></tr>
              ))}</tbody>
            </table>
            <div className="flex flex-wrap items-end gap-3 border-t border-line p-4">
              <Field label={t("Supplier")}><Select value={supplierId} onChange={(e) => setSupplierId(e.target.value)}><option value="">{t("Select…")}</option>{suppliers?.map((s) => <option key={s.id} value={s.id}>{s.code} · {s.name}</option>)}</Select></Field>
              <Field label={t("Deliver to")}><Select value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)}><option value="">{t("Default warehouse")}</option>{warehouses?.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}</Select></Field>
              <Button disabled={!supplierId} loading={busy} onClick={async () => {
                setBusy(true); setProblem(null);
                try {
                  const po = await api<PurchaseOrder>("POST", "/reorder/purchase-order", { supplierId: Number(supplierId), warehouseId: warehouseId ? Number(warehouseId) : null,
                    lines: data.filter((s) => Number(q(s)) > 0).map((s) => ({ productId: s.productId, quantity: Number(q(s)) })) });
                  router.push(`/purchase-orders/${po.id}`);
                } catch (e) { setProblem(e instanceof ApiError ? e.message : t("Could not reach the server")); setBusy(false); }
              }}><ShoppingCart className="size-4" aria-hidden /> {t("Create requisition")}</Button>
            </div>
          </>
        )}
      </Card>
    </>
  );
}
