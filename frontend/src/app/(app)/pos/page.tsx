"use client";

import { useMemo, useRef, useState } from "react";
import useSWR from "swr";
import { Minus, Plus, Printer, ScanBarcode, ShoppingCart, Trash2 } from "lucide-react";
import { api, ApiError, fetcher, printFile } from "@/lib/api";
import type { Customer, Warehouse } from "@/lib/types";
import { money } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Field, Input, PageHeader, Select, Spinner } from "@/components/ui";
import { UnitSelect, useItemUnits } from "@/components/units";
import { useT } from "@/lib/i18n";

interface GridItem { id: number; number: string | null; name: string; nameAr: string | null; barcode: string | null; category: string | null; unitPrice: number; unitCode: string; trackStock: boolean; onHand: number; scannedUnit?: string | null }
/** {@code unitCode}/{@code factor}: the unit sold (a carton holds {@code factor} base units). */
interface CartLine { item: GridItem; quantity: number; unitPrice: number; discountPct: number; unitCode: string; factor: number }
interface SaleResult { invoiceId: number; number: string; status: string; total: number; paid: number; change: number; due: number }

const r2 = (n: number) => Math.round(n * 100) / 100;

export default function PosPage() {
  const t = useT();
  const { data: warehouses } = useSWR<Warehouse[]>("/warehouses", fetcher);
  const { data: customers } = useSWR<Customer[]>("/customers", fetcher);
  const [warehouseId, setWarehouseId] = useState<string>("");
  const wh = warehouseId || String(warehouses?.[0]?.id ?? "");
  const [q, setQ] = useState("");
  const { data: items, mutate: reload } = useSWR<GridItem[]>(wh ? `/pos/products?warehouseId=${wh}${q.trim() ? `&q=${encodeURIComponent(q.trim())}` : ""}` : null, fetcher, { keepPreviousData: true });
  const [cart, setCart] = useState<CartLine[]>([]);
  const itemUnits = useItemUnits();
  const [customerId, setCustomerId] = useState("");
  const [cash, setCash] = useState("");
  const [card, setCard] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<SaleResult | null>(null);
  const scanRef = useRef<HTMLInputElement>(null);

  const totals = useMemo(() => {
    let net = 0;
    for (const l of cart) net += r2(l.quantity * r2(l.unitPrice * (1 - l.discountPct / 100)));
    const vat = r2(net * 0.15);
    return { net, vat, total: r2(net + vat) };
  }, [cart]);
  const tendered = (Number(cash) || 0) + (Number(card) || 0);
  const change = Math.max(0, r2(tendered - totals.total));

  /** Adds one of {@code unitCode} (default: the scanned unit, else the item's base unit); stock is checked in base units. */
  function add(item: GridItem, unitCode?: string) {
    setDone(null);
    setError(null);
    const code = unitCode ?? item.scannedUnit ?? item.unitCode;
    const u = itemUnits.find((x) => x.productId === item.id && x.unitCode === code);
    const factor = Number(u?.factor ?? 1);
    setCart((c) => {
      const i = c.findIndex((l) => l.item.id === item.id && l.unitCode === code);
      const inBase = c.filter((l) => l.item.id === item.id).reduce((s, l) => s + l.quantity * l.factor, 0);
      if (item.trackStock && inBase + factor > Number(item.onHand)) { setError(t("Only {n} of “{name}” in stock", { n: String(Number(item.onHand)), name: item.name })); return c; }
      if (i >= 0) return c.map((l, j) => (j === i ? { ...l, quantity: l.quantity + 1 } : l));
      return [...c, { item, quantity: 1, unitPrice: Number(u?.price ?? item.unitPrice), discountPct: 0, unitCode: code, factor }];
    });
  }
  const set = (i: number, patch: Partial<CartLine>) => setCart((c) => c.map((l, j) => (j === i ? { ...l, ...patch } : l)));

  async function sell() {
    setBusy(true);
    setError(null);
    const payments = [];
    if (Number(cash) > 0) payments.push({ method: "CASH", amount: Number(cash) });
    if (Number(card) > 0) payments.push({ method: "CARD", amount: Number(card) });
    try {
      const r = await api<SaleResult>("POST", "/pos/sale", {
        warehouseId: Number(wh), customerId: customerId ? Number(customerId) : null, payments,
        lines: cart.map((l) => ({ productId: l.item.id, quantity: l.quantity, unitPrice: l.unitPrice, discountPct: l.discountPct, unitCode: l.unitCode })),
      });
      setDone(r);
      setCart([]); setCash(""); setCard(""); setCustomerId("");
      reload();
      scanRef.current?.focus();
    } catch (e) {
      setError(e instanceof ApiError ? (e.fields ? Object.values(e.fields).join(", ") : e.message) : t("Could not reach the server"));
    } finally { setBusy(false); }
  }

  return (
    <>
      <PageHeader title={t("Point of sale")} subtitle={t("Each sale is a simplified tax invoice reported to ZATCA, with an 80 mm receipt.")} />
      <div className="grid gap-4 lg:grid-cols-[1fr_26rem]">
        <Card>
          <div className="flex flex-wrap items-end gap-3 border-b border-line p-4">
            <Field label={t("Warehouse")}>
              <Select className="w-52" value={wh} onChange={(e) => { setWarehouseId(e.target.value); setCart([]); }}>
                {warehouses?.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
              </Select>
            </Field>
            <Field label={t("Scan barcode or search")} className="min-w-64 flex-1">
              <div className="relative">
                <ScanBarcode className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted" aria-hidden />
                <Input ref={scanRef} autoFocus className="ps-9" value={q} onChange={(e) => setQ(e.target.value)}
                       onKeyDown={(e) => { if (e.key === "Enter" && items?.length) { const exact = items.find((i) => i.barcode === q.trim()) ?? (items.length === 1 ? items[0] : null); if (exact) { add(exact); setQ(""); } } }} />
              </div>
            </Field>
          </div>
          {!items ? <Spinner /> : (
            <div className="grid max-h-[60vh] grid-cols-2 gap-2 overflow-y-auto p-4 sm:grid-cols-3 xl:grid-cols-4">
              {items.map((i) => {
                const out = i.trackStock && Number(i.onHand) <= 0;
                return (
                  <button key={i.id} disabled={out} onClick={() => add(i)}
                          className={`flex flex-col rounded-lg border p-3 text-start text-sm transition ${out ? "cursor-not-allowed border-line bg-slate-50 text-muted" : "border-line bg-white hover:border-brand hover:shadow-sm"}`}>
                    <span className="font-medium" dir="auto">{i.name}</span>
                    {i.nameAr && <span className="text-xs text-muted" dir="rtl">{i.nameAr}</span>}
                    <span className="mt-auto pt-2 font-semibold tabular">{money(i.unitPrice)}</span>
                    <span className={`text-[11px] ${out ? "text-bad" : "text-muted"}`}>{i.trackStock ? t("{n} in stock", { n: String(Number(i.onHand)) }) : t("Unlimited")}</span>
                  </button>
                );
              })}
              {items.length === 0 && <p className="col-span-full p-6 text-center text-sm text-muted">{t("No products found")}</p>}
            </div>
          )}
        </Card>

        <Card>
          <CardHeader title={<span className="inline-flex items-center gap-2"><ShoppingCart className="size-4" aria-hidden /> {t("Cart")}</span>} />
          <div className="space-y-3 p-4">
            {error && <Alert tone="bad">{error}</Alert>}
            {done && (
              <Alert tone="ok" title={t("Sale {number} completed", { number: done.number })}>
                <span className="block">{t("Total")} {money(done.total)} · {t("Paid")} {money(done.paid)}{Number(done.change) > 0 && ` · ${t("Change")} ${money(done.change)}`}{Number(done.due) > 0 && ` · ${t("Due")} ${money(done.due)}`}</span>
                <Button className="mt-2" variant="secondary" onClick={() => printFile(`/pos/receipt/${done.invoiceId}?tendered=${Number(done.paid) + Number(done.change)}`)}><Printer className="size-4" aria-hidden /> {t("Print receipt")}</Button>
              </Alert>
            )}
            {cart.length === 0 && !done && <p className="py-6 text-center text-sm text-muted">{t("Tap a product or scan a barcode.")}</p>}
            {cart.map((l, i) => (
              <div key={`${l.item.id}-${l.unitCode}`} className="rounded-lg border border-line p-2 text-sm">
                <div className="flex items-start justify-between gap-2">
                  <span className="font-medium" dir="auto">{l.item.name}</span>
                  <Button variant="ghost" onClick={() => setCart(cart.filter((_, j) => j !== i))} aria-label={t("Remove")}><Trash2 className="size-4" aria-hidden /></Button>
                </div>
                <div className="mt-1 flex flex-wrap items-center gap-2">
                  <Button variant="secondary" onClick={() => (l.quantity > 1 ? set(i, { quantity: l.quantity - 1 }) : setCart(cart.filter((_, j) => j !== i)))} aria-label={t("Less")}><Minus className="size-3" aria-hidden /></Button>
                  <span className="tabular w-8 text-center">{l.quantity}</span>
                  <Button variant="secondary" onClick={() => add(l.item, l.unitCode)} aria-label={t("More")}><Plus className="size-3" aria-hidden /></Button>
                  <span className="w-28"><UnitSelect productId={l.item.id} value={l.unitCode} onPick={(u, price, factor) => {
                    const others = cart.filter((x, j) => j !== i && x.item.id === l.item.id).reduce((s2, x) => s2 + x.quantity * x.factor, 0);
                    if (l.item.trackStock && others + l.quantity * factor > Number(l.item.onHand)) { setError(t("Only {n} of “{name}” in stock", { n: String(Number(l.item.onHand)), name: l.item.name })); return; }
                    set(i, { unitCode: u, factor, unitPrice: price ?? l.unitPrice });
                  }} /></span>
                  <Input type="number" min="0" step="0.01" className="w-24" value={l.unitPrice} onChange={(e) => set(i, { unitPrice: Number(e.target.value) })} aria-label={t("Unit price")} />
                  <Input type="number" min="0" max="100" step="1" className="w-16" value={l.discountPct} onChange={(e) => set(i, { discountPct: Number(e.target.value) })} aria-label={t("Discount %")} title={t("Discount %")} />
                  <span className="tabular ms-auto font-semibold">{money(r2(l.quantity * r2(l.unitPrice * (1 - l.discountPct / 100)) * 1.15))}</span>
                </div>
              </div>
            ))}
            {cart.length > 0 && (
              <>
                <dl className="space-y-1 border-t border-line pt-3 text-sm">
                  <div className="flex justify-between"><dt className="text-muted">{t("Total excl. VAT")}</dt><dd className="tabular">{money(totals.net)}</dd></div>
                  <div className="flex justify-between"><dt className="text-muted">{t("VAT 15%")}</dt><dd className="tabular">{money(totals.vat)}</dd></div>
                  <div className="flex justify-between text-lg font-semibold"><dt>{t("Total")}</dt><dd className="tabular">{money(totals.total)}</dd></div>
                </dl>
                <Field label={t("Customer")} optional hint={t("Needed for credit or part-paid sales")}>
                  <Select value={customerId} onChange={(e) => setCustomerId(e.target.value)}>
                    <option value="">{t("Walk-in customer")}</option>
                    {customers?.map((c) => <option key={c.id} value={c.id}>{c.nameEn || c.nameAr}</option>)}
                  </Select>
                </Field>
                <div className="grid grid-cols-2 gap-2">
                  <Field label={t("Cash")}><Input type="number" min="0" step="0.01" value={cash} onChange={(e) => setCash(e.target.value)} placeholder={totals.total.toFixed(2)} /></Field>
                  <Field label={t("Card")}><Input type="number" min="0" step="0.01" value={card} onChange={(e) => setCard(e.target.value)} /></Field>
                </div>
                <div className="flex flex-wrap gap-2">
                  <Button variant="secondary" onClick={() => { setCash(totals.total.toFixed(2)); setCard(""); }}>{t("Exact cash")}</Button>
                  <Button variant="secondary" onClick={() => { setCard(totals.total.toFixed(2)); setCash(""); }}>{t("All by card")}</Button>
                </div>
                <p className="text-sm">{tendered >= totals.total ? <span className="text-ok">{t("Change")}: <b className="tabular">{money(change)}</b></span>
                  : <span className="text-amber-700">{t("Still due")}: <b className="tabular">{money(r2(totals.total - tendered))}</b></span>}</p>
                <Button className="w-full" loading={busy} onClick={sell}>{t("Complete sale")}</Button>
              </>
            )}
          </div>
        </Card>
      </div>
    </>
  );
}
