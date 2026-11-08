"use client";

import Link from "next/link";
import useSWR from "swr";
import { Plus } from "lucide-react";
import { fetcher } from "@/lib/api";
import type { SupplierPayment } from "@/lib/types";
import { date, money, paymentMethodLabel } from "@/lib/format";
import { Alert, ButtonLink, Card, Empty, PageHeader, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

export default function SupplierPaymentsPage() {
  const t = useT();
  const { data, error } = useSWR<SupplierPayment[]>("/supplier-payments", fetcher);
  return (
    <>
      <PageHeader title={t("Supplier payments")} subtitle={t("Outgoing payments, each settling one or more supplier invoices.")}
                  actions={<ButtonLink href="/supplier-payments/new"><Plus className="size-4" aria-hidden /> {t("Pay supplier")}</ButtonLink>} />
      <Card>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("No supplier payments yet")} />}
        {data && data.length > 0 && (
          <table className="w-full text-sm">
            <thead><tr className="border-b border-line text-xs text-muted">
              <th className="px-4 py-2.5 text-start font-medium">{t("Number")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
              <th className="px-4 py-2.5 text-start font-medium">{t("Supplier")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Method")}</th>
              <th className="px-4 py-2.5 text-start font-medium">{t("Invoices")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Amount")}</th>
            </tr></thead>
            <tbody className="divide-y divide-line">
              {data.map((p) => (
                <tr key={p.id} className={p.status === "CANCELLED" ? "text-muted line-through" : "hover:bg-slate-50"}>
                  <td className="tabular px-4 py-3 font-medium"><Link className="text-brand hover:underline" href={`/supplier-payments/${p.id}`}>{p.number}</Link></td>
                  <td className="tabular px-4 py-3">{date(p.paymentDate)}</td>
                  <td className="px-4 py-3" dir="auto">{p.supplierName}</td>
                  <td className="px-4 py-3">{t(paymentMethodLabel[p.method as keyof typeof paymentMethodLabel] ?? p.method)}</td>
                  <td className="tabular px-4 py-3 text-muted">{p.allocations.map((a) => a.invoiceNumber).join(", ")}</td>
                  <td className="tabular px-4 py-3 text-end">{money(p.amount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </>
  );
}
