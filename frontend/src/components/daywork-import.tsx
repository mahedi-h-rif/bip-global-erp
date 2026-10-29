"use client";

import { useState } from "react";
import { ApiError, uploadFile } from "@/lib/api";
import type { WorkerRow } from "@/lib/types";
import { money } from "@/lib/format";
import { Alert, Button, CardHeader, Select } from "@/components/ui";
import { useT } from "@/lib/i18n";

type Line = {
  name: string; days: string | null; rate: string | null; work: string | null; paid: string | null;
  suggestedWorkerId: number | null; currentWork: string | null; currentPaid: string | null;
};
export type DayPreview = { sheets: { sheet: string; year: number; month: number; lines: Line[] }[]; workers: WorkerRow[] };

const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
const cell = (v: string | null) => (v == null ? "—" : money(v));

/**
 * Confirms how the names on a day-work workbook map to saved workers, shows what each month will become,
 * then saves it. -1 skips a name, 0 adds it as a new worker.
 */
export function DayWorkImport({ file, preview, onDone, onCancel }: {
  file: File;
  preview: DayPreview;
  onDone: (text: string) => void;
  onCancel: () => void;
}) {
  const t = useT();
  const names = [...new Set(preview.sheets.flatMap((s) => s.lines.map((l) => l.name)))];
  const [map, setMap] = useState<Record<string, number>>(() => Object.fromEntries(names.map((n) => {
    const l = preview.sheets.flatMap((s) => s.lines).find((x) => x.name === n);
    return [n, l?.suggestedWorkerId ?? 0];
  })));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function apply() {
    setBusy(true);
    setError(null);
    try {
      const r = await uploadFile<{ sheets: number; months: number; created: number; skipped: number }>(
        `/workers/import-days/apply?map=${encodeURIComponent(JSON.stringify(map))}`, file);
      onDone(t("Saved {months} worker months from {sheets} sheets ({created} new workers, {skipped} skipped).", r));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t("Failed"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="border-b border-line bg-canvas/50">
      <CardHeader title={t("Import day work: {file}", { file: file.name })}
                  subtitle={t("Check which worker each name on the sheets is. Work value = days × daily rate; it replaces that month's figures.")} />
      <div className="grid gap-x-6 gap-y-2 p-4 sm:grid-cols-2 lg:grid-cols-3">
        {names.map((n) => (
          <label key={n} className="flex items-center gap-2 text-sm">
            <span className="w-32 shrink-0 truncate font-medium" title={n}>{n}</span>
            <Select value={map[n]} onChange={(e) => setMap({ ...map, [n]: Number(e.target.value) })}
                    className={map[n] === 0 ? "border-warn" : map[n] < 0 ? "opacity-60" : ""}>
              {preview.workers.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
              <option value={0}>{t("+ Add as new worker")}</option>
              <option value={-1}>{t("Skip this name")}</option>
            </Select>
          </label>
        ))}
      </div>
      {preview.sheets.map((s) => (
        <div key={s.sheet} className="overflow-x-auto px-4 pb-4">
          <p className="mb-1 text-xs font-semibold text-muted">{t(MONTHS[s.month - 1])} {s.year} · {t("sheet “{name}”", { name: s.sheet })}</p>
          <table className="w-full min-w-[640px] rounded-lg border border-line bg-white text-sm">
            <thead>
              <tr className="border-b border-line text-xs text-muted">
                <th className="px-3 py-2 text-start font-medium">{t("Name on sheet")}</th>
                <th className="px-3 py-2 text-end font-medium">{t("Days × rate")}</th>
                <th className="px-3 py-2 text-end font-medium">{t("New work value")}</th>
                <th className="px-3 py-2 text-end font-medium">{t("New paid")}</th>
                <th className="px-3 py-2 text-end font-medium">{t("Saved now")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {s.lines.map((l) => {
                const changed = map[l.name] > 0 && (Number(l.work ?? 0) !== Number(l.currentWork ?? 0) || Number(l.paid ?? 0) !== Number(l.currentPaid ?? 0))
                  && (l.currentWork != null || l.currentPaid != null);
                return (
                  <tr key={l.name} className={map[l.name] < 0 ? "opacity-50" : ""}>
                    <td className="px-3 py-2">{l.name}</td>
                    <td className="tabular px-3 py-2 text-end text-muted">{l.days ?? "—"} × {l.rate ?? "—"}</td>
                    <td className="tabular px-3 py-2 text-end">{cell(l.work)}</td>
                    <td className="tabular px-3 py-2 text-end text-ok">{cell(l.paid)}</td>
                    <td className={`tabular px-3 py-2 text-end text-xs ${changed ? "font-semibold text-warn" : "text-muted"}`}>
                      {l.currentWork == null && l.currentPaid == null ? "—" : `${cell(l.currentWork)} / ${cell(l.currentPaid)}`}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ))}
      <div className="flex flex-wrap items-center gap-2 px-4 pb-4">
        <Button onClick={apply} loading={busy}>{t("Save day work")}</Button>
        <Button variant="secondary" onClick={onCancel}>{t("Cancel")}</Button>
        <span className="text-xs text-muted">{t("Figures in orange differ from what is saved now and will replace it.")}</span>
      </div>
      {error && <div className="px-4 pb-4"><Alert tone="bad">{error}</Alert></div>}
    </div>
  );
}
