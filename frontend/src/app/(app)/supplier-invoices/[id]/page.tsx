"use client";

import { use, useState } from "react";
import useSWR from "swr";
import { FileMinus, XCircle } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { SupplierInvoice } from "@/lib/types";
import { date, money } from "@/lib/format";
import { Alert, Button, ButtonLink, Card, PageHeader, Spinner } from "@/components/ui";
import { fmtCur, StatusPill } from "@/components/purchase";
import { RelationshipMapButton } from "@/components/relationship-map";
import { useT } from "@/lib/i18n";

export default function SupplierInvoicePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const t = useT();
  const { data: v, error, mutate } = useSWR<SupplierInvoice>(`/supplier-invoices/${id}`, fetcher);
  const [problem, setProblem] = useState<string | null>(null);
  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!v) return <Spinner />;
  return (
    <>
      <PageHeader title={t("Supplier invoice {number}", { number: v.number })}
        subtitle={<span className="flex items-center gap-2"><StatusPill status={v.status} /><span dir="auto">{v.supplierName}</span></span>}
        actions={<span className="flex gap-2">
          {v.status === "OPEN" && Number(v.due) > 0 && <ButtonLink href={`/supplier-payments/new?supplierId=${v.supplierId}`}>{t("Pay supplier")}</ButtonLink>}
          {v.status === "OPEN" && Number(v.due) > 0 && <ButtonLink variant="secondary" href={`/supplier-credit-notes/new?invoiceId=${v.id}`}><FileMinus className="size-4" aria-hidden /> {t("Credit memo")}</ButtonLink>}
          <RelationshipMapButton type="SUPPLIER_INVOICE" id={v.id} />
        </span>} />
      {problem && <div className="mb-4"><Alert tone="bad">{problem}</Alert></div>}
      <Card className="mb-6">
        <dl className="grid gap-x-6 gap-y-3 p-5 text-sm md:grid-cols-4">
          <div><dt className="text-xs text-muted">{t("Supplier's invoice no.")}</dt><dd>{v.supplierInvoiceNo ?? "—"}</dd></div>
          <div><dt className="text-xs text-muted">{t("Invoice date")}</dt><dd>{date(v.invoiceDate)}</dd></div>
          <div><dt className="text-xs text-muted">{t("Due date")}</dt><dd>{date(v.dueDate)}{v.overdueDays > 0 && <span className="ms-1 text-bad">({t("{n} days overdue", { n: String(v.overdueDays) })})</span>}</dd></div>
          <div><dt className="text-xs text-muted">{t("Goods receipts")}</dt><dd>{v.goodsReceipts.join(", ") || "—"}</dd></div>
          <div><dt className="text-xs text-muted">{t("Total")}</dt><dd>{fmtCur(v.totalInclVat, v.currency ?? "SAR")}</dd></div>
          <div><dt className="text-xs text-muted">{t("Total (SAR)")}</dt><dd>{money(v.totalSar)} · {t("input VAT")} {money(v.vatSar)}</dd></div>
          <div><dt className="text-xs text-muted">{t("Paid")}</dt><dd>{money(v.paid)}</dd></div>
          <div><dt className="text-xs text-muted">{t("Due")}</dt><dd className="font-semibold">{money(v.due)}</dd></div>
        </dl>
        <table className="w-full border-t border-line text-sm">
          <thead><tr className="border-b border-line text-xs text-muted"><th className="px-4 py-2.5 text-start font-medium">{t("Item")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Quantity")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Unit cost")}</th><th className="px-4 py-2.5 text-end font-medium">{t("VAT %")}</th></tr></thead>
          <tbody className="divide-y divide-line">
            {v.lines.map((l, i) => (
              <tr key={i}><td className="px-4 py-3" dir="auto">{l.name}</td><td className="tabular px-4 py-3 text-end">{Number(l.quantity)}</td><td className="tabular px-4 py-3 text-end">{fmtCur(l.unitCost, v.currency ?? "SAR")}</td><td className="tabular px-4 py-3 text-end">{Number(l.vatRate)}</td></tr>
            ))}
          </tbody>
        </table>
      </Card>
      {v.status === "OPEN" && Number(v.paid) === 0 && (
        <Button variant="ghost" onClick={async () => {
          if (!window.confirm(t("Cancel {number}?", { number: v.number }))) return;
          try { await mutate(await api<SupplierInvoice>("POST", `/supplier-invoices/${v.id}/cancel`), { revalidate: false }); }
          catch (e) { setProblem(e instanceof ApiError ? e.message : t("Could not reach the server")); }
        }}><XCircle className="size-4" aria-hidden /> {t("Cancel supplier invoice")}</Button>
      )}
    </>
  );
}
