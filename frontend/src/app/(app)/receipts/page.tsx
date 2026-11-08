"use client";

import Link from "next/link";
import useSWR from "swr";
import { Plus } from "lucide-react";
import { fetcher } from "@/lib/api";
import type { Receipt } from "@/lib/types";
import { date, money, paymentMethodLabel } from "@/lib/format";
import { Alert, ButtonLink, Card, Empty, PageHeader, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

export default function ReceiptsPage() {
  const t = useT();
  const { data, error } = useSWR<Receipt[]>("/receipts", fetcher);
  return (
    <>
      <PageHeader
        title={t("Incoming payments")}
        subtitle={t("One payment from a customer, settled against one or more of their invoices.")}
        actions={<ButtonLink href="/receipts/new"><Plus className="size-4" aria-hidden /> {t("Receive payment")}</ButtonLink>}
      />
      <Card>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("No incoming payments yet")} />}
        {data && data.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-xs text-muted">
                  <th className="px-4 py-2.5 text-start font-medium">{t("Number")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Customer")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Method")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Invoices")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Amount")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {data.map((r) => (
                  <tr key={r.id} className="hover:bg-slate-50">
                    <td className="tabular px-4 py-3 font-medium"><Link className="text-brand hover:underline" href={`/receipts/${r.id}`}>{r.number}</Link></td>
                    <td className="tabular px-4 py-3">{date(r.receiptDate)}</td>
                    <td className="px-4 py-3" dir="auto">{r.customerName}</td>
                    <td className="px-4 py-3">{t(paymentMethodLabel[r.method as keyof typeof paymentMethodLabel] ?? r.method)}</td>
                    <td className="tabular px-4 py-3 text-muted">{r.allocations.map((a) => a.invoiceNumber).join(", ")}</td>
                    <td className="tabular px-4 py-3 text-end font-medium">{money(r.amount)}</td>
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
