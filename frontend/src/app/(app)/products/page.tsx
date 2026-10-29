"use client";

import { useState, type FormEvent } from "react";
import useSWR from "swr";
import { Archive, Pencil, Plus, RotateCcw, Search, Trash2 } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { Me, Product } from "@/lib/types";
import { money } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Empty, Field, Input, PageHeader, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

const blank: Product = { name: "", unitPrice: "" };

function ProductForm({ initial, onDone }: { initial: Product; onDone: () => void }) {
  const t = useT();
  const [p, setP] = useState<Product>(initial);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const body = { ...p, name: p.name.trim(), unitPrice: Number(p.unitPrice) };
      if (p.id) await api("PUT", `/products/${p.id}`, body);
      else await api("POST", "/products", body);
      onDone();
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError(0, "network", t("Could not reach the server")));
      setBusy(false);
    }
  }

  return (
    <Card className="mb-6">
      <CardHeader title={p.id ? t("Edit product") : t("New product")} subtitle={t("Price excludes VAT. Changing a product never changes invoices already issued.")} />
      <form onSubmit={submit} className="space-y-4 p-5">
        {error && !error.fields && <Alert tone="bad" title={t("Not saved")}>{error.message}</Alert>}
        <div className="grid gap-4 md:grid-cols-[1fr_12rem]">
          <Field label={t("Product name")} error={error?.fields?.name}>
            <Input required autoFocus value={p.name} onChange={(e) => setP({ ...p, name: e.target.value })} placeholder={t("e.g. Diesel - ديزل")} />
          </Field>
          <Field label={t("Unit price (SAR, excl. VAT)")} error={error?.fields?.unitPrice}>
            <Input required type="number" min="0" step="any" value={p.unitPrice} onChange={(e) => setP({ ...p, unitPrice: e.target.value })} />
          </Field>
        </div>
        <div className="flex gap-2">
          <Button type="submit" loading={busy}>{t("Save product")}</Button>
          <Button type="button" variant="ghost" onClick={onDone}>{t("Cancel")}</Button>
        </div>
      </form>
    </Card>
  );
}

export default function ProductsPage() {
  const t = useT();
  const { data: me } = useSWR<Me>("/auth/me", fetcher);
  const [q, setQ] = useState("");
  const [showInactive, setShowInactive] = useState(false);
  const query = new URLSearchParams({ all: String(showInactive) });
  if (q.trim()) query.set("q", q.trim());
  const { data, error, mutate } = useSWR<Product[]>(`/products?${query}`, fetcher, { keepPreviousData: true });
  const [editing, setEditing] = useState<Product | null>(null);
  const [notice, setNotice] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);
  const canWrite = me?.role !== "VIEWER";

  async function remove(p: Product) {
    if (!window.confirm(t("Delete “{name}”? If it appears on invoices it is deactivated instead.", { name: p.name }))) return;
    setNotice(null);
    try {
      const r = await api<{ result: "deleted" | "deactivated" }>("DELETE", `/products/${p.id}`);
      setNotice({
        tone: "ok",
        text: r.result === "deleted" ? t("“{name}” was deleted.", { name: p.name }) : t("“{name}” is used on invoices, so it was deactivated and hidden from the invoice form.", { name: p.name }),
      });
      mutate();
    } catch (e) {
      setNotice({ tone: "bad", text: e instanceof ApiError ? e.message : t("Could not reach the server") });
    }
  }

  async function reactivate(p: Product) {
    try {
      await api("PUT", `/products/${p.id}`, { ...p, active: true });
      mutate();
    } catch (e) {
      setNotice({ tone: "bad", text: e instanceof ApiError ? e.message : t("Could not reach the server") });
    }
  }

  return (
    <>
      <PageHeader
        title={t("Products")}
        subtitle={t("Items you sell, with their price. Pick them on the invoice form.")}
        actions={canWrite && !editing && <Button onClick={() => setEditing(blank)}><Plus className="size-4" aria-hidden /> {t("New product")}</Button>}
      />
      {editing && <ProductForm key={editing.id ?? "new"} initial={editing} onDone={() => { setEditing(null); mutate(); }} />}
      {notice && <div className="mb-4"><Alert tone={notice.tone}>{notice.text}</Alert></div>}
      <Card>
        <div className="flex flex-wrap items-center gap-3 border-b border-line p-4">
          <div className="relative min-w-52 flex-1">
            <Search className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted" aria-hidden />
            <Input className="ps-9" placeholder={t("Search products")} value={q} onChange={(e) => setQ(e.target.value)} aria-label={t("Search products")} />
          </div>
          <label className="inline-flex items-center gap-2 text-sm text-muted">
            <input type="checkbox" checked={showInactive} onChange={(e) => setShowInactive(e.target.checked)} className="size-4 accent-[var(--color-brand)]" />
            {t("Show deactivated")}
          </label>
        </div>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={q ? t("No matching products") : t("No products yet")}>{!q && t("Add the items you sell so staff can pick them on invoices.")}</Empty>}
        {data && data.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-xs text-muted">
                  <th className="px-4 py-2.5 text-start font-medium">{t("Name")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Unit price (excl. VAT)")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("With 15% VAT")}</th>
                  <th className="w-28" />
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {data.map((p) => (
                  <tr key={p.id} className={p.active ? "" : "text-muted"}>
                    <td className="px-4 py-3 font-medium">
                      {p.name}
                      {!p.active && <span className="ms-2 rounded bg-slate-100 px-1.5 py-0.5 text-[11px] font-medium text-slate-600">{t("Deactivated")}</span>}
                    </td>
                    <td className="tabular px-4 py-3 text-end">{money(p.unitPrice)}</td>
                    <td className="tabular px-4 py-3 text-end text-muted">{money(Math.round(Number(p.unitPrice) * 115) / 100)}</td>
                    <td className="px-2 py-2 text-end">
                      {canWrite && (
                        <span className="inline-flex gap-1">
                          {p.active ? (
                            <>
                              <Button variant="ghost" onClick={() => setEditing(p)} aria-label={t("Edit {name}", { name: p.name })}><Pencil className="size-4" aria-hidden /></Button>
                              <Button variant="ghost" onClick={() => remove(p)} aria-label={t("Delete {name}", { name: p.name })}><Trash2 className="size-4" aria-hidden /></Button>
                            </>
                          ) : (
                            <Button variant="ghost" onClick={() => reactivate(p)} aria-label={t("Reactivate {name}", { name: p.name })} title={t("Reactivate")}>
                              <RotateCcw className="size-4" aria-hidden />
                            </Button>
                          )}
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data && data.length > 0 && (
          <p className="flex items-center gap-1.5 border-t border-line px-4 py-3 text-xs text-muted">
            <Archive className="size-3.5" aria-hidden /> {t("Products already used on invoices are deactivated instead of deleted, so invoice history stays intact.")}
          </p>
        )}
      </Card>
    </>
  );
}
