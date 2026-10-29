"use client";

import { use } from "react";
import useSWR from "swr";
import { fetcher } from "@/lib/api";
import type { Quotation } from "@/lib/types";
import { Alert, PageHeader, Spinner } from "@/components/ui";
import { QuotationEditor, quotationStatusLabel } from "@/components/quotation-editor";
import { useT } from "@/lib/i18n";

export default function QuotationPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const t = useT();
  const { data, error } = useSWR<Quotation>(`/quotations/${id}`, fetcher);
  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!data) return <Spinner />;
  return (
    <>
      <PageHeader title={t("Quotation {number}", { number: data.number })} subtitle={`${t(quotationStatusLabel[data.status])} · ${data.customerName ?? data.clientName ?? ""}`} />
      {/* Re-mounts with fresh values after each save. */}
      <QuotationEditor key={`${data.id}-${data.totalInclVat}-${data.status}-${data.invoiceId}`} initial={data} />
    </>
  );
}
