"use client";

import { Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import useSWR from "swr";
import { BellOff, BellRing, CalendarClock, Eye, FileWarning, Files, Pencil, Plus, Search, ShieldCheck, Trash2 } from "lucide-react";
import { api, ApiError, fetcher, openFile } from "@/lib/api";
import type { CompanyDocument, WorkerLedger } from "@/lib/types";
import { date } from "@/lib/format";
import { Alert, Button, Card, Empty, Input, PageHeader, Select, Spinner } from "@/components/ui";
import { DocumentForm, typeName } from "@/components/document-form";
import { DocumentWhatsAppButton, WHATSAPP_ENABLED } from "@/components/whatsapp";
import { useT, type TFunction } from "@/lib/i18n";

const small = "inline-flex items-center gap-1 rounded-md border border-line bg-white px-2 py-1 text-xs font-medium hover:bg-canvas disabled:opacity-40";

type Filter = "all" | "expired" | "soon" | "watch" | "company" | "workers";

/** Expiry as a coloured badge: expired, last 15 days, within two months, or fine. */
function ExpiryBadge({ d, t }: { d: CompanyDocument; t: TFunction }) {
  if (d.daysLeft == null) return <span className="text-xs text-muted">{t("No expiry date")}</span>;
  const n = d.daysLeft;
  const [cls, text] =
    n < 0 ? ["bg-bad/10 text-bad", n === -1 ? t("Expired yesterday") : t("Expired {n} days ago", { n: -n })]
    : n === 0 ? ["bg-bad/10 text-bad", t("Expires today")]
    : n <= 15 ? ["bg-bad/10 text-bad", n === 1 ? t("1 day left") : t("{n} days left", { n })]
    : n <= 60 ? ["bg-warn/10 text-warn", t("{n} days left", { n })]
    : ["bg-ok/10 text-ok", t("Valid · {n} days", { n })];
  return <span className={`inline-flex rounded-full px-2 py-0.5 text-[11px] font-semibold ${cls}`}>{text}</span>;
}

function Row({ d, highlight, onEdit, onChanged }: { d: CompanyDocument; highlight: boolean; onEdit: () => void; onChanged: () => void }) {
  const t = useT();
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run(fn: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      await fn();
      onChanged();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t("Failed"));
    } finally {
      setBusy(false);
    }
  }

  const toggle = () => run(() => api("PUT", `/documents/${d.id}/reminders`, { remindersEnabled: !d.remindersEnabled, remindAfterExpiry: d.remindAfterExpiry }));

  return (
    <tr id={`doc-${d.id}`} className={`align-top hover:bg-canvas ${highlight ? "bg-brand/5" : ""}`}>
      <td className="px-4 py-3">
        <p className="font-medium">{d.title}</p>
        <p className="text-xs text-muted">{t(typeName(d.type))}{d.number && <> · <span dir="ltr">{d.number}</span></>}</p>
      </td>
      <td className="px-4 py-3">
        {d.workerName ? <p>{d.workerName}</p> : <p className="inline-flex items-center gap-1 text-muted"><ShieldCheck className="size-3.5" aria-hidden />{t("Company")}</p>}
        {d.holderName && d.holderName !== d.workerName && <p className="text-xs text-muted" dir="auto">{d.holderName}</p>}
      </td>
      <td className="px-4 py-3">
        <p className="tabular">{date(d.expiryDate)}</p>
        <ExpiryBadge d={d} t={t} />
      </td>
      <td className="px-4 py-3 text-xs">
        <button onClick={toggle} disabled={busy}
                className={`inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 font-medium ${d.remindersEnabled ? "border-brand/30 bg-brand/5 text-brand" : "border-line text-muted"}`}
                aria-pressed={d.remindersEnabled} title={d.remindersEnabled ? t("Turn reminders off") : t("Turn reminders on")}>
          {d.remindersEnabled ? <BellRing className="size-3.5" aria-hidden /> : <BellOff className="size-3.5" aria-hidden />}
          {d.remindersEnabled ? t("On") : t("Off")}
        </button>
        {d.remindersEnabled && d.nextReminderOn && <p className="mt-1 text-muted">{t("Next: {date}", { date: date(d.nextReminderOn) })}</p>}
        {d.remindersEnabled && !d.nextReminderOn && d.expiryDate && <p className="mt-1 text-muted">{t("No more reminders")}</p>}
      </td>
      <td className="px-4 py-3">
        <div className="flex flex-wrap justify-end gap-1.5">
          {d.fileName && <button className={small} onClick={() => run(() => openFile(`/documents/${d.id}/file`))}><Eye className="size-3.5" aria-hidden />{t("View")}</button>}
          {WHATSAPP_ENABLED && <DocumentWhatsAppButton documentId={d.id} phone={d.workerPhone} url={d.whatsappUrl} className={small} />}
          <button className={small} onClick={onEdit}><Pencil className="size-3.5" aria-hidden />{t("Edit")}</button>
          {confirm
            ? <button className={`${small} border-bad/40 text-bad`} disabled={busy} onClick={() => run(() => api("DELETE", `/documents/${d.id}`))}>{t("Confirm delete")}</button>
            : <button className={small} onClick={() => setConfirm(true)} aria-label={t("Delete")}><Trash2 className="size-3.5" aria-hidden /></button>}
        </div>
        {error && <p className="mt-1 text-end text-xs text-bad">{error}</p>}
      </td>
    </tr>
  );
}

