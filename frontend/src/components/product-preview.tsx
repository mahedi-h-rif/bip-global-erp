"use client";

import useSWR from "swr";
import { fetcher } from "@/lib/api";
import type { Product, StockMovement, StockRow } from "@/lib/types";
import { dateTime, money } from "@/lib/format";
import { Details } from "@/components/preview";
import { useT } from "@/lib/i18n";

const qty = (v: number | string | null | undefined) => Number(v ?? 0).toLocaleString("en-US", { maximumFractionDigits: 3 });

/** A product at a glance: its details, stock in each warehouse and its latest stock movements. */
export function ProductPreview({ product: p }: { product: Product }) {
  const t = useT();
  const tracked = !!p.trackStock;
  const { data: levels } = useSWR<StockRow[]>(tracked ? `/stock?q=${encodeURIComponent(p.number || p.name)}` : null, fetcher);
  const { data: moves } = useSWR<StockMovement[]>(tracked ? `/stock/movements?productId=${p.id}&limit=10` : null, fetcher);
  const mine = levels?.filter((l) => l.productId === p.id) ?? [];

  return (
    <div className="pb-5">
      <Details items={[
        ["Item code", p.number],
        ["Name", p.name],
        ["Arabic name", p.nameAr ? <span dir="rtl">{p.nameAr}</span> : null],
        ["Category", p.category],
        ["Brand", p.brand],
        ["Barcode", p.barcode],
        ["Unit price (excl. VAT)", money(p.unitPrice)],
        ["With 15% VAT", money(Math.round(Number(p.unitPrice) * 115) / 100)],
        ["Cost price (SAR)", p.costPrice != null && p.costPrice !== "" ? money(p.costPrice) : null],
        ["Average cost", p.avgCost != null ? money(p.avgCost) : null],
        ["Unit", p.unitCode],
        ["On hand", tracked ? `${qty(p.onHand)} ${p.unitCode ?? ""}` : t("Unlimited")],
        ["Low-stock alert at", p.lowStockThreshold != null && p.lowStockThreshold !== "" ? qty(p.lowStockThreshold) : null],
        ["Status", p.active === false ? t("Deactivated") : null],
      ]} />
      {tracked && mine.length > 0 && (
        <section className="px-5 pt-3">
          <h3 className="mb-2 text-sm font-semibold">{t("Stock by warehouse")}</h3>
          <ul className="divide-y divide-line rounded-lg border border-line text-sm">
            {mine.map((l) => (
              <li key={l.warehouseId} className="flex justify-between px-3 py-2">
                <span>{l.warehouseName}</span>
                <span className={`tabular font-medium ${l.low ? "text-bad" : ""}`}>{qty(l.quantity)} {l.unitCode}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
      {tracked && moves && moves.length > 0 && (
        <section className="px-5 pt-4">
          <h3 className="mb-2 text-sm font-semibold">{t("Latest movements")}</h3>
          <ul className="divide-y divide-line rounded-lg border border-line text-sm">
            {moves.map((m) => (
              <li key={m.id} className="flex items-center justify-between gap-3 px-3 py-2">
                <span className="min-w-0">
                  <span className="block text-xs text-muted">{dateTime(m.at)} · {m.warehouseName}</span>
                  <span className="block truncate text-xs">{[m.refNumber, m.note].filter(Boolean).join(" · ") || m.kind}</span>
                </span>
                <span className={`tabular shrink-0 font-medium ${Number(m.quantity) < 0 ? "text-bad" : "text-ok"}`}>
                  {Number(m.quantity) > 0 ? "+" : ""}{qty(m.quantity)}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
