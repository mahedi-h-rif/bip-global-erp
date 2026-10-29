"use client";

import { useState } from "react";
import useSWR from "swr";
import { Download, FileText, RotateCcw, Save } from "lucide-react";
import { api, ApiError, downloadPost, fetcher, openFile } from "@/lib/api";
import type { AgreementTemplate } from "@/lib/types";
import { dateTime } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Field, Input, PageHeader, Spinner } from "@/components/ui";
import { AgreementTextForm, parseAgreement, serializeAgreement } from "@/components/agreement-form";
import { useT } from "@/lib/i18n";

export default function AgreementTemplatePage() {
  const { data, mutate } = useSWR<AgreementTemplate>("/agreement-template", fetcher);
  if (!data) return <Spinner />;
  return <TemplateForm key={`${data.updatedAt}-${data.standard}`} initial={data} onSaved={(t) => mutate(t, false)} />;
}

function TemplateForm({ initial, onSaved }: { initial: AgreementTemplate; onSaved: (t: AgreementTemplate) => void }) {
  const t = useT();
  const [title, setTitle] = useState(initial.title);
  const [text, setText] = useState(() => parseAgreement(initial.body));
  const [busy, setBusy] = useState<string | null>(null);
  const [msg, setMsg] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);
  const [confirmReset, setConfirmReset] = useState(false);
  const edited = title !== initial.title || serializeAgreement(text) !== serializeAgreement(parseAgreement(initial.body));

  async function act(name: string, fn: () => Promise<void>) {
    setBusy(name);
    setMsg(null);
    try {
      await fn();
    } catch (e) {
      setMsg({ tone: "bad", text: e instanceof ApiError ? e.message : t("Could not reach the server") });
    } finally {
      setBusy(null);
    }
  }

  return (
    <>
      <PageHeader
        title={t("Agreement template")}
        subtitle={t("Your standard agreement. Every new agreement starts from it; existing agreements keep their own copy.")}
        actions={
          <Button variant="secondary" loading={busy === "sample"} onClick={() => act("sample", () => openFile("/agreement-template/pdf"))}>
            <FileText className="size-4" aria-hidden /> {t("Sample agreement")}
          </Button>
        }
      />
      <div className="space-y-6">
        {msg && <Alert tone={msg.tone}>{msg.text}</Alert>}
        <Alert tone="info">
          {initial.standard
            ? t("This is the built-in standard supply agreement. Change it below and save to make it yours.")
            : t("Edited by {who} on {when}.", { who: initial.updatedBy ?? t("an admin"), when: dateTime(initial.updatedAt) })}{" "}
          {t("Have it checked by a Saudi lawyer before relying on it for large contracts.")}
        </Alert>
        <Card>
          <CardHeader title={t("Title")} />
          <div className="p-5">
            <Field label={t("Agreement title")}><Input value={title} onChange={(e) => setTitle(e.target.value)} /></Field>
          </div>
        </Card>
        <AgreementTextForm value={text} onChange={setText} />
        <div className="sticky bottom-0 -mx-4 flex flex-wrap items-center justify-end gap-2 border-t border-line bg-canvas/95 px-4 py-4 backdrop-blur md:-mx-8 md:px-8">
          {!confirmReset ? (
            <Button variant="ghost" className="me-auto" onClick={() => setConfirmReset(true)}>
              <RotateCcw className="size-4" aria-hidden /> {t("Back to the standard text")}
            </Button>
          ) : (
            <span className="me-auto flex flex-wrap items-center gap-2 text-sm">
              {t("Replace your text with the built-in standard agreement?")}
              <Button variant="danger" loading={busy === "reset"}
                onClick={() => act("reset", async () => { onSaved(await api<AgreementTemplate>("DELETE", "/agreement-template")); setMsg({ tone: "ok", text: t("Back to the standard agreement.") }); })}>
                {t("Replace")}
              </Button>
              <Button variant="ghost" onClick={() => setConfirmReset(false)}>{t("Cancel")}</Button>
            </span>
          )}
          {edited && <span className="text-xs text-muted">{t("Unsaved changes")}</span>}
          <Button variant="secondary" loading={busy === "download"}
            onClick={() => act("download", () => downloadPost("/agreement-template/pdf", { title, body: serializeAgreement(text), standard: false }, "agreement-sample-edited.pdf"))}>
            <Download className="size-4" aria-hidden /> {t("Download edited sample")}
          </Button>
          <Button loading={busy === "save"}
            onClick={() => act("save", async () => {
              onSaved(await api<AgreementTemplate>("PUT", "/agreement-template", { title, body: serializeAgreement(text), standard: false }));
              setMsg({ tone: "ok", text: t("Saved. New agreements use this text.") });
            })}>
            <Save className="size-4" aria-hidden /> {t("Save template")}
          </Button>
        </div>
      </div>
    </>
  );
}
