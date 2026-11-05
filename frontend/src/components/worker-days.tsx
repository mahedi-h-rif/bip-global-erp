"use client";

import { useState } from "react";
import useSWR from "swr";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { WorkerDayEntry, WorkerRow } from "@/lib/types";
import { date as fmtDate, money } from "@/lib/format";
import { Alert, Empty, Input, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

const num = (v: string | null | undefined) => (v == null || v === "" ? 0 : Number(v));
const tone = (v: number) => (v > 0 ? "text-bad" : v < 0 ? "text-ok" : "text-muted");
const small = "inline-flex items-center gap-1 rounded-md border border-line bg-white px-2 py-1 text-xs font-medium hover:bg-canvas disabled:opacity-40";

/** Moves a yyyy-mm-dd date by a number of days. */
export function shiftDate(iso: string, by: number) {
  const d = new Date(`${iso}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + by);
  return d.toISOString().slice(0, 10);
}

export function DayPicker({ value, onChange }: { value: string; onChange: (d: string) => void }) {
  const t = useT();
  return (
    <div className="flex items-center gap-1">
      <button className={small} onClick={() => onChange(shiftDate(value, -1))} aria-label={t("Previous day")}>
        <ChevronLeft className="size-3.5 rtl:rotate-180" aria-hidden />
      </button>
      <Input type="date" className="w-40" value={value} onChange={(e) => e.target.value && onChange(e.target.value)} aria-label={t("Date")} />
      <button className={small} onClick={() => onChange(shiftDate(value, 1))} aria-label={t("Next day")}>
        <ChevronRight className="size-3.5 rtl:rotate-180" aria-hidden />
      </button>
    </div>
  );
}

/** One worker's figures for a day; each change is saved when the field is left. */
function DayRow({ w, day, entry, onSaved }: { w: WorkerRow; day: string; entry?: WorkerDayEntry; onSaved: () => void }) {
  const t = useT();
  const [work, setWork] = useState(entry?.work ?? "");
  const [paid, setPaid] = useState(entry?.paid ?? "");
  const [notes, setNotes] = useState(entry?.notes ?? "");
  const [state, setState] = useState<"idle" | "saving" | "saved" | "error">("idle");
  const [error, setError] = useState<string | null>(null);

  async function save() {
    const same = (input: string, stored: string | null | undefined) => (input === "" ? stored == null : stored != null && Number(input) === Number(stored));
    if (same(work, entry?.work) && same(paid, entry?.paid) && notes.trim() === (entry?.notes ?? "")) return;
    setState("saving");
    setError(null);
    try {
      await api("PUT", `/workers/${w.id}/days/${day}`, { work: work === "" ? null : work, paid: paid === "" ? null : paid, notes });
      setState("saved");
      onSaved();
    } catch (e) {
      setState("error");
      setError(e instanceof ApiError ? e.message : t("Failed"));
    }
  }

  const due = work === "" && paid === "" ? null : num(work) - num(paid);
  return (
    <tr className={w.active ? "" : "opacity-60"}>
      <td className="px-4 py-2 text-muted tabular">{w.no}</td>
      <td className="px-4 py-2 font-medium">{w.name}</td>
      <td className="px-2 py-1.5">
        <Input type="number" min="0" step="0.01" inputMode="decimal" className="tabular w-28 text-end" placeholder="—"
               aria-label={`${w.name} · ${t("Work value")}`} value={work} onChange={(e) => setWork(e.target.value)} onBlur={save} />
      </td>
      <td className="px-2 py-1.5">
        <Input type="number" min="0" step="0.01" inputMode="decimal" className="tabular w-28 text-end" placeholder="—"
               aria-label={`${w.name} · ${t("Paid")}`} value={paid} onChange={(e) => setPaid(e.target.value)} onBlur={save} />
      </td>
      <td className="px-2 py-1.5">
        <Input className="min-w-40" placeholder={t("Note")} aria-label={`${w.name} · ${t("Note")}`} value={notes}
               onChange={(e) => setNotes(e.target.value)} onBlur={save} dir="auto" />
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

/**
 * Daily entry for every worker on one date, and below it the month's record day by day. Days add into the
 * month's totals, so the ledger above, the statement and Accounts include them.
 */
export function WorkerDaily({ workers, day, onDay, onSaved }: { workers: WorkerRow[]; day: string; onDay: (d: string) => void; onSaved: () => void }) {
  const t = useT();
  const year = Number(day.slice(0, 4)), month = Number(day.slice(5, 7));
  const { data, error, mutate } = useSWR<WorkerDayEntry[]>(`/workers/days?year=${year}&month=${month}`, fetcher, { keepPreviousData: true });
  const saved = () => { void mutate(); onSaved(); };

  const today = (data ?? []).filter((e) => e.date === day);
  const byWorker = new Map(today.map((e) => [e.workerId, e]));
  const dayWork = today.reduce((s, e) => s + num(e.work), 0), dayPaid = today.reduce((s, e) => s + num(e.paid), 0);

  const dates = new Map<string, WorkerDayEntry[]>();
  for (const e of data ?? []) dates.set(e.date, [...(dates.get(e.date) ?? []), e]);
  const monthWork = (data ?? []).reduce((s, e) => s + num(e.work), 0), monthPaid = (data ?? []).reduce((s, e) => s + num(e.paid), 0);

  return (
    <>
      {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
      {!data && !error && <Spinner />}
      {data && (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[860px] text-sm">
            <thead>
              <tr className="border-b border-line text-xs text-muted">
                <th className="w-12 px-4 py-2.5 text-start font-medium">{t("No.")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Worker")}</th>
                <th className="px-2 py-2.5 text-start font-medium">{t("Work value")}</th>
                <th className="px-2 py-2.5 text-start font-medium">{t("Paid")}</th>
                <th className="px-2 py-2.5 text-start font-medium">{t("Note")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Day balance")}</th>
                <th className="w-20 px-4 py-2.5"><span className="sr-only">{t("Status")}</span></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {workers.filter((w) => !w.daily && (w.active || byWorker.has(w.id))).map((w) => (
                <DayRow key={`${w.id}-${day}-${byWorker.get(w.id)?.id ?? "new"}`} w={w} day={day} entry={byWorker.get(w.id)} onSaved={saved} />
              ))}
            </tbody>
            <tfoot>
              <tr className="border-t-2 border-line bg-canvas/60 font-semibold">
                <td />
                <td className="px-4 py-2.5">{t("Day total")} · {fmtDate(day)}</td>
                <td className="tabular px-2 py-2.5">{money(dayWork)}</td>
                <td className="tabular px-2 py-2.5 text-ok">{money(dayPaid)}</td>
                <td />
                <td className={`tabular px-4 py-2.5 text-end ${tone(dayWork - dayPaid)}`}>{money(dayWork - dayPaid)}</td>
                <td />
              </tr>
            </tfoot>
          </table>
        </div>
      )}

      <div className="border-t border-line px-4 py-3">
        <h3 className="text-sm font-semibold">{t("Daily record for the month")}</h3>
        <p className="text-xs text-muted">{t("Every day with entries this month; open a date to see or change it.")}</p>
      </div>
      {data && dates.size === 0 ? <Empty title={t("No daily entries this month")} /> : data && (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[640px] text-sm">
            <thead>
              <tr className="border-b border-line text-xs text-muted">
                <th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Workers")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Work value")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Paid")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Balance")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {[...dates.entries()].map(([d, list]) => {
                const w = list.reduce((s, e) => s + num(e.work), 0), p = list.reduce((s, e) => s + num(e.paid), 0);
                return (
                  <tr key={d} className={`align-top hover:bg-canvas ${d === day ? "bg-brand/5" : ""}`}>
                    <td className="whitespace-nowrap px-4 py-2.5">
                      <button className="font-medium hover:underline" onClick={() => onDay(d)}>{fmtDate(d)}</button>
                    </td>
                    <td className="px-4 py-2.5 text-xs text-muted">
                      {list.map((e) => (
                        <p key={e.id}>
                          <span className="text-ink">{e.workerName}</span>{e.notes ? ` (${e.notes})` : ""}
                          {(e.createdBy || e.updatedBy) && (
                            <span className="ms-1 text-[11px]">
                              · {e.updatedBy ? t("edited by {name}", { name: e.updatedBy }) : t("by {name}", { name: e.createdBy ?? "" })}
                            </span>
                          )}
                        </p>
                      ))}
                    </td>
                    <td className="tabular whitespace-nowrap px-4 py-2.5 text-end">{money(w)}</td>
                    <td className="tabular whitespace-nowrap px-4 py-2.5 text-end text-ok">{money(p)}</td>
                    <td className={`tabular whitespace-nowrap px-4 py-2.5 text-end ${tone(w - p)}`}>{money(w - p)}</td>
                  </tr>
                );
              })}
            </tbody>
            <tfoot>
              <tr className="border-t-2 border-line bg-canvas/60 font-semibold">
                <td className="px-4 py-2.5" colSpan={2}>{t("Daily entries total")}</td>
                <td className="tabular whitespace-nowrap px-4 py-2.5 text-end">{money(monthWork)}</td>
                <td className="tabular whitespace-nowrap px-4 py-2.5 text-end text-ok">{money(monthPaid)}</td>
                <td className={`tabular whitespace-nowrap px-4 py-2.5 text-end ${tone(monthWork - monthPaid)}`}>{money(monthWork - monthPaid)}</td>
              </tr>
            </tfoot>
          </table>
        </div>
      )}
    </>
  );
}
