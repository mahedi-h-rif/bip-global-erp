"use client";

import { useState } from "react";
import useSWR, { mutate as globalMutate } from "swr";
import { Plus, Trash2 } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import { unitCodes } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Input, Select } from "@/components/ui";
import { useT } from "@/lib/i18n";

/** One unit of one item (base unit included, factor 1), from GET /products/units. */
export interface ItemUnit {
  productId: number; unitCode: string; factor: number; price: number | null; barcode: string | null;
  base: boolean; salesDefault: boolean; purchaseDefault: boolean;
}

/** All items' units, shared by every line editor (one request, cached). */
export function useItemUnits() {
  const { data } = useSWR<ItemUnit[]>("/products/units", fetcher, { revalidateOnFocus: false, dedupingInterval: 30_000 });
  return data ?? [];
}

export function unitsOf(units: ItemUnit[], productId: number | null | undefined) {
  return productId ? units.filter((u) => u.productId === productId) : [];
}

/** The unit an item is sold (or bought) in by default: the flagged one, else its base unit. */
export function defaultUnit(units: ItemUnit[], productId: number | null | undefined, purchase = false): ItemUnit | undefined {
  const own = unitsOf(units, productId);
  return own.find((u) => (purchase ? u.purchaseDefault : u.salesDefault)) ?? own.find((u) => u.base);
}

const label = (code: string) => unitCodes.find((u) => u.code === code)?.label ?? code;

/**
 * Unit picker for a document line. For an item line it offers only that item's units ("Carton ×24"); for a free-text
 * line, every unit. {@code onPick} gets the unit, its default price (sales) and factor.
 */
export function UnitSelect({ productId, value, onPick, disabled, ariaLabel }: {
  productId?: number | null; value: string; disabled?: boolean; ariaLabel?: string;
  onPick: (unitCode: string, price: number | null, factor: number) => void;
}) {
  const t = useT();
  const units = useItemUnits();
  const own = unitsOf(units, productId);
  if (own.length > 0) {
    const known = own.some((u) => u.unitCode === value);
    return (
      <Select value={value} disabled={disabled} aria-label={ariaLabel ?? t("Unit")} onChange={(e) => {
        const u = own.find((x) => x.unitCode === e.target.value);
        if (u) onPick(u.unitCode, u.price == null ? null : Number(u.price), Number(u.factor));
      }}>
        {!known && <option value={value}>{t(label(value))}</option>}
        {own.map((u) => <option key={u.unitCode} value={u.unitCode}>{t(label(u.unitCode))}{u.base ? "" : ` ×${Number(u.factor)}`}</option>)}
      </Select>
    );
  }
  return (
    <Select value={value} disabled={disabled} aria-label={ariaLabel ?? t("Unit")} onChange={(e) => onPick(e.target.value, null, 1)}>
      {!unitCodes.some((u) => u.code === value) && <option value={value}>{value}</option>}
      {unitCodes.map((u) => <option key={u.code} value={u.code}>{t(u.label)}</option>)}
    </Select>
  );
}

/** "3 CT (72 PCE)" — a quantity with its base-unit equivalent when the unit isn't the base one. */
export function qtyWithBase(qty: number | string, unitCode: string, factor: number | string | null | undefined, baseUnit?: string) {
  const f = Number(factor ?? 1), q = Number(qty);
  return f === 1 || !baseUnit ? `${q} ${unitCode}` : `${q} ${unitCode} (${+(q * f).toFixed(3)} ${baseUnit})`;
}

interface UomRow { unitCode: string; factor: string; price: string; barcode: string; salesDefault: boolean; purchaseDefault: boolean }

/**
 * An item's alternative units (SAP B1 UoM group): e.g. Dozen ×12 at its own price, Carton ×24 with its own barcode.
 * Lines already entered keep the factor they were entered with.
 */
