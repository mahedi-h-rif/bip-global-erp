"use client";

import type { DeliveryNote } from "@/lib/types";
import { Input } from "@/components/ui";
import { useT } from "@/lib/i18n";

/** One delivery line's share of the invoice being made: line = its position on the note (from 0). */
export type InvoicePick = { deliveryNoteId: number; line: number; quantity: number };

/** Typed quantities, keyed "noteId:line". A line not typed in takes everything still to invoice. */
export type QuantityInput = Record<string, string>;

const num = (v: unknown) => Number(v ?? 0) || 0;
const fmt = (n: number) => String(Number(n.toFixed(4)));
export const leftToInvoice = (l: { quantity: number | string; invoiced?: number | null }) => num(l.quantity) - num(l.invoiced);

/** What the server needs: one pick per line still open, defaulting to the whole remaining quantity. */
export function invoicePicks(notes: DeliveryNote[], typed: QuantityInput): InvoicePick[] {
  return notes.flatMap((n) =>
    n.lines.flatMap((l, i) => {
      const left = leftToInvoice(l);
      if (left <= 0) return [];
      const v = typed[`${n.id}:${i}`];
      return [{ deliveryNoteId: n.id, line: i, quantity: v === undefined || v === "" ? left : Number(v) }];
    }),
  );
}

/**
 * The lines of delivered notes with how much is already invoiced and a box for how much goes on this invoice.
 * Lowering a quantity leaves the rest open for another invoice.
 */
export function InvoiceQuantities({ notes, value, onChange }: { notes: DeliveryNote[]; value: QuantityInput; onChange: (v: QuantityInput) => void }) {
  const t = useT();
  const rows = notes.flatMap((n) => n.lines.map((l, i) => ({ n, l, i, left: leftToInvoice(l) })).filter((r) => r.left > 0));
  if (rows.length === 0) return <p className="text-sm text-muted">{t("Nothing left to invoice on this delivery.")}</p>;
  const many = notes.length > 1;
  return (
    <div className="overflow-x-auto rounded-lg border border-line">
      <table className="w-full text-sm">
        <thead className="bg-canvas text-xs text-muted">
          <tr>
            {many && <th className="px-3 py-2 text-start font-medium">{t("Delivery")}</th>}
            <th className="px-3 py-2 text-start font-medium">{t("Item")}</th>
            <th className="px-3 py-2 text-end font-medium">{t("Delivered")}</th>
            <th className="px-3 py-2 text-end font-medium">{t("Already invoiced")}</th>
            <th className="px-3 py-2 text-end font-medium">{t("Invoice now")}</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {rows.map(({ n, l, i, left }) => {
            const key = `${n.id}:${i}`;
            const v = value[key] ?? fmt(left);
            const over = Number(v) > left + 1e-9;
            return (
              <tr key={key}>
                {many && <td className="tabular px-3 py-2 text-muted">{n.number}</td>}
                <td className="px-3 py-2">{l.name}</td>
                <td className="tabular px-3 py-2 text-end">{fmt(num(l.quantity))} {l.unitCode}</td>
                <td className="tabular px-3 py-2 text-end text-muted">{fmt(num(l.invoiced))}</td>
                <td className="px-3 py-2">
                  <Input type="number" min="0" max={left} step="any" className={`ms-auto w-28 text-end ${over ? "border-bad" : ""}`} value={v}
                         aria-label={t("Quantity to invoice for {item}", { item: l.name })}
                         onChange={(e) => onChange({ ...value, [key]: e.target.value })} />
                  <span className={`mt-0.5 block text-end text-[11px] ${over ? "text-bad" : "text-muted"}`}>{t("{n} left to invoice", { n: fmt(left) })}</span>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/** True when every typed quantity is within what is left and at least one line is invoiced. */
export function picksValid(notes: DeliveryNote[], typed: QuantityInput) {
  const picks = invoicePicks(notes, typed);
  const byKey = new Map(notes.flatMap((n) => n.lines.map((l, i) => [`${n.id}:${i}`, leftToInvoice(l)] as const)));
  return picks.some((p) => p.quantity > 0) && picks.every((p) => p.quantity >= 0 && p.quantity <= (byKey.get(`${p.deliveryNoteId}:${p.line}`) ?? 0) + 1e-9);
}
