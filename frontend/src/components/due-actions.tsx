"use client";

import { useState } from "react";
import { Mail, MessageCircle } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import type { NotificationEntry } from "@/lib/types";
import { Button } from "@/components/ui";
import { useWhatsApp } from "@/components/whatsapp";
import { useT } from "@/lib/i18n";

const statusText: Record<NotificationEntry["status"], string> = {
  SENT: "sent",
  LOGGED: "logged (channel not set up yet)",
  FAILED: "failed",
  OPENED: "opened",
};

/**
 * "Send reminder" (email/SMS, and WhatsApp when a number is linked) and "WhatsApp" for one customer's dues.
 * WhatsApp sends from the linked number, or opens a prefilled chat when none is linked.
 */
export function DueActions({ customerId, email, phone, onDone, compact = false }: {
  customerId: number;
  email: string | null;
  phone: string | null;
  onDone?: () => void;
  compact?: boolean;
}) {
  const t = useT();
  const [busy, setBusy] = useState<"remind" | "wa" | null>(null);
  const [result, setResult] = useState<{ ok: boolean; text: string; url?: string } | null>(null);
  const { direct } = useWhatsApp();

  async function remind() {
    setBusy("remind");
    setResult(null);
    try {
      const sent = await api<NotificationEntry[]>("POST", `/dues/customers/${customerId}/remind`);
      const failed = sent.some((s) => s.status === "FAILED");
      const channel = (c: NotificationEntry["channel"]) => (c === "EMAIL" ? t("Email") : c === "SMS" ? t("SMS") : "WhatsApp");
      setResult({ ok: !failed, text: sent.map((s) => `${channel(s.channel)} ${t(statusText[s.status])}`).join(", ") });
      onDone?.();
    } catch (e) {
      setResult({ ok: false, text: e instanceof ApiError ? e.message : t("Could not reach the server") });
    } finally {
      setBusy(null);
    }
  }

  async function whatsapp() {
    if (direct) return sendDirect();
    // Open the tab synchronously (inside the click) so pop-up blockers allow it, then point it at wa.me.
    const tab = window.open("", "_blank");
    setBusy("wa");
    setResult(null);
    try {
      const link = await api<{ url: string }>("POST", `/dues/customers/${customerId}/whatsapp`);
      if (tab) tab.location.href = link.url;
      else window.location.assign(link.url);
      onDone?.();
    } catch (e) {
      tab?.close();
      setResult({ ok: false, text: e instanceof ApiError ? e.message : t("Could not reach the server") });
    } finally {
      setBusy(null);
    }
  }

  /** From the linked number; if it fails, the prefilled chat is offered instead. */
  async function sendDirect() {
    setBusy("wa");
    setResult(null);
    try {
      const r = await api<{ sent: boolean; url: string | null; error: string | null }>("POST", `/dues/customers/${customerId}/whatsapp`);
      setResult(r.sent ? { ok: true, text: t("Sent on WhatsApp") } : { ok: false, text: r.error ?? t("Not sent"), url: r.url ?? undefined });
      onDone?.();
    } catch (e) {
      setResult({ ok: false, text: e instanceof ApiError ? e.message : t("Could not reach the server") });
    } finally {
      setBusy(null);
    }
  }

  return (
    <span className="inline-flex flex-col items-end gap-1">
      <span className="inline-flex gap-1.5">
        <Button
          variant="secondary"
          className={compact ? "px-2.5 py-1.5" : ""}
          loading={busy === "remind"}
          disabled={!email && !phone}
          title={!email && !phone ? t("Add an email or mobile number to this customer first") : t("Send email / SMS reminder now")}
          onClick={remind}
        >
          <Mail className="size-4" aria-hidden /> {compact ? <span className="sr-only">{t("Send reminder")}</span> : t("Send reminder")}
        </Button>
        <Button
          variant="secondary"
          className={`${compact ? "px-2.5 py-1.5" : ""} text-emerald-700`}
          loading={busy === "wa"}
          disabled={!phone}
          title={!phone ? t("Add a mobile number to this customer first") : direct ? t("Send the reminder on WhatsApp now") : t("Open WhatsApp with a ready-made reminder")}
          onClick={whatsapp}
        >
          <MessageCircle className="size-4" aria-hidden /> {compact ? <span className="sr-only">WhatsApp</span> : "WhatsApp"}
        </Button>
      </span>
      {result && (
        <span className={`text-xs ${result.ok ? "text-ok" : "text-bad"}`} role="status">
          {result.text}
          {result.url && <> · <a href={result.url} target="_blank" rel="noopener noreferrer" className="font-medium underline">{t("Open WhatsApp instead")}</a></>}
        </span>
      )}
    </span>
  );
}