export function ProductUnits({ productId, baseUnit, basePrice }: { productId: number; baseUnit: string; basePrice: number | string }) {
  const t = useT();
  const { data, mutate } = useSWR<{ unitCode: string; factor: number; price: number | null; barcode: string | null; salesDefault: boolean; purchaseDefault: boolean }[]>(
    `/products/${productId}/units`, fetcher, { revalidateOnFocus: false });
  const [rows, setRows] = useState<UomRow[] | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const list: UomRow[] = rows ?? (data ?? []).map((u) => ({ unitCode: u.unitCode, factor: String(Number(u.factor)), price: u.price == null ? "" : String(Number(u.price)),
    barcode: u.barcode ?? "", salesDefault: u.salesDefault, purchaseDefault: u.purchaseDefault }));
  const set = (i: number, patch: Partial<UomRow>) => { setSaved(false); setRows(list.map((r, k) => (k === i ? { ...r, ...patch } : r))); };
  return (
    <Card className="mb-6">
      <CardHeader title={t("Units of measure")} subtitle={t("Stock is counted in {unit}. Add the other units this item is sold or bought in.", { unit: baseUnit })} />
      {problem && <div className="px-4 pt-3"><Alert tone="bad">{problem}</Alert></div>}
      {saved && <div className="px-4 pt-3"><Alert tone="ok">{t("Units saved")}</Alert></div>}
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead><tr className="border-b border-line text-xs text-muted">
            <th className="px-4 py-2 text-start font-medium">{t("Unit")}</th><th className="px-4 py-2 text-end font-medium">{t("Contains ({unit})", { unit: baseUnit })}</th>
            <th className="px-4 py-2 text-end font-medium">{t("Selling price")}</th><th className="px-4 py-2 text-start font-medium">{t("Barcode")}</th>
            <th className="px-4 py-2 text-center font-medium">{t("Default sale")}</th><th className="px-4 py-2 text-center font-medium">{t("Default purchase")}</th><th className="w-10" />
          </tr></thead>
          <tbody className="divide-y divide-line">
            <tr className="bg-slate-50 text-muted"><td className="px-4 py-2">{t(label(baseUnit))} ({t("base")})</td><td className="tabular px-4 py-2 text-end">1</td>
              <td className="tabular px-4 py-2 text-end">{Number(basePrice).toFixed(2)}</td><td colSpan={4} /></tr>
            {list.map((r, i) => (
              <tr key={i}>
                <td className="px-4 py-2"><Select value={r.unitCode} aria-label={t("Unit")} onChange={(e) => set(i, { unitCode: e.target.value })}>
                  {unitCodes.filter((u) => u.code !== baseUnit).map((u) => <option key={u.code} value={u.code}>{t(u.label)}</option>)}</Select></td>
                <td className="px-4 py-2"><Input type="number" min="0" step="any" className="text-end" aria-label={t("Contains ({unit})", { unit: baseUnit })} value={r.factor} onChange={(e) => set(i, { factor: e.target.value })} /></td>
                <td className="px-4 py-2"><Input type="number" min="0" step="any" className="text-end" aria-label={t("Selling price")} placeholder={r.factor ? (Number(basePrice) * Number(r.factor)).toFixed(2) : ""} value={r.price} onChange={(e) => set(i, { price: e.target.value })} /></td>
                <td className="px-4 py-2"><Input aria-label={t("Barcode")} value={r.barcode} onChange={(e) => set(i, { barcode: e.target.value })} /></td>
                <td className="px-4 py-2 text-center"><input type="radio" name={`sd-${productId}`} aria-label={t("Default sale")} checked={r.salesDefault} onChange={() => setRows(list.map((x, k) => ({ ...x, salesDefault: k === i })))} /></td>
                <td className="px-4 py-2 text-center"><input type="radio" name={`pd-${productId}`} aria-label={t("Default purchase")} checked={r.purchaseDefault} onChange={() => setRows(list.map((x, k) => ({ ...x, purchaseDefault: k === i })))} /></td>
                <td className="px-2 py-2"><Button type="button" variant="ghost" aria-label={t("Remove line")} onClick={() => setRows(list.filter((_, k) => k !== i))}><Trash2 className="size-4" aria-hidden /></Button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="flex flex-wrap gap-2 border-t border-line p-4">
        <Button type="button" variant="secondary" onClick={() => setRows([...list, { unitCode: unitCodes.find((u) => u.code !== baseUnit && !list.some((r) => r.unitCode === u.code))?.code ?? "CT", factor: "", price: "", barcode: "", salesDefault: false, purchaseDefault: false }])}>
          <Plus className="size-4" aria-hidden /> {t("Add unit")}</Button>
        <Button type="button" loading={busy} disabled={rows === null} onClick={async () => {
          setBusy(true); setProblem(null);
          try {
            await api("PUT", `/products/${productId}/units`, list.map((r) => ({ unitCode: r.unitCode, factor: Number(r.factor), price: r.price === "" ? null : Number(r.price),
              barcode: r.barcode || null, salesDefault: r.salesDefault, purchaseDefault: r.purchaseDefault })));
            setRows(null); setSaved(true); await mutate(); await globalMutate("/products/units");
          } catch (e) { setProblem(e instanceof ApiError ? e.message : t("Could not reach the server")); }
          setBusy(false);
        }}>{t("Save units")}</Button>
      </div>
    </Card>
  );
}
