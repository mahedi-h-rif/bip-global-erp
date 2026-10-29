"use client";

import { use } from "react";
import useSWR from "swr";
import { fetcher } from "@/lib/api";
import type { PadDocument } from "@/lib/types";
import { dateTime } from "@/lib/format";
import { Alert, PageHeader, Spinner } from "@/components/ui";
import { PadEditor } from "@/components/pad-editor";
import { useT } from "@/lib/i18n";

export default function PadPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const t = useT();
  // Loaded once: the editor keeps its own text while it is open.
  const { data, error } = useSWR<PadDocument>(`/pads/${id}`, fetcher, { revalidateOnFocus: false, revalidateIfStale: false });
  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!data) return <Spinner />;
  return (
    <>
      <PageHeader title={data.title} subtitle={t("Last saved {date}", { date: dateTime(data.updatedAt) })} />
      <PadEditor key={data.id} initial={data} />
    </>
  );
}
