"use client";

import Link from "next/link";
import useSWR from "swr";
import { fetcher } from "@/lib/api";
import type { SupplierCreditNote } from "@/lib/types";
import { date, money } from "@/lib/format";
import { Alert, Card, Empty, PageHeader, Spinner } from "@/components/ui";
import { StatusPill } from "@/components/purchase";
import { useT } from "@/lib/i18n";
import { statusText, useListTools } from "@/components/list-tools";

/** A/P credit memos (SAP B1): a supplier's credit against one of its invoices. */
export default function SupplierCreditNotesPage() {
  const t = useT();
  const { data, error } = useSWR<SupplierCreditNote[]>("/supplier-credit-notes", fetcher);
  const { toolbar, shown } = useListTools<SupplierCreditNote>(data, { filters: [{ label: "Supplier", get: (r) => r.supplierName }, { label: "Status", get: (r) => r.status, format: statusText }], date: (r) => r.creditDate });
  return (
    <>
      <PageHeader title={t("A/P credit memos")} subtitle={t("Supplier credits for returns, price differences or discounts. They lower what you owe and your input VAT.")} />
      <Card>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("No credit memos yet")}>{t("Open a supplier invoice or a goods return to make one.")}</Empty>}
        {data && data.length > 0 && toolbar}
        {data && data.length > 0 && shown?.length === 0 && <Empty title={t("Nothing matches")} />}
        {shown && shown.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead><tr className="border-b border-line text-xs text-muted">
                <th className="px-4 py-2.5 text-start font-medium">{t("Number")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Supplier")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Supplier invoice")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Supplier's ref.")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Total (SAR)")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Status")}</th>
              </tr></thead>
              <tbody className="divide-y divide-line">
                {shown.map((c) => (
                  <tr key={c.id} className="hover:bg-slate-50">
                    <td className="tabular px-4 py-3 font-medium"><Link className="text-brand hover:underline" href={`/supplier-credit-notes/${c.id}`}>{c.number}</Link></td>
                    <td className="tabular px-4 py-3">{date(c.creditDate)}</td>
                    <td className="px-4 py-3" dir="auto">{c.supplierName}</td>
                    <td className="tabular px-4 py-3"><Link className="text-brand hover:underline" href={`/supplier-invoices/${c.supplierInvoiceId}`}>{c.supplierInvoiceNumber}</Link></td>
                    <td className="px-4 py-3 text-muted">{c.supplierRef ?? "—"}</td>
                    <td className="tabular px-4 py-3 text-end">{money(c.totalSar)}</td>
                    <td className="px-4 py-3"><StatusPill status={c.status} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