function DocumentsView() {
  const t = useT();
  const params = useSearchParams();
  const highlight = Number(params.get("id")) || null;
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<number | null>(null);
  const [filter, setFilter] = useState<Filter>("all");
  const [q, setQ] = useState("");
  const { data, error, mutate } = useSWR<CompanyDocument[]>("/documents", fetcher);
  const { data: ledger } = useSWR<WorkerLedger>("/workers/ledger", fetcher);
  const workers = (ledger?.workers ?? []).map((w) => ({ id: w.id, name: w.name }));

  const docs = data ?? [];
  const count = {
    expired: docs.filter((d) => d.daysLeft != null && d.daysLeft < 0).length,
    soon: docs.filter((d) => d.daysLeft != null && d.daysLeft >= 0 && d.daysLeft <= 15).length,
    watch: docs.filter((d) => d.daysLeft != null && d.daysLeft > 15 && d.daysLeft <= 60).length,
  };
  const needle = q.trim().toLowerCase();
  const shown = docs.filter((d) => {
    const n = d.daysLeft;
    const pass = filter === "all" ? true
      : filter === "expired" ? n != null && n < 0
      : filter === "soon" ? n != null && n >= 0 && n <= 15
      : filter === "watch" ? n != null && n > 15 && n <= 60
      : filter === "company" ? d.workerId == null
      : d.workerId != null;
    return pass && (!needle || [d.title, d.number, d.workerName, d.holderName].some((s) => s?.toLowerCase().includes(needle)));
  });

  const tiles = [
    { key: "expired" as const, label: t("Expired"), value: count.expired, icon: FileWarning, tone: count.expired ? "text-bad" : "" },
    { key: "soon" as const, label: t("Expiring within 15 days"), value: count.soon, icon: CalendarClock, tone: count.soon ? "text-bad" : "" },
    { key: "watch" as const, label: t("Expiring within 2 months"), value: count.watch, icon: BellRing, tone: count.watch ? "text-warn" : "" },
    { key: "all" as const, label: t("All documents"), value: docs.length, icon: Files, tone: "" },
  ];

  return (
    <>
      <PageHeader
        title={t("Documents")}
        subtitle={t("Iqamas, passports, licences and certificates, with expiry reminders")}
        actions={<Button onClick={() => { setAdding(true); setEditing(null); }}><Plus className="size-4" aria-hidden />{t("Add document")}</Button>}
      />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {tiles.map((s) => (
          <button key={s.key} onClick={() => setFilter(s.key)}
                  className={`rounded-xl border bg-white p-4 text-start shadow-sm transition-colors hover:border-brand/40 ${filter === s.key ? "border-brand ring-2 ring-brand/15" : "border-line"}`}>
            <span className="flex items-center justify-between text-muted"><span className="text-xs font-medium">{s.label}</span><s.icon className="size-4" aria-hidden /></span>
            <span className={`tabular mt-2 block text-2xl font-semibold ${s.tone}`}>{s.value}</span>
          </button>
        ))}
      </div>

      <Card className="mt-6">
        {adding && (
          <DocumentForm workers={workers} onCancel={() => setAdding(false)}
                        onDone={() => { setAdding(false); void mutate(); }} />
        )}
        <div className="flex flex-wrap items-center gap-3 border-b border-line p-4">
          <div className="relative min-w-56 flex-1">
            <Search className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted" aria-hidden />
            <Input className="ps-9" placeholder={t("Search name, number or document")} value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
          <div className="w-56">
            <Select value={filter} onChange={(e) => setFilter(e.target.value as Filter)} aria-label={t("Show")}>
              <option value="all">{t("All documents")}</option>
              <option value="expired">{t("Expired")}</option>
              <option value="soon">{t("Expiring within 15 days")}</option>
              <option value="watch">{t("Expiring within 2 months")}</option>
              <option value="company">{t("Company documents")}</option>
              <option value="workers">{t("Worker documents")}</option>
            </Select>
          </div>
        </div>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data && shown.length === 0 && (
          <Empty title={docs.length ? t("No documents match") : t("No documents yet")}>
            {docs.length ? null : t("Add an iqama, passport or licence: upload a scan or take a photo, and the details are read for you.")}
          </Empty>
        )}
        {shown.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[860px] text-sm">
              <thead>
                <tr className="border-b border-line text-xs text-muted">
                  <th className="px-4 py-2.5 text-start font-medium">{t("Document")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Belongs to")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Expiry")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Reminders")}</th>
                  <th className="px-4 py-2.5"><span className="sr-only">{t("Actions")}</span></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {shown.map((d) => editing === d.id ? (
                  <tr key={d.id}><td colSpan={5} className="p-0">
                    <DocumentForm initial={d} workers={workers} onCancel={() => setEditing(null)}
                                  onDone={() => { setEditing(null); void mutate(); }} />
                  </td></tr>
                ) : (
                  <Row key={d.id} d={d} highlight={d.id === highlight} onEdit={() => { setEditing(d.id); setAdding(false); }} onChanged={() => void mutate()} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      <p className="mt-3 text-xs text-muted">
        {t("Reminders go to the notification bell and by email to admins: every 15 days from two months before expiry, then every 2 days in the last 15 days.")}
      </p>
    </>
  );
}

export default function DocumentsPage() {
  return (
    <Suspense fallback={<Spinner />}>
      <DocumentsView />
    </Suspense>
  );
}
