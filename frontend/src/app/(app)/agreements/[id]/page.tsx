"use client";

import { use } from "react";
import useSWR from "swr";
import { fetcher } from "@/lib/api";
import type { Agreement } from "@/lib/types";
import { Alert, PageHeader, Spinner } from "@/components/ui";
import { AgreementEditor, agreementStatusLabel } from "@/components/agreement-editor";
import { useT } from "@/lib/i18n";

export default function AgreementPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const t = useT();
  const { data, error } = useSWR<Agreement>(`/agreements/${id}`, fetcher);
  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!data) return <Spinner />;
  return (
    <>
      <PageHeader title={t("Agreement {number}", { number: data.number })} subtitle={`${t(agreementStatusLabel[data.status])} · ${data.clientName}`} />
      {/* Re-mounts with fresh values after each save. */}
      <AgreementEditor key={`${data.id}-${data.updatedAt}`} initial={data} />
    </>
  );
}
