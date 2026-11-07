"use client";

import { useMemo, useState } from "react";
import useSWR from "swr";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { PaymentMethod, WorkerDayEntry, WorkerRow } from "@/lib/types";
import { money, paymentMethodLabel, todayKsa } from "@/lib/format";
import { DayPicker } from "@/components/worker-days";
import { Alert, Card, CardHeader, Empty, Input, Select, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

const num = (v: string | null | undefined) => (v == null || v === "" ? 0 : Number(v));
const tone = (v: number) => (v > 0 ? "text-bad" : v < 0 ? "text-ok" : "text-muted");
const small = "inline-flex items-center gap-1 rounded-md border border-line bg-white px-2 py-1 text-xs font-medium hover:bg-canvas disabled:opacity-40";
const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
const WEEKDAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

type Status = "absent" | "part" | "full";

/** What a stored work amount means next to the worker's daily rate: nothing, a part day, or a full day (or more). */
function statusOf(work: string | null | undefined, rate: number): Status {
  const w = num(work);
  if (w <= 0) return "absent";
  if (rate <= 0) return "full";
  return w < rate ? "part" : "full";
}

/** The full worker payload the update endpoint expects, so an inline change touches only the daily rate. */
function withRate(w: WorkerRow, rate: string) {
  return {
    name: w.name, phone: w.phone, iqamaNumber: w.iqamaNumber, nationality: w.nationality, notes: w.notes,
    active: w.active, daily: true, dailyRate: rate === "" ? null : rate, days: w.days,
  };
}

/**
 * Daily-wage entry. Each worker's pay for a day defaults to their daily rate, but any day can be set to a
 * different amount for a part day, and each day can be paid by cash or card. A worker's month cost is the sum of
 * the days entered here, so everything flows into the ledger, the statement and Accounts automatically.
 */
export function DailyAttendance({ workers, onSaved }: { workers: WorkerRow[]; onSaved: () => void }) {
  const t = useT();
  const daily = useMemo(() => workers.filter((w) => w.daily), [workers]);
  const [mode, setMode] = useState<"day" | "month">("day");
  const [day, setDay] = useState(todayKsa());
  const [ym, setYm] = useState(todayKsa().slice(0, 7)); // yyyy-mm, for the by-month view
  const [workerId, setWorkerId] = useState<number | null>(daily[0]?.id ?? null);

  const activeMonth = mode === "day" ? day.slice(0, 7) : ym;
  const year = Number(activeMonth.slice(0, 4)), month = Number(activeMonth.slice(5, 7));

  const { data, error, mutate } = useSWR<WorkerDayEntry[]>(
    daily.length ? `/workers/days?year=${year}&month=${month}` : null, fetcher, { keepPreviousData: true });
  const saved = () => { void mutate(); onSaved(); };

  if (daily.length === 0) return null;

  return (
    <Card className="mt-6">
      <CardHeader
        title={t("Daily-wage entry")}
        subtitle={t("Each day defaults to the worker's daily rate; change it for a part day. Cash or card can differ day to day. Each figure saves when you leave the field.")}
        action={
          <div className="flex flex-wrap items-center gap-2">
            <div className="inline-flex rounded-lg border border-line bg-white p-0.5" role="tablist">
              {(["day", "month"] as const).map((v) => (
                <button key={v} role="tab" aria-selected={mode === v} onClick={() => setMode(v)}
                        className={`rounded-md px-2.5 py-1 text-xs font-medium ${mode === v ? "bg-brand text-white" : "text-muted hover:text-ink"}`}>
                  {v === "day" ? t("By day") : t("By month")}
                </button>
              ))}
            </div>
            {mode === "day" ? <DayPicker value={day} onChange={setDay} /> : (
              <div className="flex items-center gap-2">
                <div className="w-44">
                  <Select value={workerId ?? ""} onChange={(e) => setWorkerId(Number(e.target.value))} aria-label={t("Worker")}>
                    {daily.map((w) => <option key={w.id} value={w.id}>{w.name}{w.active ? "" : ` (${t("Inactive")})`}</option>)}
                  </Select>
                </div>
                <div className="flex items-center gap-1">
                  <button className={small} onClick={() => setYm(stepMonth(ym, -1))} aria-label={t("Previous month")}>
                    <ChevronLeft className="size-3.5 rtl:rotate-180" aria-hidden />
                  </button>
                  <span className="min-w-28 text-center text-sm font-medium">{t(MONTHS[month - 1])} {year}</span>
                  <button className={small} onClick={() => setYm(stepMonth(ym, 1))} aria-label={t("Next month")}>
                    <ChevronRight className="size-3.5 rtl:rotate-180" aria-hidden />
                  </button>
                </div>
              </div>
            )}
          </div>
        }
      />

      {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
      {!data && !error && <Spinner />}
      {data && (mode === "day"
        ? <ByDay workers={daily} day={day} entries={data} onSaved={saved} />
        : <ByMonth worker={daily.find((w) => w.id === workerId) ?? daily[0]} ym={ym} entries={data}
                   onDay={(iso) => { setDay(iso); setMode("day"); }} onSaved={saved} />)}
    </Card>
  );
}

/** Every daily-wage worker's pay for one date, each row editable. */
function ByDay({ workers, day, entries, onSaved }: { workers: WorkerRow[]; day: string; entries: WorkerDayEntry[]; onSaved: () => void }) {
  const t = useT();
  const byWorker = new Map(entries.filter((e) => e.date === day).map((e) => [e.workerId, e]));
  const shown = workers.filter((w) => w.active || byWorker.has(w.id));
  const work = shown.reduce((s, w) => s + num(byWorker.get(w.id)?.work), 0);
  const paid = shown.reduce((s, w) => s + num(byWorker.get(w.id)?.paid), 0);

  if (shown.length === 0) return <Empty title={t("No daily-wage workers")} />;
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[920px] text-sm">
        <thead>
          <tr className="border-b border-line text-xs text-muted">
            <th className="w-12 px-4 py-2.5 text-start font-medium">{t("No.")}</th>
            <th className="px-4 py-2.5 text-start font-medium">{t("Worker")}</th>
            <th className="px-2 py-2.5 text-start font-medium">{t("Daily rate")}</th>
            <th className="px-2 py-2.5 text-start font-medium">{t("This day")}</th>
            <th className="px-2 py-2.5 text-start font-medium">{t("Paid")}</th>
            <th className="px-2 py-2.5 text-start font-medium">{t("Note")}</th>
            <th className="px-4 py-2.5 text-end font-medium">{t("Day balance")}</th>
            <th className="w-20 px-4 py-2.5"><span className="sr-only">{t("Status")}</span></th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {shown.map((w) => (
            <DayRow key={`${w.id}-${day}-${byWorker.get(w.id)?.id ?? "new"}`} w={w} day={day} entry={byWorker.get(w.id)} onSaved={onSaved} />
          ))}
        </tbody>
        <tfoot>
          <tr className="border-t-2 border-line bg-canvas/60 font-semibold">
            <td />
            <td className="px-4 py-2.5" colSpan={2}>{t("Day total")}</td>
            <td className="tabular px-2 py-2.5">{money(work)}</td>
            <td className="tabular px-2 py-2.5 text-ok">{money(paid)}</td>
            <td />
            <td className={`tabular px-4 py-2.5 text-end ${tone(work - paid)}`}>{money(work - paid)}</td>
            <td />
          </tr>
        </tfoot>
      </table>
    </div>
  );
}

/** One worker-day row: the daily rate (editable), the day's pay (defaults to the rate), what was paid and how. */
function DayRow({ w, day, entry, onSaved }: { w: WorkerRow; day: string; entry?: WorkerDayEntry; onSaved: () => void }) {
  const t = useT();
  const [rate, setRate] = useState(w.dailyRate ?? "");
  const [work, setWork] = useState(entry?.work ?? "");
  const [paid, setPaid] = useState(entry?.paid ?? "");
  const [method, setMethod] = useState<PaymentMethod>(entry?.method ?? "CASH");
  const [notes, setNotes] = useState(entry?.notes ?? "");
  const [state, setState] = useState<"idle" | "saving" | "saved" | "error">("idle");
  const [error, setError] = useState<string | null>(null);

  const same = (input: string, stored: string | null | undefined) =>
    (input === "" ? stored == null || stored === "" : stored != null && Number(input) === Number(stored));

  async function saveDay(nextWork: string = work, nextMethod: PaymentMethod = method) {
    if (same(nextWork, entry?.work) && same(paid, entry?.paid) && notes.trim() === (entry?.notes ?? "") && nextMethod === (entry?.method ?? "CASH")) return;
    setState("saving");
    setError(null);
    try {
      await api("PUT", `/workers/${w.id}/days/${day}`, {
        work: nextWork === "" ? null : nextWork, paid: paid === "" ? null : paid, notes, paidMethod: nextMethod,
      });
      setState("saved");
      onSaved();
    } catch (e) {
      setState("error");
      setError(e instanceof ApiError ? e.message : t("Failed"));
    }
  }

  async function saveRate() {
    if (same(rate, w.dailyRate)) return;
    setState("saving");
    setError(null);
    try {
      await api("PUT", `/workers/${w.id}`, withRate(w, rate));
      setState("saved");
      onSaved();
    } catch (e) {
      setState("error");
      setError(e instanceof ApiError ? e.message : t("Failed"));
    }
  }

  function fullDay() {
    const v = rate === "" ? "" : String(num(rate));
    if (v === "") return;
    setWork(v);
    void saveDay(v);
  }

  const rateNum = num(rate);
  const status = statusOf(work, rateNum);
  const due = work === "" && paid === "" ? null : num(work) - num(paid);
  return (
    <tr className={w.active ? "" : "opacity-60"}>
      <td className="px-4 py-2 text-muted tabular">{w.no}</td>
      <td className="px-4 py-2">
        <span className="font-medium">{w.name}</span>
        {status !== "absent" && (
          <span className={`ms-2 rounded-full px-1.5 py-0.5 text-[10px] font-medium ${status === "full" ? "bg-ok/10 text-ok" : "bg-brand/10 text-brand"}`}>
            {status === "full" ? t("Full day") : t("Part day")}
          </span>
        )}
      </td>
      <td className="px-2 py-1.5">
        <Input type="number" min="0" step="0.01" inputMode="decimal" className="tabular w-24 text-end" placeholder="—"
               aria-label={`${w.name} · ${t("Daily rate")}`} value={rate} onChange={(e) => setRate(e.target.value)} onBlur={() => saveRate()} />
      </td>
      <td className="px-2 py-1.5">
        <div className="flex items-center gap-1">
          <Input type="number" min="0" step="0.01" inputMode="decimal" className="tabular w-24 text-end"
                 placeholder={rateNum > 0 ? String(rateNum) : "—"}
                 aria-label={`${w.name} · ${t("This day")}`} value={work} onChange={(e) => setWork(e.target.value)} onBlur={() => saveDay()} />
          <button type="button" className={small} disabled={rateNum <= 0} onClick={fullDay} title={t("Set to a full day at the daily rate")}>
            {t("Full day")}
          </button>
        </div>
      </td>
      <td className="px-2 py-1.5">
        <Input type="number" min="0" step="0.01" inputMode="decimal" className="tabular w-24 text-end" placeholder="—"
               aria-label={`${w.name} · ${t("Paid")}`} value={paid} onChange={(e) => setPaid(e.target.value)} onBlur={() => saveDay()} />
        <Select className="mt-1 h-8 w-24 py-0 text-xs" aria-label={`${w.name} · ${t("Paid by")}`} value={method}
                onChange={(e) => { const v = e.target.value as PaymentMethod; setMethod(v); void saveDay(work, v); }}>
          <option value="CASH">{t(paymentMethodLabel.CASH)}</option>
          <option value="CARD">{t(paymentMethodLabel.CARD)}</option>
        </Select>
      </td>
      <td className="px-2 py-1.5">
        <Input className="min-w-36" placeholder={t("Note")} aria-label={`${w.name} · ${t("Note")}`} value={notes}
               onChange={(e) => setNotes(e.target.value)} onBlur={() => saveDay()} dir="auto" />
      </td>
      <td className="px-4 py-2 text-end tabular">{due == null ? <span className="text-muted">—</span> : <span className={tone(due)}>{money(due)}</span>}</td>
      <td className="w-20 px-4 py-2 text-xs">
        {state === "saving" && <span className="text-muted">{t("Saving…")}</span>}
        {state === "saved" && <span className="text-ok">{t("Saved")}</span>}
        {state === "error" && <span className="text-bad" title={error ?? ""}>{t("Failed")}</span>}
      </td>
    </tr>
  );
}

/** One worker's month as a calendar: each day shows its pay; tap a day to open it in the by-day view for editing. */
function ByMonth({ worker, ym, entries, onDay, onSaved: _onSaved }: {
  worker?: WorkerRow; ym: string; entries: WorkerDayEntry[]; onDay: (iso: string) => void; onSaved: () => void;
}) {
  const t = useT();
  const year = Number(ym.slice(0, 4)), month = Number(ym.slice(5, 7));
  const rate = num(worker?.dailyRate);

  const byDate = useMemo(() => {
    const m = new Map<string, WorkerDayEntry>();
    for (const e of entries) if (e.workerId === worker?.id) m.set(e.date, e);
    return m;
  }, [entries, worker?.id]);

  if (!worker) return <Empty title={t("No daily-wage workers")} />;

  const first = new Date(`${ym}-01T12:00:00Z`);
  const daysInMonth = new Date(Date.UTC(year, month, 0)).getUTCDate();
  const lead = first.getUTCDay();
  const cells: (number | null)[] = [...Array(lead).fill(null), ...Array.from({ length: daysInMonth }, (_, i) => i + 1)];
  while (cells.length % 7 !== 0) cells.push(null);

  let fullDays = 0, partDays = 0, earned = 0, paid = 0;
  for (const e of byDate.values()) {
    const s = statusOf(e.work, rate);
    if (s === "full") fullDays++; else if (s === "part") partDays++;
    earned += num(e.work);
    paid += num(e.paid);
  }
  const balance = earned - paid;

  return (
    <div className="p-4">
      {rate <= 0 && (
        <div className="mb-3"><Alert tone="info">{t("This worker has no daily rate yet. Set it in the by-day view or when editing the worker; a day then defaults to it.")}</Alert></div>
      )}
      <div className="grid grid-cols-7 gap-1.5">
        {WEEKDAYS.map((d) => (
          <div key={d} className="pb-1 text-center text-[11px] font-semibold uppercase text-muted">{t(d)}</div>
        ))}
        {cells.map((dnum, i) => {
          if (dnum == null) return <div key={`b${i}`} />;
          const iso = `${ym}-${String(dnum).padStart(2, "0")}`;
          const entry = byDate.get(iso);
          const status = statusOf(entry?.work ?? null, rate);
          const isToday = iso === todayKsa();
          const cellTone = status === "full" ? "border-ok/40 bg-ok/10"
            : status === "part" ? "border-brand/40 bg-brand/10"
            : "border-line bg-white hover:bg-canvas";
          return (
            <button key={iso} onClick={() => onDay(iso)} title={`${iso} · ${t("Open to edit")}`}
                    className={`flex min-h-16 flex-col items-stretch rounded-lg border p-1.5 text-start transition-colors ${cellTone} ${isToday ? "ring-2 ring-brand/30" : ""}`}>
              <span className="text-xs font-semibold">{dnum}</span>
              {entry && num(entry.work) > 0 && <span className="mt-auto tabular text-[10px] font-medium">{money(entry.work)}</span>}
              {entry && num(entry.paid) > 0 && <span className="tabular text-[10px] text-ok">{money(entry.paid)}</span>}
            </button>
          );
        })}
      </div>

      <div className="mt-4 grid gap-3 sm:grid-cols-4">
        <Stat label={t("Days worked")} value={`${fullDays + partDays}`}
              hint={partDays > 0 ? t("{full} full + {part} part", { full: fullDays, part: partDays }) : t("{n} days", { n: fullDays })} />
        <Stat label={t("Earned")} value={money(earned)} hint={rate > 0 ? t("rate {rate}/day", { rate: money(rate) }) : ""} />
        <Stat label={t("Paid")} value={money(paid)} tone="text-ok" />
        <Stat label={t("Balance")} value={money(balance)} tone={balance > 0 ? "text-bad" : "text-ok"} hint={balance > 0 ? t("still to pay") : balance < 0 ? t("paid ahead") : ""} />
      </div>
      <p className="mt-3 text-xs text-muted">{t("Tap any day to add or change the pay, the amount and how it was paid.")}</p>
    </div>
  );
}

function stepMonth(ym: string, by: number) {
  const d = new Date(`${ym}-01T12:00:00Z`);
  d.setUTCMonth(d.getUTCMonth() + by);
  return d.toISOString().slice(0, 7);
}

function Stat({ label, value, hint, tone: t }: { label: string; value: string; hint?: string; tone?: string }) {
  return (
    <div className="rounded-lg border border-line bg-canvas/40 p-3">
      <p className="text-xs font-medium text-muted">{label}</p>
      <p className={`tabular mt-1 text-lg font-semibold ${t ?? ""}`}>{value}</p>
      {hint && <p className="mt-0.5 text-[11px] text-muted">{hint}</p>}
    </div>
  );
}
