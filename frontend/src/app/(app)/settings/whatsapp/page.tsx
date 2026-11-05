"use client";

import { useState } from "react";
import { CheckCircle2, Link2Off, Loader2, MessageCircle, Power, QrCode, RefreshCw, Send, Smartphone } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import type { SendOutcome, WhatsAppStatus } from "@/lib/types";
import { Alert, Button, Card, CardHeader, Field, Input, PageHeader, Spinner } from "@/components/ui";
import { useWhatsApp } from "@/components/whatsapp";
import { useT } from "@/lib/i18n";

const statusLabel: Record<WhatsAppStatus["status"], string> = {
  idle: "Not connected",
  connecting: "Connecting…",
  qr: "Waiting for the QR scan",
  connected: "Connected",
  logged_out: "Unlinked",
  error: "Connection problem",
  unavailable: "Not available",
};

/**
 * The company's WhatsApp number (admin): link it by scanning a QR, as for WhatsApp Web. Once linked, reminders,
 * invoices and document messages are sent from it directly. If it ever disconnects, scan again here.
 */
export default function WhatsAppSettingsPage() {
  const t = useT();
  const { wa, mutate } = useWhatsApp();
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmUnlink, setConfirmUnlink] = useState(false);
  const [testPhone, setTestPhone] = useState("");
  const [testResult, setTestResult] = useState<{ ok: boolean; text: string } | null>(null);

  async function run(name: string, fn: () => Promise<WhatsAppStatus | void>) {
    setBusy(name);
    setError(null);
    try {
      const s = await fn();
      await mutate(s ?? undefined, { revalidate: !s });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t("Could not reach the server"));
    } finally {
      setBusy(null);
    }
  }

  async function test() {
    setBusy("test");
    setTestResult(null);
    try {
      const r = await api<SendOutcome>("POST", "/whatsapp/test", { phone: testPhone });
      setTestResult(r.status === "FAILED" ? { ok: false, text: r.error ?? t("Not sent") } : { ok: true, text: t("Sent. Check that phone.") });
    } catch (e) {
      setTestResult({ ok: false, text: e instanceof ApiError ? e.message : t("Could not reach the server") });
    } finally {
      setBusy(null);
    }
  }

  const settings = (patch: Partial<Pick<WhatsAppStatus, "reminders" | "documentReminders">>) =>
    run("settings", () => api<WhatsAppStatus>("PUT", "/whatsapp/settings", {
      reminders: patch.reminders ?? wa!.reminders,
      documentReminders: patch.documentReminders ?? wa!.documentReminders,
    }));

  if (!wa) return <Spinner />;
  const connected = wa.status === "connected";
  const tone = connected ? "text-ok" : wa.status === "qr" || wa.status === "connecting" ? "text-warn" : "text-bad";

  return (
    <>
      <PageHeader title={t("WhatsApp")} subtitle={t("Send reminders, invoices and document messages from the company's own WhatsApp number")} />
      {error && <div className="mb-4"><Alert tone="bad">{error}</Alert></div>}
      {!wa.configured && (
        <Alert tone="warn" title={t("WhatsApp is not set up on this server")}>
          {t("Ask your provider to enable it. Until then, WhatsApp buttons open a chat with the message ready, sent from your own phone.")}
        </Alert>
      )}

      {wa.configured && (
        <div className="grid gap-6 lg:grid-cols-[1fr_22rem]">
          <Card>
            <CardHeader
              title={t("Connection")}
              action={<span className={`inline-flex items-center gap-1.5 text-sm font-semibold ${tone}`}>
                {wa.status === "connecting" && <Loader2 className="size-4 animate-spin" aria-hidden />}
                {connected && <CheckCircle2 className="size-4" aria-hidden />}
                {t(statusLabel[wa.status])}
              </span>}
            />
            <div className="space-y-5 p-5">
              {connected && (
                <div className="flex items-center gap-3 rounded-lg bg-ok/5 p-4">
                  <Smartphone className="size-8 text-ok" aria-hidden />
                  <div>
                    <p className="font-semibold tabular" dir="ltr">+{wa.phone}</p>
                    {wa.name && <p className="text-sm text-muted">{wa.name}</p>}
                  </div>
                </div>
              )}

              {wa.status === "qr" && wa.qr && (
                <div className="flex flex-col items-center gap-4 sm:flex-row sm:items-start">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={wa.qr} alt={t("WhatsApp QR code")} className="size-64 rounded-lg border border-line bg-white p-2" />
                  <ol className="list-decimal space-y-1.5 ps-5 text-sm">
                    <li>{t("Open WhatsApp on the company phone.")}</li>
                    <li>{t("Tap Settings (or ⋮) → Linked devices → Link a device.")}</li>
                    <li>{t("Point the phone at this QR code.")}</li>
                    <li className="text-muted">{t("The code changes every few seconds; this page updates by itself.")}</li>
                  </ol>
                </div>
              )}

              {wa.lastError && !connected && wa.status !== "qr" && <Alert tone="warn">{t(wa.lastError)}</Alert>}

              <div className="flex flex-wrap gap-2">
                {!connected && wa.status !== "qr" && (
                  <Button loading={busy === "connect"} disabled={wa.status === "connecting"} onClick={() => run("connect", () => api<WhatsAppStatus>("POST", "/whatsapp/connect"))}>
                    <QrCode className="size-4" aria-hidden /> {wa.status === "logged_out" || wa.status === "idle" ? t("Connect (show QR)") : t("Reconnect")}
                  </Button>
                )}
                {wa.status === "qr" && (
                  <Button variant="secondary" onClick={() => mutate()}><RefreshCw className="size-4" aria-hidden /> {t("Refresh")}</Button>
                )}
                {connected && (
                  <Button variant="secondary" loading={busy === "disconnect"} onClick={() => run("disconnect", () => api<WhatsAppStatus>("POST", "/whatsapp/disconnect"))}>
                    <Power className="size-4" aria-hidden /> {t("Pause")}
                  </Button>
                )}
                {(connected || wa.status === "idle") && (confirmUnlink ? (
                  <Button variant="danger" loading={busy === "logout"} onClick={() => run("logout", async () => { setConfirmUnlink(false); return api<WhatsAppStatus>("POST", "/whatsapp/logout"); })}>
                    <Link2Off className="size-4" aria-hidden /> {t("Confirm unlink")}
                  </Button>
                ) : (
                  <Button variant="ghost" onClick={() => setConfirmUnlink(true)}><Link2Off className="size-4" aria-hidden /> {t("Unlink number")}</Button>
                ))}
              </div>
              <p className="text-xs text-muted">
                {t("Pause keeps the link, so connecting again needs no scan. Unlink removes it; to use a different number, unlink and scan with the new phone.")}
              </p>
            </div>
          </Card>

          <div className="space-y-6">
            <Card>
              <CardHeader title={t("Send automatically")} />
              <div className="space-y-4 p-5">
                <label className="flex items-start gap-2 text-sm">
                  <input type="checkbox" className="mt-0.5 size-4 accent-[var(--color-brand)]" checked={wa.reminders} disabled={busy === "settings"}
                         onChange={(e) => settings({ reminders: e.target.checked })} />
                  <span>
                    {t("Payment reminders")}
                    <span className="block text-xs text-muted">{t("The automatic 15-day reminder and \"Send reminder\" also go to the customer on WhatsApp.")}</span>
                  </span>
                </label>
                <label className="flex items-start gap-2 text-sm">
                  <input type="checkbox" className="mt-0.5 size-4 accent-[var(--color-brand)]" checked={wa.documentReminders} disabled={busy === "settings"}
                         onChange={(e) => settings({ documentReminders: e.target.checked })} />
                  <span>
                    {t("Document expiry reminders")}
                    <span className="block text-xs text-muted">{t("Iqama, passport and other worker documents: the reminder also goes to the worker on WhatsApp.")}</span>
                  </span>
                </label>
                <p className="text-xs text-muted">{t("The WhatsApp buttons on invoices, dues and documents send directly while the number is connected.")}</p>
              </div>
            </Card>

            {connected && (
              <Card>
                <CardHeader title={t("Test message")} />
                <form className="space-y-3 p-5" onSubmit={(e) => { e.preventDefault(); void test(); }}>
                  <Field label={t("Mobile number")}>
                    <Input inputMode="tel" required placeholder="05XXXXXXXX" value={testPhone} onChange={(e) => setTestPhone(e.target.value)} />
                  </Field>
                  <Button type="submit" loading={busy === "test"} disabled={!testPhone.trim()}><Send className="size-4" aria-hidden /> {t("Send test")}</Button>
                  {testResult && <p role="status" className={`text-sm ${testResult.ok ? "text-ok" : "text-bad"}`}>{testResult.text}</p>}
                </form>
              </Card>
            )}

            <p className="flex gap-2 text-xs leading-relaxed text-muted">
              <MessageCircle className="mt-0.5 size-3.5 shrink-0" aria-hidden />
              {t("This links the number like WhatsApp Web; it is not the official WhatsApp Business API. Use a number the company dedicates to this, and avoid bulk messages to people who don't expect them, or WhatsApp may block the number.")}
            </p>
          </div>
        </div>
      )}
    </>
  );
}
