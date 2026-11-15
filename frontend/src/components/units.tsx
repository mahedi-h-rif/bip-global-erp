"use client";

import useSWR from "swr";
import { Plus, Trash2 } from "lucide-react";
import { fetcher } from "@/lib/api";
import { unitCodes, unitName } from "@/lib/format";
import { Button, Input, Select } from "@/components/ui";
import { useT, type TFunction } from "@/lib/i18n";

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

/** The unit a new line of an item starts in: the unit chosen when the item was created. Other units are picked per line. */
export function defaultUnit(units: ItemUnit[], productId: number | null | undefined, purchase = false): ItemUnit | undefined {
  void purchase;
  return unitsOf(units, productId).find((u) => u.base);
}

const label = (code: string) => unitName(code);

/** A unit shown in full ("Piece", not "PCE"), translated. */
export function Unit({ code }: { code?: string | null }) {
  const t = useT();
  return <>{t(unitName(code))}</>;
}

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
      {!unitCodes.some((u) => u.code === value) && <option value={value}>{t(unitName(value))}</option>}
      {unitCodes.map((u) => <option key={u.code} value={u.code}>{t(u.label)}</option>)}
    </Select>
  );
}

/** "3 CT (72 PCE)" — a quantity with its base-unit equivalent when the unit isn't the base one. */
export function qtyWithBase(qty: number | string, unitCode: string, factor: number | string | null | undefined, baseUnit?: string) {
  const f = Number(factor ?? 1), q = Number(qty);
  return f === 1 || !baseUnit ? `${q} ${unitName(unitCode)}` : `${q} ${unitName(unitCode)} (${+(q * f).toFixed(3)} ${unitName(baseUnit)})`;
}


/** One alternative unit of an item, as typed in the product form. */
export interface UomRow { unitCode: string; factor: string; price: string; barcode: string; salesDefault: boolean; purchaseDefault: boolean }

/** An item's saved units (GET /products/{id}/units) as form rows. */
export function uomRows(data: { unitCode: string; factor: number; price: number | null; barcode: string | null; salesDefault: boolean; purchaseDefault: boolean }[]): UomRow[] {
  return data.map((u) => ({ unitCode: u.unitCode, factor: String(Number(u.factor)), price: u.price == null ? "" : String(Number(u.price)),
    barcode: u.barcode ?? "", salesDefault: u.salesDefault, purchaseDefault: u.purchaseDefault }));
}

/** Body for PUT /products/{id}/units. Rows in the base unit are dropped: the base unit is always factor 1. */
export function uomPayload(rows: UomRow[], baseUnit: string) {
  return rows.filter((r) => r.unitCode !== baseUnit).map((r) => ({ unitCode: r.unitCode, factor: Number(r.factor), price: r.price === "" ? null : Number(r.price),
    barcode: r.barcode.trim() || null, salesDefault: false, purchaseDefault: false }));
}

/** A problem with the rows the user can fix before saving, or null. */
export function uomProblem(rows: UomRow[], baseUnit: string, t: TFunction): string | null {
  const seen = new Set<string>();
  for (const r of rows) {
    const name = t(unitName(r.unitCode));
    if (r.unitCode === baseUnit) return t("{unit} is already the base unit. Pick another unit or remove the row.", { unit: name });
    if (seen.has(r.unitCode)) return t("{unit} is listed twice.", { unit: name });
    seen.add(r.unitCode);
    if (!(Number(r.factor) > 0)) return t("Enter how many {base} one {unit} contains.", { base: t(unitName(baseUnit)), unit: name });
  }
  return null;
}

/**
 * The units an item is sold in, each with its own price. The first row is the item's own unit (stock is counted in
 * it); each added unit says how many of the first it holds, so stock stays right whichever unit a line uses.
 */
