"use client";

import Link from "next/link";
import { useState } from "react";
import useSWR from "swr";
import { FileDown, FilePlus2, FileText, Mail, Pencil, Printer, Search, Share2, Trash2 } from "lucide-react";
import { api, ApiError, download, fetcher, openFile, printFile, shareFile } from "@/lib/api";
import type { PadSummary } from "@/lib/types";
import { dateTime } from "@/lib/format";
import { Alert, Button, ButtonLink, Card, CardHeader, Empty, Input, PageHeader, Spinner } from "@/components/ui";
import { padFileName } from "@/components/pad-editor";
import { useT } from "@/lib/i18n";

const DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
const small = "inline-flex items-center gap-1 rounded-md border border-line bg-white px-2 py-1 text-xs font-medium hover:bg-canvas disabled:opacity-40";

/** Runs a file action, showing failures and the "downloaded, attach it yourself" hint. */
function useRunner() {
  const t = useT();
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  async function run(name: string, fn: () => Promise<unknown>) {
    setBusy(name);
    setError(null);
    setNotice(null);
    try {
      if ((await fn()) === "downloaded") setNotice(t("Downloaded. Attach the file in WhatsApp to send it."));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t("Failed"));
    } finally {
      setBusy(null);
    }
  }
  return { busy, error, notice, run };
}

function BlankPad() {
  const t = useT();
  const { busy, error, notice, run } = useRunner();
  return (
    <Card>
      <CardHeader title={t("Blank letterhead pad")}
        subtitle={t("The company pad with logo, names, address, VAT and CR. Word can be typed on by anyone (Word, WPS, Google Docs); PDF is for printing and handwriting.")} />
      <div className="space-y-3 p-5">
        {error && <Alert tone="bad">{error}</Alert>}
        {notice && <Alert tone="ok">{notice}</Alert>}
        <div className="flex flex-wrap gap-2">
          <Button loading={busy === "word"} disabled={busy != null} onClick={() => run("word", () => download("/pads/blank/docx", "letterhead-pad.docx"))}>
            <FileText className="size-4" aria-hidden /> {t("Download Word pad")}
          </Button>
          <Button variant="secondary" loading={busy === "pdf"} disabled={busy != null} onClick={() => run("pdf", () => openFile("/pads/blank/pdf"))}>
            <FileDown className="size-4" aria-hidden /> {t("PDF pad")}
          </Button>
          <Button variant="secondary" loading={busy === "print"} disabled={busy != null} onClick={() => run("print", () => printFile("/pads/blank/pdf"))}>
            <Printer className="size-4" aria-hidden /> {t("Print pad")}
          </Button>
          <Button variant="secondary" loading={busy === "shareWord"} disabled={busy != null}
            onClick={() => run("shareWord", () => shareFile("/pads/blank/docx", "letterhead-pad.docx", DOCX))}>
            <Share2 className="size-4" aria-hidden /> {t("Send Word pad (WhatsApp)")}
          </Button>
          <Button variant="secondary" loading={busy === "sharePdf"} disabled={busy != null}
            onClick={() => run("sharePdf", () => shareFile("/pads/blank/pdf", "letterhead-pad.pdf", "application/pdf"))}>
            <Share2 className="size-4" aria-hidden /> {t("Send PDF pad (WhatsApp)")}
          </Button>
        </div>
        <p className="text-xs text-muted">
          {t("On a phone, Send opens the share sheet so you can pick WhatsApp. On a computer the file is downloaded; attach it in WhatsApp.")}
        </p>
      </div>
    </Card>
  );
}

