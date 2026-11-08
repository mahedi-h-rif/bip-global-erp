"use client";

import { useState, type FormEvent } from "react";
import useSWR from "swr";
import { api, ApiError, fetcher } from "@/lib/api";
import { Alert, Button, Card, CardHeader, Field, Input, PageHeader, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

interface Bank { bankName: string | null; bankAccountName: string | null; bankAccountNo: string | null; bankIban: string | null; invoiceFooter: string | null }

function BankForm({ initial }: { initial: Bank }) {
  const t = useT();
  const [b, setB] = useState<Bank>(initial);
  const [msg, setMsg] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const f = (k: keyof Bank) => (e: { target: { value: string } }) => setB({ ...b, [k]: e.target.value });
  async function save(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try { setB(await api<Bank>("PUT", "/settings/bank", b)); setMsg({ tone: "ok", text: t("Saved. New invoice PDFs show these details.") }); }
    catch (err) { setMsg({ tone: "bad", text: err instanceof ApiError ? err.message : t("Could not reach the server") }); }
    finally { setBusy(false); }
  }
  return (
    <Card>
      <CardHeader title={t("Bank details on invoices")} subtitle={t("Printed under the totals of every tax invoice, with the amount in words.")} />
      <form onSubmit={save} className="space-y-4 p-5">
        {msg && <Alert tone={msg.tone}>{msg.text}</Alert>}
        <div className="grid gap-4 md:grid-cols-2">
          <Field label={t("Account name")}><Input value={b.bankAccountName ?? ""} onChange={f("bankAccountName")} /></Field>
          <Field label={t("Bank")}><Input value={b.bankName ?? ""} onChange={f("bankName")} /></Field>
          <Field label={t("Account no.")}><Input dir="ltr" value={b.bankAccountNo ?? ""} onChange={f("bankAccountNo")} /></Field>
          <Field label={t("IBAN")}><Input dir="ltr" value={b.bankIban ?? ""} onChange={f("bankIban")} placeholder="SA00 0000 0000 0000 0000 0000" /></Field>
          <Field label={t("Invoice footer line")} optional className="md:col-span-2"><Input dir="auto" value={b.invoiceFooter ?? ""} onChange={f("invoiceFooter")} placeholder={t("e.g. branch offices, phone numbers")} /></Field>
        </div>
        <Button type="submit" loading={busy}>{t("Save")}</Button>
      </form>
    </Card>
  );
}

export default function BankPage() {
  const t = useT();
  const { data, error } = useSWR<Bank>("/settings/bank", fetcher);
  return (
    <>
      <PageHeader title={t("Bank details")} />
      {error ? <Alert tone="bad">{error.message}</Alert> : !data ? <Spinner /> : <BankForm initial={data} />}
    </>
  );
}
