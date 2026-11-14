"use client";

import Link from "next/link";
import useSWR from "swr";
import { Landmark } from "lucide-react";
import { fetcher } from "@/lib/api";
import { date, money } from "@/lib/format";
import { useT } from "@/lib/i18n";

interface Summary { from: string; to: string; outputVat: number; inputVat: number; payable: number }

/** Dashboard tile: this quarter's VAT payable to ZATCA (output on sales − input on purchases). */
export function VatTile() {
  const t = useT();
  const { data, error } = useSWR<Summary>("/vat/summary", fetcher);
  if (error || !data) return null;
  const refundable = Number(data.payable) < 0;
  return (
    <Link href="/vat" className={`mb-6 flex flex-wrap items-center gap-4 rounded-xl border p-4 hover:shadow-sm ${refundable ? "border-emerald-200 bg-emerald-50" : "border-amber-200 bg-amber-50"}`}>
      <span className="grid size-10 place-items-center rounded-lg bg-white"><Landmark className={`size-5 ${refundable ? "text-ok" : "text-amber-700"}`} aria-hidden /></span>
      <span>
        <span className="block text-xs text-muted">{refundable ? t("VAT refundable this quarter") : t("VAT payable to ZATCA this quarter")} · {date(data.from)} – {date(data.to)}</span>
        <span className={`tabular text-2xl font-semibold ${refundable ? "text-ok" : "text-amber-800"}`}>{money(Math.abs(Number(data.payable)))}</span>
      </span>
      <span className="ms-auto text-end text-sm">
        <span className="block text-muted">{t("Output VAT")} <b className="tabular text-ink">{money(data.outputVat)}</b></span>
        <span className="block text-muted">{t("Input VAT")} <b className="tabular text-ink">{money(data.inputVat)}</b></span>
      </span>
    </Link>
  );
}
