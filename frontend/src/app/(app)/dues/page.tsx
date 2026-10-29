"use client";

import Link from "next/link";
import { Suspense, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import useSWR from "swr";
import { ArrowLeft, Search } from "lucide-react";
import { fetcher } from "@/lib/api";
import type { Customer, DueInvoice, Me } from "@/lib/types";
import { date, kindLabel, money } from "@/lib/format";
import { Alert, Card, Empty, Input, PageHeader, PaymentBadge, Select, Spinner } from "@/components/ui";
import { DueActions } from "@/components/due-actions";
import { useT } from "@/lib/i18n";

function Dues() {
  const params = useSearchParams();
  const router = useRouter();
  const t = useT();
  const customerId = params.get("customerId") ?? "";
  const overdue = params.get("overdue") === "1";
  const [q, setQ] = useState(params.get("q") ?? "");

  const query = new URLSearchParams();
  if (customerId) query.set("customerId", customerId);
  if (overdue) query.set("overdueOnly", "true");
  if (q.trim()) query.set("q", q.trim());
  const { data, error, mutate } = useSWR<DueInvoice[]>(`/dues?${query}`, fetcher, { keepPreviousData: true });
  const { data: customers } = useSWR<Customer[]>("/customers", fetcher);
  const { data: me } = useSWR<Me>("/auth/me", fetcher);
  const canWrite = me != null && me.role !== "VIEWER";
  const byId = new Map((customers ?? []).map((c) => [c.id, c]));
  const selected = customerId ? byId.get(Number(customerId)) : undefined;

  function update(next: Record<string, string>) {
    const p = new URLSearchParams(params.toString());
    Object.entries(next).forEach(([k, v]) => (v ? p.set(k, v) : p.delete(k)));
    router.replace(`/dues?${p}`);
  }

  const totals = (data ?? []).reduce(
    (t, d) => ({ total: t.total + Number(d.adjustedTotal), paid: t.paid + Number(d.paid), due: t.due + Number(d.due) }),
    { total: 0, paid: 0, due: 0 },
  );

  return (
    <>
      <PageHeader
        title={selected ? `${t("Due")} · ${selected.nameEn || selected.nameAr}` : t("All dues")}
        subtitle={selected ? t("Unpaid and partly paid invoices of this customer") : t("Every issued invoice that is not fully paid, oldest first")}
        actions={
          selected && (
            <>
              <Link href="/dues/customers" className="inline-flex items-center gap-1.5 self-center text-sm text-muted hover:text-ink">
                <ArrowLeft className="size-4 rtl:rotate-180" aria-hidden /> {t("Customer-wise due")}
              </Link>
              {canWrite && <DueActions customerId={selected.id!} email={selected.email ?? null} phone={selected.phone ?? null} />}
            </>
          )
        }
      />

      <div className="mb-4 grid gap-4 sm:grid-cols-3">
        {[
          [t("Invoiced"), totals.total, ""],
          [t("Collected"), totals.paid, "text-ok"],
          [t("Due"), totals.due, "text-bad"],
        ].map(([label, value, tone]) => (
          <Card key={label as string} className="p-5">
            <p className="text-xs font-medium text-muted">{label}</p>
            <p className={`tabular mt-2 text-2xl font-semibold ${tone}`}>{money(value as number)}</p>
          </Card>
        ))}
      </div>

      <Card>
        <div className="flex flex-wrap items-center gap-3 border-b border-line p-4">
          <div className="relative min-w-52 flex-1">
            <Search className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted" aria-hidden />
            <Input className="ps-9" placeholder={t("Search invoice number or customer")} value={q} onChange={(e) => setQ(e.target.value)} aria-label={t("Search dues")} />
          </div>
          <Select className="w-auto" value={customerId} onChange={(e) => update({ customerId: e.target.value })} aria-label={t("Customer")}>
            <option value="">{t("All customers")}</option>
            {customers?.map((c) => <option key={c.id} value={c.id}>{c.nameEn || c.nameAr}</option>)}
          </Select>
          <label className="inline-flex items-center gap-2 text-sm text-muted">
            <input type="checkbox" checked={overdue} onChange={(e) => update({ overdue: e.target.checked ? "1" : "" })} className="size-4 accent-[var(--color-brand)]" />
            {t("Overdue only")}
          </label>
        </div>

        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("Nothing due")}>{t("Every issued invoice matching the filters is fully paid.")}</Empty>}
        {data && data.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[900px] text-sm">
              <thead>
                <tr className="border-b border-line text-xs text-muted">
                  <th className="px-4 py-2.5 text-start font-medium">{t("Invoice")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Customer")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Issued")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Due date")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Total")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Paid")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Due")}</th>
                  <th className="px-4 py-2.5 text-end font-medium" />
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {data.map((d) => {
                  const c = d.customerId != null ? byId.get(d.customerId) : undefined;
                  return (
                    <tr key={d.invoiceId} className="hover:bg-canvas">
                      <td className="px-4 py-3">
                        <Link href={`/invoices/${d.invoiceId}`} className="font-medium hover:underline">{d.number}</Link>
                        <span className="block text-xs text-muted">{t(kindLabel[d.kind])}</span>
                      </td>
                      <td className="px-4 py-3">
                        {d.customerId != null ? (
                          <Link href={`/dues?customerId=${d.customerId}`} className="hover:underline">{d.customerName}</Link>
                        ) : (
                          <span className="text-muted">{t("Walk-in")}</span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-muted">
                        {date(d.issuedAt)}
                        <span className="block text-xs">{t(d.ageDays === 1 ? "{n} day ago" : "{n} days ago", { n: d.ageDays })}</span>
                      </td>
                      <td className="px-4 py-3">
                        {d.dueDate ? (
                          <>
                            {date(d.dueDate)}
                            {d.daysOverdue != null && d.daysOverdue > 0 && (
                              <span className="block text-xs font-medium text-bad">{t(d.daysOverdue === 1 ? "{n} day overdue" : "{n} days overdue", { n: d.daysOverdue })}</span>
                            )}
                          </>
                        ) : (
                          <span className="text-muted">—</span>
                        )}
                      </td>
                      <td className="tabular px-4 py-3 text-end">{money(d.adjustedTotal)}</td>
                      <td className="tabular px-4 py-3 text-end text-ok">{money(d.paid)}</td>
                      <td className="tabular px-4 py-3 text-end">
                        <span className="font-semibold text-bad">{money(d.due)}</span>
                        <span className="mt-0.5 block"><PaymentBadge status={d.paymentStatus} /></span>
                      </td>
                      <td className="px-4 py-2 text-end">
                        {canWrite && c && !selected && (
                          <DueActions compact customerId={c.id!} email={c.email ?? null} phone={c.phone ?? null} onDone={() => mutate()} />
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}

export default function DuesPage() {
  return (
    <Suspense fallback={<Spinner />}>
      <Dues />
    </Suspense>
  );
}
