"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import { AlertTriangle, IdCard, Search } from "lucide-react";
import { fetcher } from "@/lib/api";
import { date, money } from "@/lib/format";
import { Card, CardHeader, Input } from "@/components/ui";
import { useT } from "@/lib/i18n";

interface Tile { key: string; label: string; value: number; unit: string | null; href: string }
interface Dash { tiles: Tile[]; salesTrend: { date: string; total: number }[]; stockByWarehouse: { name: string; value: number }[];
  lowStock: { id: number; number: string; name: string; warehouse: string; quantity: number; low_stock_threshold: number }[];
  expiringIds: { id: number; name: string; code: string; id_number: string | null; id_expiry: string | null; passport_expiry: string | null }[];
  expiringVehicles: { id: number; plate: string; name: string; istimara_expiry: string | null; insurance_expiry: string | null; mvpi_expiry: string | null }[] }

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
      {data.expiringVehicles.length > 0 && (
        <Card>
          <CardHeader title={<span className="inline-flex items-center gap-2"><AlertTriangle className="size-4 text-amber-600" aria-hidden />{t("Vehicle documents expiring within 30 days")}</span>} />
          <table className="w-full text-sm"><tbody className="divide-y divide-line">
            {data.expiringVehicles.map((v) => <tr key={v.id}><td className="tabular px-4 py-2 font-medium"><Link className="text-brand hover:underline" href="/fleet">{v.plate}</Link></td><td className="px-4 py-2">{v.name}</td>
              <td className="tabular px-4 py-2 text-end text-bad">{[v.istimara_expiry && `${t("Istimara")} ${date(v.istimara_expiry)}`, v.insurance_expiry && `${t("Insurance")} ${date(v.insurance_expiry)}`, v.mvpi_expiry && `${t("Fahas")} ${date(v.mvpi_expiry)}`].filter(Boolean).join(" · ")}</td></tr>)}
          </tbody></table>
        </Card>
      )}
      {(data.lowStock.length > 0 || data.expiringIds.length > 0) && (
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
          {data.expiringIds.length > 0 && (
            <Card>
              <CardHeader title={<span className="inline-flex items-center gap-2"><IdCard className="size-4 text-amber-600" aria-hidden />{t("Iqama / passport expiring within 30 days")}</span>} />
              <table className="w-full text-sm"><tbody className="divide-y divide-line">
                {data.expiringIds.map((e) => <tr key={e.id}><td className="px-4 py-2" dir="auto">{e.name}</td><td className="px-4 py-2 text-muted">{e.id_number}</td>
                  <td className="tabular px-4 py-2 text-end text-bad">{[e.id_expiry && `${t("ID")} ${date(e.id_expiry)}`, e.passport_expiry && `${t("Passport")} ${date(e.passport_expiry)}`].filter(Boolean).join(" · ")}</td></tr>)}
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
