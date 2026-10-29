import type { Metadata } from "next";
import { PageHeader } from "@/components/ui";
import { AgreementEditor } from "@/components/agreement-editor";
import { Trans } from "@/lib/i18n";

export const metadata: Metadata = { title: "New agreement" };

export default function NewAgreementPage() {
  return (
    <>
      <PageHeader title={<Trans k="New agreement" />} subtitle={<Trans k="اتفاقية · printed on the company letterhead" />} />
      <AgreementEditor />
    </>
  );
}
