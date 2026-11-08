"use client";

import { use } from "react";
import useSWR from "swr";
import { fetcher } from "@/lib/api";
import type { Quotation } from "@/lib/types";
import { Alert, ButtonLink, PageHeader, Spinner } from "@/components/ui";
import { RelationshipMapButton } from "@/components/relationship-map";
import { ClipboardCheck } from "lucide-react";
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
      <PageHeader
        title={t("Quotation {number}", { number: data.number })}
        subtitle={`${t(quotationStatusLabel[data.status])} · ${data.customerName ?? data.clientName ?? ""}`}
        actions={
          <span className="flex flex-wrap gap-2">
            {data.status !== "DECLINED" && data.status !== "INVOICED" && (
              <ButtonLink href={`/sales-orders/new?quotationId=${data.id}`}><ClipboardCheck className="size-4" aria-hidden /> {t("Copy to sales order")}</ButtonLink>
            )}
            <RelationshipMapButton type="QUOTATION" id={data.id} />
          </span>
        }
      />
      {/* Re-mounts with fresh values after each save. */}
      <QuotationEditor key={`${data.id}-${data.totalInclVat}-${data.status}-${data.invoiceId}`} initial={data} />
    </>
  );
}
