"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import { AlertTriangle, Search } from "lucide-react";
import { fetcher } from "@/lib/api";
import { date, money } from "@/lib/format";
import { Card, CardHeader, Input } from "@/components/ui";
import { useT } from "@/lib/i18n";

interface Tile { key: string; label: string; value: number; unit: string | null; href: string }
interface Dash { tiles: Tile[]; salesTrend: { date: string; total: number }[]; stockByWarehouse: { name: string; value: number }[];
  lowStock: { id: number; number: string; name: string; warehouse: string; quantity: number; low_stock_threshold: number }[];
  monthly: { month: string; sales: number; purchases: number; grossProfit: number }[];
  topCustomers: { name: string; value: number }[]; topProducts: { name: string; value: number; gp: number }[];
  receivablesAging: { bucket: string; value: number }[]; payablesAging: { bucket: string; value: number }[] }

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const compact = (v: number) => Math.abs(v) >= 1e6 ? `${(v / 1e6).toFixed(1)}M` : Math.abs(v) >= 1e3 ? `${(v / 1e3).toFixed(0)}k` : v.toFixed(0);

/** 12 months: sales and purchases as paired bars, gross profit as a line (the "graphs" the meeting asked for). */
function MonthlyChart({ data }: { data: Dash["monthly"] }) {
  const t = useT();
  const W = 720, H = 220, L = 44, B = 24, T = 10;
  const vals = data.flatMap((d) => [Number(d.sales), Number(d.purchases), Number(d.grossProfit)]);
  const max = Math.max(1, ...vals), min = Math.min(0, ...vals);
  const y = (v: number) => T + (H - T - B) * (1 - (v - min) / (max - min));
  const step = (W - L) / data.length, bw = Math.max(4, step * 0.3);
  const line = data.map((d, i) => `${i ? "L" : "M"}${L + step * i + step / 2},${y(Number(d.grossProfit))}`).join(" ");
  const ticks = [min, (max + min) / 2, max];
  return (
    <div className="overflow-x-auto p-4">
      <svg viewBox={`0 0 ${W} ${H}`} className="w-full min-w-[520px]" role="img" aria-label={t("Sales, purchases and gross profit, last 12 months")}>
        {ticks.map((v, i) => <g key={i}><line x1={L} x2={W} y1={y(v)} y2={y(v)} stroke="#e2e8f0" /><text x={L - 6} y={y(v) + 3} textAnchor="end" fontSize="10" fill="#64748b">{compact(v)}</text></g>)}
        {data.map((d, i) => {
          const x = L + step * i + step / 2;
          return (
            <g key={d.month}>
              <rect x={x - bw - 1} y={Math.min(y(Number(d.sales)), y(0))} width={bw} height={Math.abs(y(0) - y(Number(d.sales)))} rx="2" className="fill-brand/80"><title>{`${d.month} · ${t("Sales")} ${money(d.sales)}`}</title></rect>
              <rect x={x + 1} y={Math.min(y(Number(d.purchases)), y(0))} width={bw} height={Math.abs(y(0) - y(Number(d.purchases)))} rx="2" fill="#f59e0b" fillOpacity="0.75"><title>{`${d.month} · ${t("Purchases")} ${money(d.purchases)}`}</title></rect>
              <text x={x} y={H - 6} textAnchor="middle" fontSize="10" fill="#64748b">{t(MONTHS[Number(d.month.slice(5)) - 1])}</text>
            </g>
          );
        })}
        <path d={line} fill="none" stroke="#059669" strokeWidth="2" />
        {data.map((d, i) => <circle key={d.month} cx={L + step * i + step / 2} cy={y(Number(d.grossProfit))} r="3" fill="#059669"><title>{`${d.month} · ${t("Gross profit")} ${money(d.grossProfit)}`}</title></circle>)}
      </svg>
      <div className="mt-2 flex flex-wrap gap-4 text-xs text-muted">
        <span className="inline-flex items-center gap-1.5"><span className="size-2.5 rounded-sm bg-brand/80" />{t("Sales")}</span>
        <span className="inline-flex items-center gap-1.5"><span className="size-2.5 rounded-sm bg-amber-500/75" />{t("Purchases")}</span>
        <span className="inline-flex items-center gap-1.5"><span className="h-0.5 w-4 bg-emerald-600" />{t("Gross profit")}</span>
      </div>
    </div>
  );
}

