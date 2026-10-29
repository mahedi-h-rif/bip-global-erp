"use client";

import { useState, type FormEvent } from "react";
import useSWR from "swr";
import { api, ApiError, fetcher, uploadFile } from "@/lib/api";
import type { Me, Organization } from "@/lib/types";
import { Alert, Button, Card, CardHeader, Field, Input, PageHeader, Select, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

export default function CompanySettingsPage() {
  const { data, mutate } = useSWR<Organization>("/organization", fetcher);
  const { data: me } = useSWR<Me>("/auth/me", fetcher);
  if (!data || !me) return <Spinner />;
  return (
    <>
      <CompanyForm key={data.id} initial={data} provider={me.role === "OPERATOR"} onSaved={(o) => mutate(o, false)} />
      <LogoCard org={data} onSaved={(o) => mutate(o, false)} />
    </>
  );
}

function LogoCard({ org, onSaved }: { org: Organization; onSaved: (o: Organization) => void }) {
  const t = useT();
  const [msg, setMsg] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  async function run(action: () => Promise<Organization>, done: string) {
    setBusy(true);
    setMsg(null);
    try {
      onSaved(await action());
      setMsg({ tone: "ok", text: done });
    } catch (err) {
      setMsg({ tone: "bad", text: err instanceof Error ? err.message : t("Failed") });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="mt-6">
      <CardHeader title={t("Logo")} subtitle={t("Shown in the portal, on the login page and on invoice PDFs. PNG or JPEG, up to 1 MB.")} />
      <div className="flex flex-wrap items-center gap-5 p-5">
        {org.logoUrl ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={org.logoUrl} alt={t("Company logo")} className="h-20 w-auto max-w-40 rounded-lg border border-line bg-white object-contain p-1" />
        ) : (
          <span className="grid size-20 place-items-center rounded-lg border border-dashed border-line text-xs text-muted">{t("No logo")}</span>
        )}
        <div className="space-y-2">
          {msg && <Alert tone={msg.tone}>{msg.text}</Alert>}
          <div className="flex gap-2">
            <label className="inline-flex cursor-pointer items-center rounded-lg bg-brand px-4 py-2 text-sm font-medium text-white">
              {busy ? t("Uploading…") : org.logoUrl ? t("Replace logo") : t("Upload logo")}
              <input
                type="file"
                accept="image/png,image/jpeg"
                className="sr-only"
                disabled={busy}
                onChange={(e) => {
                  const file = e.target.files?.[0];
                  e.target.value = "";
                  if (file) run(() => uploadFile<Organization>("/organization/logo", file), t("Logo saved."));
                }}
              />
            </label>
            {org.logoUrl && (
              <Button type="button" variant="secondary" disabled={busy}
                onClick={() => run(() => api<Organization>("DELETE", "/organization/logo"), t("Logo removed."))}>
                {t("Remove")}
              </Button>
            )}
          </div>
        </div>
      </div>
    </Card>
  );
}

function CompanyForm({ initial, provider, onSaved }: {
  initial: Organization;
  /** The e-invoicing provider may change legal identity and the ZATCA environment; client admins may not. */
  provider: boolean;
  onSaved: (o: Organization) => void;
}) {
  const t = useT();
  const [org, setOrg] = useState<Organization>(initial);
  const [msg, setMsg] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (k: keyof Organization) => (e: { target: { value: string } }) => setOrg({ ...org, [k]: e.target.value });
  const locked = !provider;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setMsg(null);
    try {
      onSaved(await api<Organization>("PUT", "/organization", org));
      setMsg({ tone: "ok", text: t("Saved. New invoices use these details.") });
    } catch (err) {
      const fields = err instanceof ApiError && err.fields ? " — " + Object.values(err.fields).join(", ") : "";
      setMsg({ tone: "bad", text: (err instanceof Error ? err.message : t("Save failed")) + fields });
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHeader title={t("Company")} subtitle={t("Details printed on every invoice")} />
      <form onSubmit={submit} className="space-y-6">
        {msg && <Alert tone={msg.tone}>{msg.text}</Alert>}
        {locked && (
          <Alert tone="info">
            {t("Your legal name, VAT number, CR number and address are registered for e-invoicing and managed by your e-invoicing provider. Contact them if any of these change.")}
          </Alert>
        )}
        <Card>
          <CardHeader title={t("Legal identity")} subtitle={provider ? t("Must match the VAT registration certificate exactly") : undefined} />
          <fieldset disabled={locked} className="grid gap-4 p-5 md:grid-cols-2">
            <Field label={t("Legal name (Arabic)")}><Input dir="rtl" required value={org.legalNameAr} onChange={set("legalNameAr")} /></Field>
            <Field label={t("Legal name (English)")}><Input value={org.legalNameEn ?? ""} onChange={set("legalNameEn")} /></Field>
            <Field label={t("VAT number")}><Input required maxLength={15} value={org.vatNumber} onChange={set("vatNumber")} /></Field>
            <Field label={t("CR number")}><Input required value={org.otherId} onChange={set("otherId")} /></Field>
          </fieldset>
        </Card>
        <Card>
          <CardHeader title={t("National address")} />
          <fieldset disabled={locked} className="grid gap-4 p-5 md:grid-cols-4">
            <Field label={t("Building no.")}><Input required maxLength={4} value={org.buildingNumber} onChange={set("buildingNumber")} /></Field>
            <Field label={t("Street")} className="md:col-span-2"><Input required value={org.street} onChange={set("street")} /></Field>
            <Field label={t("Additional no.")}><Input maxLength={4} value={org.additionalNumber ?? ""} onChange={set("additionalNumber")} /></Field>
            <Field label={t("District")}><Input required value={org.district} onChange={set("district")} /></Field>
            <Field label={t("City")}><Input required value={org.city} onChange={set("city")} /></Field>
            <Field label={t("Postal code")}><Input required maxLength={5} value={org.postalCode} onChange={set("postalCode")} /></Field>
          </fieldset>
        </Card>
        <Card>
          <CardHeader title={t("Branding, numbering & contact")} />
          <div className="grid gap-4 p-5 md:grid-cols-4">
            <Field label={t("Brand colour")}><Input type="color" className="h-10 p-1" value={org.brandColor} onChange={set("brandColor")} /></Field>
            <Field label={t("Invoice prefix")}><Input required value={org.invoicePrefix} onChange={set("invoicePrefix")} /></Field>
            <Field label={t("Credit note prefix")}><Input required value={org.creditNotePrefix} onChange={set("creditNotePrefix")} /></Field>
            <Field label={t("Debit note prefix")}><Input required value={org.debitNotePrefix} onChange={set("debitNotePrefix")} /></Field>
            <Field label={t("Email")}><Input type="email" value={org.email ?? ""} onChange={set("email")} /></Field>
            <Field label={t("Phone")}><Input value={org.phone ?? ""} onChange={set("phone")} /></Field>
            <Field label={t("Website")} hint={t("Printed on invoices")}><Input value={org.website ?? ""} onChange={set("website")} placeholder={t("e.g. asthacompany.com")} /></Field>
            <Field label={t("Signatory name")} hint={t("Signs agreements and quotations")}><Input value={org.signatoryName ?? ""} onChange={set("signatoryName")} /></Field>
            <Field label={t("Signatory title")} hint={t("e.g. General Manager")}><Input value={org.signatoryTitle ?? ""} onChange={set("signatoryTitle")} /></Field>
          </div>
        </Card>
        {provider && (
          <Card>
            <CardHeader title={t("ZATCA environment · provider only")} subtitle={t("Sandbox for testing, Simulation for the pre-production dry run, Production for legally binding invoices.")} />
            <div className="p-5">
              <Field label={t("Environment")} hint={t("Changing it requires onboarding a ZATCA device in the new environment.")}>
                <Select className="max-w-xs" value={org.zatcaEnvironment} onChange={set("zatcaEnvironment")}>
                  <option value="SANDBOX">{t("Sandbox (developer portal)")}</option>
                  <option value="SIMULATION">{t("Simulation")}</option>
                  <option value="PRODUCTION">{t("Production")}</option>
                </Select>
              </Field>
            </div>
          </Card>
        )}
        <Button type="submit" loading={busy}>{t("Save company details")}</Button>
      </form>
    </>
  );
}
