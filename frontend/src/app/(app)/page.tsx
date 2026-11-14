"use client";

import { ErpDashboard } from "@/components/erp-dashboard";
import { VatTile } from "@/components/vat-tile";
import Link from "next/link";
import { useEffect } from "react";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import { AlertTriangle, Clock, FilePen, Plus, ShieldAlert, TrendingUp } from "lucide-react";
import { fetcher } from "@/lib/api";
import type { AccountMonth, Dashboard, Me, UserMonth } from "@/lib/types";
import { countdown, date, dateTime, money, todayKsa, typeLabel } from "@/lib/format";
import { roleLabel } from "@/lib/roles";
import { Alert, ButtonLink, Card, CardHeader, Empty, PageHeader, Spinner } from "@/components/ui";
import { SalesReportPanel } from "@/components/sales-report";
import { Trans, useT } from "@/lib/i18n";

export default function DashboardPage() {
  const router = useRouter();
  const t = useT();
  const { data: me } = useSWR<Me>("/auth/me", fetcher);
  // Sales figures are for the client's admin; normal staff start at Invoices. A branch login gets a summary of
  // only what it created: its invoices and its own cash book this month.
  const isBranch = me?.role === "BRANCH";
  const isAdmin = me?.role === "ADMIN" || me?.role === "OPERATOR" || isBranch;
  const { data, error } = useSWR<Dashboard>(isAdmin ? "/dashboard" : null, fetcher, { refreshInterval: 60_000 });
  const today = todayKsa();
  const { data: book } = useSWR<AccountMonth>(isBranch ? `/accounts/month?year=${today.slice(0, 4)}&month=${Number(today.slice(5, 7))}` : null, fetcher);
  useEffect(() => {
    if (me && !isAdmin) router.replace("/invoices");
  }, [me, isAdmin, router]);

  if (!me || !isAdmin) return <Spinner />;
  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!data) return <Spinner />;

  const stats = [
    { label: t("Issued this month"), value: money(data.issuedThisMonth), icon: TrendingUp, href: "/invoices" },
    { label: t("Awaiting ZATCA"), value: data.awaitingSubmission, icon: Clock, href: "/invoices?status=SIGNED" },
    { label: t("Drafts"), value: data.drafts, icon: FilePen, href: "/invoices?status=DRAFT" },
    { label: t("Rejected"), value: data.rejected, icon: AlertTriangle, href: "/invoices?status=REJECTED", bad: data.rejected > 0 },
  ];
  const expiring = data.certificates.filter((c) => c.daysLeft < 30);

  return (
    <>
      <PageHeader
        title={t("Dashboard")}
        subtitle={isBranch ? t("Your invoices and your cash book: only what you created") : t("Sales, collections, dues and ZATCA submissions at a glance")}
        actions={<ButtonLink href="/invoices/new"><Plus className="size-4" aria-hidden /> {t("New invoice")}</ButtonLink>}
      />
      {!isBranch && <VatTile />}
      {!isBranch && <ErpDashboard />}

      {expiring.map((c) => (
        <div key={c.egsUnitId} className="mb-4">
          <Alert tone={c.daysLeft < 7 ? "bad" : "warn"} title={t("Certificate for {name} expires {date}", { name: c.name, date: date(c.expiresAt) })}>
            <Trans
              k="Renew it under {link} with a new OTP from the Fatoora portal before it lapses."
              values={{ link: <Link className="underline" href="/settings/devices">{t("ZATCA devices")}</Link> }}
            />
          </Alert>
        </div>
      ))}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {stats.map((s) => (
          <Link key={s.label} href={s.href} className="group">
            <Card className="p-5 transition group-hover:border-brand/40">
              <div className="flex items-center justify-between text-muted">
                <span className="text-xs font-medium">{s.label}</span>
                <s.icon className={`size-4 ${s.bad ? "text-bad" : ""}`} aria-hidden />
              </div>
              <p className={`tabular mt-2 text-2xl font-semibold ${s.bad ? "text-bad" : ""}`}>{s.value}</p>
            </Card>
          </Link>
        ))}
      </div>

      {isBranch ? book && (
        <Card className="mt-6">
          <CardHeader title={t("My cash book this month")} subtitle={t("Income and expenses you entered")}
                      action={<Link className="text-sm font-medium text-brand hover:underline" href="/accounts">{t("Open")}</Link>} />
          <div className="grid gap-4 p-5 sm:grid-cols-2 lg:grid-cols-4">
            {[
              { label: t("Total bill / work"), value: book.totals.billed },
              { label: t("Received"), value: book.totals.received, tone: "text-ok" },
              { label: t("Expenses"), value: book.totals.expense },
              { label: t("Cash after expenses"), value: book.totals.cashAfterExpense, tone: Number(book.totals.cashAfterExpense) < 0 ? "text-bad" : "text-ok" },
            ].map((s) => (
              <div key={s.label}>
                <p className="text-xs font-medium text-muted">{s.label}</p>
                <p className={`tabular mt-1 text-xl font-semibold ${s.tone ?? ""}`}>{money(s.value)}</p>
              </div>
            ))}
          </div>
        </Card>
      ) : (
        <>
          <SalesReportPanel />
          <UsersThisMonth />
        </>
      )}

      <Card className="mt-6">
        <CardHeader
          title={t("ZATCA queue")}
          subtitle={t("Simplified invoices must be reported within 24 hours. Queued ones are sent automatically before the deadline.")}
        />
        {data.queue.length === 0 ? (
          <Empty title={t("Nothing waiting")}>{t("Every issued document has been accepted by ZATCA.")}</Empty>
        ) : (
          <ul className="divide-y divide-line">
            {data.queue.map((i) => {
              const cd = countdown(i.reportDeadlineAt);
              return (
                <li key={i.id}>
                  <Link href={`/invoices/${i.id}`} className="flex items-center justify-between gap-4 px-5 py-3 hover:bg-canvas">
                    <div>
                      <p className="text-sm font-medium">{i.number} <span className="font-normal text-muted">· {t(typeLabel[i.type])}</span></p>
                      <p className="text-xs text-muted">{i.customerName ?? t("Walk-in customer")} · {money(i.totalInclVat)}</p>
                    </div>
                    {cd && (
                      <span className={`inline-flex items-center gap-1 text-xs font-medium ${cd.overdue ? "text-bad" : "text-warn"}`}>
                        {cd.overdue ? <ShieldAlert className="size-3.5" aria-hidden /> : <Clock className="size-3.5" aria-hidden />}
                        {cd.overdue ? t("overdue {time}", { time: cd.time }) : t("deadline in {time}", { time: cd.time })}
                      </span>
                    )}
                  </Link>
                </li>
              );
            })}
          </ul>
        )}
      </Card>
    </>
  );
}

