"use client";

import Link from "next/link";
import { useEffect } from "react";
import useSWR from "swr";
import { FilePlus2, Pencil, X } from "lucide-react";
import { fetcher } from "@/lib/api";
import type { Customer } from "@/lib/types";
import { date, money } from "@/lib/format";
import { Alert, ButtonLink, Button, Spinner, StatusBadge } from "@/components/ui";
import { LifecycleBadge } from "@/components/invoice-lifecycle";
import { RelationshipMapButton } from "@/components/relationship-map";
import { useT } from "@/lib/i18n";

interface Overview {
  customer: Customer;
  invoiceCount: number;
  invoiced: number;
  paid: number;
  due: number;
  credit: number;
  quotations: number;
  salesOrders: number;
  recent: { id: number; number: string | null; issuedAt: string | null; total: number; due: number | null; status: string; lifecycle: string | null }[];
}

/** A customer at a glance, in a panel beside the list: details, balance and latest invoices. */
export function CustomerPreview({ id, canWrite, onEdit, onClose }: { id: number; canWrite: boolean; onEdit: (c: Customer) => void; onClose: () => void }) {
  const t = useT();
  const { data, error } = useSWR<Overview>(`/customers/${id}/overview`, fetcher);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  const c = data?.customer;
  const row = (label: string, value?: string | null) =>
    value ? <div className="flex justify-between gap-4 py-1.5 text-sm"><dt className="text-muted">{t(label)}</dt><dd className="text-end font-medium" dir="auto">{value}</dd></div> : null;
  const address = c ? [c.street, c.buildingNumber && `${t("Bldg")} ${c.buildingNumber}`, c.district, c.city, c.postalCode, c.countryCode].filter(Boolean).join(", ") : "";

  return (
    <div className="fixed inset-0 z-50 flex justify-end" role="dialog" aria-modal="true" aria-label={t("Customer preview")}>
      <button type="button" className="absolute inset-0 bg-black/30" aria-label={t("Close")} onClick={onClose} />
      <aside className="relative flex h-full w-full max-w-lg flex-col overflow-y-auto bg-white shadow-xl">
        <div className="flex items-start justify-between gap-3 border-b border-line p-5">
          <div className="min-w-0">
            <h2 className="truncate text-lg font-semibold">{c ? c.nameEn || c.nameAr : t("Customer")}</h2>
            {c?.nameEn && c.nameAr && <p className="text-sm text-muted" dir="rtl">{c.nameAr}</p>}
          </div>
          <Button variant="ghost" onClick={onClose} aria-label={t("Close")}><X className="size-5" aria-hidden /></Button>
        </div>
        {error && <div className="p-5"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data && c && (
          <div className="space-y-5 p-5">
            <div className="flex flex-wrap gap-2">
              {canWrite && <ButtonLink href={`/invoices/new?customerId=${c.id}`}><FilePlus2 className="size-4" aria-hidden /> {t("New invoice")}</ButtonLink>}
              {canWrite && <Button variant="secondary" onClick={() => onEdit(c)}><Pencil className="size-4" aria-hidden /> {t("Edit")}</Button>}
              <RelationshipMapButton type="CUSTOMER" id={c.id!} label={c.nameEn || c.nameAr || undefined} />
            </div>

            <div className="grid grid-cols-2 gap-3">
              {[
                { label: "Invoiced", value: money(data.invoiced) },
                { label: "Paid", value: money(data.paid), tone: "text-ok" },
                { label: "Due", value: money(data.due), tone: Number(data.due) > 0 ? "text-bad" : "" },
                { label: "Customer credit", value: money(data.credit) },
              ].map((k) => (
                <div key={k.label} className="rounded-lg border border-line p-3">
                  <p className="text-xs text-muted">{t(k.label)}</p>
                  <p className={`tabular mt-1 text-lg font-semibold ${k.tone ?? ""}`}>{k.value}</p>
                </div>
              ))}
            </div>
            <p className="text-xs text-muted">
              {t("{n} invoices · {q} quotations · {o} sales orders", { n: String(data.invoiceCount), q: String(data.quotations), o: String(data.salesOrders) })}
            </p>

            <dl className="divide-y divide-line rounded-lg border border-line px-4">
              {row("VAT number", c.vatNumber)}
              {row(c.otherIdScheme === "CRN" ? "CR number" : "Other ID", c.otherId)}
              {row("Mobile", c.phone)}
              {row("Email", c.email)}
              {row("Address", c.address)}
              {row("National address", address)}
            </dl>

            <div>
              <h3 className="mb-2 text-sm font-semibold">{t("Latest invoices")}</h3>
              {data.recent.length === 0 ? <p className="text-sm text-muted">{t("No invoices yet")}</p> : (
                <ul className="divide-y divide-line rounded-lg border border-line">
                  {data.recent.map((i) => (
                    <li key={i.id} className="flex items-center justify-between gap-3 px-3 py-2 text-sm">
                      <span className="min-w-0">
                        <Link className="tabular font-medium text-brand hover:underline" href={`/invoices/${i.id}`}>{i.number}</Link>
                        <span className="ms-2 text-xs text-muted">{i.issuedAt ? date(i.issuedAt) : ""}</span>
                        <LifecycleBadge lifecycle={i.lifecycle} />
                      </span>
                      <span className="text-end">
                        <span className="tabular block font-medium">{money(i.total)}</span>
                        {i.due != null && Number(i.due) > 0 && <span className="tabular block text-xs text-bad">{t("Due")} {money(i.due)}</span>}
                      </span>
                      <StatusBadge status={i.status as never} />
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        )}
      </aside>
    </div>
  );
}
