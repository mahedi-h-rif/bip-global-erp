"use client";

import { useState } from "react";
import useSWR from "swr";
import { Mail, MessageCircle, Smartphone } from "lucide-react";
import { fetcher } from "@/lib/api";
import type { NotificationEntry, Page } from "@/lib/types";
import { dateTime, money } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Empty, PageHeader, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

const icon = { EMAIL: Mail, SMS: Smartphone, WHATSAPP: MessageCircle };

const statusStyle: Record<NotificationEntry["status"], string> = {
  SENT: "bg-emerald-50 text-emerald-800",
  OPENED: "bg-emerald-50 text-emerald-800",
  LOGGED: "bg-slate-100 text-slate-700",
  FAILED: "bg-red-50 text-red-700",
};

const statusLabel: Record<NotificationEntry["status"], string> = {
  SENT: "Sent",
  OPENED: "WhatsApp opened",
  LOGGED: "Logged only",
  FAILED: "Failed",
};

export default function RemindersPage() {
  const t = useT();
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<number | null>(null);
  const { data, error } = useSWR<Page<NotificationEntry>>(`/notifications?page=${page}&size=50`, fetcher);

  return (
    <>
      <PageHeader title={t("Reminder log")} subtitle={t("Every payment reminder: automatic (every 15 days) and sent by staff")} />
      <Card>
        <CardHeader
          title={t("Messages")}
          subtitle={t("“Logged only” means the email or SMS service is not set up yet: the message was written to the server log, not delivered.")}
        />
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.content.length === 0 && <Empty title={t("No reminders yet")}>{t("Send one from the Due pages, or wait for the automatic run.")}</Empty>}
        {data && data.content.length > 0 && (
          <ul className="divide-y divide-line">
            {data.content.map((n) => {
              const Icon = icon[n.channel];
              return (
                <li key={n.id} className="px-5 py-3 text-sm">
                  <button type="button" onClick={() => setOpen(open === n.id ? null : n.id)} className="flex w-full items-start gap-3 text-start" aria-expanded={open === n.id}>
                    <Icon className="mt-0.5 size-4 shrink-0 text-muted" aria-hidden />
                    <div className="min-w-0 flex-1">
                      <p className="font-medium">
                        {n.customerName ?? t("Deleted customer")}
                        <span className="font-normal text-muted"> · {n.recipient} · {n.dueAmount != null && money(n.dueAmount)}</span>
                      </p>
                      <p className="text-xs text-muted">
                        {dateTime(n.createdAt)} · {n.trigger === "AUTO" ? t("Automatic") : t("By {name}", { name: n.createdBy })}
                      </p>
                      {n.error && <p className="mt-1 text-xs text-bad">{n.error}</p>}
                    </div>
                    <span className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-medium ${statusStyle[n.status]}`}>{t(statusLabel[n.status])}</span>
                  </button>
                  {open === n.id && n.message && (
                    <pre className="mt-3 ms-7 whitespace-pre-wrap rounded-lg bg-canvas p-3 font-sans text-xs" dir="auto">{n.message}</pre>
                  )}
                </li>
              );
            })}
          </ul>
        )}
        {data && data.totalPages > 1 && (
          <div className="flex items-center justify-between border-t border-line px-4 py-3 text-sm text-muted">
            <span>{t("Page {n} of {total}", { n: data.number + 1, total: data.totalPages })}</span>
            <div className="flex gap-2">
              <Button variant="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>{t("Previous")}</Button>
              <Button variant="secondary" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>{t("Next")}</Button>
            </div>
          </div>
        )}
      </Card>
    </>
  );
}
