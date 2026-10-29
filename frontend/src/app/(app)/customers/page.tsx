"use client";

import { Suspense, useState, type FormEvent } from "react";
import { useSearchParams } from "next/navigation";
import useSWR from "swr";
import { BellOff, Pencil, Plus, Trash2 } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { Customer, Me } from "@/lib/types";
import { Alert, Button, Card, CardHeader, Empty, Field, Input, PageHeader, Select, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

const blank: Customer = { nameAr: "", nameEn: "", vatNumber: "", otherIdScheme: "CRN", countryCode: "SA", remindersEnabled: true };

function CustomerForm({ initial, onDone }: { initial: Customer; onDone: () => void }) {
  const t = useT();
  const [c, setC] = useState<Customer>(initial);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (k: keyof Customer) => (e: { target: { value: string } }) => setC({ ...c, [k]: e.target.value });
  const fe = (k: keyof Customer) => error?.fields?.[k];
  const ksa = !c.countryCode || c.countryCode.toUpperCase() === "SA";
  // Open when the customer already has a structured address, or has a VAT number (a likely B2B buyer).
  const [nationalOpen, setNationalOpen] = useState(
    [initial.buildingNumber, initial.street, initial.district, initial.city, initial.postalCode, initial.vatNumber].some((v) => v),
  );

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (c.id) await api("PUT", `/customers/${c.id}`, c);
      else await api("POST", "/customers", c);
      onDone();
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError(0, "network", t("Could not reach the server")));
      setBusy(false);
    }
  }

  return (
    <Card className="mb-6">
      <CardHeader
        title={c.id ? t("Edit customer") : t("New customer")}
        subtitle={t("English name and mobile are required. For standard (B2B) invoices ZATCA also needs the VAT number (or another ID) and the national address.")}
      />
      <form onSubmit={submit} className="space-y-5 p-5">
        {error && <Alert tone="bad" title={t("Not saved")}>{error.fields ? t("Please correct the highlighted fields.") : error.message}</Alert>}
        <div className="grid gap-4 md:grid-cols-2">
          <Field label={t("Name (English)")}><Input required value={c.nameEn} onChange={set("nameEn")} /></Field>
          <Field label={t("Name (Arabic)")} hint={t("Optional")}><Input dir="rtl" value={c.nameAr ?? ""} onChange={set("nameAr")} /></Field>
          <Field label={t("VAT number")} hint={t("15 digits, starts and ends with 3")} error={fe("vatNumber")}><Input inputMode="numeric" maxLength={15} value={c.vatNumber ?? ""} onChange={set("vatNumber")} /></Field>
          <div className="grid grid-cols-[8rem_1fr] gap-3">
            <Field label={t("Other ID type")}>
              <Select value={c.otherIdScheme ?? "CRN"} onChange={set("otherIdScheme")}>
                <option value="CRN">{t("CR number")}</option>
                <option value="700">{t("700 number")}</option>
                <option value="NAT">{t("National ID")}</option>
                <option value="IQA">{t("Iqama")}</option>
                <option value="PAS">{t("Passport")}</option>
                <option value="OTH">{t("Other")}</option>
              </Select>
            </Field>
            <Field label={t("Other ID")} hint={t("Needed if there is no VAT number. CR, 700, national ID and Iqama have 10 digits")} error={fe("otherId")}><Input value={c.otherId ?? ""} onChange={set("otherId")} /></Field>
          </div>
        </div>
        <div className="grid gap-4 md:grid-cols-2">
          <Field label={t("Mobile (WhatsApp)")} hint={t("Use the customer's WhatsApp number: 05XXXXXXXX or with country code. Used for WhatsApp and SMS reminders")} error={fe("phone")}>
            <Input inputMode="tel" required value={c.phone ?? ""} onChange={set("phone")} />
          </Field>
          <Field label={t("Email")} hint={t("Optional. Payment reminders are emailed here")} error={fe("email")}><Input type="email" value={c.email ?? ""} onChange={set("email")} /></Field>
          <Field label={t("Address")} className="md:col-span-2">
            <textarea
              rows={2}
              className="w-full rounded-lg border border-line bg-white px-3 py-2 text-sm outline-none focus:border-brand"
              value={c.address ?? ""}
              onChange={set("address")}
            />
          </Field>
        </div>
        <details open={nationalOpen} onToggle={(e) => setNationalOpen(e.currentTarget.open)} className="rounded-lg border border-line">
          <summary className="cursor-pointer px-4 py-3 text-sm font-medium">
            {t("National address for B2B tax invoices")}
            <span className="block text-xs font-normal text-muted">{t("Only needed for standard (B2B) invoices: ZATCA rejects them without building no., street, district, city and postal code.")}</span>
          </summary>
          <div className="grid gap-4 border-t border-line p-4 md:grid-cols-4">
            <Field label={t("Country")} error={fe("countryCode")}>
              <Select value={(c.countryCode ?? "SA").toUpperCase()} onChange={set("countryCode")}>
                <option value="SA">{t("Saudi Arabia")} (SA)</option>
                <option value="AE">{t("United Arab Emirates")} (AE)</option>
                <option value="BH">{t("Bahrain")} (BH)</option>
                <option value="KW">{t("Kuwait")} (KW)</option>
                <option value="OM">{t("Oman")} (OM)</option>
                <option value="QA">{t("Qatar")} (QA)</option>
                <option value="BD">{t("Bangladesh")} (BD)</option>
                <option value="IN">{t("India")} (IN)</option>
                <option value="PK">{t("Pakistan")} (PK)</option>
                <option value="EG">{t("Egypt")} (EG)</option>
              </Select>
            </Field>
            <Field label={t("Building no.")} hint={ksa ? t("4 digits") : undefined} error={fe("buildingNumber")}>
              <Input inputMode={ksa ? "numeric" : "text"} maxLength={ksa ? 4 : 20} value={c.buildingNumber ?? ""} onChange={set("buildingNumber")} />
            </Field>
            <Field label={t("Street")} className="md:col-span-2"><Input value={c.street ?? ""} onChange={set("street")} /></Field>
            <Field label={t("District")}><Input value={c.district ?? ""} onChange={set("district")} /></Field>
            <Field label={t("City")}><Input value={c.city ?? ""} onChange={set("city")} /></Field>
            <Field label={t("Postal code")} hint={ksa ? t("5 digits") : undefined} error={fe("postalCode")}>
              <Input inputMode={ksa ? "numeric" : "text"} maxLength={ksa ? 5 : 10} value={c.postalCode ?? ""} onChange={set("postalCode")} />
            </Field>
            <Field label={t("Additional no.")} hint={ksa ? t("4 digits, optional") : t("Optional")} error={fe("additionalNumber")}>
              <Input maxLength={ksa ? 4 : 10} value={c.additionalNumber ?? ""} onChange={set("additionalNumber")} />
            </Field>
          </div>
        </details>
        <label className="flex items-start gap-2 text-sm">
          <input
            type="checkbox"
            className="mt-0.5 size-4 accent-[var(--color-brand)]"
            checked={c.remindersEnabled ?? true}
            onChange={(e) => setC({ ...c, remindersEnabled: e.target.checked })}
          />
          <span>
            {t("Send automatic payment reminders")}
            <span className="block text-xs text-muted">{t("Every 15 days while this customer has money due, by email and SMS if given.")}</span>
          </span>
        </label>
        <div className="flex gap-2">
          <Button type="submit" loading={busy}>{t("Save customer")}</Button>
          <Button type="button" variant="ghost" onClick={onDone}>{t("Cancel")}</Button>
        </div>
      </form>
    </Card>
  );
}

