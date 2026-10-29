"use client";

import { useState } from "react";
import useSWR from "swr";
import { Banknote, CircleDollarSign, FileText, Hourglass, Search } from "lucide-react";
import { fetcher } from "@/lib/api";
import type { SalesReport, SalesRow } from "@/lib/types";
import { date, money, todayKsa } from "@/lib/format";
import { Alert, Card, CardHeader, Empty, Field, Input, Select, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

type Preset = "today" | "week" | "month" | "lastMonth" | "year" | "custom";

const presets: { value: Preset; label: string }[] = [
  { value: "today", label: "Today" },
  { value: "week", label: "This week" },
  { value: "month", label: "This month" },
  { value: "lastMonth", label: "Last month" },
  { value: "year", label: "This year" },
  { value: "custom", label: "Custom / month" },
];

/** Time-of-day segments in Saudi time; [from, to) hours, "to" < "from" wraps past midnight. */
const segments: Record<string, { label: string; from?: number; to?: number }> = {
  all: { label: "All day" },
  morning: { label: "Morning (05–12)", from: 5, to: 12 },
  afternoon: { label: "Afternoon (12–17)", from: 12, to: 17 },
  evening: { label: "Evening (17–21)", from: 17, to: 21 },
  night: { label: "Night (21–05)", from: 21, to: 5 },
};

const iso = (d: Date) => d.toISOString().slice(0, 10);

function range(preset: Preset): { from: string; to: string } {
  const today = todayKsa();
  const t = new Date(`${today}T00:00:00Z`);
  const y = t.getUTCFullYear();
  const m = t.getUTCMonth();
  switch (preset) {
    case "today":
      return { from: today, to: today };
    case "week": {
      // Saudi working week starts on Sunday.
      const start = new Date(t);
      start.setUTCDate(t.getUTCDate() - t.getUTCDay());
      return { from: iso(start), to: today };
    }
    case "month":
      return { from: iso(new Date(Date.UTC(y, m, 1))), to: today };
    case "lastMonth":
      return { from: iso(new Date(Date.UTC(y, m - 1, 1))), to: iso(new Date(Date.UTC(y, m, 0))) };
    default:
      return { from: iso(new Date(Date.UTC(y, 0, 1))), to: today };
  }
}

function monthRange(ym: string) {
  const [y, m] = ym.split("-").map(Number);
  return { from: iso(new Date(Date.UTC(y, m - 1, 1))), to: iso(new Date(Date.UTC(y, m, 0))) };
}

function Bar({ row, max }: { row: SalesRow; max: number }) {
  const net = Number(row.net);
  const width = max > 0 ? Math.max(2, (net / max) * 100) : 0;
  const paidShare = net > 0 ? (Number(row.collected) / net) * 100 : 0;
  return (
    <div className="h-2 w-full overflow-hidden rounded-full bg-canvas" aria-hidden>
      <div className="flex h-full" style={{ width: `${width}%` }}>
        <div className="h-full bg-ok" style={{ width: `${paidShare}%` }} />
        <div className="h-full flex-1 bg-bad/60" />
      </div>
    </div>
  );
}

function RowsTable({ rows, first }: { rows: SalesRow[]; first: string }) {
  const t = useT();
  const max = Math.max(0, ...rows.map((r) => Number(r.net)));
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[760px] text-sm">
        <thead>
          <tr className="border-b border-line text-xs text-muted">
            <th className="px-4 py-2.5 text-start font-medium">{first}</th>
            <th className="px-4 py-2.5 text-end font-medium">{t("Invoices")}</th>
            <th className="px-4 py-2.5 text-end font-medium">{t("Net sales")}</th>
            <th className="px-4 py-2.5 text-end font-medium">{t("VAT")}</th>
            <th className="px-4 py-2.5 text-end font-medium">{t("Collected")}</th>
            <th className="px-4 py-2.5 text-end font-medium">{t("Due")}</th>
            <th className="w-40 px-4 py-2.5 font-medium"><span className="sr-only">{t("Collected vs due")}</span></th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {rows.map((r) => (
            <tr key={r.key}>
              <td className="px-4 py-2.5 font-medium">{r.label}</td>
              <td className="tabular px-4 py-2.5 text-end">{r.invoices}</td>
              <td className="tabular px-4 py-2.5 text-end font-medium">{money(r.net)}</td>
              <td className="tabular px-4 py-2.5 text-end text-muted">{money(r.vat)}</td>
              <td className="tabular px-4 py-2.5 text-end text-ok">{money(r.collected)}</td>
              <td className={`tabular px-4 py-2.5 text-end ${Number(r.due) > 0 ? "text-bad" : "text-muted"}`}>{money(r.due)}</td>
              <td className="px-4 py-2.5"><Bar row={r} max={max} /></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Admin-only sales report: totals, month-wise, day-wise and customer-wise, with filters. */
export function SalesReportPanel() {
  const t = useT();
  const [preset, setPreset] = useState<Preset>("year");
  const [custom, setCustom] = useState(() => range("month"));
  const [month, setMonth] = useState("");
  const [segment, setSegment] = useState("all");
  const [kind, setKind] = useState("");
  const [q, setQ] = useState("");
  const [view, setView] = useState<"months" | "days" | "customers">("months");

  const { from, to } = preset === "custom" ? custom : range(preset);
  const query = new URLSearchParams({ from, to });
  const seg = segments[segment];
  if (seg.from != null && seg.to != null) {
    query.set("hourFrom", String(seg.from));
    query.set("hourTo", String(seg.to));
  }
  if (kind) query.set("kind", kind);
  if (q.trim()) query.set("q", q.trim());
  const { data, error } = useSWR<SalesReport>(`/reports/sales?${query}`, fetcher, { keepPreviousData: true });

  const tot = data?.totals;
  const stats = tot && [
    { label: t("Net sales"), value: money(tot.net), icon: CircleDollarSign, hint: t("Gross {gross} · notes {notes}", { gross: money(tot.gross), notes: money(tot.notes) }) },
    { label: t("Collected"), value: money(tot.collected), icon: Banknote, tone: "text-ok", hint: t("Received, without dues") },
    { label: t("Due"), value: money(tot.due), icon: Hourglass, tone: Number(tot.due) > 0 ? "text-bad" : "", hint: t("Still to collect") },
    { label: t("Invoices"), value: tot.invoices, icon: FileText, hint: `${t("VAT")} ${money(tot.vat)}` },
  ];

  return (
    <Card className="mt-6">
      <CardHeader
        title={t("Sales report")}
        subtitle={data ? `${date(data.from)} – ${date(data.to)} · ${t("issued invoices, after credit and debit notes")}` : t("Issued invoices, after credit and debit notes")}
      />
      <div className="grid gap-3 border-b border-line p-4 sm:grid-cols-2 lg:grid-cols-5">
        <Field label={t("Period")}>
          <Select value={preset} onChange={(e) => setPreset(e.target.value as Preset)}>
            {presets.map((p) => <option key={p.value} value={p.value}>{t(p.label)}</option>)}
          </Select>
        </Field>
        {preset === "custom" ? (
          <>
            <Field label={t("Month")} hint={t("Or pick a date range")}>
              <Input
                type="month"
                value={month}
                onChange={(e) => {
                  setMonth(e.target.value);
                  if (e.target.value) setCustom(monthRange(e.target.value));
                }}
              />
            </Field>
            <div className="grid grid-cols-2 gap-2">
              <Field label={t("From")}><Input type="date" value={custom.from} onChange={(e) => { setMonth(""); setCustom({ ...custom, from: e.target.value }); }} /></Field>
              <Field label={t("To")}><Input type="date" value={custom.to} onChange={(e) => { setMonth(""); setCustom({ ...custom, to: e.target.value }); }} /></Field>
            </div>
          </>
        ) : null}
        <Field label={t("Time of day")}>
          <Select value={segment} onChange={(e) => setSegment(e.target.value)}>
            {Object.entries(segments).map(([k, s]) => <option key={k} value={k}>{t(s.label)}</option>)}
          </Select>
        </Field>
        <Field label={t("Invoice type")}>
          <Select value={kind} onChange={(e) => setKind(e.target.value)}>
            <option value="">{t("B2B and B2C")}</option>
            <option value="STANDARD">{t("Standard (B2B)")}</option>
            <option value="SIMPLIFIED">{t("Simplified (B2C)")}</option>
          </Select>
        </Field>
        <Field label={t("Customer")}>
          <div className="relative">
            <Search className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted" aria-hidden />
            <Input className="ps-9" placeholder={t("Search name")} value={q} onChange={(e) => setQ(e.target.value)} />
          </div>
        </Field>
      </div>

      {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
      {!data && !error && <Spinner />}
      {stats && (
        <div className="grid gap-4 p-4 sm:grid-cols-2 lg:grid-cols-4">
          {stats.map((s) => (
            <div key={s.label} className="rounded-lg border border-line p-4">
              <div className="flex items-center justify-between text-muted">
                <span className="text-xs font-medium">{s.label}</span>
                <s.icon className="size-4" aria-hidden />
              </div>
              <p className={`tabular mt-2 text-xl font-semibold ${s.tone ?? ""}`}>{s.value}</p>
              <p className="mt-1 text-xs text-muted">{s.hint}</p>
            </div>
          ))}
        </div>
      )}

      {data && (
        <>
          <div className="flex gap-1 border-y border-line px-4 py-2" role="tablist">
            {([["months", "Month-wise"], ["days", "Day-wise"], ["customers", "By customer"]] as const).map(([v, label]) => (
              <button
                key={v}
                role="tab"
                aria-selected={view === v}
                onClick={() => setView(v)}
                className={`rounded-md px-3 py-1.5 text-sm transition ${view === v ? "bg-brand/10 font-medium text-brand" : "text-muted hover:text-ink"}`}
              >
                {t(label)}
              </button>
            ))}
            <span className="ms-auto hidden items-center gap-3 text-xs text-muted sm:inline-flex">
              <span className="inline-flex items-center gap-1"><span className="size-2 rounded-full bg-ok" /> {t("Collected")}</span>
              <span className="inline-flex items-center gap-1"><span className="size-2 rounded-full bg-bad/60" /> {t("Due")}</span>
            </span>
          </div>
          {data.totals.invoices === 0 ? (
            <Empty title={t("No sales in this period")}>{t("Change the period or filters.")}</Empty>
          ) : view === "months" ? (
            <RowsTable rows={data.months} first={t("Month")} />
          ) : view === "days" ? (
            <RowsTable rows={[...data.days].reverse().map((d) => ({ ...d, label: date(d.key) }))} first={t("Day")} />
          ) : (
            <RowsTable rows={data.customers} first={t("Customer")} />
          )}
        </>
      )}
    </Card>
  );
}
