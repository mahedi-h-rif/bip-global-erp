"use client";

import Link from "next/link";
import { use, useState } from "react";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import { Trash2 } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { Receipt } from "@/lib/types";
import { date, money, paymentMethodLabel } from "@/lib/format";
import { Alert, Button, Card, PageHeader, Spinner } from "@/components/ui";
import { RelationshipMapButton } from "@/components/relationship-map";
import { useT } from "@/lib/i18n";

export default function ReceiptPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const t = useT();
  const router = useRouter();
  const { data: r, error } = useSWR<Receipt>(`/receipts/${id}`, fetcher);
  const [problem, setProblem] = useState<string | null>(null);
  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!r) return <Spinner />;

  async function remove() {
    if (!window.confirm(t("Delete payment {number}? Its amounts are taken off the invoices again.", { number: r!.number }))) return;
    try {
      await api("DELETE", `/receipts/${r!.id}`);
      router.push("/receipts");
    } catch (e) {
      setProblem(e instanceof ApiError ? e.message : t("Could not reach the server"));
    }
  }

  return (
    <>
      <PageHeader
        title={t("Incoming payment {number}", { number: r.number })}
        subtitle={<span dir="auto">{r.customerName}</span>}
        actions={<span className="flex gap-2"><RelationshipMapButton type="RECEIPT" id={r.id} /><Button variant="ghost" onClick={remove}><Trash2 className="size-4" aria-hidden /> {t("Delete")}</Button></span>}
      />
      {problem && <div className="mb-4"><Alert tone="bad">{problem}</Alert></div>}
      <Card>
        <dl className="grid gap-x-6 gap-y-3 p-5 text-sm md:grid-cols-4">
          <div><dt className="text-xs text-muted">{t("Date")}</dt><dd>{date(r.receiptDate)}</dd></div>
          <div><dt className="text-xs text-muted">{t("Method")}</dt><dd>{t(paymentMethodLabel[r.method as keyof typeof paymentMethodLabel] ?? r.method)}</dd></div>
          <div><dt className="text-xs text-muted">{t("Reference")}</dt><dd>{r.reference ?? "—"}</dd></div>
          <div><dt className="text-xs text-muted">{t("Amount")}</dt><dd className="tabular font-semibold">{money(r.amount)}</dd></div>
          {r.note && <div className="md:col-span-4"><dt className="text-xs text-muted">{t("Note")}</dt><dd dir="auto">{r.note}</dd></div>}
        </dl>
        <table className="w-full border-t border-line text-sm">
          <thead><tr className="border-b border-line text-xs text-muted"><th className="px-4 py-2.5 text-start font-medium">{t("Invoice")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Paid")}</th></tr></thead>
          <tbody className="divide-y divide-line">
            {r.allocations.map((a) => (
              <tr key={a.invoiceId}>
                <td className="tabular px-4 py-3"><Link className="text-brand hover:underline" href={`/invoices/${a.invoiceId}`}>{a.invoiceNumber}</Link></td>
                <td className="tabular px-4 py-3 text-end">{money(a.amount)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </>
  );
}
