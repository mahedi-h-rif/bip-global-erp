"use client";

import { use } from "react";
import { PageHeader } from "@/components/ui";
import { PadEditor } from "@/components/pad-editor";
import { useT } from "@/lib/i18n";

export default function NewPadPage({ searchParams }: { searchParams: Promise<{ start?: string }> }) {
  const { start } = use(searchParams);
  const t = useT();
  return (
    <>
      <PageHeader title={t("New document on the pad")} subtitle={t("Type on the letterhead like in Word, then save, download or share it.")} />
      <PadEditor start={start === "blank" ? "blank" : "letter"} />
    </>
  );
}
