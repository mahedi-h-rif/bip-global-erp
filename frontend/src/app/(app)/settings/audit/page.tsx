"use client";

import { useState } from "react";
import useSWR from "swr";
import { fetcher } from "@/lib/api";
import type { AuditEntry, Page } from "@/lib/types";
import { dateTime } from "@/lib/format";
import { Alert, Button, Card, Empty, PageHeader, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

export default function AuditPage() {
  const t = useT();
  const [page, setPage] = useState(0);
  const { data, error } = useSWR<Page<AuditEntry>>(`/audit?page=${page}&size=50`, fetcher);

  return (
    <>
      <PageHeader title={t("Audit log")} subtitle={t("Who did what, and every ZATCA outcome")} />
      <Card>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.content.length === 0 && <Empty title={t("No activity yet")} />}
        {data && data.content.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-xs text-muted">
                  <th className="px-4 py-2.5 text-start font-medium">{t("When")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Who")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Action")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Details")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {data.content.map((a) => (
                  <tr key={a.id}>
                    <td className="whitespace-nowrap px-4 py-2.5 text-muted">{dateTime(a.createdAt)}</td>
                    <td className="px-4 py-2.5">{a.actor}</td>
                    <td className="px-4 py-2.5 font-mono text-xs">{a.action}</td>
                    <td className="px-4 py-2.5 text-muted">{a.details}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data && data.totalPages > 1 && (
          <div className="flex justify-end gap-2 border-t border-line p-3">
            <Button variant="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>{t("Newer")}</Button>
            <Button variant="secondary" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>{t("Older")}</Button>
          </div>
        )}
      </Card>
    </>
  );
}
