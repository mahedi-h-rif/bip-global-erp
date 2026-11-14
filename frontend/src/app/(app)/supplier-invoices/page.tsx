"use client";

import Link from "next/link";
import { useState } from "react";
import useSWR from "swr";
import { Plus } from "lucide-react";
import { fetcher } from "@/lib/api";
import type { SupplierInvoice } from "@/lib/types";
import { date, money } from "@/lib/format";
import { Alert, ButtonLink, Card, Empty, PageHeader, Spinner } from "@/components/ui";
import { fmtCur, StatusPill } from "@/components/purchase";
import { useT } from "@/lib/i18n";
import { statusText, useListTools } from "@/components/list-tools";
import { PendingBilling } from "@/components/pending-work";
import { PagePreview, PreviewDrawer, usePreview, ViewButton } from "@/components/preview";
import SupplierInvoicePage from "./[id]/page";

export default function SupplierInvoicesPage() {
  const t = useT();
  const [openOnly, setOpenOnly] = useState(false);
  const { data, error } = useSWR<SupplierInvoice[]>(`/supplier-invoices?openOnly=${openOnly}`, fetcher, { keepPreviousData: true });
  const { toolbar, shown } = useListTools<SupplierInvoice>(data, { filters: [{ label: "Supplier", get: (r) => r.supplierName }, { label: "Status", get: (r) => r.status, format: statusText }], date: (r) => r.invoiceDate });
  const preview = usePreview<SupplierInvoice>();
  const pv = preview.current;
  const due = (data ?? []).reduce((s, v) => s + Number(v.due), 0);
  return (
    <>
      <PageHeader title={t("Supplier invoices")} subtitle={t("What we owe our suppliers. Make them from goods receipts.")}
                  actions={<span className="flex gap-2"><ButtonLink variant="secondary" href="/supplier-payments/new">{t("Pay supplier")}</ButtonLink><ButtonLink href="/supplier-invoices/new"><Plus className="size-4" aria-hidden /> {t("Direct invoice")}</ButtonLink></span>} />
      <PendingBilling />
      <Card>
        <div className="flex items-center justify-between border-b border-line p-4 text-sm">
          <label className="inline-flex items-center gap-2 text-muted"><input type="checkbox" className="size-4 accent-[var(--color-brand)]" checked={openOnly} onChange={(e) => setOpenOnly(e.target.checked)} /> {t("Unpaid only")}</label>
          <span>{t("Total payable")}: <b className="tabular">{money(due)}</b></span>
        </div>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("No supplier invoices")} />}
        {data && data.length > 0 && toolbar}
        {data && data.length > 0 && shown?.length === 0 && <Empty title={t("Nothing matches")} />}
        {shown && shown.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead><tr className="border-b border-line text-xs text-muted">
                <th className="px-4 py-2.5 text-start font-medium">{t("Number")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Supplier invoice no.")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Supplier")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Total")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Due (SAR)")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Overdue days")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Status")}</th>
              <th className="w-16" /></tr></thead>
              <tbody className="divide-y divide-line">
                {shown.map((v) => (
                  <tr key={v.id} {...preview.row(v, "hover:bg-slate-50")}>
                    <td className="tabular px-4 py-3 font-medium"><Link className="text-brand hover:underline" href={`/supplier-invoices/${v.id}`}>{v.number}</Link></td>
                    <td className="tabular px-4 py-3 text-muted">{v.supplierInvoiceNo ?? "—"}</td>
                    <td className="tabular px-4 py-3">{date(v.invoiceDate)}</td>
                    <td className="px-4 py-3" dir="auto">{v.supplierName}</td>
                    <td className="tabular px-4 py-3 text-end">{fmtCur(v.totalInclVat, v.currency ?? "SAR")}</td>
                    <td className={`tabular px-4 py-3 text-end ${Number(v.due) > 0 ? "font-semibold" : "text-muted"}`}>{money(v.due)}</td>
                    <td className={`tabular px-4 py-3 text-end ${v.overdueDays > 0 ? "text-bad" : "text-muted"}`}>{v.overdueDays}</td>
                    <td className="px-4 py-3"><StatusPill status={v.status === "OPEN" && Number(v.due) === 0 ? "PAID" : v.status} /></td>
  <td className="px-2 py-3 text-end"><ViewButton onClick={() => preview.open(v)} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      {pv && (
        <PreviewDrawer wide title={pv.number} subtitle={pv.supplierName} href={`/supplier-invoices/${pv.id}`} onClose={preview.close}>
          <PagePreview page={SupplierInvoicePage} id={pv.id} />
        </PreviewDrawer>
      )}
    </>
  );
}
