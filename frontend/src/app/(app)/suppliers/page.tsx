"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import useSWR from "swr";
import { Pencil, Plus, Search } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { Supplier } from "@/lib/types";
import { money } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Empty, Field, Input, PageHeader, Select, Spinner } from "@/components/ui";
import { currencies } from "@/components/purchase";
import { useT } from "@/lib/i18n";
import { RelationshipMapButton } from "@/components/relationship-map";
import { CityInput, CountrySelect, SuggestInput } from "@/components/suggest";

function SupplierForm({ initial, onDone }: { initial: Supplier; onDone: () => void }) {
  const t = useT();
  const [s, setS] = useState<Supplier>(initial);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const f = (k: keyof Supplier) => (e: { target: { value: string } }) => setS({ ...s, [k]: e.target.value });

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const body = { ...s, paymentTermsDays: Number(s.paymentTermsDays ?? 30), country: (s.country || "SA").toUpperCase() };
      if (s.id) await api("PUT", `/suppliers/${s.id}`, body);
      else await api("POST", "/suppliers", body);
      onDone();
    } catch (err) {
      setError(err instanceof ApiError ? (err.fields ? Object.values(err.fields).join(", ") : err.message) : t("Could not reach the server"));
      setBusy(false);
    }
  }

  return (
    <Card className="mb-6">
      <CardHeader title={s.id ? t("Edit supplier") : t("New supplier")} />
      <form onSubmit={submit} className="space-y-4 p-5">
        {error && <Alert tone="bad" title={t("Not saved")}>{error}</Alert>}
        <div className="grid gap-4 md:grid-cols-3">
          <Field label={t("Code")} optional hint={t("Auto if left empty")}><Input dir="ltr" value={s.code ?? ""} onChange={f("code")} placeholder="V00001" /></Field>
          <Field label={t("Name")}><SuggestInput field="party" required dir="auto" value={s.name} onChange={f("name")} /></Field>
          <Field label={t("Arabic name")} optional><Input dir="rtl" value={s.nameAr ?? ""} onChange={f("nameAr")} /></Field>
          <Field label={t("VAT number")} optional><Input dir="ltr" value={s.vatNumber ?? ""} onChange={f("vatNumber")} /></Field>
          <Field label={t("CR number")} optional><Input dir="ltr" value={s.crNumber ?? ""} onChange={f("crNumber")} /></Field>
          <Field label={t("Contact person")} optional><SuggestInput field="person" dir="auto" value={s.contactPerson ?? ""} onChange={f("contactPerson")} /></Field>
          <Field label={t("Phone")} optional><Input dir="ltr" value={s.phone ?? ""} onChange={f("phone")} /></Field>
          <Field label={t("Email")} optional><Input type="email" dir="ltr" value={s.email ?? ""} onChange={f("email")} /></Field>
          <Field label={t("City")} optional><CityInput country={s.country} dir="auto" value={s.city ?? ""} onChange={f("city")} /></Field>
          <Field label={t("Address")} optional className="md:col-span-2"><SuggestInput field="address" dir="auto" value={s.address ?? ""} onChange={f("address")} /></Field>
          <Field label={t("Country")}><CountrySelect value={s.country ?? "SA"} onChange={(v) => f("country")({ target: { value: v } })} /></Field>
          <Field label={t("Currency")}>
            <Select value={s.currency ?? "SAR"} onChange={f("currency")}>{currencies.map((c) => <option key={c}>{c}</option>)}</Select>
          </Field>
          <Field label={t("Payment terms (days)")}><Input type="number" min="0" max="365" value={s.paymentTermsDays ?? 30} onChange={f("paymentTermsDays")} /></Field>
        </div>
        <div className="flex gap-2">
          <Button type="submit" loading={busy}>{t("Save supplier")}</Button>
          <Button type="button" variant="ghost" onClick={onDone}>{t("Cancel")}</Button>
        </div>
      </form>
    </Card>
  );
}

export default function SuppliersPage() {
  const t = useT();
  const [q, setQ] = useState("");
  const { data, error, mutate } = useSWR<Supplier[]>(`/suppliers${q.trim() ? `?q=${encodeURIComponent(q.trim())}` : ""}`, fetcher, { keepPreviousData: true });
  const [editing, setEditing] = useState<Supplier | null>(null);
  return (
    <>
      <PageHeader title={t("Suppliers")} subtitle={t("Vendors, local or international, and what is owed to each.")}
                  actions={!editing && <Button onClick={() => setEditing({ name: "", country: "SA", currency: "SAR", paymentTermsDays: 30 })}><Plus className="size-4" aria-hidden /> {t("New supplier")}</Button>} />
      {editing && <SupplierForm key={editing.id ?? "new"} initial={editing} onDone={() => { setEditing(null); mutate(); }} />}
      <Card>
        <div className="border-b border-line p-4">
          <div className="relative max-w-md">
            <Search className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted" aria-hidden />
            <Input className="ps-9" placeholder={t("Search suppliers")} value={q} onChange={(e) => setQ(e.target.value)} aria-label={t("Search suppliers")} />
          </div>
        </div>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("No suppliers yet")} />}
        {data && data.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead><tr className="border-b border-line text-xs text-muted">
                <th className="px-4 py-2.5 text-start font-medium">{t("Code")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Name")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Country")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Currency")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Contact")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Payable")}</th><th className="w-24" />
              </tr></thead>
              <tbody className="divide-y divide-line">
                {data.map((s) => (
                  <tr key={s.id}>
                    <td className="tabular px-4 py-3 text-xs text-muted">{s.code}</td>
                    <td className="px-4 py-3 font-medium" dir="auto">{s.name}{s.nameAr && <span className="ms-2 text-muted" dir="rtl">{s.nameAr}</span>}</td>
                    <td className="px-4 py-3">{s.country}</td>
                    <td className="px-4 py-3">{s.currency}</td>
                    <td className="px-4 py-3 text-muted">{[s.contactPerson, s.phone].filter(Boolean).join(" · ") || "—"}</td>
                    <td className={`tabular px-4 py-3 text-end ${Number(s.payable) > 0 ? "font-semibold text-bad" : ""}`}>{money(s.payable ?? 0)}</td>
                    <td className="px-2 py-2 text-end">
                      <span className="inline-flex gap-1">
                        <Link className="rounded px-2 py-1 text-xs text-brand hover:underline" href={`/purchase-orders/new?supplierId=${s.id}`}>{t("New PO")}</Link>
                        <RelationshipMapButton type="SUPPLIER" id={s.id!} compact label={s.name} />
                        <Button variant="ghost" onClick={() => setEditing(s)} aria-label={t("Edit {name}", { name: s.name })}><Pencil className="size-4" aria-hidden /></Button>
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
