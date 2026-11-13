"use client";

import Link from "next/link";
import { useState } from "react";
import { Ban, Lock, RotateCcw } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import type { InvoiceDetail } from "@/lib/types";
import { dateTime, money } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Field, Input } from "@/components/ui";
import { useT } from "@/lib/i18n";

/** "Cancelled" / "Closed" chip for lists. */
export function LifecycleBadge({ lifecycle }: { lifecycle?: string | null }) {
  const t = useT();
  if (!lifecycle) return null;
  const cancelled = lifecycle === "CANCELLED";
  return (
    <span className={`ms-1.5 inline-flex rounded-full px-2 py-0.5 text-[11px] font-semibold ${cancelled ? "bg-red-50 text-bad" : "bg-slate-100 text-slate-600"}`}>
      {cancelled ? t("Cancelled") : t("Closed")}
    </span>
  );
}

/**
 * Cancel (a full credit note to ZATCA, then goods and payment reversed only if ticked), close (write off the unpaid
 * rest) and reopen, for an issued invoice.
 */
export function InvoiceLifecycle({ inv, canWrite, onChanged }: { inv: InvoiceDetail; canWrite: boolean; onChanged: () => void }) {
  const t = useT();
  const [mode, setMode] = useState<"cancel" | "close" | null>(null);
  const [reason, setReason] = useState("");
  const [goodsReturned, setGoodsReturned] = useState(true);
  const [refunded, setRefunded] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const accepted = ["CLEARED", "CLEARED_WITH_WARNINGS", "REPORTED", "REPORTED_WITH_WARNINGS"].includes(inv.status);
  const billable = accepted || inv.status === "SIGNED";
  if (inv.type !== "INVOICE" || !billable) return null;
  const paid = Number(inv.paidAmount ?? 0);
  const hasStockLines = inv.lines.some((l) => l.productId != null);

  async function run(fn: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await fn();
      setMode(null);
      setReason("");
      onChanged();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t("Could not reach the server"));
    } finally {
      setBusy(false);
    }
  }

  if (inv.lifecycle === "CANCELLED") {
    return (
      <Alert tone="bad" title={t("This invoice is cancelled")}>
        {inv.lifecycleReason && <span className="block">{inv.lifecycleReason}</span>}
        <span className="block text-xs">
          {inv.lifecycleAt && dateTime(inv.lifecycleAt)}
          {inv.cancelNoteId && <> · {t("Credit note")} <Link className="font-medium underline" href={`/invoices/${inv.cancelNoteId}`}>{inv.cancelNoteNumber}</Link></>}
        </span>
      </Alert>
    );
  }

  return (
    <>
      {inv.lifecycle === "CLOSED" && (
        <Alert tone="warn" title={t("This invoice is closed")}>
          {inv.lifecycleReason && <span className="block">{inv.lifecycleReason}</span>}
          <span className="block text-xs">{inv.lifecycleAt && dateTime(inv.lifecycleAt)} · {t("The unpaid rest is written off: no dues or reminders.")}</span>
          {canWrite && (
            <Button className="mt-2" variant="secondary" loading={busy} onClick={() => run(() => api("POST", `/invoices/${inv.id}/reopen`))}>
              <RotateCcw className="size-4" aria-hidden /> {t("Reopen")}
            </Button>
          )}
        </Alert>
      )}
      {canWrite && !inv.lifecycle && !mode && (
        <div className="flex flex-wrap gap-2">
          {accepted && (
            <Button variant="danger" onClick={() => { setMode("cancel"); setError(null); }}>
              <Ban className="size-4" aria-hidden /> {t("Cancel invoice")}
            </Button>
          )}
          {Number(inv.dueAmount ?? 0) > 0 && (
            <Button variant="secondary" onClick={() => { setMode("close"); setError(null); }}>
              <Lock className="size-4" aria-hidden /> {t("Close invoice")}
            </Button>
          )}
        </div>
      )}
      {mode === "cancel" && (
        <Card>
          <CardHeader title={t("Cancel invoice {number}", { number: inv.number ?? "" })}
            subtitle={t("A full credit note is issued to ZATCA. The invoice stays in the list, marked Cancelled.")} />
          <form className="space-y-4 p-5" onSubmit={(e) => {
            e.preventDefault();
            void run(() => api("POST", `/invoices/${inv.id}/cancel-invoice`, { reason, goodsReturned: hasStockLines && goodsReturned, paymentRefunded: paid > 0 && refunded }));
          }}>
            {error && <Alert tone="bad">{error}</Alert>}
            <Field label={t("Reason")}><Input required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} placeholder={t("e.g. Customer called off the order")} /></Field>
            {hasStockLines && (
              <label className="flex items-start gap-2 text-sm">
                <input type="checkbox" className="mt-0.5 size-4 accent-[var(--color-brand)]" checked={goodsReturned} onChange={(e) => setGoodsReturned(e.target.checked)} />
                <span><b>{t("The goods were returned")}</b><span className="block text-xs text-muted">{t("Ticked: the items go back into stock. Unticked: stock stays as it is.")}</span></span>
              </label>
            )}
            {paid > 0 && (
              <label className="flex items-start gap-2 text-sm">
                <input type="checkbox" className="mt-0.5 size-4 accent-[var(--color-brand)]" checked={refunded} onChange={(e) => setRefunded(e.target.checked)} />
                <span><b>{t("The payment of {amount} was refunded", { amount: money(paid) })}</b><span className="block text-xs text-muted">{t("Ticked: a refund is recorded so nothing shows as paid. Unticked: the amount stays as the customer's credit.")}</span></span>
              </label>
            )}
            <div className="flex gap-2">
              <Button type="submit" variant="danger" loading={busy}>{t("Cancel invoice")}</Button>
              <Button type="button" variant="ghost" onClick={() => setMode(null)}>{t("Back")}</Button>
            </div>
          </form>
        </Card>
      )}
      {mode === "close" && (
        <Card>
          <CardHeader title={t("Close invoice {number}", { number: inv.number ?? "" })}
            subtitle={t("The unpaid {amount} is written off: no more dues or reminders. The invoice stays valid at ZATCA. You can reopen it.", { amount: money(inv.dueAmount) })} />
          <form className="flex flex-wrap items-end gap-3 p-5" onSubmit={(e) => { e.preventDefault(); void run(() => api("POST", `/invoices/${inv.id}/close`, { reason })); }}>
            {error && <div className="w-full"><Alert tone="bad">{error}</Alert></div>}
            <Field label={t("Reason")} optional className="min-w-64 flex-1"><Input maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} /></Field>
            <Button type="submit" loading={busy}><Lock className="size-4" aria-hidden /> {t("Close invoice")}</Button>
            <Button type="button" variant="ghost" onClick={() => setMode(null)}>{t("Back")}</Button>
          </form>
        </Card>
      )}
    </>
  );
}
