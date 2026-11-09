"use client";

import { useState } from "react";
import useSWR from "swr";
import { ChevronDown, TrendingUp } from "lucide-react";
import { fetcher } from "@/lib/api";
import type { GrossProfit } from "@/lib/types";
import { money } from "@/lib/format";
import { Card } from "@/components/ui";
import { useT } from "@/lib/i18n";

/**
 * Gross profit of a sales document (video: "GP value / GP %" on the A/R invoice). Collapsed by default so it isn't on
 * screen when the customer is looking; the bar shows how revenue splits into cost and profit.
 */
export function GrossProfitCard({ path }: { path: string }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const { data } = useSWR<GrossProfit>(path, fetcher);
  if (!data || Number(data.revenue) === 0) return null;
  const rev = Math.abs(Number(data.revenue)), cost = Math.abs(Number(data.cost));
  const costPct = rev === 0 ? 0 : Math.min(100, (cost / rev) * 100);
  const gpBad = Number(data.grossProfit) < 0;
  return (
    <Card className="mb-6">
      <button type="button" className="flex w-full items-center gap-3 px-5 py-3 text-start" onClick={() => setOpen(!open)} aria-expanded={open}>
        <TrendingUp className={`size-4 ${gpBad ? "text-bad" : "text-emerald-600"}`} aria-hidden />
        <span className="text-sm font-semibold">{t("Gross profit")}</span>
        <span className={`tabular text-sm font-semibold ${gpBad ? "text-bad" : "text-emerald-700"}`}>{money(data.grossProfit)} · {Number(data.gpPct).toFixed(2)} %</span>
        <ChevronDown className={`ms-auto size-4 text-muted transition ${open ? "rotate-180" : ""}`} aria-hidden />
      </button>
      {open && (
        <div className="border-t border-line p-5">
          <div className="mb-1 flex justify-between text-xs text-muted"><span>{t("Cost")} {money(data.cost)}</span><span>{t("Revenue")} {money(data.revenue)}</span></div>
          <div className="flex h-4 overflow-hidden rounded bg-slate-100" role="img" aria-label={t("Cost {cost} of revenue {revenue}", { cost: money(data.cost), revenue: money(data.revenue) })}>
            <div className="h-4 bg-slate-400" style={{ width: `${costPct}%` }} />
            <div className={`h-4 ${gpBad ? "bg-red-500" : "bg-emerald-500"}`} style={{ width: `${100 - costPct}%` }} />
          </div>
          <p className="mt-2 text-xs text-muted">{t(data.basis)}</p>
          <table className="mt-3 w-full text-sm">
            <thead><tr className="border-b border-line text-xs text-muted">
              <th className="py-2 text-start font-medium">{t("Item")}</th><th className="py-2 text-end font-medium">{t("Quantity")}</th>
              <th className="py-2 text-end font-medium">{t("Revenue")}</th><th className="py-2 text-end font-medium">{t("Unit cost")}</th>
              <th className="py-2 text-end font-medium">{t("Cost")}</th><th className="py-2 text-end font-medium">{t("Gross profit")}</th><th className="py-2 text-end font-medium">{t("GP %")}</th>
            </tr></thead>
            <tbody className="divide-y divide-line">
              {data.lines.map((l, i) => (
                <tr key={i}>
                  <td className="py-2" dir="auto">{l.name}</td><td className="tabular py-2 text-end">{Number(l.quantity)}</td>
                  <td className="tabular py-2 text-end">{money(l.revenue)}</td><td className="tabular py-2 text-end">{Number(l.unitCost).toFixed(2)}</td>
                  <td className="tabular py-2 text-end">{money(l.cost)}</td>
                  <td className={`tabular py-2 text-end font-medium ${Number(l.grossProfit) < 0 ? "text-bad" : ""}`}>{money(l.grossProfit)}</td>
                  <td className="tabular py-2 text-end">{Number(l.gpPct).toFixed(1)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}
