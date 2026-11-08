"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import useSWR from "swr";
import { fetcher } from "@/lib/api";
import type { DeliveryNoteRequest, InvoiceDetail } from "@/lib/types";
import { Alert, PageHeader, Spinner } from "@/components/ui";
import { DeliveryNoteEditor } from "@/components/delivery-note-editor";
import { useT } from "@/lib/i18n";

function NewDeliveryNote() {
  const t = useT();
  const invoiceId = useSearchParams().get("invoiceId");
  const { data: prefill, error } = useSWR<DeliveryNoteRequest>(invoiceId ? `/delivery-notes/from-invoice/${invoiceId}` : null, fetcher);
  const { data: invoice } = useSWR<InvoiceDetail>(invoiceId ? `/invoices/${invoiceId}` : null, fetcher);

  if (!invoiceId)
    return <DeliveryNoteEditor initial={{ lines: [{ name: "", quantity: "", unitCode: "PCE" }] }} />;
  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!prefill) return <Spinner />;
  if (prefill.lines.length === 0) return <Alert tone="ok">{t("Everything on this invoice has already been put on delivery notes.")}</Alert>;
  return <DeliveryNoteEditor initial={prefill} invoiceNumber={invoice?.number} />;
}

export default function NewDeliveryNotePage() {
  const t = useT();
  return (
    <>
      <PageHeader title={t("New delivery note")} subtitle={t("سند تسليم · bilingual A4 with driver, vehicle and receiver's signature")} />
      <Suspense fallback={<Spinner />}>
        <NewDeliveryNote />
      </Suspense>
    </>
  );
}
