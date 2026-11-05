"use client";

import { useState, type FormEvent } from "react";
import useSWR from "swr";
import { AlertTriangle, CheckCircle2, Cpu, KeyRound, Plus, RefreshCw, Star, XCircle } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { EgsUnit, OnboardingResult, Organization } from "@/lib/types";
import { date, expiresWithinDays } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Empty, Field, Input, PageHeader, RequiredLegend, Select, Spinner } from "@/components/ui";
import { Trans, useT } from "@/lib/i18n";

function Steps({ result }: { result: OnboardingResult }) {
  return (
    <ol className="mt-3 space-y-1.5">
      {result.steps.map((s, i) => (
        <li key={i} className="flex items-start gap-2 text-sm">
          {!s.ok ? (
            <XCircle className="mt-0.5 size-4 shrink-0 text-bad" aria-hidden />
          ) : s.warning ? (
            <AlertTriangle className="mt-0.5 size-4 shrink-0 text-warn" aria-hidden />
          ) : (
            <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-ok" aria-hidden />
          )}
          <span>
            <span className="font-medium">{s.name}</span>
            {s.detail && <span className={s.warning ? "text-warn" : "text-muted"}> · {s.detail}</span>}
          </span>
        </li>
      ))}
    </ol>
  );
}

function OtpForm({ unit, mode, onDone }: { unit: EgsUnit; mode: "onboard" | "renew"; onDone: () => void }) {
  const t = useT();
  const [otp, setOtp] = useState(unit.environment === "SANDBOX" ? "123345" : "");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<OnboardingResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const r = await api<OnboardingResult>("POST", `/egs-units/${unit.id}/${mode}`, { otp });
      setResult(r);
      if (r.status === "ACTIVE") onDone();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : t("Could not reach the server"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mt-4 rounded-lg border border-line bg-canvas p-4">
      <form onSubmit={submit} className="flex flex-wrap items-end gap-3">
        <Field
          label={t("One-time password (OTP)")}
          hint={unit.environment === "SANDBOX"
            ? t("The developer sandbox always accepts 123345.")
            : <Trans k="Generate it at fatoora.zatca.gov.sa → “Onboard new solution unit/device”. It is valid for 1 hour." />}
          className="w-72"
        >
          <Input inputMode="numeric" pattern="\d{6}" maxLength={6} required value={otp} onChange={(e) => setOtp(e.target.value)} />
        </Field>
        <Button type="submit" loading={busy}>
          <KeyRound className="size-4" aria-hidden /> {mode === "onboard" ? t("Register with ZATCA") : t("Renew certificate")}
        </Button>
      </form>
      {busy && mode === "onboard" && <p className="mt-3 text-sm text-muted">{t("Generating keys, requesting certificates and running the compliance checks. This takes about 10–20 seconds…")}</p>}
      {error && <div className="mt-3"><Alert tone="bad">{error}</Alert></div>}
      {result && <Steps result={result} />}
    </div>
  );
}

function NewDeviceForm({ onCreated }: { onCreated: () => void }) {
  const t = useT();
  const [form, setForm] = useState({ name: "PORTAL-1", branch: "Head Office", location: "", industry: "", invoiceTypes: "1100" });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api("POST", "/egs-units", form);
      onCreated();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : t("Could not reach the server"));
      setBusy(false);
    }
  }

  return (
    <Card className="mb-6">
      <CardHeader title={t("New ZATCA device")} subtitle={t("Each device gets its own certificate and its own unbroken invoice chain.")} />
      <form onSubmit={submit} className="grid gap-4 p-5 md:grid-cols-2">
        {error && <div className="md:col-span-2"><Alert tone="bad">{error}</Alert></div>}
        <RequiredLegend className="md:col-span-2" />
        <Field label={t("Device name")} hint={t("Unique, e.g. PORTAL-1 or STATION-2-POS")}><Input required value={form.name} onChange={set("name")} /></Field>
        <Field label={t("Branch")}><Input required value={form.branch} onChange={set("branch")} /></Field>
        <Field label={t("Location")} hint={t("Short national address, e.g. QFDA3287")}><Input required value={form.location} onChange={set("location")} /></Field>
        <Field label={t("Industry / business category")} hint={t("e.g. Fuel retail, Supermarket, Trading")}><Input required value={form.industry} onChange={set("industry")} /></Field>
        <Field label={t("Documents this device issues")}>
          <Select value={form.invoiceTypes} onChange={set("invoiceTypes")}>
            <option value="1100">{t("Standard and simplified (recommended)")}</option>
            <option value="1000">{t("Standard (B2B) only")}</option>
            <option value="0100">{t("Simplified (B2C) only")}</option>
          </Select>
        </Field>
        <div className="flex items-end"><Button type="submit" loading={busy}>{t("Create device")}</Button></div>
      </form>
    </Card>
  );
}

