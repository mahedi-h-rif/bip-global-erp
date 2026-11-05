"use client";

import Link from "next/link";
import { useState } from "react";
import useSWR from "swr";
import { MessageCircle, Send, WifiOff } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { SendOutcome, WhatsAppStatus } from "@/lib/types";
import { Button, Input } from "@/components/ui";
import { useT } from "@/lib/i18n";

/**
 * The linked WhatsApp number's status, refreshed every minute (every 3 s while a QR is waiting to be scanned).
 * `direct` is true when messages go out from the linked number; otherwise WhatsApp buttons open a prefilled chat.
 */
export function useWhatsApp() {
  const { data, mutate } = useSWR<WhatsAppStatus>("/whatsapp/status", fetcher, {
    refreshInterval: (d) => (d?.status === "qr" || d?.status === "connecting" ? 3000 : 60_000),
  });
  return { wa: data, direct: data?.configured === true && data.status === "connected", mutate };
}

/** For admins, under the header: the number was unlinked or dropped and needs a new scan. */
export function WhatsAppBanner() {
  const t = useT();
  const { wa } = useWhatsApp();
  if (!wa?.configured || wa.status === "connected" || wa.status === "connecting") return null;
  return (
    <div className="mb-4 flex flex-wrap items-center gap-3 rounded-lg border border-warn/30 bg-warn/5 px-4 py-2.5 text-sm">
      <WifiOff className="size-4 shrink-0 text-warn" aria-hidden />
      <span className="flex-1">{t("WhatsApp is not connected, so messages are not sent automatically.")}</span>
      <Link href="/settings/whatsapp" className="rounded-md bg-brand px-2.5 py-1 text-xs font-medium text-white hover:brightness-110">
        {t("Scan QR to connect")}
      </Link>
    </div>
  );
}

/**
 * A document's expiry message to the worker: sent from the linked number when there is one and the worker has a
 * mobile; otherwise a link that opens the prefilled chat (`url`).
 */
export function DocumentWhatsAppButton({ documentId, phone, url, className }: {
  documentId: number;
  phone: string | null;
  url: string;
  className: string;
}) {
  const t = useT();
  const { direct } = useWhatsApp();
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);

  if (!direct || !phone) {
    return (
      <a className={className} href={url} target="_blank" rel="noopener noreferrer" title={phone ? t("Send to the worker on WhatsApp") : t("Share on WhatsApp")}>
        <MessageCircle className="size-3.5" aria-hidden />{t("WhatsApp")}
      </a>
    );
  }

  async function send() {
    setBusy(true);
    setResult(null);
    try {
      const r = await api<SendOutcome>("POST", `/documents/${documentId}/whatsapp`);
      setResult(r.status === "FAILED" ? { ok: false, text: r.error ?? t("Not sent") } : { ok: true, text: t("Sent on WhatsApp") });
    } catch (e) {
      setResult({ ok: false, text: e instanceof ApiError ? e.message : t("Could not reach the server") });
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <button type="button" className={className} disabled={busy} onClick={send} title={t("Send to the worker on WhatsApp now")}>
        <MessageCircle className="size-3.5" aria-hidden />{busy ? t("Sending…") : t("WhatsApp")}
      </button>
      {result && <span role="status" className={`basis-full text-end text-[11px] ${result.ok ? "text-ok" : "text-bad"}`}>{result.text}</span>}
    </>
  );
}

/**
 * Sends the invoice PDF to the customer from the linked number. Asks for a number when the customer has none
 * (e.g. a walk-in customer). Hidden when no number is linked.
 */
export function WhatsAppInvoiceButton({ invoiceId, disabled }: { invoiceId: number; disabled?: boolean }) {
  const t = useT();
  const { direct } = useWhatsApp();
  const [busy, setBusy] = useState(false);
  const [askPhone, setAskPhone] = useState(false);
  const [phone, setPhone] = useState("");
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(null);
  if (!direct) return null;

  async function send(to?: string) {
    setBusy(true);
    setResult(null);
    try {
      const r = await api<SendOutcome>("POST", `/invoices/${invoiceId}/whatsapp`, to ? { phone: to } : {});
      setResult(r.status === "FAILED" ? { ok: false, text: r.error ?? t("Not sent") } : { ok: true, text: t("Sent on WhatsApp") });
      if (r.status !== "FAILED") setAskPhone(false);
    } catch (e) {
      if (e instanceof ApiError && e.code === "no_mobile") setAskPhone(true);
      else setResult({ ok: false, text: e instanceof ApiError ? e.message : t("Could not reach the server") });
    } finally {
      setBusy(false);
    }
  }

  return (
    <span className="inline-flex flex-col items-end gap-1">
      {askPhone ? (
        <form className="inline-flex items-center gap-1.5" onSubmit={(e) => { e.preventDefault(); void send(phone); }}>
          <Input autoFocus required inputMode="tel" className="w-40" placeholder="05XXXXXXXX" value={phone} onChange={(e) => setPhone(e.target.value)}
                 aria-label={t("Customer's WhatsApp number")} />
          <Button type="submit" loading={busy} disabled={!phone.trim()}><Send className="size-4" aria-hidden /> {t("Send")}</Button>
          <Button type="button" variant="ghost" onClick={() => setAskPhone(false)}>{t("Cancel")}</Button>
        </form>
      ) : (
        <Button variant="secondary" className="text-emerald-700" loading={busy} disabled={disabled} onClick={() => send()}
                title={disabled ? t("Available once ZATCA clears this invoice") : t("Send the PDF to the customer on WhatsApp")}>
          <MessageCircle className="size-4" aria-hidden /> WhatsApp
        </Button>
      )}
      {result && <span role="status" className={`text-xs ${result.ok ? "text-ok" : "text-bad"}`}>{result.text}</span>}
    </span>
  );
}
