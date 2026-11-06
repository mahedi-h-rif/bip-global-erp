"use client";

import { useRef, useState, type FormEvent } from "react";
import useSWR from "swr";
import {
  Banknote,
  CalendarClock,
  CalendarDays,
  ChevronDown,
  CircleDollarSign,
  FileDown,
  FileSpreadsheet,
  HandCoins,
  Hourglass,
  Pencil,
  Plus,
  Printer,
  Trash2,
  Upload,
  Users,
} from "lucide-react";
import { api, ApiError, download, fetcher, openFile, printFile, uploadFile } from "@/lib/api";
import type { PaymentMethod, WorkerLedger, WorkerRow, WorkerTotals } from "@/lib/types";
import { money, paymentMethodLabel, todayKsa } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Empty, Field, Input, PageHeader, Select, Spinner } from "@/components/ui";
import { BalanceBars, MonthlyChart } from "@/components/worker-charts";
import { DayWorkImport, type DayPreview } from "@/components/daywork-import";
import { DayPicker, WorkerDaily } from "@/components/worker-days";
import { DailyAttendance } from "@/components/worker-attendance";
import { WorkerLeaves } from "@/components/worker-leaves";
import { useT } from "@/lib/i18n";

const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
const small = "inline-flex items-center gap-1 rounded-md border border-line bg-white px-2 py-1 text-xs font-medium hover:bg-canvas disabled:opacity-40";

const num = (v: string | null | undefined) => (v == null || v === "" ? 0 : Number(v));
const tone = (v: number) => (v > 0 ? "text-bad" : v < 0 ? "text-ok" : "text-muted");

/** A balance: owed to the worker in red, paid ahead (negative) in green and marked as an advance. */
function Balance({ value, strong }: { value: number; strong?: boolean }) {
  const t = useT();
  return (
    <span className={`tabular ${strong ? "font-semibold" : ""} ${tone(value)}`}>
      {value < 0 ? `(${money(-value)})` : money(value)}
      {value < 0 && <span className="ms-1 text-[10px] font-medium uppercase">{t("advance")}</span>}
    </span>
  );
}

/** The worker's pay type, shown the same way everywhere so monthly and daily-wage workers are never confused. */
function WorkerTypeBadge({ daily }: { daily: boolean }) {
  const t = useT();
  return daily ? (
    <span className="ms-2 inline-flex items-center gap-1 rounded bg-brand/10 px-1.5 py-0.5 text-[10px] font-semibold uppercase text-brand">
      <CalendarDays className="size-3" aria-hidden />{t("Daily-wage")}
    </span>
  ) : (
    <span className="ms-2 inline-flex items-center gap-1 rounded bg-canvas px-1.5 py-0.5 text-[10px] font-semibold uppercase text-muted">
      <CalendarClock className="size-3" aria-hidden />{t("Monthly")}
    </span>
  );
}

type Draft = { name: string; phone: string; iqamaNumber: string; nationality: string; notes: string; active: boolean; daily: boolean; dailyRate: string; days: string };
const blankDraft: Draft = { name: "", phone: "", iqamaNumber: "", nationality: "", notes: "", active: true, daily: false, dailyRate: "", days: "" };

