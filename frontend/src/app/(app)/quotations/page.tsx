"use client";

import Link from "next/link";
import { useState } from "react";
import useSWR from "swr";
import { FileDown, Pencil, Plus, Printer, Search, Trash2 } from "lucide-react";
import { api, ApiError, fetcher, openFile, printFile } from "@/lib/api";
import type { Quotation } from "@/lib/types";
import { date, money } from "@/lib/format";
import { Alert, ButtonLink, Card, Empty, Input, PageHeader, Spinner } from "@/components/ui";
import { quotationStatusLabel } from "@/components/quotation-editor";
import { useT } from "@/lib/i18n";

const small = "inline-flex items-center gap-1 rounded-md border border-line bg-white px-2 py-1 text-xs font-medium hover:bg-canvas disabled:opacity-40";

function Row({ q, onChanged }: { q: Quotation; onChanged: () => void }) {
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
    <tr className="hover:bg-canvas">
      <td className="px-4 py-3 font-medium"><Link href={`/quotations/${q.id}`} className="hover:underline">{q.number}</Link></td>
      <td className="px-4 py-3">{q.customerName ?? q.clientName}</td>
      <td className="px-4 py-3 text-muted">{date(q.issueDate)}</td>
      <td className="px-4 py-3 text-muted">{q.validUntil ? date(q.validUntil) : "—"}</td>
      <td className="tabular px-4 py-3 text-end">{money(q.totalInclVat)}</td>
      <td className="px-4 py-3">
        {t(quotationStatusLabel[q.status])}
        {q.invoiceId && <Link href={`/invoices/${q.invoiceId}`} className="block text-[11px] text-brand hover:underline">{t("Open invoice")}</Link>}
      </td>
      <td className="px-4 py-3">
        <div className="flex flex-wrap justify-end gap-1.5">
          <Link href={`/quotations/${q.id}`} className={small}><Pencil className="size-3.5" aria-hidden /> {t("Edit")}</Link>
          <button type="button" className={small} disabled={busy} onClick={() => run(() => openFile(`/quotations/${q.id}/pdf`))}>
            <FileDown className="size-3.5" aria-hidden /> PDF
          </button>
          <button type="button" className={small} disabled={busy} onClick={() => run(() => printFile(`/quotations/${q.id}/pdf`))}>
            <Printer className="size-3.5" aria-hidden /> {t("Print")}
          </button>
          {!confirm ? (
            <button type="button" className={`${small} text-bad`} onClick={() => setConfirm(true)}><Trash2 className="size-3.5" aria-hidden /> {t("Delete")}</button>
          ) : (
            <>
              <button type="button" disabled={busy} className="rounded-md bg-bad px-2 py-1 text-xs font-medium text-white"
                onClick={() => run(async () => { await api("DELETE", `/quotations/${q.id}`); onChanged(); })}>
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

export default function QuotationsPage() {
  const t = useT();
  const { data, error, mutate } = useSWR<Quotation[]>("/quotations", fetcher);
  const [q, setQ] = useState("");
  const needle = q.trim().toLowerCase();
  const shown = data?.filter((x) => !needle || [x.number, x.customerName, x.clientName].some((s) => s?.toLowerCase().includes(needle)));

  return (
    <>
      <PageHeader
        title={t("Quotations")}
        subtitle={t("Price offers for clients · never sent to ZATCA")}
        actions={<ButtonLink href="/quotations/new"><Plus className="size-4" aria-hidden /> {t("New quotation")}</ButtonLink>}
      />
      <Card>
        <div className="border-b border-line p-4">
          <div className="relative max-w-md">
            <Search className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted" aria-hidden />
            <Input className="ps-9" placeholder={t("Search by number or client")} value={q} onChange={(e) => setQ(e.target.value)} aria-label={t("Search quotations")} />
          </div>
        </div>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {shown && shown.length === 0 && <Empty title={t("No quotations yet")}>{t("Create one with “New quotation”.")}</Empty>}
        {shown && shown.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-xs text-muted">
                  <th className="px-4 py-2.5 text-start font-medium">{t("Number")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Client")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Valid until")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Total")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Status")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Actions")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {shown.map((x) => <Row key={x.id} q={x} onChanged={() => mutate()} />)}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
