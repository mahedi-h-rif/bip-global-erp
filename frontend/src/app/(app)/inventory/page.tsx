"use client";

import { useState, type FormEvent } from "react";
import useSWR from "swr";
import { ArrowLeftRight, Boxes, FileSpreadsheet, History, Pencil, Plus, ScrollText, Search, SlidersHorizontal, Trash2, Warehouse as WarehouseIcon } from "lucide-react";
import { api, ApiError, download, fetcher } from "@/lib/api";
import type { InventoryAudit, Me, Product, StockMovement, StockRow, StockTransfer, Warehouse } from "@/lib/types";
import { dateTime, money, todayKsa } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Empty, Field, Input, PageHeader, Select, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

type Tab = "stock" | "warehouses" | "transfers" | "movements" | "audit";

const qty = (v: number | string | null | undefined) => Number(v ?? 0).toLocaleString("en-US", { maximumFractionDigits: 3 });

function errText(e: unknown, fallback: string) {
  if (e instanceof ApiError) return e.fields ? Object.values(e.fields).join(", ") : e.message;
  return fallback;
}

function Tabs({ tab, setTab }: { tab: Tab; setTab: (t: Tab) => void }) {
  const t = useT();
  const items: { id: Tab; label: string; icon: typeof Boxes }[] = [
    { id: "stock", label: "Stock levels", icon: Boxes },
    { id: "warehouses", label: "Warehouses", icon: WarehouseIcon },
    { id: "transfers", label: "Transfers", icon: ArrowLeftRight },
    { id: "movements", label: "Movement history", icon: History },
    { id: "audit", label: "Inventory audit", icon: ScrollText },
  ];
  return (
    <div className="mb-4 flex flex-wrap gap-2" role="tablist">
      {items.map((i) => (
        <button
          key={i.id}
          role="tab"
          aria-selected={tab === i.id}
          onClick={() => setTab(i.id)}
          className={`inline-flex items-center gap-1.5 rounded-lg border px-3 py-2 text-sm font-medium ${tab === i.id ? "border-brand bg-brand text-white" : "border-line bg-white text-ink hover:bg-slate-50"}`}
        >
          <i.icon className="size-4" aria-hidden /> {t(i.label)}
        </button>
      ))}
    </div>
  );
}

function AdjustForm({ warehouses, products, onDone }: { warehouses: Warehouse[]; products: Product[]; onDone: () => void }) {
  const t = useT();
  const [productId, setProductId] = useState("");
  const [warehouseId, setWarehouseId] = useState(String(warehouses[0]?.id ?? ""));
  const [mode, setMode] = useState<"add" | "remove" | "count">("add");
  const [amount, setAmount] = useState("");
  const [note, setNote] = useState("");
  const [opening, setOpening] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const n = Number(amount);
    try {
      await api("POST", "/stock/adjust", {
        productId: Number(productId),
        warehouseId: Number(warehouseId),
        quantity: mode === "count" ? null : mode === "add" ? n : -n,
        countedQuantity: mode === "count" ? n : null,
        note: note || null,
        opening,
      });
      onDone();
    } catch (err) {
      setError(errText(err, t("Could not reach the server")));
      setBusy(false);
    }
  }

  return (
    <Card className="mb-4">
      <CardHeader title={t("Adjust stock")} subtitle={t("Opening stock, received goods, damage or a physical count. Every change is kept in the movement history.")} />
      <form onSubmit={submit} className="space-y-4 p-5">
        {error && <Alert tone="bad" title={t("Not saved")}>{error}</Alert>}
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
          <Field label={t("Product")}>
            <Select required value={productId} onChange={(e) => setProductId(e.target.value)}>
              <option value="">{t("Select…")}</option>
              {products.filter((p) => p.trackStock).map((p) => <option key={p.id} value={p.id}>{p.number} · {p.name}</option>)}
            </Select>
          </Field>
          <Field label={t("Warehouse")}>
            <Select required value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)}>
              {warehouses.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
            </Select>
          </Field>
          <Field label={t("Change")}>
            <Select value={mode} onChange={(e) => setMode(e.target.value as typeof mode)}>
              <option value="add">{t("Add to stock")}</option>
              <option value="remove">{t("Remove from stock")}</option>
              <option value="count">{t("Set to counted quantity")}</option>
            </Select>
          </Field>
          <Field label={mode === "count" ? t("Counted quantity") : t("Quantity")}>
            <Input required type="number" min="0" step="any" value={amount} onChange={(e) => setAmount(e.target.value)} />
          </Field>
        </div>
        <div className="grid gap-4 md:grid-cols-[1fr_auto] md:items-end">
          <Field label={t("Note")} optional>
            <Input value={note} onChange={(e) => setNote(e.target.value)} placeholder={t("e.g. GRN 1023, damaged in transit")} />
          </Field>
          <label className="inline-flex items-center gap-2 pb-2 text-sm text-muted">
            <input type="checkbox" checked={opening} onChange={(e) => setOpening(e.target.checked)} className="size-4 accent-[var(--color-brand)]" />
            {t("Opening stock")}
          </label>
        </div>
        <div className="flex gap-2">
          <Button type="submit" loading={busy}>{t("Save")}</Button>
          <Button type="button" variant="ghost" onClick={onDone}>{t("Cancel")}</Button>
        </div>
      </form>
    </Card>
  );
}