function WorkerForm({ initial, defaultDaily, onDone, onCancel }: { initial?: WorkerRow; defaultDaily?: boolean; onDone: () => void; onCancel: () => void }) {
  const t = useT();
  const [d, setD] = useState<Draft>(() => initial
    ? { name: initial.name, phone: initial.phone ?? "", iqamaNumber: initial.iqamaNumber ?? "", nationality: initial.nationality ?? "", notes: initial.notes ?? "", active: initial.active, daily: initial.daily, dailyRate: initial.dailyRate ?? "", days: initial.days ?? "" }
    : { ...blankDraft, daily: defaultDaily ?? false });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const body = {
        ...d,
        dailyRate: d.daily && d.dailyRate.trim() !== "" ? Number(d.dailyRate) : null,
        days: d.daily && d.days.trim() !== "" ? Number(d.days) : null,
      };
      if (initial) await api("PUT", `/workers/${initial.id}`, body);
      else await api("POST", "/workers", body);
      onDone();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : t("Failed"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="grid gap-3 border-b border-line bg-canvas/60 p-4 sm:grid-cols-2 lg:grid-cols-6">
      <Field label={t("Worker name")} className="lg:col-span-2">
        <Input required autoFocus value={d.name} onChange={(e) => setD({ ...d, name: e.target.value })} />
      </Field>
      <Field label={t("Mobile")}><Input value={d.phone} onChange={(e) => setD({ ...d, phone: e.target.value })} inputMode="tel" /></Field>
      <Field label={t("Iqama number")}><Input value={d.iqamaNumber} onChange={(e) => setD({ ...d, iqamaNumber: e.target.value })} inputMode="numeric" /></Field>
      <Field label={t("Nationality")}><Input value={d.nationality} onChange={(e) => setD({ ...d, nationality: e.target.value })} /></Field>
      <Field label={t("Status")}>
        <Select value={d.active ? "1" : "0"} onChange={(e) => setD({ ...d, active: e.target.value === "1" })}>
          <option value="1">{t("Active")}</option>
          <option value="0">{t("Inactive")}</option>
        </Select>
      </Field>
      <Field label={t("Worker type")} hint={d.daily ? t("Paid per day worked; mark days in the attendance calendar") : t("Fixed monthly salary")}>
        <Select value={d.daily ? "daily" : "permanent"} onChange={(e) => setD({ ...d, daily: e.target.value === "daily" })}>
          <option value="permanent">{t("Monthly — fixed salary")}</option>
          <option value="daily">{t("Daily-wage — paid per day")}</option>
        </Select>
      </Field>
      {d.daily && <>
        <Field label={t("Days worked")} hint={t("Or mark each day in the attendance calendar")}>
          <Input type="number" min="0" step="0.5" inputMode="decimal" className="text-end" placeholder="—" value={d.days} onChange={(e) => setD({ ...d, days: e.target.value })} />
        </Field>
        <Field label={t("Daily wage")}>
          <Input type="number" min="0" step="0.01" inputMode="decimal" className="text-end" placeholder="—" value={d.dailyRate} onChange={(e) => setD({ ...d, dailyRate: e.target.value })} />
        </Field>
        <Field label={t("Total")} hint={t("Days × daily wage; counts as this month's cost")}>
          <Input readOnly tabIndex={-1} className="text-end bg-canvas" value={d.days && d.dailyRate ? money(num(d.days) * num(d.dailyRate)) : "—"} />
        </Field>
      </>}
      <Field label={t("Notes")} className="sm:col-span-2 lg:col-span-4">
        <Input value={d.notes} onChange={(e) => setD({ ...d, notes: e.target.value })} />
      </Field>
      <div className="flex items-end gap-2 lg:col-span-2">
        <Button type="submit" loading={busy}>{initial ? t("Save") : t("Add worker")}</Button>
        <Button type="button" variant="secondary" onClick={onCancel}>{t("Cancel")}</Button>
      </div>
      {error && <div className="sm:col-span-2 lg:col-span-6"><Alert tone="bad">{error}</Alert></div>}
    </form>
  );
}

/** One worker's figures for the chosen month; each change is saved when the field is left. Daily-wage workers
 * enter days × rate instead of a work value, and the salary's cash/card method is recorded alongside the paid amount. */
