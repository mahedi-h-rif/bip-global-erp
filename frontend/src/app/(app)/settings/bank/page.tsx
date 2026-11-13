"use client";

import { useState, type FormEvent } from "react";
import useSWR from "swr";
import { QrCode, Trash2, Upload } from "lucide-react";
import { api, ApiError, fetcher, uploadFile } from "@/lib/api";
import { Alert, Button, Card, CardHeader, Field, Input, PageHeader, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

interface Bank { bankName: string | null; bankAccountName: string | null; bankAccountNo: string | null; bankIban: string | null; invoiceFooter: string | null; hasQr?: boolean }

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

/** The payment QR printed beside the bank details: an uploaded one (e.g. the bank's own QR), or one made from the details. */
function BankQr({ hasQr, onChange }: { hasQr: boolean; onChange: () => void }) {
  const t = useT();
  const [busy, setBusy] = useState<string | null>(null);
  const [msg, setMsg] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);
  const [version, setVersion] = useState(() => Date.now());

  async function run(name: string, fn: () => Promise<unknown>, ok: string) {
    setBusy(name);
    setMsg(null);
    try { await fn(); setVersion(Date.now()); onChange(); setMsg({ tone: "ok", text: ok }); }
    catch (err) { setMsg({ tone: "bad", text: err instanceof ApiError ? err.message : t("Could not reach the server") }); }
    finally { setBusy(null); }
  }

  return (
    <Card className="mt-6">
      <CardHeader title={t("Payment QR code")}
        subtitle={t("Upload your bank's QR code to print it next to the bank details on invoices, proforma invoices, sales orders and agreements. Without one, a QR made from the details above is printed.")} />
      <div className="flex flex-wrap items-center gap-5 p-5">
        <div className="grid size-36 place-items-center rounded-lg border border-line bg-white">
          {hasQr
            // eslint-disable-next-line @next/next/no-img-element
            ? <img src={`/api/v1/settings/bank/qr?v=${version}`} alt={t("Payment QR code")} className="size-32 object-contain" />
            : <QrCode className="size-12 text-muted" aria-hidden />}
        </div>
        <div className="space-y-2">
          {msg && <Alert tone={msg.tone}>{msg.text}</Alert>}
          <p className="text-sm text-muted">{hasQr ? t("Your uploaded QR code is printed on documents.") : t("No QR uploaded: the QR is made from the bank details.")}</p>
          <div className="flex flex-wrap gap-2">
            <label className="inline-flex cursor-pointer items-center gap-2 rounded-lg bg-brand px-3.5 py-2 text-sm font-medium text-white hover:brightness-110">
              <Upload className="size-4" aria-hidden /> {busy === "upload" ? t("Uploading…") : hasQr ? t("Replace QR code") : t("Upload QR code")}
              <input type="file" accept="image/png,image/jpeg" className="sr-only" disabled={busy != null} onChange={(e) => {
                const file = e.target.files?.[0];
                e.target.value = "";
                if (file) void run("upload", () => uploadFile("/settings/bank/qr", file), t("QR code saved. New PDFs show it."));
              }} />
            </label>
            {hasQr && (
              <Button variant="ghost" loading={busy === "remove"} onClick={() => run("remove", () => api("DELETE", "/settings/bank/qr"), t("QR code removed."))}>
                <Trash2 className="size-4" aria-hidden /> {t("Remove")}
              </Button>
            )}
          </div>
          <p className="text-xs text-muted">{t("PNG or JPEG, up to 1 MB.")}</p>
        </div>
      </div>
    </Card>
  );
}

export default function BankPage() {
  const t = useT();
  const { data, error, mutate } = useSWR<Bank>("/settings/bank", fetcher);
  return (
    <>
      <PageHeader title={t("Bank details")} />
      {error ? <Alert tone="bad">{error.message}</Alert> : !data ? <Spinner /> : (
        <>
          <BankForm initial={data} />
          <BankQr hasQr={!!data.hasQr} onChange={() => mutate()} />
        </>
      )}
    </>
  );
}
