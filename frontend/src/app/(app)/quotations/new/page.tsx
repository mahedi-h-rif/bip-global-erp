import type { Metadata } from "next";
import { PageHeader } from "@/components/ui";
import { QuotationEditor } from "@/components/quotation-editor";
import { Trans } from "@/lib/i18n";

export const metadata: Metadata = { title: "New quotation" };

export default function NewQuotationPage() {
  return (
    <>
      <PageHeader title={<Trans k="New quotation" />} subtitle={<Trans k="عرض سعر · a price offer, not sent to ZATCA" />} />
      <QuotationEditor />
    </>
  );
}