function EntryRow({ w, year, month, onSaved }: { w: WorkerRow; year: number; month: number; onSaved: () => void }) {
  const t = useT();
  const cell = w.months[month - 1];
  const [work, setWork] = useState(cell.work ?? "");
  const [paid, setPaid] = useState(cell.paid ?? "");
  const [method, setMethod] = useState<PaymentMethod>(cell.paidMethod ?? "CASH");
  const [state, setState] = useState<"idle" | "saving" | "saved" | "error">("idle");
  const [error, setError] = useState<string | null>(null);

  // Daily-wage workers: work (days × wage) is set on the worker and shown read-only here; only Paid is edited.
  const workValue = w.daily ? (cell.work ?? "") : work;

  async function save(nextMethod: PaymentMethod = method) {
    const same = (input: string, stored: string | null) => (input === "" ? stored == null : stored != null && Number(input) === Number(stored));
    const unchanged = nextMethod === (cell.paidMethod ?? "CASH") && same(paid, cell.paid) && (w.daily || same(work, cell.work));
    if (unchanged) return;
    setState("saving");
    setError(null);
    try {
      // For a daily worker keep the month's posted work; otherwise save the typed work value.
      const body = {
        work: w.daily ? cell.work : work === "" ? null : work,
        paid: paid === "" ? null : paid,
        paidMethod: nextMethod,
      };
      await api("PUT", `/workers/${w.id}/months/${year}/${month}`, body);
      setState("saved");
      onSaved();
    } catch (e) {
      setState("error");
      setError(e instanceof ApiError ? e.message : t("Failed"));
    }
  }

  const due = workValue === "" && paid === "" ? null : num(workValue) - num(paid);
  return (
    <tr className={w.active ? "" : "opacity-60"}>
      <td className="px-4 py-2 text-muted tabular">{w.no}</td>
      <td className="px-4 py-2 font-medium">{w.name}{w.daily && <WorkerTypeBadge daily />}</td>
      <td className="px-2 py-1.5">
        {w.daily ? (
          <span className="tabular inline-block w-28 text-end" title={t("Set on the worker (days × daily wage)")}>
            {cell.work != null ? money(cell.work) : <span className="text-muted">—</span>}
          </span>
        ) : (
          <Input type="number" min="0" step="0.01" inputMode="decimal" className="tabular w-28 text-end" placeholder="—"
                 aria-label={`${w.name} · ${t("Work value")}`} value={work} onChange={(e) => setWork(e.target.value)} onBlur={() => save()} />
        )}
      </td>
      <td className="px-2 py-1.5">
        <Input type="number" min="0" step="0.01" inputMode="decimal" className="tabular w-28 text-end" placeholder="—"
               aria-label={`${w.name} · ${t("Paid")}`} value={paid} onChange={(e) => setPaid(e.target.value)} onBlur={() => save()} />
        <Select className="mt-1 h-8 w-28 py-0 text-xs" aria-label={`${w.name} · ${t("Salary method")}`} value={method}
                onChange={(e) => { const v = e.target.value as PaymentMethod; setMethod(v); void save(v); }}>
          <option value="CASH">{t(paymentMethodLabel.CASH)}</option>
          <option value="CARD">{t(paymentMethodLabel.CARD)}</option>
        </Select>
      </td>
      <td className="px-4 py-2 text-end">{due == null ? <span className="text-muted">—</span> : <Balance value={due} />}</td>
      <td className="px-4 py-2 text-end"><Balance value={num(w.due)} strong /></td>
      <td className="w-20 px-4 py-2 text-xs">
        {state === "saving" && <span className="text-muted">{t("Saving…")}</span>}
        {state === "saved" && <span className="text-ok">{t("Saved")}</span>}
        {state === "error" && <span className="text-bad" title={error ?? ""}>{t("Failed")}</span>}
      </td>
    </tr>
  );
}