export function UnitRows({ rows, onChange, baseUnit, onBaseUnit, basePrice, onBasePrice, priceError }: {
  rows: UomRow[]; onChange: (rows: UomRow[]) => void; baseUnit: string; onBaseUnit: (code: string) => void;
  basePrice: number | string; onBasePrice: (price: string) => void; priceError?: string;
}) {
  const t = useT();
  const set = (i: number, patch: Partial<UomRow>) => onChange(rows.map((r, k) => (k === i ? { ...r, ...patch } : r)));
  const base = Number(basePrice) || 0;
  const baseName = t(unitName(baseUnit));
  return (
    <div className="rounded-lg border border-line">
      <div className="border-b border-line px-4 py-3">
        <p className="text-sm font-semibold">{t("Units and prices")}</p>
        <p className="text-xs text-muted">{t("Prices exclude VAT. Add every unit this item is sold in, each with its own price. Stock is counted in the first unit.")}</p>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead><tr className="border-b border-line text-xs text-muted">
            <th className="px-3 py-2 text-start font-medium">{t("Unit")}</th>
            <th className="px-3 py-2 text-end font-medium">{t("Price (SAR, excl. VAT)")}</th>
            <th className="px-3 py-2 text-end font-medium">{t("Contains ({unit})", { unit: baseName })}</th><th className="w-10" />
          </tr></thead>
          <tbody className="divide-y divide-line">
            <tr>
              <td className="min-w-40 px-3 py-2"><Select value={baseUnit} aria-label={t("Unit")} onChange={(e) => onBaseUnit(e.target.value)}>
                {!unitCodes.some((u) => u.code === baseUnit) && <option value={baseUnit}>{baseName}</option>}
                {unitCodes.map((u) => <option key={u.code} value={u.code}>{t(u.label)}</option>)}</Select></td>
              <td className="min-w-32 px-3 py-2">
                <Input required type="number" min="0" step="any" className="text-end" aria-label={t("Price (SAR, excl. VAT)")} value={basePrice} onChange={(e) => onBasePrice(e.target.value)} />
                {priceError && <span className="mt-1 block text-xs text-bad">{priceError}</span>}
              </td>
              <td className="tabular px-3 py-2 text-end text-muted">1</td><td />
            </tr>
            {rows.map((r, i) => (
              <tr key={i}>
                <td className="min-w-40 px-3 py-2"><Select value={r.unitCode} aria-label={t("Unit")} onChange={(e) => set(i, { unitCode: e.target.value })}>
                  {unitCodes.filter((u) => u.code !== baseUnit || u.code === r.unitCode).map((u) => <option key={u.code} value={u.code}>{t(u.label)}</option>)}</Select></td>
                <td className="min-w-32 px-3 py-2"><Input type="number" min="0" step="any" className="text-end" aria-label={t("Price (SAR, excl. VAT)")} placeholder={Number(r.factor) > 0 ? (base * Number(r.factor)).toFixed(2) : ""} value={r.price} onChange={(e) => set(i, { price: e.target.value })} /></td>
                <td className="min-w-28 px-3 py-2"><Input type="number" min="0" step="any" className="text-end" aria-label={t("Contains ({unit})", { unit: baseName })} placeholder="12" value={r.factor} onChange={(e) => set(i, { factor: e.target.value })} /></td>
                <td className="px-2 py-2"><Button type="button" variant="ghost" aria-label={t("Remove line")} onClick={() => onChange(rows.filter((_, k) => k !== i))}><Trash2 className="size-4" aria-hidden /></Button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="flex flex-wrap items-center gap-3 border-t border-line px-4 py-3">
        <Button type="button" variant="secondary" onClick={() => onChange([...rows, { unitCode: unitCodes.find((u) => u.code !== baseUnit && !rows.some((r) => r.unitCode === u.code))?.code ?? "CT", factor: "", price: "", barcode: "", salesDefault: false, purchaseDefault: false }])}>
          <Plus className="size-4" aria-hidden /> {t("Add unit")}</Button>
        <span className="text-xs text-muted">{t("“Contains”: how many {unit} are in one of that unit, e.g. a carton of 24.", { unit: baseName })}</span>
      </div>
    </div>
  );
}
