"use client";

import { useState, type FormEvent } from "react";
import useSWR from "swr";
import { CalendarDays, Pencil, Plus, Trash2 } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { WorkerLeave, WorkerLeaveType, WorkerRow } from "@/lib/types";
import { date, money, todayKsa } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Empty, Field, Input, Select, Spinner, Textarea } from "@/components/ui";
import { useT } from "@/lib/i18n";

const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
const LEAVE_TYPES: WorkerLeaveType[] = ["ANNUAL", "SICK", "UNPAID", "OTHER"];
const typeLabel: Record<WorkerLeaveType, string> = {
  ANNUAL: "Annual leave", SICK: "Sick leave", UNPAID: "Unpaid leave", OTHER: "Other",
};
const num = (v: string | null | undefined) => (v == null ? 0 : Number(v) || 0);

type Draft = { workerId: string; startDate: string; endDate: string; type: WorkerLeaveType; deduction: string; notes: string };

function LeaveForm({ workers, initial, lockWorkerId, onDone, onCancel }: {
  workers: WorkerRow[]; initial?: WorkerLeave; lockWorkerId?: number; onDone: () => void; onCancel: () => void;
}) {
  const t = useT();
  const [d, setD] = useState<Draft>(() => ({
    workerId: String(initial?.workerId ?? lockWorkerId ?? workers[0]?.id ?? ""),
    startDate: initial?.startDate ?? todayKsa(),
    endDate: initial?.endDate ?? todayKsa(),
    type: initial?.type ?? "ANNUAL",
    deduction: initial?.deduction ?? "",
    notes: initial?.notes ?? "",
  }));
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (k: keyof Draft) => (e: { target: { value: string } }) => setD({ ...d, [k]: e.target.value });

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const body = {
        startDate: d.startDate, endDate: d.endDate, type: d.type,
        deduction: d.deduction.trim() === "" ? null : Number(d.deduction), notes: d.notes.trim() || null,
      };
      if (initial) await api("PUT", `/workers/leaves/${initial.id}`, body);
      else await api("POST", `/workers/${d.workerId}/leaves`, body);
      onDone();
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError(0, "network", t("Could not reach the server")));
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="grid gap-3 border-b border-line bg-canvas/60 p-4 sm:grid-cols-2 lg:grid-cols-6">
      {error && !error.fields && <div className="sm:col-span-2 lg:col-span-6"><Alert tone="bad">{error.message}</Alert></div>}
      <Field label={t("Worker")} className="lg:col-span-2">
        <Select value={d.workerId} onChange={set("workerId")} disabled={!!initial || lockWorkerId != null} aria-label={t("Worker")}>
          {workers.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
        </Select>
      </Field>
      <Field label={t("Leave type")}>
        <Select value={d.type} onChange={set("type")}>
          {LEAVE_TYPES.map((lt) => <option key={lt} value={lt}>{t(typeLabel[lt])}</option>)}
        </Select>
      </Field>
      <Field label={t("From")}><Input type="date" required value={d.startDate} onChange={set("startDate")} /></Field>
      <Field label={t("To")} error={error?.fields?.endDate}><Input type="date" required value={d.endDate} min={d.startDate} onChange={set("endDate")} /></Field>
      <Field label={t("Salary deduction (optional)")} hint={t("Leave blank for a paid leave with no salary cut.")}>
        <Input type="number" min="0" step="0.01" inputMode="decimal" className="text-end" placeholder="—" value={d.deduction} onChange={set("deduction")} />
      </Field>
      <Field label={t("Notes")} className="sm:col-span-2 lg:col-span-5"><Textarea rows={1} value={d.notes} onChange={set("notes")} dir="auto" /></Field>
      <div className="flex items-end gap-2">
        <Button type="submit" loading={busy}>{initial ? t("Save") : t("Add leave")}</Button>
        <Button type="button" variant="secondary" onClick={onCancel}>{t("Cancel")}</Button>
      </div>
    </form>
  );
}

function LeaveRow({ l, showWorker, canWrite, onEdit, onChanged }: {
  l: WorkerLeave; showWorker: boolean; canWrite: boolean; onEdit: () => void; onChanged: () => void;
}) {
  const t = useT();
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function remove() {
    setBusy(true);
    setError(null);
    try {
      await api("DELETE", `/workers/leaves/${l.id}`);
      onChanged();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t("Could not reach the server"));
      setBusy(false);
    }
  }

  return (
    <tr className="align-top hover:bg-canvas">
      {showWorker && <td className="px-4 py-2.5 font-medium" dir="auto">{l.workerName ?? "—"}</td>}
      <td className="whitespace-nowrap px-4 py-2.5">
        {date(l.startDate)}{l.endDate !== l.startDate && <> – {date(l.endDate)}</>}
      </td>
      <td className="tabular px-4 py-2.5 text-end text-muted">{t("{n} days", { n: l.days })}</td>
      <td className="px-4 py-2.5">
        <span className="whitespace-nowrap rounded-full bg-canvas px-2 py-0.5 text-[11px] font-medium text-muted">{t(typeLabel[l.type])}</span>
        {l.notes && <p className="mt-1 text-xs text-muted" dir="auto">{l.notes}</p>}
      </td>
      <td className={`tabular whitespace-nowrap px-4 py-2.5 text-end ${num(l.deduction) > 0 ? "text-bad" : "text-muted"}`}>
        {num(l.deduction) > 0 ? money(l.deduction) : "—"}
      </td>
      <td className="px-2 py-2.5">
        {canWrite && (
          <div className="flex justify-end gap-1.5">
            <button type="button" className="rounded border border-line p-1.5 text-muted hover:text-ink" onClick={onEdit} aria-label={t("Edit")}><Pencil className="size-3.5" aria-hidden /></button>
            {confirm
              ? <button type="button" className="rounded border border-bad/40 px-2 py-1 text-xs text-bad" disabled={busy} onClick={remove}>{t("Confirm delete")}</button>
              : <button type="button" className="rounded border border-line p-1.5 text-muted hover:text-bad" onClick={() => setConfirm(true)} aria-label={t("Delete")}><Trash2 className="size-3.5" aria-hidden /></button>}
          </div>
        )}
        {error && <p className="mt-1 text-end text-xs text-bad">{error}</p>}
      </td>
    </tr>
  );
}