function WorkerLine({ w, onEdit, onChanged, canDelete }: { w: WorkerRow; onEdit: () => void; onChanged: () => void; canDelete: boolean }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function remove() {
    try {
      await api("DELETE", `/workers/${w.id}`);
      onChanged();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t("Failed"));
    }
  }

  return (
    <>
      <tr className={`hover:bg-canvas ${w.active ? "" : "opacity-60"}`}>
        <td className="px-4 py-3 text-muted tabular">{w.no}</td>
        <td className="px-4 py-3">
          <button onClick={() => setOpen(!open)} className="inline-flex items-center gap-1.5 font-medium hover:underline" aria-expanded={open}>
            <ChevronDown className={`size-3.5 text-muted transition-transform ${open ? "" : "-rotate-90 rtl:rotate-90"}`} aria-hidden />
            {w.name}
          </button>
          <WorkerTypeBadge daily={w.daily} />
          {!w.active && <span className="ms-2 rounded bg-canvas px-1.5 py-0.5 text-[10px] font-semibold uppercase text-muted">{t("Inactive")}</span>}
          {w.daily && w.dailyRate && (
            <p className="mt-0.5 text-[11px] text-muted">{w.days ? `${w.days} ${t("days")} × ${money(w.dailyRate)}` : `${money(w.dailyRate)} / ${t("day")}`}</p>
          )}
        </td>
        <td className="px-4 py-3 text-muted">{w.iqamaNumber ?? "—"}</td>
        <td className="px-4 py-3 text-muted">{w.phone ?? "—"}</td>
        <td className="tabular px-4 py-3 text-end">{money(w.work)}</td>
        <td className="tabular px-4 py-3 text-end text-ok">{money(w.paid)}</td>
        <td className="px-4 py-3 text-end"><Balance value={num(w.due)} strong /></td>
        <td className="px-4 py-3">
          <div className="flex justify-end gap-1.5">
            <button className={small} onClick={onEdit}><Pencil className="size-3.5" aria-hidden />{t("Edit")}</button>
            {canDelete && (confirm
              ? <button className={`${small} border-bad/40 text-bad`} onClick={remove}>{t("Confirm delete")}</button>
              : <button className={small} onClick={() => setConfirm(true)} aria-label={t("Delete")}><Trash2 className="size-3.5" aria-hidden /></button>)}
          </div>
          {error && <p className="mt-1 text-end text-xs text-bad">{error}</p>}
        </td>
      </tr>
      {open && (
        <tr>
          <td colSpan={8} className="bg-canvas/60 px-4 py-3">
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-6">
              {w.months.map((m) => (
                <div key={m.month} className="rounded-md border border-line bg-white p-2 text-xs">
                  <p className="mb-1 font-semibold">{t(MONTHS[m.month - 1])}</p>
                  {m.work == null && m.paid == null ? <p className="text-muted">—</p> : (
                    <>
                      <p className="flex justify-between"><span className="text-muted">{t("Work")}</span><span className="tabular">{money(m.work ?? 0)}</span></p>
                      <p className="flex justify-between"><span className="text-muted">{t("Paid")}</span><span className="tabular text-ok">{money(m.paid ?? 0)}</span></p>
                      <p className="flex justify-between"><span className="text-muted">{t("Due")}</span><Balance value={num(m.due)} /></p>
                    </>
                  )}
                </div>
              ))}
            </div>
            {w.notes && <p className="mt-2 text-xs text-muted">{w.notes}</p>}
          </td>
        </tr>
      )}
    </>
  );
}

