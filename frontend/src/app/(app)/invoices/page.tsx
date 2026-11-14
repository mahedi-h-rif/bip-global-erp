"use client";

import Link from "next/link";
import { Suspense, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import useSWR from "swr";
import { Eye, FileDown, FileMinus2, Plus, Printer, Search, Send } from "lucide-react";
import { api, ApiError, download, fetcher, printFile } from "@/lib/api";
import { EditDeleteActions } from "@/components/invoice-actions";
import type { InvoiceStatus, InvoiceSummary, Me, Page } from "@/lib/types";
import { countdown, dateTime, kindLabel, money, statusLabel, typeLabel } from "@/lib/format";
import { Alert, Button, ButtonLink, Card, Empty, Input, PageHeader, PaymentBadge, Select, Spinner, StatusBadge } from "@/components/ui";
import { useT } from "@/lib/i18n";
import { LifecycleBadge } from "@/components/invoice-lifecycle";
import { PendingInvoicing } from "@/components/pending-work";
import { PagePreview, PdfPreview, PreviewDrawer, usePreview } from "@/components/preview";
import InvoicePage from "./[id]/page";

const statuses = Object.keys(statusLabel) as InvoiceStatus[];

/** Accepted by ZATCA: the only documents a credit or debit note can reference. */
const accepted = new Set<InvoiceStatus>(["CLEARED", "CLEARED_WITH_WARNINGS", "REPORTED", "REPORTED_WITH_WARNINGS"]);

const iconLink ="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs font-medium text-ink hover:bg-canvas border border-line bg-white";

/** Waiting individual invoices must reach ZATCA within 24 hours of issue: show how long is left. */
function DeadlineNote({ invoice: i }: { invoice: InvoiceSummary }) {
  const t = useT();
  if (i.status !== "SIGNED" || i.test) return null;
  const left = countdown(i.reportDeadlineAt);
  if (!left) return null;
  return (
    <span className={`mt-0.5 block text-[11px] font-medium ${left.overdue ? "text-bad" : "text-warn"}`}>
      {left.overdue ? t("Late: send to ZATCA now") : t("Send within {time}", { time: left.time })}
    </span>
  );
}

/** View, PDF and credit/debit note, plus Edit and Delete for every invoice (see EditDeleteActions). */
function RowActions({ invoice: i, canWrite, onChanged, onView }: { invoice: InvoiceSummary; canWrite: boolean; onChanged: () => void; onView: () => void }) {
  const t = useT();
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

  const draft = i.status === "DRAFT";
  return (
    <div className="flex flex-col items-end gap-1">
      <div className="flex flex-wrap justify-end gap-1.5">
        <button type="button" onClick={onView} className={iconLink}><Eye className="size-3.5" aria-hidden /> {t("View")}</button>
        {i.status === "SIGNED" && canWrite && (
          <button
            type="button"
            disabled={busy}
            className="inline-flex items-center gap-1 rounded-md bg-brand px-2 py-1 text-xs font-medium text-white hover:brightness-110 disabled:opacity-50"
            onClick={() => run(async () => { await api("POST", `/invoices/${i.id}/submit`); onChanged(); })}
          >
            <Send className="size-3.5" aria-hidden /> {busy ? t("Sending…") : t("Send to ZATCA")}
          </button>
        )}
        {!draft && i.downloadable && (
          <button type="button" disabled={busy} className={iconLink}
            onClick={() => run(() => download(`/invoices/${i.id}/pdf`, `${i.number}.pdf`))}>
            <FileDown className="size-3.5" aria-hidden /> PDF
          </button>
        )}
        {!draft && i.downloadable && (
          <button type="button" disabled={busy} className={iconLink} onClick={() => run(() => printFile(`/invoices/${i.id}/pdf`))}>
            <Printer className="size-3.5" aria-hidden /> {t("Print")}
          </button>
        )}
        {i.type === "INVOICE" && accepted.has(i.status) && canWrite && (
          <Link href={`/invoices/${i.id}#adjust`} className={iconLink} title={t("Issued invoices can't be edited; raise a credit or debit note")}>
            <FileMinus2 className="size-3.5" aria-hidden /> {t("Credit/Debit")}
          </Link>
        )}
      </div>
      {canWrite && <EditDeleteActions doc={i} onChanged={onChanged} />}
      {error && <span className="text-[11px] text-bad">{error}</span>}
    </div>
  );
}

function InvoiceList() {
  const params = useSearchParams();
  const router = useRouter();
  const t = useT();
  const status = params.get("status") ?? "";
  const kind = params.get("kind") ?? "";
  const q = params.get("q") ?? "";
  const page = Number(params.get("page") ?? 0);
  const [search, setSearch] = useState(q);

  const query = new URLSearchParams({ page: String(page), size: "25" });
  if (status) query.set("status", status);
  if (kind) query.set("kind", kind);
  if (q) query.set("q", q);
  const { data, error, mutate } = useSWR<Page<InvoiceSummary>>(`/invoices?${query}`, fetcher);
  const { data: me } = useSWR<Me>("/auth/me", fetcher);
  const canWrite = me != null && me.role !== "VIEWER";
  const preview = usePreview<InvoiceSummary>();
  const pv = preview.current;

  function update(next: Record<string, string>) {
    const p = new URLSearchParams(params.toString());
    Object.entries(next).forEach(([k, v]) => (v ? p.set(k, v) : p.delete(k)));
    if (!("page" in next)) p.delete("page");
    router.replace(`/invoices?${p}`);
  }

  return (
    <>
      <PageHeader
        title={t("Invoices")}
        subtitle={t("Tax invoices, simplified invoices, credit and debit notes")}
        actions={<ButtonLink href="/invoices/new"><Plus className="size-4" aria-hidden /> {t("New invoice")}</ButtonLink>}
      />
      <PendingInvoicing />
      <Card>
        <div className="flex flex-wrap gap-3 border-b border-line p-4">
          <form
            className="relative min-w-52 flex-1"
            onSubmit={(e) => {
              e.preventDefault();
              update({ q: search });
            }}
          >
            <Search className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted" aria-hidden />
            <Input className="ps-9" placeholder={t("Search by number")} value={search} onChange={(e) => setSearch(e.target.value)} aria-label={t("Search by number")} />
          </form>
          <Select className="w-auto" value={status} onChange={(e) => update({ status: e.target.value })} aria-label={t("Status")}>
            <option value="">{t("All statuses")}</option>
            {statuses.map((s) => <option key={s} value={s}>{t(statusLabel[s])}</option>)}
          </Select>
          <Select className="w-auto" value={kind} onChange={(e) => update({ kind: e.target.value })} aria-label={t("Kind")}>
            <option value="">{t("B2B and B2C")}</option>
            <option value="STANDARD">{t("Standard (B2B)")}</option>
            <option value="SIMPLIFIED">{t("Simplified (B2C)")}</option>
          </Select>
        </div>

        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data && data.content.length === 0 && <Empty title={t("No invoices found")}>{t("Create one with “New invoice”.")}</Empty>}
        {data && data.content.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-start text-xs text-muted">
                  <th className="px-4 py-2.5 text-start font-medium">{t("Number")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Type")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Customer")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Issued")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Total")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Due")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Status")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Actions")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {data.content.map((i) => (
                  <tr key={i.id} {...preview.row(i, "hover:bg-canvas")}>
                    <td className="px-4 py-3 font-medium">
                      <Link href={`/invoices/${i.id}`} className="hover:underline">{i.number ?? t("Draft #{id}", { id: i.id })}</Link>
                    </td>
                    <td className="px-4 py-3">
                      {t(typeLabel[i.type])}
                      <span className="block text-xs text-muted">{t(kindLabel[i.kind])}</span>
                    </td>
                    <td className="px-4 py-3">{i.customerName ?? <span className="text-muted">{t("Walk-in")}</span>}</td>
                    <td className="px-4 py-3 text-muted">{dateTime(i.issuedAt)}</td>
                    <td className="tabular px-4 py-3 text-end">{money(i.totalInclVat)}</td>
                    <td className="tabular px-4 py-3 text-end">
                      {i.paymentStatus ? (
                        <>
                          <span className={Number(i.dueAmount) > 0 ? "font-medium text-bad" : "text-muted"}>{money(i.dueAmount)}</span>
                          <span className="mt-0.5 block"><PaymentBadge status={i.paymentStatus} /></span>
                        </>
                      ) : (
                        <span className="text-muted">—</span>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <StatusBadge status={i.status} />
                      <LifecycleBadge lifecycle={i.lifecycle} />
                      <DeadlineNote invoice={i} />
                    </td>
                    <td className="px-4 py-3">
                      <RowActions invoice={i} canWrite={canWrite} onChanged={() => mutate()} onView={() => preview.open(i)} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data && data.totalPages > 1 && (
          <div className="flex items-center justify-between border-t border-line px-4 py-3 text-sm text-muted">
            <span>{t("Page {n} of {total} · {count} documents", { n: data.number + 1, total: data.totalPages, count: data.totalElements })}</span>
            <div className="flex gap-2">
              <Button variant="secondary" disabled={page === 0} onClick={() => update({ page: String(page - 1) })}>{t("Previous")}</Button>
              <Button variant="secondary" disabled={page + 1 >= data.totalPages} onClick={() => update({ page: String(page + 1) })}>{t("Next")}</Button>
            </div>
          </div>
        )}
      </Card>
      {pv && (
        <PreviewDrawer wide title={pv.number ?? t("Draft #{id}", { id: pv.id })} subtitle={pv.customerName ?? t("Walk-in")}
          href={`/invoices/${pv.id}`} onClose={preview.close}>
          {pv.status === "DRAFT" ? <PagePreview page={InvoicePage} id={pv.id} /> : <PdfPreview path={`/invoices/${pv.id}/pdf`} />}
        </PreviewDrawer>
      )}
    </>
  );
}

export default function InvoicesPage() {
  return (
    <Suspense fallback={<Spinner />}>
      <InvoiceList />
    </Suspense>
  );
}
