"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import useSWR from "swr";
import { Bell, CheckCheck } from "lucide-react";
import { api, fetcher } from "@/lib/api";
import type { DocumentAlerts } from "@/lib/types";
import { dateTime } from "@/lib/format";
import { useT } from "@/lib/i18n";
import { DocumentWhatsAppButton, WHATSAPP_ENABLED } from "@/components/whatsapp";

/** Document expiry reminders: unread count on the bell, the list in a panel, a WhatsApp share for each. */
export function NotificationBell({ className = "" }: { className?: string }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const box = useRef<HTMLDivElement>(null);
  const { data, mutate } = useSWR<DocumentAlerts>("/documents/alerts", fetcher, { refreshInterval: 5 * 60_000 });

  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false); };
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", esc);
    return () => { document.removeEventListener("mousedown", close); document.removeEventListener("keydown", esc); };
  }, [open]);

  async function read(id: number) {
    await api("POST", `/documents/alerts/${id}/read`);
    void mutate();
  }

  async function readAll() {
    await api("POST", "/documents/alerts/read-all");
    void mutate();
  }

  const unread = data?.unread ?? 0;
  return (
    <div ref={box} className={`relative ${className}`}>
      <button onClick={() => setOpen(!open)} aria-expanded={open}
              aria-label={unread ? t("Notifications, {n} unread", { n: unread }) : t("Notifications")}
              className="relative grid size-11 place-items-center rounded-lg text-white/80 hover:bg-white/10 hover:text-white focus-visible:outline-white lg:size-9">
        <Bell className="size-5" aria-hidden />
        {unread > 0 && (
          <span className="absolute -end-0.5 -top-0.5 grid min-w-4.5 place-items-center rounded-full bg-bad px-1 text-[10px] font-bold leading-4.5 text-white tabular">
            {unread > 99 ? "99+" : unread}
          </span>
        )}
      </button>
      {open && (
        <div className="fixed inset-x-2 top-14 z-50 overflow-hidden lg:absolute lg:inset-x-auto lg:start-0 lg:top-11 lg:w-88 rounded-xl border border-line bg-white text-ink shadow-xl">
          <div className="flex items-center justify-between border-b border-line px-4 py-3">
            <p className="text-sm font-semibold">{t("Document reminders")}</p>
            {unread > 0 && (
              <button onClick={readAll} className="inline-flex items-center gap-1 text-xs font-medium text-brand hover:underline">
                <CheckCheck className="size-3.5" aria-hidden />{t("Mark all read")}
              </button>
            )}
          </div>
          <ul className="max-h-96 divide-y divide-line overflow-y-auto">
            {(data?.items ?? []).length === 0 && <li className="px-4 py-8 text-center text-sm text-muted">{t("No reminders yet")}</li>}
            {data?.items.map((a) => (
              <li key={a.id} className={`px-4 py-3 text-sm ${a.readAt ? "" : "bg-brand/5"}`}>
                <div className="flex gap-2">
                  <span className={`mt-1.5 size-2 shrink-0 rounded-full ${a.readAt ? "bg-transparent" : a.daysLeft <= 15 ? "bg-bad" : "bg-warn"}`} aria-hidden />
                  <div className="min-w-0 flex-1">
                    <p className="leading-snug" dir="auto">{a.message}</p>
                    <p className="mt-0.5 text-xs text-muted">{dateTime(a.createdAt)}</p>
                    <div className="mt-2 flex flex-wrap gap-1.5">
                      <Link href={`/documents?id=${a.documentId}`} onClick={() => { setOpen(false); if (!a.readAt) void read(a.id); }}
                            className="rounded-md border border-line px-2 py-0.5 text-xs font-medium hover:bg-canvas">{t("Open")}</Link>
                      {WHATSAPP_ENABLED && (
                        <DocumentWhatsAppButton documentId={a.documentId} phone={a.workerPhone} url={a.whatsappUrl}
                                                className="inline-flex items-center gap-1 rounded-md border border-line px-2 py-0.5 text-xs font-medium hover:bg-canvas" />
                      )}
                      {!a.readAt && (
                        <button onClick={() => read(a.id)} className="rounded-md px-2 py-0.5 text-xs text-muted hover:bg-canvas hover:text-ink">{t("Mark read")}</button>
                      )}
                    </div>
                  </div>
                </div>
              </li>
            ))}
          </ul>
          <Link href="/documents" onClick={() => setOpen(false)} className="block border-t border-line px-4 py-2.5 text-center text-xs font-medium text-brand hover:bg-canvas">
            {t("All documents")}
          </Link>
        </div>
      )}
    </div>
  );
}
