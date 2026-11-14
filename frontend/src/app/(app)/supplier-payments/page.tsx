"use client";

import Link from "next/link";
import useSWR from "swr";
import { Plus } from "lucide-react";
import { fetcher } from "@/lib/api";
import type { SupplierPayment } from "@/lib/types";
import { date, money, paymentMethodLabel } from "@/lib/format";
import { Alert, ButtonLink, Card, Empty, PageHeader, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";
import { statusText, useListTools } from "@/components/list-tools";
import { PendingSupplierPayments } from "@/components/pending-work";
import { PagePreview, PreviewDrawer, usePreview, ViewButton } from "@/components/preview";
import SupplierPaymentPage from "./[id]/page";

export default function SupplierPaymentsPage() {
  const t = useT();
  const { data, error } = useSWR<SupplierPayment[]>("/supplier-payments", fetcher);
  const { toolbar, shown } = useListTools<SupplierPayment>(data, { filters: [{ label: "Supplier", get: (r) => r.supplierName }, { label: "Method", get: (r) => r.method, format: statusText }, { label: "Status", get: (r) => r.status, format: statusText }], date: (r) => r.paymentDate });
  const preview = usePreview<SupplierPayment>();
  const pv = preview.current;
  return (
    <>
      <PageHeader title={t("Supplier payments")} subtitle={t("Outgoing payments, each settling one or more supplier invoices.")}
                  actions={<ButtonLink href="/supplier-payments/new"><Plus className="size-4" aria-hidden /> {t("Pay supplier")}</ButtonLink>} />
      <PendingSupplierPayments />
      <Card>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("No supplier payments yet")} />}
        {data && data.length > 0 && toolbar}
        {data && data.length > 0 && shown?.length === 0 && <Empty title={t("Nothing matches")} />}
        {shown && shown.length > 0 && (
          <table className="w-full text-sm">
            <thead><tr className="border-b border-line text-xs text-muted">
              <th className="px-4 py-2.5 text-start font-medium">{t("Number")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
              <th className="px-4 py-2.5 text-start font-medium">{t("Supplier")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Method")}</th>
              <th className="px-4 py-2.5 text-start font-medium">{t("Invoices")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Amount")}</th>
            <th className="w-16" /></tr></thead>
            <tbody className="divide-y divide-line">
              {shown.map((p) => (
                <tr key={p.id} {...preview.row(p, p.status === "CANCELLED" ? "text-muted line-through" : "hover:bg-slate-50")}>
                  <td className="tabular px-4 py-3 font-medium"><Link className="text-brand hover:underline" href={`/supplier-payments/${p.id}`}>{p.number}</Link></td>
                  <td className="tabular px-4 py-3">{date(p.paymentDate)}</td>
                  <td className="px-4 py-3" dir="auto">{p.supplierName}</td>
                  <td className="px-4 py-3">{t(paymentMethodLabel[p.method as keyof typeof paymentMethodLabel] ?? p.method)}</td>
                  <td className="tabular px-4 py-3 text-muted">{p.allocations.map((a) => a.invoiceNumber).join(", ")}</td>
                  <td className="tabular px-4 py-3 text-end">{money(p.amount)}</td>
  <td className="px-2 py-3 text-end"><ViewButton onClick={() => preview.open(p)} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
      {pv && (
        <PreviewDrawer wide title={pv.number} subtitle={pv.supplierName} href={`/supplier-payments/${pv.id}`} onClose={preview.close}>
          <PagePreview page={SupplierPaymentPage} id={pv.id} />
        </PreviewDrawer>
      )}
    </>
  );
}
