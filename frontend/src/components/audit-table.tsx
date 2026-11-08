"use client";

import { useState } from "react";
import useSWR from "swr";
import { fetcher } from "@/lib/api";
import type { AppUserRow, AuditEntry, Page } from "@/lib/types";
import { dateTime } from "@/lib/format";
import { Alert, Button, Card, Empty, Field, Input, Select, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

/**
 * The audit history, newest first, with filters: user (unless fixed by {@code actor}), text search over the
 * action and details, and a date range.
 */
export function AuditTable({ actor }: { actor?: string }) {
  const t = useT();
  const [page, setPage] = useState(0);
  const [who, setWho] = useState("");
  const [q, setQ] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const { data: users } = useSWR<AppUserRow[]>(actor ? null : "/users", fetcher);

  const params = new URLSearchParams({ page: String(page), size: "50" });
  const user = actor ?? who;
  if (user) params.set("actor", user);
  if (q.trim()) params.set("q", q.trim());
  if (from) params.set("from", from);
  if (to) params.set("to", to);
  const { data, error } = useSWR<Page<AuditEntry>>(`/audit?${params}`, fetcher);
  const names = new Map((users ?? []).map((u) => [u.email.toLowerCase(), u.fullName]));
  const reset = <T,>(set: (v: T) => void) => (v: T) => { set(v); setPage(0); };

  return (
    <Card>
      <div className="grid gap-3 border-b border-line p-4 sm:grid-cols-2 lg:grid-cols-4">
        {!actor && (
          <Field label={t("User")}>
            <Select value={who} onChange={(e) => reset(setWho)(e.target.value)}>
              <option value="">{t("All users")}</option>
              {(users ?? []).map((u) => <option key={u.id} value={u.email}>{u.fullName}</option>)}
              <option value="system">{t("System (automatic)")}</option>
            </Select>
          </Field>
        )}
        <Field label={t("Search")} className={actor ? "lg:col-span-2" : ""}>
          <Input value={q} placeholder={t("e.g. INVOICE, QUOTATION, a number or name")} onChange={(e) => reset(setQ)(e.target.value)} />
        </Field>
        <Field label={t("From")}><Input type="date" value={from} onChange={(e) => reset(setFrom)(e.target.value)} /></Field>
        <Field label={t("To")}><Input type="date" value={to} onChange={(e) => reset(setTo)(e.target.value)} /></Field>
      </div>
      {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
      {!data && !error && <Spinner />}
      {data?.content.length === 0 && <Empty title={t("No activity found")} />}
      {data && data.content.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-xs text-muted">
                <th className="px-4 py-2.5 text-start font-medium">{t("When")}</th>
                {!actor && <th className="px-4 py-2.5 text-start font-medium">{t("Who")}</th>}
                <th className="px-4 py-2.5 text-start font-medium">{t("Action")}</th>
                <th className="px-4 py-2.5 text-start font-medium">{t("Details")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {data.content.map((a) => (
                <tr key={a.id}>
                  <td className="whitespace-nowrap px-4 py-2.5 text-muted">{dateTime(a.createdAt)}</td>
                  {!actor && (
                    <td className="px-4 py-2.5">
                      {names.get((a.actor ?? "").toLowerCase()) ?? a.actor}
                      {names.has((a.actor ?? "").toLowerCase()) && <div className="text-xs text-muted" dir="ltr">{a.actor}</div>}
                    </td>
                  )}
                  <td className="px-4 py-2.5 font-mono text-xs">{a.action}</td>
                  <td className="px-4 py-2.5 text-muted">{a.details}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-end gap-2 border-t border-line p-3">
          <span className="me-auto text-xs text-muted">{t("{count} entries", { count: data.totalElements })}</span>
          <Button variant="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>{t("Newer")}</Button>
          <Button variant="secondary" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>{t("Older")}</Button>
        </div>
      )}
    </Card>
  );
}
