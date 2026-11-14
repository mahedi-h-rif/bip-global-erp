"use client";

import Link from "next/link";
import { useState } from "react";
import useSWR from "swr";
import { FileDown, Pencil, Plus, Printer, Trash2 } from "lucide-react";
import { api, ApiError, fetcher, openFile, printFile } from "@/lib/api";
import type { Agreement } from "@/lib/types";
import { date } from "@/lib/format";
import { Alert, ButtonLink, Card, Empty, PageHeader, Spinner } from "@/components/ui";
import { agreementStatusLabel } from "@/components/agreement-editor";
import { useT } from "@/lib/i18n";
import { statusText, useListTools } from "@/components/list-tools";
import { PdfPreview, PreviewDrawer, usePreview, ViewButton } from "@/components/preview";

const small = "inline-flex items-center gap-1 rounded-md border border-line bg-white px-2 py-1 text-xs font-medium hover:bg-canvas disabled:opacity-40";

function Row({ a, onChanged, rowProps, onView }: { a: Agreement; onChanged: () => void; rowProps: object; onView: () => void }) {
  const t = useT();
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run(fn: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      await fn();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t("Failed"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <tr {...rowProps}>
      <td className="px-4 py-3 font-medium"><Link href={`/agreements/${a.id}`} className="hover:underline">{a.number}</Link></td>
      <td className="px-4 py-3">{a.clientName}</td>
      <td className="px-4 py-3">{a.title}</td>
      <td className="px-4 py-3 text-muted">{date(a.agreementDate)}</td>
      <td className="px-4 py-3">
        {t(agreementStatusLabel[a.status])}
        {a.signedOn && a.status === "SIGNED" && <span className="block text-[11px] text-muted">{t("on {date}", { date: date(a.signedOn) })}</span>}
      </td>
      <td className="px-4 py-3">
        <div className="flex flex-wrap justify-end gap-1.5">
          <ViewButton onClick={onView} className={small} />
          <Link href={`/agreements/${a.id}`} className={small}><Pencil className="size-3.5" aria-hidden /> {t("Edit")}</Link>
          <button type="button" className={small} disabled={busy} onClick={() => run(() => openFile(`/agreements/${a.id}/pdf?lang=en`))}>
            <FileDown className="size-3.5" aria-hidden /> PDF EN
          </button>
          <button type="button" className={small} disabled={busy} onClick={() => run(() => openFile(`/agreements/${a.id}/pdf?lang=ar`))}>
            <FileDown className="size-3.5" aria-hidden /> PDF عربي
          </button>
          <button type="button" className={small} disabled={busy} onClick={() => run(() => printFile(`/agreements/${a.id}/pdf`))}>
            <Printer className="size-3.5" aria-hidden /> {t("Print")}
          </button>
          {!confirm ? (
            <button type="button" className={`${small} text-bad`} onClick={() => setConfirm(true)}><Trash2 className="size-3.5" aria-hidden /> {t("Delete")}</button>
          ) : (
            <>
              <button type="button" disabled={busy} className="rounded-md bg-bad px-2 py-1 text-xs font-medium text-white"
                onClick={() => run(async () => { await api("DELETE", `/agreements/${a.id}`); onChanged(); })}>
                {busy ? t("Deleting…") : t("Confirm delete")}
              </button>
              <button type="button" className={small} onClick={() => setConfirm(false)}>{t("Cancel")}</button>
            </>
          )}
        </div>
        {error && <p className="mt-1 text-end text-[11px] text-bad">{error}</p>}
      </td>
    </tr>
  );
}

export default function AgreementsPage() {
  const t = useT();
  const { data, error, mutate } = useSWR<Agreement[]>("/agreements", fetcher);
  const preview = usePreview<Agreement>();
  const pv = preview.current;
  const { toolbar, shown } = useListTools<Agreement>(data, { filters: [{ label: "Status", get: (r) => r.status, format: statusText }, { label: "Client", get: (r) => r.clientName }], date: (r) => r.agreementDate });

  return (
    <>
      <PageHeader
        title={t("Agreements")}
        subtitle={t("Client agreements on the company letterhead")}
        actions={<ButtonLink href="/agreements/new"><Plus className="size-4" aria-hidden /> {t("New agreement")}</ButtonLink>}
      />
      <Card>
        {data && data.length > 0 && toolbar}
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {shown && shown.length === 0 && <Empty title={t("No agreements yet")}>{t("Create one with “New agreement”.")}</Empty>}
        {shown && shown.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-xs text-muted">
                  <th className="px-4 py-2.5 text-start font-medium">{t("Number")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Client")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Title")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Status")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Actions")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {shown.map((x) => <Row key={x.id} a={x} onChanged={() => mutate()} rowProps={preview.row(x, "hover:bg-canvas")} onView={() => preview.open(x)} />)}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      {pv && (
        <PreviewDrawer wide title={pv.number} subtitle={pv.clientName} href={`/agreements/${pv.id}`} onClose={preview.close}>
          <PdfPreview path={`/agreements/${pv.id}/pdf?lang=en`} />
        </PreviewDrawer>
      )}
    </>
  );
}
