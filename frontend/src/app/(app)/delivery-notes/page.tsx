"use client";

import Link from "next/link";
import { useState } from "react";
import useSWR from "swr";
import { Plus } from "lucide-react";
import { fetcher } from "@/lib/api";
import type { DeliveryNote } from "@/lib/types";
import { date } from "@/lib/format";
import { Alert, ButtonLink, Card, Empty, PageHeader, Select, Spinner } from "@/components/ui";
import { deliveryStatusLabel } from "@/components/delivery-note-editor";
import { useT } from "@/lib/i18n";

const tone: Record<DeliveryNote["status"], string> = {
  DRAFT: "bg-amber-50 text-amber-700",
  DELIVERED: "bg-emerald-50 text-emerald-700",
  CANCELLED: "bg-slate-100 text-slate-500",
};

export default function DeliveryNotesPage() {
  const t = useT();
  const [status, setStatus] = useState("");
  const { data, error } = useSWR<DeliveryNote[]>(`/delivery-notes${status ? `?status=${status}` : ""}`, fetcher, { keepPreviousData: true });
  return (
    <>
      <PageHeader
        title={t("Delivery notes")}
        subtitle={t("Create them from an issued invoice (open the invoice) or on their own.")}
        actions={<ButtonLink href="/delivery-notes/new"><Plus className="size-4" aria-hidden /> {t("New delivery note")}</ButtonLink>}
      />
      <Card>
        <div className="border-b border-line p-4">
          <Select className="w-48" value={status} onChange={(e) => setStatus(e.target.value)} aria-label={t("Status")}>
            <option value="">{t("All statuses")}</option>
            {Object.entries(deliveryStatusLabel).map(([k, v]) => <option key={k} value={k}>{t(v)}</option>)}
          </Select>
        </div>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("No delivery notes yet")} />}
        {data && data.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-xs text-muted">
                  <th className="px-4 py-2.5 text-start font-medium">{t("Number")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Client")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Invoice")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Driver / vehicle")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Status")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {data.map((n) => (
                  <tr key={n.id} className="hover:bg-slate-50">
                    <td className="tabular px-4 py-3 font-medium"><Link className="text-brand hover:underline" href={`/delivery-notes/${n.id}`}>{n.number}</Link></td>
                    <td className="tabular px-4 py-3">{date(n.deliveryDate)}</td>
                    <td className="px-4 py-3" dir="auto">{n.customerName ?? n.clientName ?? "—"}</td>
                    <td className="tabular px-4 py-3">{n.invoiceNumber ?? "—"}</td>
                    <td className="px-4 py-3 text-muted">{[n.driverName, n.vehicleNo].filter(Boolean).join(" · ") || "—"}</td>
                    <td className="px-4 py-3"><span className={`rounded px-2 py-0.5 text-xs font-medium ${tone[n.status]}`}>{t(deliveryStatusLabel[n.status])}</span></td>
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