/** Horizontal bars, one row per item, scaled to the largest. */
function Bars({ rows, tone = "bg-brand/70", note }: { rows: { label: string; value: number; extra?: string }[]; tone?: string; note?: string }) {
  const t = useT();
  const max = Math.max(1, ...rows.map((r) => Math.abs(Number(r.value))));
  if (rows.length === 0) return <p className="p-4 text-sm text-muted">{t("Nothing yet")}</p>;
  return (
    <div className="space-y-2 p-4">
      {rows.map((r) => (
        <div key={r.label} className="text-sm">
          <div className="flex justify-between gap-2"><span className="truncate" dir="auto">{t(r.label)}</span><span className="tabular shrink-0">{money(r.value)}{r.extra ? <span className="ms-1 text-xs text-muted">{r.extra}</span> : null}</span></div>
          <div className="mt-1 h-2 rounded bg-slate-100"><div className={`h-2 rounded ${tone}`} style={{ width: `${(Math.abs(Number(r.value)) / max) * 100}%` }} /></div>
        </div>
      ))}
      {note && <p className="pt-1 text-xs text-muted">{note}</p>}
    </div>
  );
}

/** ERP overview (quotation "Dashboard"): sales, invoices, stock, ZATCA, employee and payroll tiles; 7-day trend; stock by warehouse. */
export function ErpDashboard() {
  const t = useT();
  const { data } = useSWR<Dash>("/erp-dashboard", fetcher, { refreshInterval: 60_000 });
  if (!data) return null;
  const max = Math.max(1, ...data.salesTrend.map((d) => Number(d.total)));
  const whMax = Math.max(1, ...data.stockByWarehouse.map((w) => Number(w.value)));
  return (
    <div className="mb-6 space-y-4">
      <div className="grid grid-cols-2 gap-3 md:grid-cols-4 xl:grid-cols-6">
        {data.tiles.map((x) => (
          <Link key={x.key} href={x.href} className="rounded-xl border border-line bg-white p-3 transition hover:border-brand">
            <p className="text-xs text-muted">{t(x.label)}</p>
            <p className={`tabular mt-1 text-lg font-semibold ${(x.key === "zatcaRejected" || x.key === "pendingPo") && Number(x.value) > 0 ? "text-bad" : ""}`}>
              {x.unit === "SAR" ? money(x.value) : Number(x.value).toLocaleString("en-US")}
            </p>
          </Link>
        ))}
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader title={t("Sales, last 7 days")} />
          <div className="flex h-44 items-end gap-2 p-4" role="img" aria-label={t("Sales, last 7 days")}>
            {data.salesTrend.map((d) => (
              <div key={d.date} className="flex flex-1 flex-col items-center gap-1">
                <span className="tabular text-[10px] text-muted">{Number(d.total) ? Math.round(Number(d.total)).toLocaleString("en-US") : ""}</span>
                <div className="w-full rounded-t bg-brand/80" style={{ height: `${Math.max(2, (Number(d.total) / max) * 120)}px` }} title={money(d.total)} />
                <span className="text-[10px] text-muted">{d.date.slice(5)}</span>
              </div>
            ))}
          </div>
        </Card>
        <Card>
          <CardHeader title={t("Stock value by warehouse")} />
          <div className="space-y-2 p-4">
            {data.stockByWarehouse.map((w) => (
              <div key={w.name} className="text-sm">
                <div className="flex justify-between"><span>{w.name}</span><span className="tabular">{money(w.value)}</span></div>
                <div className="mt-1 h-2 rounded bg-slate-100"><div className="h-2 rounded bg-brand/70" style={{ width: `${(Number(w.value) / whMax) * 100}%` }} /></div>
              </div>
            ))}
          </div>
        </Card>
      </div>
      {data.monthly && (
        <Card>
          <CardHeader title={t("Sales, purchases and gross profit, last 12 months")} action={<Link className="text-sm text-brand hover:underline" href="/erp-reports?r=gross-profit">{t("Gross profit report")}</Link>} />
          <MonthlyChart data={data.monthly} />
        </Card>
      )}
      {data.topCustomers && (
        <div className="grid gap-4 lg:grid-cols-2 xl:grid-cols-4">
          <Card><CardHeader title={t("Top customers this year")} /><Bars rows={data.topCustomers.map((c) => ({ label: c.name, value: c.value }))} /></Card>
          <Card><CardHeader title={t("Top items this year")} /><Bars tone="bg-emerald-500/70" rows={data.topProducts.map((p) => ({ label: p.name, value: p.value, extra: Number(p.value) ? `GP ${Math.round((Number(p.gp) / Number(p.value)) * 100)}%` : undefined }))} /></Card>
          <Card><CardHeader title={t("Receivables by age")} action={<Link className="text-sm text-brand hover:underline" href="/erp-reports?r=receivables">{t("Details")}</Link>} /><Bars tone="bg-sky-500/70" rows={data.receivablesAging.map((a) => ({ label: a.bucket, value: a.value }))} /></Card>
          <Card><CardHeader title={t("Payables by age")} action={<Link className="text-sm text-brand hover:underline" href="/erp-reports?r=payables">{t("Details")}</Link>} /><Bars tone="bg-amber-500/70" rows={data.payablesAging.map((a) => ({ label: a.bucket, value: a.value }))} /></Card>
        </div>
      )}
      {data.lowStock.length > 0 && (
        <div className="grid gap-4 lg:grid-cols-2">
          {data.lowStock.length > 0 && (
            <Card>
              <CardHeader title={<span className="inline-flex items-center gap-2"><AlertTriangle className="size-4 text-amber-600" aria-hidden />{t("Low stock")}</span>} action={<Link className="text-sm text-brand hover:underline" href="/reorder">{t("Reorder")}</Link>} />
              <table className="w-full text-sm"><tbody className="divide-y divide-line">
                {data.lowStock.map((l, i) => <tr key={i}><td className="px-4 py-2" dir="auto">{l.name}</td><td className="px-4 py-2 text-muted">{l.warehouse}</td>
                  <td className="tabular px-4 py-2 text-end font-semibold text-bad">{Number(l.quantity)} / {Number(l.low_stock_threshold)}</td></tr>)}
              </tbody></table>
            </Card>
          )}
        </div>
      )}
    </div>
  );
}

