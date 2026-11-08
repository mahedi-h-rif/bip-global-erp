"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import { Undo2 } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import { money } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Field, Input } from "@/components/ui";
import { useT } from "@/lib/i18n";

interface Returnable { lineIndex: number; name: string; unitPrice: number; sold: number; returned: number; returnable: number }

/** Full or partial sales return: pick quantities per line; a credit note is issued and the stock comes back. */
export function ReturnItems({ invoiceId }: { invoiceId: number }) {
  const t = useT();
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const { data } = useSWR<Returnable[]>(open ? `/invoices/${invoiceId}/returns` : null, fetcher);
  const [qty, setQty] = useState<Record<number, string>>({});
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (!open) return <Button variant="secondary" onClick={() => setOpen(true)}><Undo2 className="size-4" aria-hidden /> {t("Return items")}</Button>;
  const lines = Object.entries(qty).filter(([, v]) => Number(v) > 0).map(([i, v]) => ({ lineIndex: Number(i), quantity: Number(v) }));
  return (
    <Card className="mt-4">
      <CardHeader title={t("Return items")} subtitle={t("A credit note for the chosen quantities is sent to ZATCA; stock goes back to the warehouse and the customer's balance goes down.")} />
      <div className="space-y-3 p-5">
        {error && <Alert tone="bad">{error}</Alert>}
        <table className="w-full text-sm">
          <thead><tr className="border-b border-line text-xs text-muted"><th className="py-2 text-start font-medium">{t("Item")}</th><th className="py-2 text-end font-medium">{t("Sold")}</th><th className="py-2 text-end font-medium">{t("Returned")}</th><th className="w-32 py-2 text-end font-medium">{t("Return now")}</th></tr></thead>
          <tbody className="divide-y divide-line">
            {data?.map((l) => (
              <tr key={l.lineIndex}>
                <td className="py-2" dir="auto">{l.name} <span className="text-xs text-muted">@ {money(l.unitPrice)}</span></td>
                <td className="tabular py-2 text-end">{Number(l.sold)}</td>
                <td className="tabular py-2 text-end">{Number(l.returned)}</td>
                <td className="py-2"><Input type="number" min="0" max={l.returnable} step="any" className="text-end" disabled={Number(l.returnable) <= 0}
                                           value={qty[l.lineIndex] ?? ""} onChange={(e) => setQty({ ...qty, [l.lineIndex]: e.target.value })} aria-label={t("Return now")} /></td>
              </tr>
            ))}
          </tbody>
        </table>
        <Field label={t("Reason")}><Input required value={reason} onChange={(e) => setReason(e.target.value)} placeholder={t("e.g. damaged goods returned")} /></Field>
        <div className="flex gap-2">
          <Button loading={busy} disabled={lines.length === 0 || !reason.trim()} onClick={async () => {
            setBusy(true); setError(null);
            try { const r = await api<{ creditNoteId: number }>("POST", `/invoices/${invoiceId}/returns`, { reason, lines, issue: true }); router.push(`/invoices/${r.creditNoteId}`); }
            catch (e) { setError(e instanceof ApiError ? e.message : t("Could not reach the server")); setBusy(false); }
          }}>{t("Issue return credit note")}</Button>
          <Button variant="ghost" onClick={() => setOpen(false)}>{t("Cancel")}</Button>
        </div>
      </div>
    </Card>
  );
}
