"use client";

import { useState, type FormEvent } from "react";
import { Banknote, CheckCircle2, Pencil, Plus, Trash2 } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import type { InvoiceDetail, Payment, PaymentMethod } from "@/lib/types";
import { date, money, paymentMethodLabel, todayKsa } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Field, Input, PaymentBadge, Select } from "@/components/ui";
import { useT } from "@/lib/i18n";

type Draft = { id?: number; amount: string; paidOn: string; method: PaymentMethod; reference: string; note: string };

function PaymentForm({ inv, initial, onDone }: { inv: InvoiceDetail; initial: Draft; onDone: (saved: boolean) => void }) {
  const t = useT();
  const [p, setP] = useState<Draft>(initial);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (k: keyof Draft) => (e: { target: { value: string } }) => setP({ ...p, [k]: e.target.value });

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const body = { ...p, amount: Number(p.amount), reference: p.reference || null, note: p.note || null };
    try {
      if (p.id) await api("PUT", `/payments/${p.id}`, body);
      else await api("POST", `/invoices/${inv.id}/payments`, body);
      onDone(true);
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError(0, "network", t("Could not reach the server")));
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-4 border-t border-line bg-canvas/50 p-5">
      {error && !error.fields && <Alert tone="bad">{error.message}</Alert>}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Field label={t("Amount (SAR)")} error={error?.fields?.amount}>
          <Input required autoFocus type="number" min="0.01" step="0.01" value={p.amount} onChange={set("amount")} />
        </Field>
        <Field label={t("Date received")}>
          <Input required type="date" value={p.paidOn} onChange={set("paidOn")} />
        </Field>
        <Field label={t("Method")}>
          <Select value={p.method} onChange={set("method")}>
            {(Object.keys(paymentMethodLabel) as PaymentMethod[]).map((m) => <option key={m} value={m}>{t(paymentMethodLabel[m])}</option>)}
          </Select>
        </Field>
        <Field label={t("Reference")} hint={t("Transfer or cheque no., optional")}>
          <Input value={p.reference} onChange={set("reference")} />
        </Field>
        <Field label={t("Note")} className="sm:col-span-2 lg:col-span-4">
          <Input value={p.note} onChange={set("note")} />
        </Field>
      </div>
      <div className="flex gap-2">
        <Button type="submit" loading={busy}>{p.id ? t("Save payment") : t("Record payment")}</Button>
        <Button type="button" variant="ghost" onClick={() => onDone(false)}>{t("Cancel")}</Button>
      </div>
    </form>
  );
}