function Customers() {
  const params = useSearchParams();
  const t = useT();
  const { data, error, mutate } = useSWR<Customer[]>("/customers", fetcher);
  const { data: me } = useSWR<Me>("/auth/me", fetcher);
  const [editing, setEditing] = useState<Customer | null>(params.get("new") ? blank : null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const canWrite = me != null && me.role !== "VIEWER";

  async function remove(c: Customer) {
    if (!window.confirm(t("Delete {name}?", { name: c.nameEn || c.nameAr }))) return;
    setDeleteError(null);
    try {
      await api("DELETE", `/customers/${c.id}`);
      mutate();
    } catch (e) {
      setDeleteError(e instanceof ApiError ? e.message : t("Could not reach the server"));
    }
  }

  return (
    <>
      <PageHeader
        title={t("Customers")}
        subtitle={t("Buyers for standard (B2B) invoices")}
        actions={canWrite && !editing && <Button onClick={() => setEditing(blank)}><Plus className="size-4" aria-hidden /> {t("New customer")}</Button>}
      />
      {deleteError && <div className="mb-4"><Alert tone="bad">{deleteError}</Alert></div>}
      {editing && <CustomerForm key={editing.id ?? "new"} initial={editing} onDone={() => { setEditing(null); mutate(); }} />}
      <Card>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("No customers yet")}>{t("Add your first business customer to issue standard invoices.")}</Empty>}
        {data && data.length > 0 && (
          <ul className="divide-y divide-line">
            {data.map((c) => (
              <li key={c.id} className="flex items-center justify-between gap-4 px-5 py-3">
                <div className="min-w-0">
                  <p className="truncate text-sm font-medium">{c.nameEn || c.nameAr} <span className="ar font-normal text-muted" dir="rtl">{c.nameEn ? c.nameAr : ""}</span></p>
                  <p className="text-xs text-muted">
                    {c.vatNumber ? `${t("VAT")} ${c.vatNumber}` : c.otherId ? `${c.otherIdScheme} ${c.otherId}` : t("No tax ID")} · {c.city || t("No address")}
                    {c.phone && ` · ${c.phone}`}
                    {c.remindersEnabled === false && (
                      <span className="ms-2 inline-flex items-center gap-1 text-warn"><BellOff className="size-3" aria-hidden /> {t("Reminders off")}</span>
                    )}
                  </p>
                </div>
                {canWrite && (
                  <span className="inline-flex gap-1">
                    <Button variant="ghost" onClick={() => setEditing(c)} aria-label={t("Edit {name}", { name: c.nameEn || c.nameAr })}>
                      <Pencil className="size-4" aria-hidden />
                    </Button>
                    <Button variant="ghost" onClick={() => remove(c)} aria-label={t("Delete {name}", { name: c.nameEn || c.nameAr })}>
                      <Trash2 className="size-4" aria-hidden />
                    </Button>
                  </span>
                )}
              </li>
            ))}
          </ul>
        )}
      </Card>
    </>
  );
}

export default function CustomersPage() {
  return (
    <Suspense fallback={<Spinner />}>
      <Customers />
    </Suspense>
  );
}