function StockTab({ warehouses, products, canWrite }: { warehouses: Warehouse[]; products: Product[]; canWrite: boolean }) {
  const t = useT();
  const [warehouseId, setWarehouseId] = useState("");
  const [q, setQ] = useState("");
  const [low, setLow] = useState(false);
  const [adjusting, setAdjusting] = useState(false);
  const params = new URLSearchParams({ low: String(low) });
  if (warehouseId) params.set("warehouseId", warehouseId);
  if (q.trim()) params.set("q", q.trim());
  const { data, error, mutate } = useSWR<StockRow[]>(`/stock?${params}`, fetcher, { keepPreviousData: true });
  const total = (data ?? []).reduce((s, r) => s + Number(r.value), 0);

  return (
    <>
      {adjusting && <AdjustForm warehouses={warehouses} products={products} onDone={() => { setAdjusting(false); mutate(); }} />}
      <Card>
        <div className="flex flex-wrap items-center gap-3 border-b border-line p-4">
          <div className="relative min-w-52 flex-1">
            <Search className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted" aria-hidden />
            <Input className="ps-9" placeholder={t("Search products")} value={q} onChange={(e) => setQ(e.target.value)} aria-label={t("Search products")} />
          </div>
          <Select className="w-56" value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)} aria-label={t("Warehouse")}>
            <option value="">{t("All warehouses")}</option>
            {warehouses.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
          </Select>
          <label className="inline-flex items-center gap-2 text-sm text-muted">
            <input type="checkbox" checked={low} onChange={(e) => setLow(e.target.checked)} className="size-4 accent-[var(--color-brand)]" />
            {t("Low stock only")}
          </label>
          {canWrite && !adjusting && <Button onClick={() => setAdjusting(true)}><SlidersHorizontal className="size-4" aria-hidden /> {t("Adjust stock")}</Button>}
        </div>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && (
          <Empty title={t("No stock items")}>{t("Turn on “Track stock” on a product, then add its opening stock here.")}</Empty>
        )}
        {data && data.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-xs text-muted">
                  <th className="px-4 py-2.5 text-start font-medium">{t("Item code")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Product")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Warehouse")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("On hand")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Cost")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Value")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {data.map((r) => (
                  <tr key={`${r.productId}-${r.warehouseId}`}>
                    <td className="tabular whitespace-nowrap px-4 py-3 text-xs text-muted">{r.productNumber ?? "—"}</td>
                    <td className="px-4 py-3 font-medium" dir="auto">{r.productName}</td>
                    <td className="px-4 py-3">{r.warehouseName}</td>
                    <td className={`tabular px-4 py-3 text-end ${r.low ? "font-semibold text-bad" : ""}`}>
                      {qty(r.quantity)} {r.unitCode}
                      {r.low && <span className="ms-2 rounded bg-red-50 px-1.5 py-0.5 text-[11px] text-bad">{t("Low")}</span>}
                    </td>
                    <td className="tabular px-4 py-3 text-end text-muted">{r.costPrice == null ? "—" : money(r.costPrice)}</td>
                    <td className="tabular px-4 py-3 text-end">{money(r.value)}</td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr className="border-t border-line font-semibold">
                  <td colSpan={5} className="px-4 py-3 text-end">{t("Stock value (at cost)")}</td>
                  <td className="tabular px-4 py-3 text-end">{money(total)}</td>
                </tr>
              </tfoot>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}

function WarehousesTab({ warehouses, canWrite, onChange }: { warehouses: Warehouse[]; canWrite: boolean; onChange: () => void }) {
  const t = useT();
  const [editing, setEditing] = useState<Warehouse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function save(e: FormEvent) {
    e.preventDefault();
    if (!editing) return;
    setBusy(true);
    setError(null);
    try {
      if (editing.id) await api("PUT", `/warehouses/${editing.id}`, editing);
      else await api("POST", "/warehouses", editing);
      setEditing(null);
      onChange();
    } catch (err) {
      setError(errText(err, t("Could not reach the server")));
    } finally {
      setBusy(false);
    }
  }

  async function deactivate(w: Warehouse) {
    if (!window.confirm(t("Deactivate warehouse “{name}”?", { name: w.name }))) return;
    try {
      await api("PUT", `/warehouses/${w.id}`, { ...w, active: false });
      onChange();
    } catch (err) {
      setError(errText(err, t("Could not reach the server")));
    }
  }

  return (
    <>
      {error && <div className="mb-4"><Alert tone="bad">{error}</Alert></div>}
      {editing && (
        <Card className="mb-4">
          <CardHeader title={editing.id ? t("Edit warehouse") : t("New warehouse")} />
          <form onSubmit={save} className="space-y-4 p-5">
            <div className="grid gap-4 md:grid-cols-[10rem_1fr_1fr]">
              <Field label={t("Code")}>
                <Input required maxLength={20} dir="ltr" value={editing.code} onChange={(e) => setEditing({ ...editing, code: e.target.value })} placeholder="RUH" />
              </Field>
              <Field label={t("Name")}>
                <Input required dir="auto" value={editing.name} onChange={(e) => setEditing({ ...editing, name: e.target.value })} placeholder={t("e.g. Riyadh warehouse")} />
              </Field>
              <Field label={t("Location")} optional>
                <Input dir="auto" value={editing.location ?? ""} onChange={(e) => setEditing({ ...editing, location: e.target.value })} />
              </Field>
            </div>
            <label className="inline-flex items-center gap-2 text-sm">
              <input type="checkbox" checked={!!editing.isDefault} onChange={(e) => setEditing({ ...editing, isDefault: e.target.checked })} className="size-4 accent-[var(--color-brand)]" />
              {t("Default warehouse for invoices and delivery notes")}
            </label>
            <div className="flex gap-2">
              <Button type="submit" loading={busy}>{t("Save")}</Button>
              <Button type="button" variant="ghost" onClick={() => setEditing(null)}>{t("Cancel")}</Button>
            </div>
          </form>
        </Card>
      )}
      <Card>
        <CardHeader
          title={t("Warehouses")}
          action={canWrite && !editing && <Button onClick={() => setEditing({ code: "", name: "" })}><Plus className="size-4" aria-hidden /> {t("New warehouse")}</Button>}
        />
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-line text-xs text-muted">
              <th className="px-4 py-2.5 text-start font-medium">{t("Code")}</th>
              <th className="px-4 py-2.5 text-start font-medium">{t("Name")}</th>
              <th className="px-4 py-2.5 text-start font-medium">{t("Location")}</th>
              <th className="px-4 py-2.5 text-end font-medium">{t("Stock value")}</th>
              <th className="w-24" />
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {warehouses.map((w) => (
              <tr key={w.id}>
                <td className="tabular px-4 py-3 text-xs">{w.code}</td>
                <td className="px-4 py-3 font-medium" dir="auto">
                  {w.name}
                  {w.isDefault && <span className="ms-2 rounded bg-blue-50 px-1.5 py-0.5 text-[11px] text-brand">{t("Default")}</span>}
                </td>
                <td className="px-4 py-3 text-muted" dir="auto">{w.location ?? "—"}</td>
                <td className="tabular px-4 py-3 text-end">{money(w.stockValue ?? 0)}</td>
                <td className="px-2 py-2 text-end">
                  {canWrite && (
                    <span className="inline-flex gap-1">
                      <Button variant="ghost" onClick={() => setEditing(w)} aria-label={t("Edit {name}", { name: w.name })}><Pencil className="size-4" aria-hidden /></Button>
                      {!w.isDefault && <Button variant="ghost" onClick={() => deactivate(w)} aria-label={t("Deactivate {name}", { name: w.name })}><Trash2 className="size-4" aria-hidden /></Button>}
                    </span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </>
  );
}

function TransfersTab({ warehouses, products, canWrite }: { warehouses: Warehouse[]; products: Product[]; canWrite: boolean }) {
  const t = useT();
  const { data, mutate } = useSWR<StockTransfer[]>("/stock/transfers", fetcher);
  const [open, setOpen] = useState(false);
  const [from, setFrom] = useState(String(warehouses[0]?.id ?? ""));
  const [to, setTo] = useState(String(warehouses[1]?.id ?? ""));
  const [date, setDate] = useState(todayKsa());
  const [note, setNote] = useState("");
  const [lines, setLines] = useState<{ productId: string; quantity: string }[]>([{ productId: "", quantity: "" }]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const stockProducts = products.filter((p) => p.trackStock);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api("POST", "/stock/transfers", {
        fromWarehouseId: Number(from),
        toWarehouseId: Number(to),
        transferDate: date,
        note: note || null,
        lines: lines.filter((l) => l.productId).map((l) => ({ productId: Number(l.productId), quantity: Number(l.quantity) })),
      });
      setOpen(false);
      setLines([{ productId: "", quantity: "" }]);
      setNote("");
      mutate();
    } catch (err) {
      setError(errText(err, t("Could not reach the server")));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      {open && (
        <Card className="mb-4">
          <CardHeader title={t("New transfer")} subtitle={t("Moves stock between two warehouses in one step.")} />
          <form onSubmit={submit} className="space-y-4 p-5">
            {error && <Alert tone="bad" title={t("Not saved")}>{error}</Alert>}
            <div className="grid gap-4 md:grid-cols-3">
              <Field label={t("From")}>
                <Select value={from} onChange={(e) => setFrom(e.target.value)}>
                  {warehouses.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
                </Select>
              </Field>
              <Field label={t("To")}>
                <Select value={to} onChange={(e) => setTo(e.target.value)}>
                  {warehouses.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
                </Select>
              </Field>
              <Field label={t("Date")}>
                <Input type="date" value={date} onChange={(e) => setDate(e.target.value)} />
              </Field>
            </div>
            {lines.map((l, i) => (
              <div key={i} className="grid gap-3 md:grid-cols-[1fr_10rem_auto] md:items-end">
                <Field label={i === 0 ? t("Product") : ""}>
                  <Select required value={l.productId} onChange={(e) => setLines(lines.map((x, j) => (j === i ? { ...x, productId: e.target.value } : x)))}>
                    <option value="">{t("Select…")}</option>
                    {stockProducts.map((p) => <option key={p.id} value={p.id}>{p.number} · {p.name}</option>)}
                  </Select>
                </Field>
                <Field label={i === 0 ? t("Quantity") : ""}>
                  <Input required type="number" min="0" step="any" value={l.quantity} onChange={(e) => setLines(lines.map((x, j) => (j === i ? { ...x, quantity: e.target.value } : x)))} />
                </Field>
                <Button type="button" variant="ghost" disabled={lines.length === 1} onClick={() => setLines(lines.filter((_, j) => j !== i))} aria-label={t("Remove line")}>
                  <Trash2 className="size-4" aria-hidden />
                </Button>
              </div>
            ))}
            <Button type="button" variant="secondary" onClick={() => setLines([...lines, { productId: "", quantity: "" }])}><Plus className="size-4" aria-hidden /> {t("Add line")}</Button>
            <Field label={t("Note")} optional>
              <Input value={note} onChange={(e) => setNote(e.target.value)} />
            </Field>
            <div className="flex gap-2">
              <Button type="submit" loading={busy}>{t("Transfer stock")}</Button>
              <Button type="button" variant="ghost" onClick={() => setOpen(false)}>{t("Cancel")}</Button>
            </div>
          </form>
        </Card>
      )}
      <Card>
        <CardHeader
          title={t("Transfers")}
          action={canWrite && !open && warehouses.length > 1 && <Button onClick={() => setOpen(true)}><ArrowLeftRight className="size-4" aria-hidden /> {t("New transfer")}</Button>}
        />
        {warehouses.length < 2 && <div className="p-4"><Alert tone="info">{t("Add a second warehouse to transfer stock between them.")}</Alert></div>}
        {!data && <Spinner />}
        {data?.length === 0 && <Empty title={t("No transfers yet")} />}
        {data && data.length > 0 && (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-xs text-muted">
                <th className="px-4 py-2.5 text-start font-medium">{t("Number")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("From → To")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Items")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("By")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {data.map((tr) => (
                <tr key={tr.id}>
                  <td className="tabular px-4 py-3 font-medium">{tr.number}</td>
                  <td className="tabular px-4 py-3">{tr.transferDate}</td>
                  <td className="px-4 py-3">{tr.fromWarehouse} → {tr.toWarehouse}</td>
                  <td className="px-4 py-3" dir="auto">{tr.lines.map((l) => `${l.productName} × ${qty(l.quantity)}`).join(", ")}</td>
                  <td className="px-4 py-3 text-xs text-muted">{tr.createdBy}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </>
  );
}

const kindLabel: Record<string, string> = {
  OPENING: "Opening", ADJUST: "Adjustment", SALE: "Sale", RETURN: "Return", TRANSFER_IN: "Transfer in",
  TRANSFER_OUT: "Transfer out", DELIVERY: "Delivery", RECEIPT: "Receipt",
};

function MovementsTab({ warehouses, products }: { warehouses: Warehouse[]; products: Product[] }) {
  const t = useT();
  const [productId, setProductId] = useState("");
  const [warehouseId, setWarehouseId] = useState("");
  const params = new URLSearchParams();
  if (productId) params.set("productId", productId);
  if (warehouseId) params.set("warehouseId", warehouseId);
  const { data, error } = useSWR<StockMovement[]>(`/stock/movements?${params}`, fetcher, { keepPreviousData: true });
  return (
    <Card>
      <div className="flex flex-wrap gap-3 border-b border-line p-4">
        <Select className="w-72" value={productId} onChange={(e) => setProductId(e.target.value)} aria-label={t("Product")}>
          <option value="">{t("All products")}</option>
          {products.filter((p) => p.trackStock).map((p) => <option key={p.id} value={p.id}>{p.number} · {p.name}</option>)}
        </Select>
        <Select className="w-56" value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)} aria-label={t("Warehouse")}>
          <option value="">{t("All warehouses")}</option>
          {warehouses.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
        </Select>
      </div>
      {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
      {!data && !error && <Spinner />}
      {data?.length === 0 && <Empty title={t("No stock movements yet")} />}
      {data && data.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-xs text-muted">
                <th className="px-4 py-2.5 text-start font-medium">{t("When")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Product")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Warehouse")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Type")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Change")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Balance")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Reference")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {data.map((m) => (
                <tr key={m.id}>
                  <td className="tabular whitespace-nowrap px-4 py-3 text-xs">{dateTime(m.at)}</td>
                  <td className="px-4 py-3" dir="auto">{m.productName}</td>
                  <td className="px-4 py-3">{m.warehouseName}</td>
                  <td className="px-4 py-3">{t(kindLabel[m.kind] ?? m.kind)}</td>
                  <td className={`tabular px-4 py-3 text-end font-medium ${Number(m.quantity) < 0 ? "text-bad" : "text-ok"}`}>
                    {Number(m.quantity) > 0 ? "+" : ""}{qty(m.quantity)}
                  </td>
                  <td className="tabular px-4 py-3 text-end">{qty(m.balanceAfter)}</td>
                  <td className="px-4 py-3 text-xs text-muted">{[m.refNumber, m.note, m.createdBy].filter(Boolean).join(" · ")}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

function AuditTab({ warehouses, products }: { warehouses: Warehouse[]; products: Product[] }) {
  const t = useT();
  const stockItems = products.filter((p) => p.trackStock);
  const [productId, setProductId] = useState(String(stockItems[0]?.id ?? ""));
  const [warehouseId, setWarehouseId] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const params = new URLSearchParams({ productId });
  if (warehouseId) params.set("warehouseId", warehouseId);
  if (from) params.set("from", from);
  if (to) params.set("to", to);
  const { data, error } = useSWR<InventoryAudit>(productId ? `/stock/audit?${params}` : null, fetcher, { keepPreviousData: true });
  return (
    <Card>
      <div className="flex flex-wrap items-end gap-3 border-b border-line p-4">
        <Field label={t("Item")}>
          <Select className="w-72" value={productId} onChange={(e) => setProductId(e.target.value)}>
            {stockItems.map((p) => <option key={p.id} value={p.id}>{p.number} · {p.name}</option>)}
          </Select>
        </Field>
        <Field label={t("Warehouse")}>
          <Select className="w-52" value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)}>
            <option value="">{t("All warehouses")}</option>
            {warehouses.map((w) => <option key={w.id} value={w.id}>{w.code} · {w.name}</option>)}
          </Select>
        </Field>
        <Field label={t("From")}><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
        <Field label={t("To")}><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
        <Button variant="secondary" disabled={!productId} onClick={() => download(`/stock/audit.xlsx?${params}`, "inventory-audit.xlsx")}><FileSpreadsheet className="size-4" aria-hidden /> Excel</Button>
      </div>
      {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
      {!productId && <Empty title={t("No stock items")} />}
      {data && data.rows.length > 1 && <AuditChart data={data} />}
      {data && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead><tr className="border-b border-line text-xs text-muted">
              <th className="px-3 py-2.5 text-start font-medium">{t("Posting date")}</th><th className="px-3 py-2.5 text-start font-medium">{t("Document")}</th>
              <th className="px-3 py-2.5 text-start font-medium">{t("Warehouse")}</th><th className="px-3 py-2.5 text-end font-medium">{t("Quantity")}</th>
              <th className="px-3 py-2.5 text-end font-medium">{t("Cost")}</th><th className="px-3 py-2.5 text-end font-medium">{t("Trans. value")}</th>
              <th className="px-3 py-2.5 text-end font-medium">{t("Cumulative qty")}</th><th className="px-3 py-2.5 text-end font-medium">{t("Cumulative value")}</th>
            </tr></thead>
            <tbody className="divide-y divide-line">
              <tr className="bg-slate-50 text-muted"><td className="px-3 py-2" colSpan={6}>{t("Opening balance")}</td>
                <td className="tabular px-3 py-2 text-end">{qty(data.openingQty)}</td><td className="tabular px-3 py-2 text-end">{money(data.openingValue)}</td></tr>
              {data.rows.map((r, i) => (
                <tr key={i}>
                  <td className="tabular px-3 py-2">{r.postingDate}</td>
                  <td className="px-3 py-2"><span className="tabular font-medium">{r.document}</span> <span className="text-xs text-muted">{t(kindLabel[r.kind] ?? r.kind)}</span></td>
                  <td className="px-3 py-2">{r.warehouseName}</td>
                  <td className={`tabular px-3 py-2 text-end ${Number(r.quantity) < 0 ? "text-bad" : "text-ok"}`}>{qty(r.quantity)}</td>
                  <td className="tabular px-3 py-2 text-end">{r.cost == null ? "—" : Number(r.cost).toFixed(4)}</td>
                  <td className="tabular px-3 py-2 text-end">{money(r.transValue)}</td>
                  <td className="tabular px-3 py-2 text-end">{qty(r.cumulativeQty)}</td>
                  <td className="tabular px-3 py-2 text-end">{money(r.cumulativeValue)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot><tr className="border-t border-line font-semibold"><td colSpan={6} className="px-3 py-2 text-end">{t("Closing balance")}</td>
              <td className="tabular px-3 py-2 text-end">{qty(data.closingQty)}</td><td className="tabular px-3 py-2 text-end">{money(data.closingValue)}</td></tr></tfoot>
          </table>
        </div>
      )}
    </Card>
  );
}

/**
 * The inventory audit as a picture: cumulative quantity (bars) and cumulative value (line) after every movement,
 * so receipts, landed costs and sales at moving average are visible at a glance.
 */
function AuditChart({ data }: { data: InventoryAudit }) {
  const t = useT();
  const pts = [{ q: Number(data.openingQty), v: Number(data.openingValue), label: t("Opening balance") },
    ...data.rows.map((r) => ({ q: Number(r.cumulativeQty), v: Number(r.cumulativeValue), label: `${r.postingDate} · ${r.document}` }))];
  const W = 720, H = 170, L = 8, R = 8, T = 10, B = 10;
  const maxQ = Math.max(1, ...pts.map((p) => p.q)), maxV = Math.max(1, ...pts.map((p) => p.v));
  const step = (W - L - R) / pts.length, bw = Math.max(2, step * 0.6);
  const yq = (q: number) => T + (H - T - B) * (1 - Math.max(0, q) / maxQ), yv = (v: number) => T + (H - T - B) * (1 - Math.max(0, v) / maxV);
  const line = pts.map((p, i) => `${i ? "L" : "M"}${L + step * i + step / 2},${yv(p.v)}`).join(" ");
  return (
    <div className="border-b border-line p-4">
      <svg viewBox={`0 0 ${W} ${H}`} className="w-full" role="img" aria-label={t("Cumulative quantity and value")}>
        {pts.map((p, i) => <rect key={i} x={L + step * i + (step - bw) / 2} y={yq(p.q)} width={bw} height={H - B - yq(p.q)} rx="1.5" className="fill-brand/25"><title>{`${p.label} · ${t("Cumulative qty")} ${p.q}`}</title></rect>)}
        <path d={line} fill="none" stroke="#059669" strokeWidth="2" />
        {pts.map((p, i) => <circle key={i} cx={L + step * i + step / 2} cy={yv(p.v)} r="2.5" fill="#059669"><title>{`${p.label} · ${t("Cumulative value")} ${money(p.v)}`}</title></circle>)}
      </svg>
      <div className="mt-1 flex flex-wrap gap-4 text-xs text-muted">
        <span className="inline-flex items-center gap-1.5"><span className="size-2.5 rounded-sm bg-brand/25" />{t("Cumulative qty")} ({t("max")} {maxQ})</span>
        <span className="inline-flex items-center gap-1.5"><span className="h-0.5 w-4 bg-emerald-600" />{t("Cumulative value")} ({t("max")} {money(maxV)})</span>
      </div>
    </div>
  );
}

export default function InventoryPage() {
  const t = useT();
  const [tab, setTab] = useState<Tab>("stock");
  const { data: me } = useSWR<Me>("/auth/me", fetcher);
  const { data: warehouses, mutate: mutateWh } = useSWR<Warehouse[]>("/warehouses", fetcher);
  const { data: products } = useSWR<Product[]>("/products", fetcher);
  const canWrite = me?.role === "ADMIN" || me?.role === "ACCOUNTANT" || me?.role === "OPERATOR";

  return (
    <>
      <PageHeader title={t("Inventory")} subtitle={t("Stock per warehouse. Issued invoices take stock out, credit notes put it back.")} />
      <Tabs tab={tab} setTab={setTab} />
      {!warehouses || !products ? (
        <Spinner />
      ) : tab === "stock" ? (
        <StockTab warehouses={warehouses} products={products} canWrite={canWrite} />
      ) : tab === "warehouses" ? (
        <WarehousesTab warehouses={warehouses} canWrite={canWrite} onChange={() => mutateWh()} />
      ) : tab === "transfers" ? (
        <TransfersTab warehouses={warehouses} products={products} canWrite={canWrite} />
      ) : tab === "audit" ? (
        <AuditTab warehouses={warehouses} products={products} />
      ) : (
        <MovementsTab warehouses={warehouses} products={products} />
      )}
    </>
  );
}