function Row({ p, onChanged }: { p: PadSummary; onChanged: () => void }) {
  const t = useT();
  const { busy, error, notice, run } = useRunner();
  const [confirm, setConfirm] = useState(false);
  const name = padFileName(p.title);
  return (
    <tr className="hover:bg-canvas">
      <td className="px-4 py-3 font-medium"><Link href={`/pad/${p.id}`} className="hover:underline" dir="auto">{p.title}</Link></td>
      <td className="px-4 py-3 text-muted">{p.createdBy}</td>
      <td className="px-4 py-3 whitespace-nowrap text-muted">{dateTime(p.updatedAt)}</td>
      <td className="px-4 py-3">
        <div className="flex flex-wrap justify-end gap-1.5">
          <Link href={`/pad/${p.id}`} className={small}><Pencil className="size-3.5" aria-hidden /> {t("Edit")}</Link>
          <button type="button" className={small} disabled={busy != null} onClick={() => run("pdf", () => openFile(`/pads/${p.id}/pdf`))}>
            <FileDown className="size-3.5" aria-hidden /> PDF
          </button>
          <button type="button" className={small} disabled={busy != null} onClick={() => run("word", () => download(`/pads/${p.id}/docx`, `${name}.docx`))}>
            <FileText className="size-3.5" aria-hidden /> Word
          </button>
          <button type="button" className={small} disabled={busy != null} onClick={() => run("print", () => printFile(`/pads/${p.id}/pdf`))}>
            <Printer className="size-3.5" aria-hidden /> {t("Print")}
          </button>
          <button type="button" className={small} disabled={busy != null}
            onClick={() => run("share", () => shareFile(`/pads/${p.id}/pdf`, `${name}.pdf`, "application/pdf"))}>
            <Share2 className="size-3.5" aria-hidden /> {t("Share")}
          </button>
          {!confirm ? (
            <button type="button" className={`${small} text-bad`} onClick={() => setConfirm(true)}><Trash2 className="size-3.5" aria-hidden /> {t("Delete")}</button>
          ) : (
            <>
              <button type="button" disabled={busy != null} className="rounded-md bg-bad px-2 py-1 text-xs font-medium text-white"
                onClick={() => run("delete", async () => { await api("DELETE", `/pads/${p.id}`); onChanged(); })}>
                {busy === "delete" ? t("Deleting…") : t("Confirm delete")}
              </button>
              <button type="button" className={small} onClick={() => setConfirm(false)}>{t("Cancel")}</button>
            </>
          )}
        </div>
        {error && <p className="mt-1 text-end text-[11px] text-bad">{error}</p>}
        {notice && <p className="mt-1 text-end text-[11px] text-muted">{notice}</p>}
      </td>
    </tr>
  );
}

export default function PadListPage() {
  const t = useT();
  const { data, error, mutate } = useSWR<PadSummary[]>("/pads", fetcher);
  const [q, setQ] = useState("");
  const needle = q.trim().toLowerCase();
  const shown = data?.filter((p) => !needle || p.title.toLowerCase().includes(needle));

  return (
    <>
      <PageHeader
        title={t("Letterhead pad")}
        subtitle={t("Download the company pad, or write letters and notices on it here.")}
        actions={
          <div className="flex flex-wrap gap-2">
            <ButtonLink href="/pad/new?start=blank" variant="secondary"><FilePlus2 className="size-4" aria-hidden /> {t("Blank page")}</ButtonLink>
            <ButtonLink href="/pad/new"><Mail className="size-4" aria-hidden /> {t("New letter")}</ButtonLink>
          </div>
        }
      />
      <div className="space-y-6">
        <BlankPad />
        <Card>
          <CardHeader title={t("Written on the pad")}
            action={
              <div className="relative w-56 max-w-full">
                <Search className="pointer-events-none absolute start-2.5 top-1/2 size-4 -translate-y-1/2 text-muted" aria-hidden />
                <Input className="ps-8" placeholder={t("Search")} value={q} onChange={(e) => setQ(e.target.value)} />
              </div>
            } />
          {error && <div className="p-5"><Alert tone="bad">{error.message}</Alert></div>}
          {!data && !error && <Spinner />}
          {shown && shown.length === 0 && (
            <Empty title={needle ? t("Nothing matches") : t("No documents yet")}>
              {!needle && t("Start a new letter or a blank page; it is saved here to download, print or share again.")}
            </Empty>
          )}
          {shown && shown.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="border-b border-line text-start text-xs text-muted">
                  <tr>
                    <th className="px-4 py-2 text-start font-medium">{t("Title")}</th>
                    <th className="px-4 py-2 text-start font-medium">{t("Written by")}</th>
                    <th className="px-4 py-2 text-start font-medium">{t("Last saved")}</th>
                    <th className="px-4 py-2" />
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {shown.map((p) => <Row key={p.id} p={p} onChanged={() => mutate()} />)}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>
    </>
  );
}