/** Payments, due amount and due date of an issued invoice. Internal bookkeeping; nothing here goes to ZATCA. */
export function PaymentsCard({ inv, canWrite, onChange }: { inv: InvoiceDetail; canWrite: boolean; onChange: () => void }) {
  const t = useT();
  const [editing, setEditing] = useState<Draft | null>(null);
  const [dueDate, setDueDate] = useState(inv.dueDate ?? "");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const due = Number(inv.dueAmount ?? 0);
  const paid = Number(inv.paidAmount ?? 0);
  const [amount, setAmount] = useState("");

  const pay = (value: number, note: string) =>
    api("POST", `/invoices/${inv.id}/payments`, { amount: Number(value.toFixed(2)), paidOn: todayKsa(), method: "CASH", reference: null, note });

  async function act(name: string, fn: () => Promise<unknown>) {
    setBusy(name);
    setError(null);
    try {
      await fn();
      onChange();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t("Could not reach the server"));
    } finally {
      setBusy(null);
    }
  }

  const fromPayment = (p: Payment): Draft => ({
    id: p.id, amount: String(p.amount), paidOn: p.paidOn, method: p.method, reference: p.reference ?? "", note: p.note ?? "",
  });

  return (
    <Card>
      <CardHeader
        title={t("Payments")}
        subtitle={t("Money received for this invoice. Internal only, never sent to ZATCA.")}
        action={<PaymentBadge status={inv.paymentStatus} />}
      />
      {error && <div className="px-5 pt-4"><Alert tone="bad">{error}</Alert></div>}
      <div className="px-5 pt-4">
        {due <= 0 && paid > 0 ? (
          <Alert tone="ok" title={t("Full paid")}>{t("The customer paid the full {amount}. Nothing is due.", { amount: money(inv.paidAmount) })}</Alert>
        ) : paid > 0 ? (
          <Alert tone="warn" title={t("Partly paid")}>{t("Paid {paid} of {total}. Still due: {due}.", { paid: money(inv.paidAmount), total: money(inv.adjustedTotal), due: money(inv.dueAmount) })}</Alert>
        ) : (
          <Alert tone="info" title={t("Not paid yet")}>{t("The full {amount} is due.", { amount: money(inv.dueAmount) })}</Alert>
        )}
      </div>
      <div className="grid gap-6 p-5 md:grid-cols-2">
        <dl className="tabular space-y-1.5 text-sm">
          <div className="flex justify-between"><dt className="text-muted">{t("Invoice total")}</dt><dd>{money(inv.totalInclVat)}</dd></div>
          {Number(inv.notesTotal ?? 0) !== 0 && (
            <div className="flex justify-between"><dt className="text-muted">{t("Credit / debit notes")}</dt><dd>{money(inv.notesTotal)}</dd></div>
          )}
          <div className="flex justify-between"><dt className="text-muted">{t("Amount owed")}</dt><dd>{money(inv.adjustedTotal)}</dd></div>
          <div className="flex justify-between"><dt className="text-muted">{t("Paid")}</dt><dd className="text-ok">{money(inv.paidAmount)}</dd></div>
          <div className="flex justify-between border-t border-line pt-2 text-base font-semibold">
            <dt>{t("Due")}</dt><dd className={due > 0 ? "text-bad" : ""}>{money(inv.dueAmount)}</dd>
          </div>
          {Number(inv.overpaidAmount ?? 0) > 0 && (
            <p className="pt-1 text-xs text-warn">{t("Overpaid by {amount} after a credit note: refund or keep as customer credit.", { amount: money(inv.overpaidAmount) })}</p>
          )}
        </dl>
        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            act("due", () => api("PUT", `/invoices/${inv.id}/due-date`, { dueDate: dueDate || null }));
          }}
        >
          <Field label={t("Payment due date")} hint={t("Internal payment terms; can be changed at any time.")} className="min-w-44 flex-1">
            <Input type="date" value={dueDate} disabled={!canWrite} onChange={(e) => setDueDate(e.target.value)} />
          </Field>
          {canWrite && (
            <Button type="submit" variant="secondary" loading={busy === "due"} disabled={(inv.dueDate ?? "") === dueDate}>{t("Save")}</Button>
          )}
        </form>
      </div>

      {inv.payments.length > 0 && (
        <ul className="divide-y divide-line border-t border-line">
          {inv.payments.map((p) => (
            <li key={p.id} className="flex items-center gap-4 px-5 py-3 text-sm">
              <Banknote className="size-4 shrink-0 text-ok" aria-hidden />
              <div className="min-w-0 flex-1">
                <p className="font-medium">{money(p.amount)} <span className="font-normal text-muted">· {t(paymentMethodLabel[p.method])} · {date(p.paidOn)}</span></p>
                <p className="truncate text-xs text-muted">{[p.reference, p.note, p.createdBy].filter(Boolean).join(" · ")}</p>
              </div>
              {canWrite && (
                <span className="inline-flex gap-1">
                  <Button variant="ghost" onClick={() => setEditing(fromPayment(p))} aria-label={t("Edit payment")}><Pencil className="size-4" aria-hidden /></Button>
                  <Button
                    variant="ghost"
                    loading={busy === `del${p.id}`}
                    onClick={() => window.confirm(t("Delete the payment of {amount}?", { amount: money(p.amount) })) && act(`del${p.id}`, () => api("DELETE", `/payments/${p.id}`))}
                    aria-label={t("Delete payment")}
                  >
                    <Trash2 className="size-4" aria-hidden />
                  </Button>
                </span>
              )}
            </li>
          ))}
        </ul>
      )}

      {editing ? (
        <PaymentForm key={editing.id ?? "new"} inv={inv} initial={editing} onDone={(saved) => { setEditing(null); if (saved) onChange(); }} />
      ) : (
        canWrite && due > 0 && (
          <div className="flex flex-wrap items-end gap-3 border-t border-line p-5">
            <Button
              variant="success"
              loading={busy === "full"}
              onClick={() => act("full", () => pay(due, "Full paid"))}
            >
              <CheckCircle2 className="size-4" aria-hidden /> {t("Full paid")}
            </Button>
            <span className="pb-2 text-sm text-muted">{t("or")}</span>
            <Field label={t("Amount received (SAR)")} hint={t("Up to {amount}", { amount: money(due) })} error={Number(amount) > due ? t("More than the amount due") : undefined} className="w-52">
              <Input type="number" min="0.01" step="0.01" max={due} value={amount} onChange={(e) => setAmount(e.target.value)} />
            </Field>
            <Button
              variant="secondary"
              loading={busy === "part"}
              disabled={!(Number(amount) > 0) || Number(amount) > due}
              onClick={() => act("part", async () => {
                await pay(Number(amount), Number(amount) >= due ? "Full paid" : "Partial payment");
                setAmount("");
              })}
            >
              <Banknote className="size-4" aria-hidden /> {t("Paid now")}
            </Button>
            <Button
              variant="ghost"
              onClick={() => setEditing({ amount: due.toFixed(2), paidOn: todayKsa(), method: "CASH", reference: "", note: "" })}
            >
              <Plus className="size-4" aria-hidden /> {t("More details")}
            </Button>
          </div>
        )
      )}
    </Card>
  );
}
