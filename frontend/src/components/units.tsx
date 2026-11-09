"use client";

import useSWR from "swr";
import { fetcher } from "@/lib/api";
import { unitCodes } from "@/lib/format";
import { Select } from "@/components/ui";
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