interface Hit { type: string; label: string; detail: string; href: string }

/** Quick search across customers, products, invoices, orders, suppliers and employees. */
export function QuickSearch() {
  const t = useT();
  const router = useRouter();
  const [q, setQ] = useState("");
  const { data } = useSWR<Hit[]>(q.trim().length >= 2 ? `/search?q=${encodeURIComponent(q.trim())}` : null, fetcher, { keepPreviousData: true });
  return (
    <div className="relative px-3 pt-2">
      <Search className="pointer-events-none absolute start-6 top-1/2 mt-1 size-4 -translate-y-1/2 text-white/60" aria-hidden />
      <Input className="border-white/20 bg-white/10 ps-9 text-white placeholder:text-white/60" placeholder={t("Search everything…")} value={q}
             onChange={(e) => setQ(e.target.value)} aria-label={t("Search everything")} />
      {q.trim().length >= 2 && data && (
        <div className="absolute inset-x-3 top-full z-50 mt-1 max-h-80 overflow-y-auto rounded-lg border border-line bg-white text-ink shadow-lg">
          {data.length === 0 ? <p className="p-3 text-sm text-muted">{t("Nothing found")}</p> : data.map((h, i) => (
            <button key={i} className="block w-full px-3 py-2 text-start text-sm hover:bg-slate-50" onClick={() => { setQ(""); router.push(h.href); }}>
              <span className="text-[11px] uppercase text-muted">{t(h.type)}</span>
              <span className="block font-medium" dir="auto">{h.label}</span>
              {h.detail && <span className="block text-xs text-muted">{h.detail}</span>}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
