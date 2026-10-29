"use client";

import { useState } from "react";
import type { WorkerTotals } from "@/lib/types";
import { money } from "@/lib/format";
import { useT } from "@/lib/i18n";

// Validated categorical pair (dataviz validator, light surface): blue = work, aqua = paid.
export const WORK_COLOR = "#2a78d6";
export const PAID_COLOR = "#1baf7a";

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** A round axis maximum and its gridline step. */
function scale(max: number) {
  if (max <= 0) return { top: 1000, step: 250 };
  const raw = max / 4;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= raw)!;
  return { top: Math.ceil(max / step) * step, step };
}

const short = (v: number) => (v >= 1000 ? `${(v / 1000).toFixed(v % 1000 === 0 ? 0 : 1)}k` : String(v));

/** Bar path with 4px rounded top corners, anchored on the baseline. */
function bar(x: number, y: number, w: number, h: number) {
  if (h <= 0) return "";
  const r = Math.min(4, w / 2, h);
  return `M${x},${y + h}V${y + r}Q${x},${y} ${x + r},${y}H${x + w - r}Q${x + w},${y} ${x + w},${y + r}V${y + h}Z`;
}

/** Work value and amount paid for each month of the year, side by side. */
export function MonthlyChart({ months, labels, positiveIsGood = false }: {
  months: WorkerTotals[];
  /** Series names; default: work value / paid / balance. `diff` is work − paid. */
  labels?: { a: string; b: string; diff: string; title: string };
  /** Colour a positive difference green (e.g. net profit) instead of red (money still owed). */
  positiveIsGood?: boolean;
}) {
  const t = useT();
  const L = labels ?? { a: t("Work value"), b: t("Paid"), diff: t("Balance"), title: t("Work value and paid by month") };
  const [hover, setHover] = useState<number | null>(null);
  const W = 720, H = 240, left = 44, right = 8, top = 12, bottom = 26;
  const plotW = W - left - right, plotH = H - top - bottom;
  const values = months.map((m) => ({ work: Number(m.work), paid: Number(m.paid), due: Number(m.due) }));
  const { top: max, step } = scale(Math.max(0, ...values.flatMap((v) => [v.work, v.paid])));
  const slot = plotW / 12;
  const bw = Math.min(18, (slot - 14) / 2);
  const y = (v: number) => top + plotH - (v / max) * plotH;
  const ticks = Array.from({ length: Math.round(max / step) + 1 }, (_, i) => i * step);
  const h = hover == null ? null : values[hover];

  return (
    <div className="relative">
      <div className="mb-2 flex flex-wrap items-center gap-4 text-xs text-muted">
        <span className="inline-flex items-center gap-1.5"><span className="size-2.5 rounded-sm" style={{ background: WORK_COLOR }} />{L.a}</span>
        <span className="inline-flex items-center gap-1.5"><span className="size-2.5 rounded-sm" style={{ background: PAID_COLOR }} />{L.b}</span>
      </div>
      <div className="overflow-x-auto">
      <svg viewBox={`0 0 ${W} ${H}`} className="h-auto w-full min-w-[560px]" role="img" aria-label={L.title} onMouseLeave={() => setHover(null)}>
        {ticks.map((v) => (
          <g key={v}>
            <line x1={left} x2={W - right} y1={y(v)} y2={y(v)} stroke="#e3e7ef" strokeWidth={1} />
            <text x={left - 6} y={y(v)} dy="0.32em" textAnchor="end" fontSize="10" fill="#5a6275" className="tabular">{short(v)}</text>
          </g>
        ))}
        {values.map((v, i) => {
          const cx = left + slot * i + slot / 2;
          return (
            <g key={i}>
              {hover === i && <rect x={left + slot * i + 2} y={top} width={slot - 4} height={plotH} rx={4} fill="#f5f7fb" />}
              <path d={bar(cx - bw - 1, y(v.work), bw, top + plotH - y(v.work))} fill={WORK_COLOR} />
              <path d={bar(cx + 1, y(v.paid), bw, top + plotH - y(v.paid))} fill={PAID_COLOR} />
              <text x={cx} y={H - 8} textAnchor="middle" fontSize="10.5" fill={hover === i ? "#1b2233" : "#5a6275"}>{t(MONTHS[i])}</text>
              {/* Hit area: the whole month column, larger than the bars. */}
              <rect x={left + slot * i} y={top} width={slot} height={plotH + bottom} fill="transparent"
                    onMouseEnter={() => setHover(i)} onFocus={() => setHover(i)} tabIndex={0}
                    aria-label={`${t(MONTHS[i])}: ${L.a} ${money(v.work)}, ${L.b} ${money(v.paid)}`} />
            </g>
          );
        })}
        <line x1={left} x2={W - right} y1={top + plotH} y2={top + plotH} stroke="#c9d0dc" strokeWidth={1} />
      </svg>
      </div>
      {h && hover != null && (
        <div
          className="pointer-events-none absolute top-8 z-10 w-48 rounded-lg border border-line bg-white p-3 text-xs shadow-lg"
          style={{ left: `clamp(0px, calc(${((left + slot * hover + slot / 2) / W) * 100}% - 6rem), calc(100% - 12rem))` }}
        >
          <p className="mb-1.5 font-semibold text-ink">{t(MONTHS[hover])}</p>
          <p className="flex justify-between gap-2"><span className="inline-flex items-center gap-1.5 text-muted"><span className="size-2 rounded-sm" style={{ background: WORK_COLOR }} />{L.a}</span><span className="tabular text-ink">{money(h.work)}</span></p>
          <p className="flex justify-between gap-2"><span className="inline-flex items-center gap-1.5 text-muted"><span className="size-2 rounded-sm" style={{ background: PAID_COLOR }} />{L.b}</span><span className="tabular text-ink">{money(h.paid)}</span></p>
          <p className="mt-1 flex justify-between gap-2 border-t border-line pt-1"><span className="text-muted">{L.diff}</span><span className={`tabular font-semibold ${h.due === 0 ? "text-ink" : (h.due > 0) !== positiveIsGood ? "text-bad" : "text-ok"}`}>{money(h.due)}</span></p>
        </div>
      )}
    </div>
  );
}

/** Outstanding balance per worker, largest first; workers who are settled are left out. */
export function BalanceBars({ rows }: { rows: { name: string; due: number }[] }) {
  const t = useT();
  const owed = rows.filter((r) => r.due !== 0).sort((a, b) => b.due - a.due);
  if (owed.length === 0) return <p className="py-8 text-center text-sm text-muted">{t("Every worker is settled")}</p>;
  const max = Math.max(...owed.map((r) => Math.abs(r.due)));
  return (
    <ul className="space-y-2.5">
      {owed.map((r) => (
        <li key={r.name} className="text-sm">
          <div className="mb-1 flex justify-between gap-2">
            <span className="truncate">{r.name}</span>
            <span className={`tabular shrink-0 font-semibold ${r.due > 0 ? "text-bad" : "text-ok"}`}>
              {r.due < 0 ? `${money(-r.due)} ${t("advance")}` : money(r.due)}
            </span>
          </div>
          <div className="h-2 overflow-hidden rounded-full bg-canvas" aria-hidden>
            <div className={`h-full rounded-full ${r.due > 0 ? "bg-bad/70" : "bg-ok/70"}`} style={{ width: `${Math.max(3, (Math.abs(r.due) / max) * 100)}%` }} />
          </div>
        </li>
      ))}
    </ul>
  );
}
