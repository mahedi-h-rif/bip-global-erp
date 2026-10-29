"use client";

import Link from "next/link";
import { useState } from "react";
import useSWR from "swr";
import { BellOff, ChevronRight, Search } from "lucide-react";
import { fetcher } from "@/lib/api";
import type { CustomerDue, Me } from "@/lib/types";
import { date, money } from "@/lib/format";
import { Alert, Card, Empty, Input, PageHeader, Spinner } from "@/components/ui";
import { DueActions } from "@/components/due-actions";
import { useT } from "@/lib/i18n";

export default function CustomerDuesPage() {
  const t = useT();
  const [q, setQ] = useState("");
  const { data, error, mutate } = useSWR<CustomerDue[]>(`/dues/customers${q.trim() ? `?q=${encodeURIComponent(q.trim())}` : ""}`, fetcher, {
    keepPreviousData: true,
  });
  const { data: me } = useSWR<Me>("/auth/me", fetcher);
  const canWrite = me != null && me.role !== "VIEWER";
  const total = (data ?? []).reduce((s, c) => s + Number(c.due), 0);

  return (
    <>
      <PageHeader
        title={t("Customer-wise due")}
        subtitle={data ? t(data.length === 1 ? "{n} customer owes {amount} in all" : "{n} customers owe {amount} in all", { n: data.length, amount: money(total) }) : t("What each customer still owes")}
      />
      <Card>
        <div className="border-b border-line p-4">
          <div className="relative">
            <Search className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted" aria-hidden />
            <Input className="ps-9" placeholder={t("Search customer")} value={q} onChange={(e) => setQ(e.target.value)} aria-label={t("Search customer")} />
          </div>
        </div>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("Nobody owes anything")}>{t("All issued invoices are fully paid.")}</Empty>}
        {data && data.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[860px] text-sm">
              <thead>
                <tr className="border-b border-line text-xs text-muted">
                  <th className="px-4 py-2.5 text-start font-medium">{t("Customer")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Invoices")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Invoiced")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Paid")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Due")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Last reminder")}</th>
                  <th className="px-4 py-2.5" />
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {data.map((c) => (
                  <tr key={c.customerId ?? "walk-in"} className="hover:bg-canvas">
                    <td className="px-4 py-3">
                      {c.customerId != null ? (
                        <Link href={`/dues?customerId=${c.customerId}`} className="inline-flex items-center gap-1 font-medium hover:underline">
                          {c.customerName} <ChevronRight className="size-3.5 text-muted rtl:rotate-180" aria-hidden />
                        </Link>
                      ) : (
                        <span className="font-medium text-muted">{c.customerName}</span>
                      )}
                      <span className="block text-xs text-muted">
                        {[c.phone, c.email].filter(Boolean).join(" · ") || (c.customerId != null ? t("No contact details") : t("No customer on the invoice"))}
                      </span>
                    </td>
                    <td className="tabular px-4 py-3 text-end">{c.invoiceCount}</td>
                    <td className="tabular px-4 py-3 text-end">{money(c.totalInvoiced)}</td>
                    <td className="tabular px-4 py-3 text-end text-ok">{money(c.paid)}</td>
                    <td className="tabular px-4 py-3 text-end">
                      <span className="font-semibold text-bad">{money(c.due)}</span>
                      {c.maxDaysOverdue > 0 && <span className="block text-xs text-bad">{t("up to {n} days overdue", { n: c.maxDaysOverdue })}</span>}
                    </td>
                    <td className="px-4 py-3 text-muted">
                      {c.customerId == null ? "—" : c.remindersEnabled ? date(c.lastReminderAt) : (
                        <span className="inline-flex items-center gap-1 text-warn"><BellOff className="size-3.5" aria-hidden /> {t("Off")}</span>
                      )}
                    </td>
                    <td className="px-4 py-2 text-end">
                      {canWrite && c.customerId != null && (
                        <DueActions compact customerId={c.customerId} email={c.email} phone={c.phone} onDone={() => mutate()} />
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