/** The admin's view of every login this month: invoices issued and their value, quotations and actions. */
function UsersThisMonth() {
  const t = useT();
  const { data } = useSWR<UserMonth[]>("/users/this-month", fetcher, { refreshInterval: 60_000 });
  if (!data || data.length === 0) return null;
  return (
    <Card className="mt-6">
      <CardHeader title={t("This month by user")} subtitle={t("What each login issued and did this month; open a user for their full history")}
        action={<Link className="text-sm font-medium text-brand hover:underline" href="/settings/users">{t("Users")}</Link>} />
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-line text-xs text-muted">
              <th className="px-4 py-2.5 text-start font-medium">{t("User")}</th>
              <th className="px-4 py-2.5 text-end font-medium">{t("Invoices")}</th>
              <th className="px-4 py-2.5 text-end font-medium">{t("Invoiced")}</th>
              <th className="px-4 py-2.5 text-end font-medium">{t("Quotations")}</th>
              <th className="px-4 py-2.5 text-end font-medium">{t("Actions")}</th>
              <th className="px-4 py-2.5 text-start font-medium">{t("Last active")}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {data.map((u) => (
              <tr key={u.id} className={u.enabled ? "" : "opacity-60"}>
                <td className="px-4 py-2.5">
                  <Link href={`/settings/users/${u.id}`} className="font-medium text-brand hover:underline">{u.fullName}</Link>
                  <span className="ms-2 text-xs text-muted">{t(roleLabel(u.role))}</span>
                </td>
                <td className="tabular px-4 py-2.5 text-end">{u.invoices}</td>
                <td className="tabular px-4 py-2.5 text-end">{money(u.issued)}</td>
                <td className="tabular px-4 py-2.5 text-end">{u.quotations}</td>
                <td className="tabular px-4 py-2.5 text-end">{u.actions}</td>
                <td className="whitespace-nowrap px-4 py-2.5 text-muted">{u.lastActiveAt ? dateTime(u.lastActiveAt) : "–"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  );
}