export default function WorkersPage() {
  const t = useT();
  const thisYear = Number(todayKsa().slice(0, 4));
  const [year, setYear] = useState(thisYear);
  const [month, setMonth] = useState(Number(todayKsa().slice(5, 7)));
  // The page shows one worker kind at a time so monthly and daily-wage are never mixed; it opens on monthly.
  const [workerType, setWorkerType] = useState<"monthly" | "daily">("monthly");
  const [entryMode, setEntryMode] = useState<"day" | "month">("month");
  const [day, setDay] = useState(todayKsa());
  const [editing, setEditing] = useState<number | "new" | null>(null);
  const [notice, setNotice] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const dayRef = useRef<HTMLInputElement>(null);
  const [dayImport, setDayImport] = useState<{ file: File; preview: DayPreview } | null>(null);
  const { data, error, mutate } = useSWR<WorkerLedger>(`/workers/ledger?year=${year}`, fetcher, { keepPreviousData: true });
  const { data: me } = useSWR<{ role: string }>("/auth/me", fetcher);
  const canDelete = me?.role === "ADMIN" || me?.role === "OPERATOR";

  async function run(key: string, fn: () => Promise<void>) {
    setBusy(key);
    setNotice(null);
    try {
      await fn();
    } catch (e) {
      setNotice({ tone: "bad", text: e instanceof ApiError ? e.message : t("Failed") });
    } finally {
      setBusy(null);
    }
  }

  async function importFile(file: File) {
    await run("import", async () => {
      const r = await uploadFile<{ year: number; workers: number; created: number; months: number }>(`/workers/import?year=${year}`, file);
      await mutate();
      setNotice({ tone: "ok", text: t("Imported {workers} workers ({created} new) and {months} months into {year}.", r) });
    });
  }

  async function previewDays(file: File) {
    await run("days", async () => {
      setDayImport({ file, preview: await uploadFile<DayPreview>("/workers/import-days", file) });
    });
  }

  // Everything on the page is for the chosen worker kind only. Totals, the chart and the balances are worked
  // out from that kind's workers so the two are kept completely apart.
  const isDaily = workerType === "daily";
  const allWorkers = data?.workers ?? [];
  const monthlyCount = allWorkers.filter((w) => !w.daily).length;
  const dailyCount = allWorkers.filter((w) => w.daily).length;
  const shown = allWorkers.filter((w) => (isDaily ? w.daily : !w.daily));
  const agg = {
    work: shown.reduce((s, w) => s + num(w.work), 0),
    paid: shown.reduce((s, w) => s + num(w.paid), 0),
    due: shown.reduce((s, w) => s + num(w.due), 0),
    outstanding: shown.reduce((s, w) => s + Math.max(num(w.due), 0), 0),
    advances: shown.reduce((s, w) => s + Math.max(-num(w.due), 0), 0),
    active: shown.filter((w) => w.active).length,
  };
  const typeMonths: WorkerTotals[] = Array.from({ length: 12 }, (_, i) => {
    const work = shown.reduce((s, w) => s + num(w.months[i]?.work), 0);
    const paid = shown.reduce((s, w) => s + num(w.months[i]?.paid), 0);
    return { work: String(work), paid: String(paid), due: String(work - paid) };
  });
  const monthTotal = typeMonths[month - 1];

  const stats = data && [
    { label: t("Total work value"), value: money(agg.work), icon: CircleDollarSign, hint: t("Value of work completed in {year}", { year: data.year }) },
    { label: t("Total paid"), value: money(agg.paid), icon: Banknote, tone: "text-ok", hint: t("Amount given to workers") },
    { label: t("Outstanding to workers"), value: money(agg.outstanding), icon: Hourglass, tone: agg.outstanding > 0 ? "text-bad" : "", hint: t("Still to pay") },
    { label: t("Paid in advance"), value: money(agg.advances), icon: HandCoins, hint: t("Paid ahead of the work") },
    { label: isDaily ? t("Daily-wage workers") : t("Monthly workers"), value: shown.length, icon: Users, hint: t("{n} active", { n: agg.active }) },
  ];

  return (
    <>
      <PageHeader
        title={t("Workers")}
        subtitle={t("Outside worker ledger: work value, payments and balances (SAR)")}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <div className="w-28">
              <Select value={year} onChange={(e) => setYear(Number(e.target.value))} aria-label={t("Year")}>
                {(data?.years ?? [thisYear]).map((y) => <option key={y} value={y}>{y}</option>)}
                {!data?.years.includes(thisYear + 1) && <option value={thisYear + 1}>{thisYear + 1}</option>}
              </Select>
            </div>
            <input ref={fileRef} type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" className="hidden"
                   onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ""; if (f) void importFile(f); }} />
            <Button variant="secondary" loading={busy === "import"} onClick={() => fileRef.current?.click()}><Upload className="size-4" aria-hidden />{t("Import Excel")}</Button>
            <Button variant="secondary" loading={busy === "xlsx"} onClick={() => run("xlsx", () => download(`/workers/ledger.xlsx?year=${year}`, `worker-ledger-${year}.xlsx`))}>
              <FileSpreadsheet className="size-4" aria-hidden />{t("Excel")}
            </Button>
            <Button variant="secondary" loading={busy === "pdf"} onClick={() => run("pdf", () => openFile(`/workers/ledger/pdf?year=${year}`))}>
              <FileDown className="size-4" aria-hidden />{t("PDF")}
            </Button>
            <Button variant="secondary" loading={busy === "print"} onClick={() => run("print", () => printFile(`/workers/ledger/pdf?year=${year}`))}>
              <Printer className="size-4" aria-hidden />{t("Print")}
            </Button>
            <Button onClick={() => setEditing("new")}><Plus className="size-4" aria-hidden />{t("Add worker")}</Button>
          </div>
        }
      />

      {notice && <div className="mb-4"><Alert tone={notice.tone}>{notice.text}</Alert></div>}
      {error && <Alert tone="bad">{error.message}</Alert>}
      {!data && !error && <Spinner />}

      {data && stats && (
        <>
          {/* The one switch that keeps the two kinds apart: pick monthly or daily-wage, and the whole page follows. */}
          <div className="mb-5 grid max-w-xl grid-cols-2 gap-2 rounded-xl border border-line bg-white p-1.5 shadow-sm" role="tablist" aria-label={t("Worker type")}>
            {([
              { key: "monthly" as const, label: t("Monthly workers"), icon: CalendarClock, count: monthlyCount, hint: t("fixed monthly salary") },
              { key: "daily" as const, label: t("Daily-wage workers"), icon: CalendarDays, count: dailyCount, hint: t("paid per day worked") },
            ]).map((o) => {
              const on = workerType === o.key;
              return (
                <button key={o.key} role="tab" aria-selected={on} onClick={() => setWorkerType(o.key)}
                        className={`flex items-center justify-center gap-2.5 rounded-lg px-4 py-3 transition ${on ? "bg-brand text-white shadow-sm" : "text-muted hover:bg-canvas hover:text-ink"}`}>
                  <o.icon className="size-5 shrink-0" aria-hidden />
                  <span className="text-start leading-tight">
                    <span className="block text-sm font-semibold">{o.label}</span>
                    <span className={`block text-[11px] ${on ? "text-white/80" : "text-muted"}`}>{o.count} · {o.hint}</span>
                  </span>
                </button>
              );
            })}
          </div>

          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
            {stats.map((s) => (
              <div key={s.label} className="rounded-xl border border-line bg-white p-4 shadow-sm">
                <div className="flex items-center justify-between text-muted">
                  <span className="text-xs font-medium">{s.label}</span>
                  <s.icon className="size-4" aria-hidden />
                </div>
                <p className={`tabular mt-2 text-xl font-semibold ${s.tone ?? ""}`}>{s.value}</p>
                <p className="mt-1 text-xs text-muted">{s.hint}</p>
              </div>
            ))}
          </div>

          <div className="mt-6 grid gap-6 lg:grid-cols-3">
            <Card className="lg:col-span-2">
              <CardHeader title={t("Work value and paid by month")} subtitle={t("Hover a month for its figures")} />
              <div className="p-4"><MonthlyChart months={typeMonths} /></div>
            </Card>
            <Card>
              <CardHeader title={t("Balance by worker")} subtitle={t("Owed to the worker, largest first")} />
              <div className="max-h-80 overflow-y-auto p-4">
                <BalanceBars rows={shown.map((w) => ({ name: w.name, due: num(w.due) }))} />
              </div>
            </Card>
          </div>

          {isDaily ? (
            <DailyAttendance workers={shown} onSaved={() => void mutate()} />
          ) : (
          <Card className="mt-6">
            <CardHeader
              title={entryMode === "day" ? t("Entry by day") : t("Entry by month")}
              subtitle={entryMode === "day"
                ? t("Type each worker's work value and amount paid for the day; it is added to that month's total. Each figure saves when you leave the field.")
                : t("Type the work value and amount paid for each worker; each figure saves when you leave the field.")}
              action={
                <div className="flex flex-wrap items-center gap-2">
                  <div className="inline-flex rounded-lg border border-line bg-white p-0.5" role="tablist">
                    {(["day", "month"] as const).map((v) => (
                      <button key={v} role="tab" aria-selected={entryMode === v} onClick={() => setEntryMode(v)}
                              className={`rounded-md px-2.5 py-1 text-xs font-medium ${entryMode === v ? "bg-brand text-white" : "text-muted hover:text-ink"}`}>
                        {v === "day" ? t("By day") : t("By month")}
                      </button>
                    ))}
                  </div>
                  {entryMode === "day" && <DayPicker value={day} onChange={setDay} />}
                  {entryMode === "month" && <>
                  <div className="w-44">
                    <Select value={month} onChange={(e) => setMonth(Number(e.target.value))} aria-label={t("Month")}>
                      {MONTHS.map((m, i) => <option key={m} value={i + 1}>{t(m)} {year}</option>)}
                    </Select>
                  </div>
                  <button className={small} onClick={() => run("statement", () => openFile(`/workers/statement/pdf?year=${year}&month=${month}`))}>
                    <FileDown className="size-3.5" aria-hidden />{t("Payment statement")}
                  </button>
                  <button className={small} onClick={() => run("statement", () => printFile(`/workers/statement/pdf?year=${year}&month=${month}`))} aria-label={t("Print payment statement")}>
                    <Printer className="size-3.5" aria-hidden />
                  </button>
                  </>}
                  <input ref={dayRef} type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" className="hidden"
                         onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ""; if (f) void previewDays(f); }} />
                  <button className={small} disabled={busy === "days"} onClick={() => dayRef.current?.click()}>
                    <Upload className="size-3.5" aria-hidden />{busy === "days" ? t("Reading…") : t("Import day work")}
                  </button>
                </div>
              }
            />
            {dayImport && (
              <DayWorkImport file={dayImport.file} preview={dayImport.preview} onCancel={() => setDayImport(null)}
                             onDone={(text) => { setDayImport(null); setNotice({ tone: "ok", text }); void mutate(); }} />
            )}
            {shown.length === 0 ? (
              <Empty title={t("No monthly workers yet")}>{t("Add one with the Add worker button, or import the company's Excel ledger.")}</Empty>
            ) : entryMode === "day" ? (
              <WorkerDaily workers={shown} day={day} onDay={setDay} onSaved={() => void mutate()} />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full min-w-[720px] text-sm">
                  <thead>
                    <tr className="border-b border-line text-xs text-muted">
                      <th className="w-12 px-4 py-2.5 text-start font-medium">{t("No.")}</th>
                      <th className="px-4 py-2.5 text-start font-medium">{t("Worker")}</th>
                      <th className="px-2 py-2.5 text-start font-medium">{t("Work value")}</th>
                      <th className="px-2 py-2.5 text-start font-medium">{t("Paid")}</th>
                      <th className="px-4 py-2.5 text-end font-medium">{t("Month balance")}</th>
                      <th className="px-4 py-2.5 text-end font-medium">{t("Year balance")}</th>
                      <th className="w-20 px-4 py-2.5"><span className="sr-only">{t("Status")}</span></th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-line">
                    {shown.map((w) => (
                      <EntryRow key={`${w.id}-${year}-${month}`} w={w} year={year} month={month} onSaved={() => void mutate()} />
                    ))}
                  </tbody>
                  <tfoot>
                    <tr className="border-t-2 border-line bg-canvas/60 font-semibold">
                      <td />
                      <td className="px-4 py-2.5">{t("Monthly workers total")} · {t(MONTHS[month - 1])}</td>
                      <td className="tabular px-2 py-2.5">{money(monthTotal.work)}</td>
                      <td className="tabular px-2 py-2.5 text-ok">{money(monthTotal.paid)}</td>
                      <td className="px-4 py-2.5 text-end"><Balance value={num(monthTotal.due)} strong /></td>
                      <td className="px-4 py-2.5 text-end"><Balance value={agg.due} strong /></td>
                      <td />
                    </tr>
                  </tfoot>
                </table>
              </div>
            )}
          </Card>
          )}

          <Card className="mt-6">
            <CardHeader
              title={isDaily ? t("Daily-wage worker register") : t("Monthly worker register")}
              subtitle={t("Year totals per worker; open a name for the month-by-month account.")}
            />
            {editing === "new" && <WorkerForm defaultDaily={isDaily} onDone={() => { setEditing(null); void mutate(); }} onCancel={() => setEditing(null)} />}
            {shown.length === 0 ? (
              editing === "new" ? null : <Empty title={isDaily ? t("No daily-wage workers yet") : t("No monthly workers yet")}>{t("Add one with the Add worker button above.")}</Empty>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full min-w-[820px] text-sm">
                  <thead>
                    <tr className="border-b border-line text-xs text-muted">
                      <th className="w-12 px-4 py-2.5 text-start font-medium">{t("No.")}</th>
                      <th className="px-4 py-2.5 text-start font-medium">{t("Worker")}</th>
                      <th className="px-4 py-2.5 text-start font-medium">{t("Iqama number")}</th>
                      <th className="px-4 py-2.5 text-start font-medium">{t("Mobile")}</th>
                      <th className="px-4 py-2.5 text-end font-medium">{t("Total work")}</th>
                      <th className="px-4 py-2.5 text-end font-medium">{t("Total paid")}</th>
                      <th className="px-4 py-2.5 text-end font-medium">{t("Balance")}</th>
                      <th className="px-4 py-2.5"><span className="sr-only">{t("Actions")}</span></th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-line">
                    {shown.map((w) => editing === w.id ? (
                      <tr key={w.id}><td colSpan={8} className="p-0">
                        <WorkerForm initial={w} onDone={() => { setEditing(null); void mutate(); }} onCancel={() => setEditing(null)} />
                      </td></tr>
                    ) : (
                      <WorkerLine key={w.id} w={w} canDelete={canDelete} onEdit={() => setEditing(w.id)} onChanged={() => void mutate()} />
                    ))}
                  </tbody>
                  <tfoot>
                    <tr className="border-t-2 border-line bg-canvas/60 font-semibold">
                      <td />
                      <td className="px-4 py-2.5" colSpan={3}>{isDaily ? t("Daily-wage workers total") : t("Monthly workers total")}</td>
                      <td className="tabular px-4 py-2.5 text-end">{money(agg.work)}</td>
                      <td className="tabular px-4 py-2.5 text-end text-ok">{money(agg.paid)}</td>
                      <td className="px-4 py-2.5 text-end"><Balance value={agg.due} strong /></td>
                      <td />
                    </tr>
                  </tfoot>
                </table>
              </div>
            )}
          </Card>
          <p className="mt-3 text-xs text-muted">
            {t("Work value = value of work completed · Paid = amount given · Balance = work value minus paid; a figure in brackets was paid in advance.")}
          </p>

          <WorkerLeaves year={year} workers={shown} />
        </>
      )}
    </>
  );
}
