"use client";

import Link from "next/link";
import { use, useState } from "react";
import useSWR from "swr";
import { XCircle } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { SupplierCreditNote } from "@/lib/types";
import { date, money } from "@/lib/format";
import { Alert, Button, Card, PageHeader, Spinner } from "@/components/ui";
import { fmtCur, StatusPill } from "@/components/purchase";
import { RelationshipMapButton } from "@/components/relationship-map";
import { useT } from "@/lib/i18n";

export default function SupplierCreditNotePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const t = useT();
  const { data: c, error, mutate } = useSWR<SupplierCreditNote>(`/supplier-credit-notes/${id}`, fetcher);
  const [problem, setProblem] = useState<string | null>(null);
  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!c) return <Spinner />;
  return (
    <>
      <PageHeader title={t("A/P credit memo {number}", { number: c.number })}
        subtitle={<span className="flex items-center gap-2"><StatusPill status={c.status} /><span dir="auto">{c.supplierName}</span></span>}
        actions={<RelationshipMapButton type="SUPPLIER_CREDIT" id={c.id} />} />
      {problem && <div className="mb-4"><Alert tone="bad">{problem}</Alert></div>}
      <Card className="mb-6">
        <dl className="grid gap-x-6 gap-y-3 p-5 text-sm md:grid-cols-4">
          <div><dt className="text-xs text-muted">{t("Date")}</dt><dd>{date(c.creditDate)}</dd></div>
          <div><dt className="text-xs text-muted">{t("Supplier invoice")}</dt><dd><Link className="text-brand hover:underline" href={`/supplier-invoices/${c.supplierInvoiceId}`}>{c.supplierInvoiceNumber}</Link></dd></div>
          <div><dt className="text-xs text-muted">{t("Goods return")}</dt><dd>{c.goodsReturnId ? <Link className="text-brand hover:underline" href={`/goods-returns/${c.goodsReturnId}`}>{c.goodsReturnNumber}</Link> : "—"}</dd></div>
          <div><dt className="text-xs text-muted">{t("Supplier's credit note no.")}</dt><dd>{c.supplierRef ?? "—"}</dd></div>
          <div><dt className="text-xs text-muted">{t("Total")}</dt><dd>{fmtCur(c.totalInclVat, c.currency)}</dd></div>
          <div><dt className="text-xs text-muted">{t("Total (SAR)")}</dt><dd className="font-semibold">{money(c.totalSar)} · {t("input VAT")} {money(c.vatSar)}</dd></div>
          {c.remarks && <div className="md:col-span-2"><dt className="text-xs text-muted">{t("Remarks")}</dt><dd dir="auto">{c.remarks}</dd></div>}
        </dl>
        <table className="w-full border-t border-line text-sm">
          <thead><tr className="border-b border-line text-xs text-muted"><th className="px-4 py-2.5 text-start font-medium">{t("Item")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Quantity")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Unit cost")}</th><th className="px-4 py-2.5 text-end font-medium">{t("VAT %")}</th></tr></thead>
          <tbody className="divide-y divide-line">
            {c.lines.map((l, i) => <tr key={i}><td className="px-4 py-3" dir="auto">{l.name}</td><td className="tabular px-4 py-3 text-end">{Number(l.quantity)}</td><td className="tabular px-4 py-3 text-end">{fmtCur(l.unitCost, c.currency)}</td><td className="tabular px-4 py-3 text-end">{Number(l.vatRate)}</td></tr>)}
          </tbody>
        </table>
      </Card>
      {c.status === "POSTED" && (
        <Button variant="ghost" onClick={async () => {
          const reason = window.prompt(t("Why cancel {number}?", { number: c.number }));
          if (reason === null) return;
          try { await mutate(await api<SupplierCreditNote>("POST", `/supplier-credit-notes/${c.id}/cancel`, { reason }), { revalidate: false }); }
          catch (e) { setProblem(e instanceof ApiError ? e.message : t("Could not reach the server")); }
        }}><XCircle className="size-4" aria-hidden /> {t("Cancel credit memo")}</Button>
      )}
    </>
  );
}