/** Leave entry and history for workers: by month (all workers) or by individual worker for the year. */
export function WorkerLeaves({ year, workers }: { year: number; workers: WorkerRow[] }) {
  const t = useT();
  const { data: me } = useSWR<{ role: string }>("/auth/me", fetcher);
  const canWrite = me?.role === "ADMIN" || me?.role === "ACCOUNTANT";
  const [scope, setScope] = useState<"month" | "worker">("month");
  const [month, setMonth] = useState(Number(todayKsa().slice(5, 7)));
  const [workerId, setWorkerId] = useState<number | "">(workers[0]?.id ?? "");
  const [editing, setEditing] = useState<number | "new" | null>(null);

  const key = scope === "month"
    ? `/workers/leaves?year=${year}&month=${month}`
    : workerId === "" ? null : `/workers/leaves?year=${year}&workerId=${workerId}`;
  const { data, error, mutate } = useSWR<WorkerLeave[]>(key, fetcher, { keepPreviousData: true });
  const done = () => { setEditing(null); void mutate(); };

  const list = data ?? [];
  const totalDays = list.reduce((s, l) => s + l.days, 0);
  const totalDeduction = list.reduce((s, l) => s + num(l.deduction), 0);
  const showWorker = scope === "month";

  return (
    <Card className="mt-6" id="leaves">
      <CardHeader
        title={t("Leave")}
        subtitle={t("Record worker leave and view the history. A salary deduction is optional.")}
        action={canWrite && workers.length > 0 && editing == null && (
          <Button variant="secondary" onClick={() => setEditing("new")}><Plus className="size-4" aria-hidden />{t("Add leave")}</Button>
        )}
      />

      <div className="flex flex-wrap items-center gap-3 border-b border-line p-4">
        <div className="inline-flex rounded-lg border border-line bg-white p-0.5" role="tablist">
          {(["month", "worker"] as const).map((v) => (
            <button key={v} type="button" role="tab" aria-selected={scope === v} onClick={() => setScope(v)}
                    className={`rounded-md px-3 py-1.5 text-sm font-medium ${scope === v ? "bg-brand text-white" : "text-muted hover:text-ink"}`}>
              {v === "month" ? t("By month") : t("By worker")}
            </button>
          ))}
        </div>
        {scope === "month" ? (
          <div className="w-40">
            <Select value={month} onChange={(e) => setMonth(Number(e.target.value))} aria-label={t("Month")}>
              {MONTHS.map((m, i) => <option key={m} value={i + 1}>{t(m)}</option>)}
            </Select>
          </div>
        ) : (
          <div className="w-56">
            <Select value={workerId} onChange={(e) => setWorkerId(e.target.value === "" ? "" : Number(e.target.value))} aria-label={t("Worker")}>
              {workers.length === 0 && <option value="">{t("No workers yet")}</option>}
              {workers.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
            </Select>
          </div>
        )}
        <span className="ms-auto text-xs text-muted">
          {t("{n} leaves · {days} days", { n: list.length, days: totalDays })}
          {totalDeduction > 0 && <> · {t("deductions {amount}", { amount: money(totalDeduction) })}</>}
        </span>
      </div>

      {editing === "new" && <LeaveForm workers={workers} lockWorkerId={scope === "worker" && workerId !== "" ? workerId : undefined} onDone={done} onCancel={() => setEditing(null)} />}

      {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
      {!data && !error && <Spinner />}
      {data && list.length === 0 && editing !== "new" && (
        <Empty title={t("No leave recorded")}>{canWrite && workers.length > 0 && t("Add a leave to start the history.")}</Empty>
      )}
      {data && list.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[640px] text-sm">
            <thead>
              <tr className="border-b border-line text-xs text-muted">
                {showWorker && <th className="px-4 py-2.5 text-start font-medium">{t("Worker")}</th>}
                <th className="px-4 py-2.5 text-start font-medium">{t("Dates")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Days")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Type")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Deduction")}</th>
                <th className="px-2 py-2.5"><span className="sr-only">{t("Actions")}</span></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {list.map((l) => editing === l.id ? (
                <tr key={l.id}><td colSpan={showWorker ? 6 : 5} className="p-0">
                  <LeaveForm workers={workers} initial={l} onDone={done} onCancel={() => setEditing(null)} />
                </td></tr>
              ) : (
                <LeaveRow key={l.id} l={l} showWorker={showWorker} canWrite={canWrite} onEdit={() => setEditing(l.id)} onChanged={() => void mutate()} />
              ))}
            </tbody>
          </table>
        </div>
      )}

      <p className="flex items-center gap-1.5 border-t border-line px-4 py-3 text-xs text-muted">
        <CalendarDays className="size-3.5" aria-hidden />
        {t("A salary deduction here is recorded with the leave; it is not subtracted from the monthly ledger automatically.")}
      </p>
    </Card>
  );
}
