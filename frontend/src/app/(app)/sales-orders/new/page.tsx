"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import useSWR from "swr";
import { fetcher } from "@/lib/api";
import type { Quotation, SalesOrderRequest } from "@/lib/types";
import { Alert, PageHeader, Spinner } from "@/components/ui";
import { SalesOrderEditor } from "@/components/sales-order-editor";
import { useT } from "@/lib/i18n";

function NewOrder() {
  const t = useT();
  const quotationId = useSearchParams().get("quotationId");
  const { data, error } = useSWR<SalesOrderRequest>(quotationId ? `/sales-orders/from-quotation/${quotationId}` : null, fetcher);
  const { data: q } = useSWR<Quotation>(quotationId ? `/quotations/${quotationId}` : null, fetcher);
  if (!quotationId) return <SalesOrderEditor initial={{ lines: [] }} />;
  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!data) return <Spinner />;
  if (data.lines.length === 0) return <Alert tone="ok">{t("Everything on this quotation is already on sales orders.")}</Alert>;
  return <SalesOrderEditor initial={data} quotationNumber={q?.number} />;
}

export default function NewSalesOrderPage() {
  const t = useT();
  return (
    <>
      <PageHeader title={t("New sales order")} subtitle={t("أمر بيع · deliver it in one or more deliveries")} />
      <Suspense fallback={<Spinner />}><NewOrder /></Suspense>
    </>
  );
}
