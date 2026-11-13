"use client";

import { useMemo, useState, type ReactNode } from "react";
import { Search, X } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { useT } from "@/lib/i18n";

/** A dropdown filter; its options are the distinct values found in the list. */
export interface ListFilter<T> {
  label: string;
  get: (row: T) => string | null | undefined;
  /** How a value is shown in the dropdown (e.g. translated status). */
  format?: (value: string) => string;
}

/** Every text and number in a row (and in its lines), lower-cased, for the search box. */
function haystack(value: unknown, depth = 0): string {
  if (value == null || depth > 3) return "";
  if (typeof value === "string" || typeof value === "number") return String(value).toLowerCase();
  if (Array.isArray(value)) return value.map((v) => haystack(v, depth + 1)).join(" ");
  if (typeof value === "object") return Object.values(value as Record<string, unknown>).map((v) => haystack(v, depth + 1)).join(" ");
  return "";
}

/**
 * Search, filters and a date range for any list loaded in the page: type anything shown in a row (number, name,
 * item, amount…) or pick a filter. Returns the toolbar and the rows to show.
 */
export function useListTools<T>(rows: T[] | undefined, opts: { filters?: ListFilter<T>[]; date?: (row: T) => string | null | undefined; placeholder?: string } = {}) {
  const t = useT();
  const [q, setQ] = useState("");
  const [picked, setPicked] = useState<Record<number, string>>({});
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const filters = opts.filters ?? [];

  const options = useMemo(() => filters.map((f) =>
    [...new Set((rows ?? []).map((r) => f.get(r)).filter((v): v is string => v != null && v !== ""))].sort((a, b) => a.localeCompare(b))),
  // eslint-disable-next-line react-hooks/exhaustive-deps
  [rows, filters.length]);

  const index = useMemo(() => new Map((rows ?? []).map((r) => [r, haystack(r)])), [rows]);

  const shown = useMemo(() => {
    if (!rows) return rows;
    const words = q.trim().toLowerCase().split(/\s+/).filter(Boolean);
    return rows.filter((r) => {
      if (words.length && !words.every((w) => index.get(r)!.includes(w))) return false;
      for (const [i, v] of Object.entries(picked)) if (v && filters[Number(i)]?.get(r) !== v) return false;
      if (opts.date && (from || to)) {
        const d = (opts.date(r) ?? "").slice(0, 10);
        if (!d || (from && d < from) || (to && d > to)) return false;
      }
      return true;
    });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, q, picked, from, to, index]);

  const active = q !== "" || Object.values(picked).some(Boolean) || from !== "" || to !== "";
  const toolbar: ReactNode = (
    <div className="flex flex-wrap items-center gap-2 border-b border-line p-3">
      <div className="relative min-w-52 flex-1">
        <Search className="pointer-events-none absolute start-2.5 top-1/2 size-4 -translate-y-1/2 text-muted" aria-hidden />
        <Input className="ps-8" value={q} onChange={(e) => setQ(e.target.value)} placeholder={opts.placeholder ?? t("Search anything in this list…")} aria-label={t("Search")} />
      </div>
      {filters.map((f, i) => options[i]?.length > 1 && (
        <Select key={f.label} className="w-auto min-w-36" value={picked[i] ?? ""} onChange={(e) => setPicked({ ...picked, [i]: e.target.value })} aria-label={t(f.label)}>
          <option value="">{t("All")} · {t(f.label)}</option>
          {options[i].map((v) => <option key={v} value={v}>{f.format ? f.format(v) : v}</option>)}
        </Select>
      ))}
      {opts.date && (
        <span className="inline-flex items-center gap-1 text-xs text-muted">
          <Input type="date" className="w-36" value={from} onChange={(e) => setFrom(e.target.value)} aria-label={t("From")} />
          –
          <Input type="date" className="w-36" value={to} onChange={(e) => setTo(e.target.value)} aria-label={t("To")} />
        </span>
      )}
      {active && (
        <button type="button" className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs text-muted hover:bg-canvas hover:text-ink"
          onClick={() => { setQ(""); setPicked({}); setFrom(""); setTo(""); }}>
          <X className="size-3.5" aria-hidden /> {t("Clear")}
        </button>
      )}
      {rows && <span className="ms-auto text-xs text-muted">{t("{n} of {total}", { n: String(shown?.length ?? 0), total: String(rows.length) })}</span>}
    </div>
  );
  return { toolbar, shown };
}

/** "PARTIALLY_RECEIVED" → "Partially received", for status filters. */
export function statusText(v: string) {
  const s = v.replace(/_/g, " ").toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}
