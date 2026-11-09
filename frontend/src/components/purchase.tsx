"use client";

import useSWR from "swr";
import { Plus, Trash2 } from "lucide-react";
import { fetcher } from "@/lib/api";
import type { Product } from "@/lib/types";
import { money, unitCodes } from "@/lib/format";
import { Button, Input, Select } from "@/components/ui";
import { useT } from "@/lib/i18n";

export const poStatus: Record<string, { label: string; tone: string }> = {
  PENDING: { label: "Pending approval", tone: "bg-amber-50 text-amber-700" },
  APPROVED: { label: "Approved", tone: "bg-blue-50 text-blue-700" },
  PARTIALLY_RECEIVED: { label: "Partially received", tone: "bg-indigo-50 text-indigo-700" },
  RECEIVED: { label: "Received", tone: "bg-emerald-50 text-emerald-700" },
  CANCELLED: { label: "Cancelled", tone: "bg-slate-100 text-slate-500" },
  POSTED: { label: "Posted", tone: "bg-emerald-50 text-emerald-700" },
  OPEN: { label: "Open", tone: "bg-blue-50 text-blue-700" },
  PAID: { label: "Paid", tone: "bg-emerald-50 text-emerald-700" },
  ORDERED: { label: "Ordered", tone: "bg-emerald-50 text-emerald-700" },
};

export function StatusPill({ status }: { status: string }) {
  const t = useT();
  const s = poStatus[status] ?? { label: status, tone: "bg-slate-100 text-slate-600" };
  return <span className={`rounded px-2 py-0.5 text-xs font-medium ${s.tone}`}>{t(s.label)}</span>;
}

export const currencies = ["SAR", "USD", "EUR", "BHD", "AED", "CNY", "INR", "KWD", "OMR", "QAR"];

export interface EditLine {
  productId?: number | null;
  name: string;
  quantity: number | string;
  unitCode?: string;
  unitCost: number | string;
  vatRate?: number | string;
  locked?: boolean;
  hint?: string;
}

/** Item lines with a product picker, quantity, unit, unit cost and VAT; totals in the document currency. */
export function CostLines<L extends EditLine>({ lines, onChange, currency, blank }: {
  lines: L[];
  onChange: (lines: L[]) => void;
  currency: string;
  blank: () => L;
}) {
  const t = useT();
  const { data: products } = useSWR<Product[]>("/products", fetcher);
  const set = (i: number, patch: Partial<L>) => onChange(lines.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  let net = 0, vat = 0;
  for (const l of lines) {
    const n = Number(l.quantity || 0) * Number(l.unitCost || 0);
    net += n;
    vat += (n * Number(l.vatRate ?? 15)) / 100;
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[52rem] text-sm">
        <thead>
          <tr className="text-xs text-muted">
            <th className="pb-2 text-start font-medium">{t("Item")}</th>
            <th className="w-28 pb-2 text-start font-medium">{t("Quantity")}</th>
            <th className="w-28 pb-2 text-start font-medium">{t("Unit")}</th>
            <th className="w-32 pb-2 text-start font-medium">{t("Unit cost")} ({currency})</th>
            <th className="w-20 pb-2 text-start font-medium">{t("VAT %")}</th>
            <th className="w-32 pb-2 text-end font-medium">{t("Total")}</th>
            <th className="w-10" />
          </tr>
        </thead>
        <tbody>
          {lines.map((l, i) => (
            <tr key={i} className="align-top">
              <td className="py-1.5 pe-2">
                {l.locked ? <Input readOnly dir="auto" value={l.name} /> : (
                  <div className="flex gap-2">
                    <Select className="w-40 shrink-0" value={l.productId ?? ""} aria-label={t("Product")} onChange={(e) => {
                      const p = products?.find((x) => String(x.id) === e.target.value);
                      set(i, (p ? { productId: p.id, name: p.name, unitCode: p.unitCode ?? "PCE", unitCost: p.avgCost ?? p.costPrice ?? l.unitCost } : { productId: null }) as Partial<L>);
                    }}>
                      <option value="">{t("Free text")}</option>
                      {products?.filter((p) => p.purchaseItem !== false).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
                    </Select>
                    <Input required dir="auto" value={l.name} onChange={(e) => set(i, { name: e.target.value } as Partial<L>)} />
                  </div>
                )}
                {l.hint && <span className="mt-1 block text-[11px] text-muted">{l.hint}</span>}
              </td>
              <td className="py-1.5 pe-2"><Input required type="number" min="0" step="any" value={l.quantity} onChange={(e) => set(i, { quantity: e.target.value } as Partial<L>)} /></td>
              <td className="py-1.5 pe-2">
                <Select disabled={l.locked} value={l.unitCode ?? "PCE"} onChange={(e) => set(i, { unitCode: e.target.value } as Partial<L>)}>
                  {unitCodes.map((u) => <option key={u.code} value={u.code}>{t(u.label)}</option>)}
                </Select>
              </td>
              <td className="py-1.5 pe-2"><Input required type="number" min="0" step="any" value={l.unitCost} onChange={(e) => set(i, { unitCost: e.target.value } as Partial<L>)} /></td>
              <td className="py-1.5 pe-2"><Input type="number" min="0" step="any" value={l.vatRate ?? 15} onChange={(e) => set(i, { vatRate: e.target.value } as Partial<L>)} /></td>
              <td className="tabular py-3 pe-2 text-end">{(Number(l.quantity || 0) * Number(l.unitCost || 0) * (1 + Number(l.vatRate ?? 15) / 100)).toFixed(2)}</td>
              <td className="py-1.5">
                <Button type="button" variant="ghost" disabled={lines.length === 1} onClick={() => onChange(lines.filter((_, j) => j !== i))} aria-label={t("Remove line")}>
                  <Trash2 className="size-4" aria-hidden />
                </Button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {!lines.every((l) => l.locked) && (
        <Button type="button" variant="secondary" className="mt-2" onClick={() => onChange([...lines, blank()])}><Plus className="size-4" aria-hidden /> {t("Add line")}</Button>
      )}
      <dl className="ms-auto mt-4 w-72 space-y-1 text-sm">
        <div className="flex justify-between"><dt className="text-muted">{t("Total excl. VAT")}</dt><dd className="tabular">{net.toFixed(2)} {currency}</dd></div>
        <div className="flex justify-between"><dt className="text-muted">{t("VAT")}</dt><dd className="tabular">{vat.toFixed(2)} {currency}</dd></div>
        <div className="flex justify-between border-t border-line pt-1 font-semibold"><dt>{t("Total")}</dt><dd className="tabular">{(net + vat).toFixed(2)} {currency}</dd></div>
      </dl>
    </div>
  );
}

export const fmtCur = (v: number | string | null | undefined, currency: string) =>
  currency === "SAR" ? money(v) : `${Number(v ?? 0).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency}`;