export default function DevicesPage() {
  const t = useT();
  const { data: units, error, mutate } = useSWR<EgsUnit[]>("/egs-units", fetcher);
  const { data: org } = useSWR<Organization>("/organization", fetcher);
  const [creating, setCreating] = useState(false);
  const [open, setOpen] = useState<{ id: number; mode: "onboard" | "renew" } | null>(null);

  return (
    <>
      <PageHeader
        title={t("ZATCA devices")}
        subtitle={t("E-invoice generation solution (EGS) units registered with the Fatoora platform")}
        actions={!creating && <Button onClick={() => setCreating(true)}><Plus className="size-4" aria-hidden /> {t("New device")}</Button>}
      />
      {org && (
        <div className="mb-6">
          <Alert tone={org.zatcaEnvironment === "PRODUCTION" ? "ok" : "info"} title={t("Environment: {env}", { env: org.zatcaEnvironment })}>
            {org.zatcaEnvironment === "PRODUCTION"
              ? t("Invoices are legally binding and sent to ZATCA for real.")
              : t("Test environment: invoices are validated by ZATCA but are not legally binding. Switch to Production in Company settings when ready, then onboard a device there.")}
          </Alert>
        </div>
      )}
      {creating && <NewDeviceForm onCreated={() => { setCreating(false); mutate(); }} />}

      <Card>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!units && !error && <Spinner />}
        {units?.length === 0 && <Empty title={t("No devices yet")}>{t("Create a device, then register it with an OTP from the Fatoora portal.")}</Empty>}
        <ul className="divide-y divide-line">
          {units?.map((u) => {
            const expiresSoon = expiresWithinDays(u.certificateExpiresAt, 30);
            return (
              <li key={u.id} className="px-5 py-4">
                <div className="flex flex-wrap items-start justify-between gap-4">
                  <div className="flex items-start gap-3">
                    <Cpu className="mt-0.5 size-5 text-muted" aria-hidden />
                    <div>
                      <p className="text-sm font-semibold">
                        {u.name} {u.isDefault && <span className="ms-1 inline-flex items-center gap-1 rounded bg-brand/10 px-1.5 py-0.5 text-[11px] font-medium text-brand"><Star className="size-3" aria-hidden />{t("Default")}</span>}
                      </p>
                      <p className="text-xs text-muted">{u.branch} · {u.environment} · {u.status} · {t("{n} documents issued", { n: u.icv })}</p>
                      {u.certificateExpiresAt && (
                        <p className={`text-xs ${expiresSoon ? "font-medium text-bad" : "text-muted"}`}>{t("Certificate valid until {date}", { date: date(u.certificateExpiresAt) })}</p>
                      )}
                      <p className="mt-1 break-all font-mono text-[11px] text-muted/80">{u.serialNumber}</p>
                    </div>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {u.status !== "ACTIVE" && (
                      <Button onClick={() => setOpen({ id: u.id, mode: "onboard" })}><KeyRound className="size-4" aria-hidden /> {t("Register")}</Button>
                    )}
                    {u.status === "ACTIVE" && !u.isDefault && (
                      <Button variant="secondary" onClick={async () => { await api("POST", `/egs-units/${u.id}/default`); mutate(); }}>{t("Make default")}</Button>
                    )}
                    {u.status === "ACTIVE" && (
                      <Button variant="secondary" onClick={() => setOpen({ id: u.id, mode: "renew" })}><RefreshCw className="size-4" aria-hidden /> {t("Renew")}</Button>
                    )}
                  </div>
                </div>
                {open?.id === u.id && <OtpForm unit={u} mode={open.mode} onDone={() => mutate()} />}
              </li>
            );
          })}
        </ul>
      </Card>
    </>
  );
}
