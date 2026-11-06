"use client";

import { useMemo, useState } from "react";
import useSWR from "swr";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { WorkerDayEntry, WorkerRow } from "@/lib/types";
import { money, todayKsa } from "@/lib/format";
import { Alert, Card, CardHeader, Empty, Select, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

const num = (v: string | null | undefined) => (v == null || v === "" ? 0 : Number(v));
const small = "inline-flex items-center gap-1 rounded-md border border-line bg-white px-2 py-1 text-xs font-medium hover:bg-canvas disabled:opacity-40";
const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
const WEEKDAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

type Status = "absent" | "full" | "half";

/** The attendance status a stored work amount represents, given the worker's daily wage. */
function statusOf(work: string | null, rate: number): Status {
  const w = num(work);
  if (w <= 0) return "absent";
  if (rate <= 0) return "full";
  return w <= rate * 0.75 ? "half" : "full"; // below three-quarters of a day's wage reads as a half day
}

/**
 * A month calendar for one daily-wage worker. Tapping a day cycles Absent → Full day → Half day; each tap saves
 * one attendance record. The worker's month cost is the sum of the days marked present, so it flows into the
 * ledger, the statement and Accounts automatically. Any amount paid stays on the day and is left untouched.
 */
export function DailyAttendance({ workers, onSaved }: { workers: WorkerRow[]; onSaved: () => void }) {
  const t = useT();
  const daily = useMemo(() => workers.filter((w) => w.daily), [workers]);
  const [workerId, setWorkerId] = useState<number | null>(daily[0]?.id ?? null);
  const [ym, setYm] = useState(todayKsa().slice(0, 7)); // yyyy-mm
  const year = Number(ym.slice(0, 4)), month = Number(ym.slice(5, 7));

  const worker = daily.find((w) => w.id === workerId) ?? daily[0] ?? null;
  const rate = num(worker?.dailyRate);

  const { data, error, mutate } = useSWR<WorkerDayEntry[]>(
    worker ? `/workers/days?year=${year}&month=${month}` : null, fetcher, { keepPreviousData: true });

  const [saving, setSaving] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);

  const byDate = useMemo(() => {
    const m = new Map<string, WorkerDayEntry>();
    for (const e of data ?? []) if (e.workerId === worker?.id) m.set(e.date, e);
    return m;
  }, [data, worker?.id]);

  function stepMonth(by: number) {
    const d = new Date(`${ym}-01T12:00:00Z`);
    d.setUTCMonth(d.getUTCMonth() + by);
    setYm(d.toISOString().slice(0, 7));
  }

  async function setStatus(dateIso: string, status: Status) {
    if (!worker) return;
    const entry = byDate.get(dateIso);
    const work = status === "absent" ? null : status === "half" ? rate / 2 : rate;
    setSaving(dateIso);
    setSaveError(null);
    try {
      // Keep whatever was paid on this day; only the attendance (work value) changes.
      await api("PUT", `/workers/${worker.id}/days/${dateIso}`, {
        work, paid: entry?.paid ?? null, notes: entry?.notes ?? "", paidMethod: entry?.method ?? null,
      });
      await mutate();
      onSaved();
    } catch (e) {
      setSaveError(e instanceof ApiError ? e.message : t("Failed"));
    } finally {
      setSaving(null);
    }
  }

  // Build the weeks grid: leading blanks for the first weekday, then each day of the month.
  const first = new Date(`${ym}-01T12:00:00Z`);
  const daysInMonth = new Date(Date.UTC(year, month, 0)).getUTCDate();
  const lead = first.getUTCDay();
  const cells: (number | null)[] = [...Array(lead).fill(null), ...Array.from({ length: daysInMonth }, (_, i) => i + 1)];
  while (cells.length % 7 !== 0) cells.push(null);

  // Totals for this worker this month.
  let fullDays = 0, halfDays = 0, earned = 0, paid = 0;
  for (const e of byDate.values()) {
    const s = statusOf(e.work, rate);
    if (s === "full") fullDays++;
    else if (s === "half") halfDays++;
    earned += num(e.work);
    paid += num(e.paid);
  }
  const present = fullDays + halfDays * 0.5;
  const balance = earned - paid;

  const label = (iso: string) => {
    const s = statusOf(byDate.get(iso)?.work ?? null, rate);
    return s === "full" ? t("Present") : s === "half" ? t("Half day") : t("Absent");
  };
  const next: Record<Status, Status> = { absent: "full", full: "half", half: "absent" };

  if (daily.length === 0) return null;

  return (
    <Card className="mt-6">
      <CardHeader
        title={t("Daily-wage attendance")}
        subtitle={t("Tap a day to mark it: Absent → Present → Half day. The days marked here add up to the worker's cost for the month.")}
        action={
          <div className="flex flex-wrap items-center gap-2">
            <div className="w-48">
              <Select value={workerId ?? ""} onChange={(e) => setWorkerId(Number(e.target.value))} aria-label={t("Worker")}>
                {daily.map((w) => <option key={w.id} value={w.id}>{w.name}{w.active ? "" : ` (${t("Inactive")})`}</option>)}
              </Select>
            </div>
            <div className="flex items-center gap-1">
              <button className={small} onClick={() => stepMonth(-1)} aria-label={t("Previous month")}>
                <ChevronLeft className="size-3.5 rtl:rotate-180" aria-hidden />
              </button>
              <span className="min-w-28 text-center text-sm font-medium">{t(MONTHS[month - 1])} {year}</span>
              <button className={small} onClick={() => stepMonth(1)} aria-label={t("Next month")}>
                <ChevronRight className="size-3.5 rtl:rotate-180" aria-hidden />
              </button>
            </div>
          </div>
        }
      />

      {!worker ? <Empty title={t("No daily-wage workers")} /> : (
        <div className="p-4">
          {rate <= 0 && (
            <div className="mb-3"><Alert tone="bad">{t("Set this worker's daily wage first (edit the worker below); without it a day can't be valued.")}</Alert></div>
          )}
          {saveError && <div className="mb-3"><Alert tone="bad">{saveError}</Alert></div>}
          {error && <div className="mb-3"><Alert tone="bad">{error.message}</Alert></div>}
          {!data && !error && <Spinner />}

          {data && (
            <>
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
                  const tone = status === "full" ? "border-ok/40 bg-ok/10 text-ok"
                    : status === "half" ? "border-brand/40 bg-brand/10 text-brand"
                    : "border-line bg-white text-muted hover:bg-canvas";
                  return (
                    <button
                      key={iso}
                      disabled={rate <= 0 || saving === iso}
                      onClick={() => setStatus(iso, next[status])}
                      title={`${iso} · ${label(iso)}`}
                      aria-label={`${iso} · ${label(iso)}`}
                      className={`flex min-h-16 flex-col items-stretch rounded-lg border p-1.5 text-start transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${tone} ${isToday ? "ring-2 ring-brand/30" : ""}`}
                    >
                      <span className="text-xs font-semibold">{dnum}</span>
                      <span className="mt-auto text-[10px] font-medium">
                        {saving === iso ? t("Saving…") : status === "full" ? t("Present") : status === "half" ? t("Half") : ""}
                      </span>
                      {entry && num(entry.paid) > 0 && <span className="text-[10px] text-ok">{money(entry.paid)}</span>}
                    </button>
                  );
                })}
              </div>

              <div className="mt-4 grid gap-3 sm:grid-cols-4">
                <Stat label={t("Days present")} value={`${present}`} hint={halfDays > 0 ? t("{full} full + {half} half", { full: fullDays, half: halfDays }) : t("{n} days", { n: fullDays })} />
                <Stat label={t("Earned")} value={money(earned)} hint={rate > 0 ? t("at {rate}/day", { rate: money(rate) }) : ""} />
                <Stat label={t("Paid")} value={money(paid)} tone="text-ok" />
                <Stat label={t("Balance")} value={money(balance)} tone={balance > 0 ? "text-bad" : "text-ok"} hint={balance > 0 ? t("still to pay") : balance < 0 ? t("paid ahead") : ""} />
              </div>
              <p className="mt-3 text-xs text-muted">
                {t("Record what you pay this worker in the Monthly entry above; the balance here is the days' value minus what has been paid.")}
              </p>
            </>
          )}
        </div>
      )}
    </Card>
  );
}

function Stat({ label, value, hint, tone }: { label: string; value: string; hint?: string; tone?: string }) {
  return (
    <div className="rounded-lg border border-line bg-canvas/40 p-3">
      <p className="text-xs font-medium text-muted">{label}</p>
      <p className={`tabular mt-1 text-lg font-semibold ${tone ?? ""}`}>{value}</p>
      {hint && <p className="mt-0.5 text-[11px] text-muted">{hint}</p>}
    </div>
  );
}
