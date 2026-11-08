"use client";

import Link from "next/link";
import { use } from "react";
import useSWR from "swr";
import { ArrowLeft } from "lucide-react";
import { fetcher } from "@/lib/api";
import type { UserActivity } from "@/lib/types";
import { dateTime, money } from "@/lib/format";
import { Alert, Card, CardHeader, PageHeader, Spinner } from "@/components/ui";
import { AuditTable } from "@/components/audit-table";
import { useT } from "@/lib/i18n";
import { roleLabel } from "@/lib/roles";

/** What each kind of created record is called, and where the admin can see all of them. */
const KINDS: { key: string; label: string; href: string }[] = [
  { key: "invoices", label: "Invoices & notes", href: "/invoices" },
  { key: "quotations", label: "Quotations", href: "/quotations" },
  { key: "agreements", label: "Agreements", href: "/agreements" },
  { key: "pads", label: "Pad documents", href: "/pad" },
  { key: "payments", label: "Payments recorded", href: "/dues" },
  { key: "income", label: "Cash book income", href: "/accounts" },
  { key: "expenses", label: "Cash book expenses", href: "/accounts" },
  { key: "withdrawals", label: "Partner withdrawals", href: "/accounts" },
  { key: "documents", label: "Documents", href: "/documents" },
  { key: "workerDays", label: "Worker day entries", href: "/workers" },
  { key: "workerLeaves", label: "Worker leaves", href: "/workers" },
];

export default function UserActivityPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const t = useT();
  const { data, error } = useSWR<UserActivity>(`/users/${id}/activity`, fetcher);

  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!data) return <Spinner />;
  const u = data.user;

  return (
    <>
      <Link href="/settings/users" className="mb-3 inline-flex items-center gap-1 text-sm text-muted hover:text-ink">
        <ArrowLeft className="size-4 rtl:rotate-180" aria-hidden /> {t("Users")}
      </Link>
      <PageHeader
        title={u.fullName}
        subtitle={<span dir="ltr">{u.email} · {t(roleLabel(u.role))} · {u.enabled ? t("Active") : t("Disabled")}</span>}
      />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {[
          { label: t("Invoices issued this month"), value: String(data.invoicesThisMonth) },
          { label: t("Invoiced this month"), value: money(data.issuedThisMonth) },
          { label: t("Quotations this month"), value: String(data.quotationsThisMonth) },
          { label: t("Actions this month"), value: String(u.actionsThisMonth) },
        ].map((s) => (
          <Card key={s.label} className="p-5">
            <p className="text-xs font-medium text-muted">{s.label}</p>
            <p className="tabular mt-2 text-2xl font-semibold">{s.value}</p>
          </Card>
        ))}
      </div>

      <Card className="mt-6">
        <CardHeader title={t("Records created (all time)")}
          subtitle={t("Last sign-in: {when}", { when: u.lastLoginAt ? dateTime(u.lastLoginAt) : t("Never") })} />
        <div className="grid gap-px bg-line sm:grid-cols-2 lg:grid-cols-4">
          {KINDS.map((k) => (
            <Link key={k.key} href={k.href} className="bg-white px-5 py-3 hover:bg-canvas">
              <p className="text-xs text-muted">{t(k.label)}</p>
              <p className="tabular text-lg font-semibold">{data.created[k.key] ?? 0}</p>
            </Link>
          ))}
        </div>
      </Card>

      <h2 className="mb-3 mt-8 text-base font-semibold">{t("History")}</h2>
      <AuditTable actor={u.email} />
    </>
  );
}
