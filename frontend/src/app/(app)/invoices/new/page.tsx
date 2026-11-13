import type { Metadata } from "next";
import { Suspense } from "react";
import { PageHeader } from "@/components/ui";
import { NewInvoice } from "@/components/new-invoice";
import { Trans } from "@/lib/i18n";

export const metadata: Metadata = { title: "New invoice" };

export default function NewInvoicePage() {
  return (
    <>
      <PageHeader title={<Trans k="New invoice" />} subtitle={<Trans k="Saudi tax invoice (فاتورة) · e-invoicing Phase 2" />} />
      <Suspense>
        <NewInvoice />
      </Suspense>
    </>
  );
}
