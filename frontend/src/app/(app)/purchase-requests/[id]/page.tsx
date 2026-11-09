"use client";

import Link from "next/link";
import { use, useState } from "react";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import { ShoppingCart, XCircle } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { PurchaseRequest, Supplier } from "@/lib/types";
import { date } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Field, PageHeader, Select, Spinner } from "@/components/ui";
import { StatusPill } from "@/components/purchase";
import { RelationshipMapButton } from "@/components/relationship-map";
import { useT } from "@/lib/i18n";

export default function PurchaseRequestPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const t = useT();
  const router = useRouter();
  const { data: r, error, mutate } = useSWR<PurchaseRequest>(`/purchase-requests/${id}`, fetcher);
  const { data: suppliers } = useSWR<Supplier[]>("/suppliers", fetcher);
  const [supplierId, setSupplierId] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!r) return <Spinner />;
  return (
    <>
      <PageHeader title={t("Purchase request {number}", { number: r.number })}
        subtitle={<span className="flex items-center gap-2"><StatusPill status={r.status} /><span dir="auto">{r.requester}</span></span>}
        actions={<RelationshipMapButton type="PURCHASE_REQUEST" id={r.id} />} />
      {problem && <div className="mb-4"><Alert tone="bad">{problem}</Alert></div>}
      <Card className="mb-6">
        <dl className="grid gap-x-6 gap-y-3 p-5 text-sm md:grid-cols-4">
          <div><dt className="text-xs text-muted">{t("Date")}</dt><dd>{date(r.requestDate)}</dd></div>
          <div><dt className="text-xs text-muted">{t("Department")}</dt><dd>{r.department ?? "—"}</dd></div>
          <div><dt className="text-xs text-muted">{t("Required by")}</dt><dd>{date(r.requiredDate)}</dd></div>
          <div><dt className="text-xs text-muted">{t("Deliver to")}</dt><dd>{r.warehouseName ?? t("Default warehouse")}</dd></div>
          {r.purchaseOrders.length > 0 && <div><dt className="text-xs text-muted">{t("Purchase order")}</dt><dd>{r.purchaseOrders.map((p) => <Link key={p.id} className="text-brand hover:underline" href={`/purchase-orders/${p.id}`}>{p.number}</Link>)}</dd></div>}
          {r.remarks && <div className="md:col-span-3"><dt className="text-xs text-muted">{t("Remarks")}</dt><dd dir="auto">{r.remarks}</dd></div>}
        </dl>
        <table className="w-full border-t border-line text-sm">
          <thead><tr className="border-b border-line text-xs text-muted"><th className="px-4 py-2.5 text-start font-medium">{t("Item")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Quantity")}</th></tr></thead>
          <tbody className="divide-y divide-line">
            {r.lines.map((l, i) => <tr key={i}><td className="px-4 py-3" dir="auto">{l.name}</td><td className="tabular px-4 py-3 text-end">{Number(l.quantity)} {l.unitCode}</td></tr>)}
          </tbody>
        </table>
      </Card>
      {r.status === "OPEN" && (
        <Card className="mb-6">
          <CardHeader title={t("Copy to purchase order")} subtitle={t("Creates a purchase order awaiting approval, priced at each item's current cost. Adjust prices on the order before approving.")} />
          <div className="flex flex-wrap items-end gap-3 p-5">
            <Field label={t("Supplier")}><Select value={supplierId} onChange={(e) => setSupplierId(e.target.value)}><option value="">{t("Select…")}</option>{suppliers?.filter((s) => s.active !== false).map((s) => <option key={s.id} value={s.id}>{s.code} · {s.name}</option>)}</Select></Field>
            <Button disabled={!supplierId} loading={busy} onClick={async () => {
              setBusy(true); setProblem(null);
              try {
                const po = await api<{ id: number }>("POST", `/purchase-requests/${r.id}/purchase-order`, { supplierId: Number(supplierId) });
                router.push(`/purchase-orders/${po.id}`);
              } catch (e) { setProblem(e instanceof ApiError ? e.message : t("Could not reach the server")); setBusy(false); }
            }}><ShoppingCart className="size-4" aria-hidden /> {t("Create purchase order")}</Button>
          </div>
        </Card>
      )}
      {r.status === "OPEN" && (
        <Button variant="ghost" onClick={async () => {
          if (!window.confirm(t("Cancel {number}?", { number: r.number }))) return;
          try { await mutate(await api<PurchaseRequest>("POST", `/purchase-requests/${r.id}/cancel`), { revalidate: false }); }
          catch (e) { setProblem(e instanceof ApiError ? e.message : t("Could not reach the server")); }
        }}><XCircle className="size-4" aria-hidden /> {t("Cancel request")}</Button>
      )}
    </>
  );
}
