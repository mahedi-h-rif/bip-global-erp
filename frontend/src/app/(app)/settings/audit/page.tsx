"use client";

import { PageHeader } from "@/components/ui";
import { AuditTable } from "@/components/audit-table";
import { useT } from "@/lib/i18n";

export default function AuditPage() {
  const t = useT();
  return (
    <>
      <PageHeader title={t("Audit log")} subtitle={t("Who did what, and every ZATCA outcome")} />
      <AuditTable />
    </>
  );
}
