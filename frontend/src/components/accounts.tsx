"use client";

import { useId, useRef, useState, type FormEvent } from "react";
import useSWR from "swr";
import { Banknote, CircleDollarSign, Coins, CreditCard, Hourglass, Pencil, PiggyBank, Plus, Receipt, ScanLine, Trash2, Users, Wallet } from "lucide-react";
import { api, ApiError, fetcher, uploadFile } from "@/lib/api";
import type { AccountExpense, AccountIncome, AccountMonth, AccountPeople, AccountYear, EditedBy, ExpenseCategory, InvoiceScan, PartnerRow, PartnerWithdrawal, PaymentMethod } from "@/lib/types";
import { date, dateTime, money, paymentMethodLabel } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Empty, Field, Input, Select } from "@/components/ui";
import { MonthlyChart } from "@/components/worker-charts";
import { DayPicker } from "@/components/worker-days";
import { useT } from "@/lib/i18n";

export const MONTH_NAMES = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];

export const categoryLabel: Record<ExpenseCategory | "WORKERS", string> = {
  MATERIALS: "Materials", VEHICLE: "Vehicle & fuel", FOOD: "Food", GOVERNMENT: "Government & fees", SALARY: "Salaries",
  RENT_UTILITIES: "Rent & utilities", OFFICE: "Office", OTHER: "Other", WORKERS: "Worker payments",
};
const categories = Object.keys(categoryLabel).filter((c) => c !== "WORKERS") as ExpenseCategory[];

const small = "inline-flex items-center gap-1 rounded-md border border-line bg-white px-2 py-1 text-xs font-medium hover:bg-canvas disabled:opacity-40";
const num = (v: string | null | undefined) => (v == null || v === "" ? 0 : Number(v));

/** Totals of a payment-method amount split into Cash and Card (other methods and null are ignored). */
function methodSplit<T extends { method: PaymentMethod | null }>(rows: T[], value: (r: T) => string) {
  let cash = 0, card = 0;
  for (const r of rows) {
    if (r.method === "CASH") cash += num(value(r));
    else if (r.method === "CARD") card += num(value(r));
  }
  return { cash, card };
}

/** Four tiles with the cash/card split of money in (received) and money out (expenses). */
function MethodBreakdown({ received, expense }: { received: { cash: number; card: number }; expense: { cash: number; card: number } }) {
  const t = useT();
  const items: { label: string; value: number; icon: typeof Wallet; tone?: string }[] = [
    { label: t("Income · cash"), value: received.cash, icon: Coins, tone: "text-ok" },
    { label: t("Income · card"), value: received.card, icon: CreditCard, tone: "text-ok" },
    { label: t("Expense · cash"), value: expense.cash, icon: Coins },
    { label: t("Expense · card"), value: expense.card, icon: CreditCard },
  ];
  return (
    <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
      {items.map((it) => (
        <div key={it.label} className="rounded-xl border border-line bg-white p-4 shadow-sm">
          <div className="flex items-center justify-between text-muted"><span className="text-xs font-medium">{it.label}</span><it.icon className="size-4" aria-hidden /></div>
          <p className={`tabular mt-2 text-lg font-semibold ${it.tone ?? ""}`}>{money(it.value)}</p>
        </div>
      ))}
    </div>
  );
}

type Tile = { label: string; value: string; icon: typeof Wallet; tone?: string; hint?: string };

export function Tiles({ tiles }: { tiles: Tile[] }) {
  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
      {tiles.map((s) => (
        <div key={s.label} className="rounded-xl border border-line bg-white p-4 shadow-sm">
          <div className="flex items-center justify-between text-muted"><span className="text-xs font-medium">{s.label}</span><s.icon className="size-4" aria-hidden /></div>
          <p className={`tabular mt-2 text-xl font-semibold ${s.tone ?? ""}`}>{s.value}</p>
          {s.hint && <p className="mt-1 text-xs text-muted">{s.hint}</p>}
        </div>
      ))}
    </div>
  );
}

/** Runs a change and reports a failure in place. */
function useAction(onDone: () => void) {
  const t = useT();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function run(fn: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await fn();
      onDone();
      return true;
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t("Failed"));
      return false;
    } finally {
      setBusy(false);
    }
  }
  return { busy, error, run };
}

/** An amount shown as text; click it to type a new one, saved on Enter or when the field is left (Escape cancels). */
function AmountCell({ value, onSave, className = "", label }: { value: string; onSave: (v: string) => Promise<unknown>; className?: string; label: string }) {
  const t = useT();
  const [editing, setEditing] = useState(false);
  const [v, setV] = useState(value);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function save() {
    if (v === "" || Number(v) === Number(value)) { setEditing(false); setV(value); return; }
    setBusy(true);
    setError(null);
    try {
      await onSave(v);
      setEditing(false);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t("Failed"));
    } finally {
      setBusy(false);
    }
  }
  if (!editing)
    return (
      <button type="button" title={t("Click to change the amount")} aria-label={`${label}: ${money(value)}. ${t("Edit")}`}
              className={`tabular rounded px-1 -mx-1 hover:bg-brand/10 hover:underline decoration-dotted ${className}`}
              onClick={() => { setV(value); setEditing(true); }}>
        {money(value)}
      </button>
    );
  return (
    <span className="inline-flex flex-col items-end">
      <Input type="number" min="0" step="0.01" inputMode="decimal" autoFocus disabled={busy} aria-label={label}
             className="tabular w-28 text-end" value={v} onChange={(e) => setV(e.target.value)} onBlur={save}
             onKeyDown={(e) => {
               if (e.key === "Enter") { e.preventDefault(); void save(); }
               if (e.key === "Escape") { setEditing(false); setV(value); }
             }} />
      {error && <span className="mt-0.5 text-[11px] text-bad">{error}</span>}
    </span>
  );
}

const incomeBody = (e: AccountIncome) => ({ date: e.date, customerName: e.customerName, mobile: e.mobile, details: e.details, receivedBy: e.receivedBy, amount: e.amount, paid: e.paid, method: e.method });
const expenseBody = (e: AccountExpense) => ({ date: e.date, description: e.description, category: e.category === "WORKERS" ? null : e.category, paidBy: e.paidBy, invoiceNumber: e.invoiceNumber, amount: e.amount, method: e.method });

const methods = Object.keys(paymentMethodLabel) as PaymentMethod[];

/** Cash, card, bank transfer, cheque or other. */
function MethodSelect({ value, onChange }: { value: string; onChange: (e: { target: { value: string } }) => void }) {
  const t = useT();
  return (
    <Select value={value} onChange={onChange}>
      {methods.map((m) => <option key={m} value={m}>{t(paymentMethodLabel[m])}</option>)}
    </Select>
  );
}

function MethodBadge({ method }: { method: PaymentMethod | null | undefined }) {
  const t = useT();
  if (!method) return null;
  return <span className="whitespace-nowrap rounded-full bg-canvas px-2 py-0.5 text-[11px] font-medium text-muted">{t(paymentMethodLabel[method])}</span>;
}

/** Who added an entry and, when it was changed, who edited it last and when. */
export function WhoLine({ e }: { e: Partial<EditedBy> }) {
  const t = useT();
  if (!e.createdBy && !e.updatedBy) return null;
  return (
    <p className="mt-0.5 text-[11px] text-muted">
      {e.createdBy && t("Added by {name}", { name: e.createdBy })}
      {e.createdBy && e.updatedBy && " · "}
      {e.updatedBy && t("edited by {name} on {date}", { name: e.updatedBy, date: dateTime(e.updatedAt) })}
    </p>
  );
}

/** A name field with a dropdown of names already used; typing a new one keeps it (it is saved on the entry). */
function NameInput({ value, onChange, options, placeholder }: {
  value: string; onChange: (e: { target: { value: string } }) => void; options?: string[]; placeholder?: string;
}) {
  const listId = useId();
  return (
    <>
      <Input value={value} onChange={onChange} dir="auto" placeholder={placeholder} list={listId} autoComplete="off" />
      <datalist id={listId}>{(options ?? []).map((o) => <option key={o} value={o} />)}</datalist>
    </>
  );
}

/** Optional: read an uploaded invoice (ZATCA QR first, then OCR) and hand the fields back to prefill the form. */
function InvoiceScanButton({ onScan }: { onScan: (s: InvoiceScan) => void }) {
  const t = useT();
  const ref = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);
  async function pick(file: File) {
    setBusy(true);
    setNote(null);
    try {
      const s = await uploadFile<InvoiceScan>("/accounts/scan", file);
      const found = s.source !== "NONE" && (s.amount != null || !!s.date || !!s.invoiceNumber || !!s.vendor);
      if (!found) { setNote({ tone: "bad", text: t("Could not read this invoice; please type the details.") }); return; }
      onScan(s);
      setNote({ tone: "ok", text: s.source === "QR" ? t("Read from the invoice QR code.") : t("Read from the invoice — please check the figures.") });
    } catch {
      setNote({ tone: "bad", text: t("Could not read this invoice; please type the details.") });
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <input ref={ref} type="file" accept="image/*,application/pdf" className="hidden"
             onChange={(e) => { const file = e.target.files?.[0]; e.target.value = ""; if (file) void pick(file); }} />
      <Button type="button" variant="secondary" loading={busy} onClick={() => ref.current?.click()}>
        <ScanLine className="size-4" aria-hidden />{t("Scan invoice")}
      </Button>
      {note && <span className={`text-xs ${note.tone === "ok" ? "text-ok" : "text-bad"}`}>{note.text}</span>}
    </>
  );
}

// --- income ---------------------------------------------------------------------------------------

function IncomeForm({ initial, defaultDate, onDone, onCancel }: { initial?: AccountIncome; defaultDate: string; onDone: () => void; onCancel: () => void }) {
  const t = useT();
  const { data: people } = useSWR<AccountPeople>("/accounts/people", fetcher);
  const [f, setF] = useState({
    date: initial?.date ?? defaultDate, customerName: initial?.customerName ?? "", mobile: initial?.mobile ?? "",
    details: initial?.details ?? "", receivedBy: initial?.receivedBy ?? "", amount: initial?.amount ?? "", paid: initial?.paid ?? "",
    method: initial?.method ?? "CASH",
  });
  const { busy, error, run } = useAction(onDone);
  async function submit(e: FormEvent) {
    e.preventDefault();
    await run(() => initial ? api("PUT", `/accounts/income/${initial.id}`, f) : api("POST", "/accounts/income", f));
  }
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  return (
    <form onSubmit={submit} className="grid gap-3 border-b border-line bg-canvas/60 p-4 sm:grid-cols-2 lg:grid-cols-6">
      <div className="flex flex-wrap items-center gap-2 sm:col-span-2 lg:col-span-6">
        <InvoiceScanButton onScan={(s) => setF((prev) => ({ ...prev,
          date: s.date ?? prev.date,
          amount: s.amount != null ? String(s.amount) : prev.amount,
        }))} />
        <span className="text-xs text-muted">{t("Optional — reads a ZATCA QR or the invoice text to fill the amount and date.")}</span>
      </div>
      <Field label={t("Date")}><Input type="date" required value={f.date} onChange={set("date")} /></Field>
      <Field label={t("Customer name")} className="lg:col-span-2"><Input value={f.customerName} onChange={set("customerName")} dir="auto" /></Field>
      <Field label={t("Mobile")}><Input value={f.mobile} onChange={set("mobile")} inputMode="tel" dir="ltr" /></Field>
      <Field label={t("Total amount")}><Input type="number" min="0" step="0.01" required value={f.amount} onChange={set("amount")} className="text-end" /></Field>
      <Field label={t("Paid")}><Input type="number" min="0" step="0.01" value={f.paid} onChange={set("paid")} className="text-end" /></Field>
      <Field label={t("Payment method")}><MethodSelect value={f.method} onChange={set("method")} /></Field>
      <Field label={t("Received by")} className="lg:col-span-2"><NameInput value={f.receivedBy} onChange={set("receivedBy")} options={people?.receivedBy} placeholder={t("Who received the amount")} /></Field>
      <Field label={t("Work / invoice details")} className="sm:col-span-2 lg:col-span-4"><Input value={f.details} onChange={set("details")} dir="auto" /></Field>
      <div className="flex items-end gap-2 lg:col-span-2">
        <Button type="submit" loading={busy}>{initial ? t("Save") : t("Add income")}</Button>
        <Button type="button" variant="secondary" onClick={onCancel}>{t("Cancel")}</Button>
      </div>
      {error && <div className="sm:col-span-2 lg:col-span-6"><Alert tone="bad">{error}</Alert></div>}
    </form>
  );
}

function IncomeRow({ e, onEdit, onChanged }: { e: AccountIncome; onEdit: () => void; onChanged: () => void }) {
  const t = useT();
  const [confirm, setConfirm] = useState(false);
  const { busy, error, run } = useAction(onChanged);
  return (
    <tr className="align-top hover:bg-canvas">
      <td className="whitespace-nowrap px-4 py-2.5 text-muted">{date(e.date)}</td>
      <td className="px-4 py-2.5">
        <p className="font-medium" dir="auto">{e.customerName ?? "—"}</p>
        {e.mobile && <p className="text-xs text-muted" dir="ltr">{e.mobile}</p>}
      </td>
      <td className="px-4 py-2.5">
        <p className="text-muted" dir="auto">{e.details}</p>
        {e.receivedBy && <p className="text-xs text-muted" dir="auto">{t("Received by {name}", { name: e.receivedBy })}</p>}
        <div className="mt-1 flex flex-wrap items-center gap-2"><MethodBadge method={e.method} /></div>
        <WhoLine e={e} />
      </td>
      <td className="whitespace-nowrap px-4 py-2.5 text-end">
        <AmountCell value={e.amount} label={t("Bill")} onSave={async (v) => { await api("PUT", `/accounts/income/${e.id}`, { ...incomeBody(e), amount: v }); onChanged(); }} />
      </td>
      <td className="whitespace-nowrap px-4 py-2.5 text-end">
        <AmountCell value={e.paid} label={t("Paid")} className="text-ok" onSave={async (v) => { await api("PUT", `/accounts/income/${e.id}`, { ...incomeBody(e), paid: v }); onChanged(); }} />
      </td>
      <td className={`tabular whitespace-nowrap px-4 py-2.5 text-end ${num(e.due) > 0 ? "font-semibold text-bad" : "text-muted"}`}>{num(e.due) > 0 ? money(e.due) : "—"}</td>
      <td className="px-4 py-2.5">
        <div className="flex justify-end gap-1.5">
          <button className={small} onClick={onEdit} aria-label={t("Edit")}><Pencil className="size-3.5" aria-hidden /></button>
          {confirm
            ? <button className={`${small} border-bad/40 text-bad`} disabled={busy} onClick={() => run(() => api("DELETE", `/accounts/income/${e.id}`))}>{t("Confirm delete")}</button>
            : <button className={small} onClick={() => setConfirm(true)} aria-label={t("Delete")}><Trash2 className="size-3.5" aria-hidden /></button>}
        </div>
        {error && <p className="mt-1 text-end text-xs text-bad">{error}</p>}
      </td>
    </tr>
  );
}

// --- expenses -------------------------------------------------------------------------------------

function ExpenseForm({ initial, defaultDate, onDone, onCancel }: { initial?: AccountExpense; defaultDate: string; onDone: () => void; onCancel: () => void }) {
  const t = useT();
  const { data: people } = useSWR<AccountPeople>("/accounts/people", fetcher);
  const [f, setF] = useState({
    date: initial?.date ?? defaultDate, description: initial?.description ?? "",
    category: (initial?.category === "WORKERS" ? "OTHER" : initial?.category) ?? "", amount: initial?.amount ?? "",
    method: initial?.method ?? "CASH", paidBy: initial?.paidBy ?? "", invoiceNumber: initial?.invoiceNumber ?? "",
  });
  const { busy, error, run } = useAction(onDone);
  async function submit(e: FormEvent) {
    e.preventDefault();
    const body = { ...f, category: f.category || null };
    await run(() => initial ? api("PUT", `/accounts/expenses/${initial.id}`, body) : api("POST", "/accounts/expenses", body));
  }
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  return (
    <form onSubmit={submit} className="grid gap-3 border-b border-line bg-canvas/60 p-4 sm:grid-cols-2 lg:grid-cols-6">
      <div className="flex flex-wrap items-center gap-2 sm:col-span-2 lg:col-span-6">
        <InvoiceScanButton onScan={(s) => setF((prev) => ({ ...prev,
          date: s.date ?? prev.date,
          amount: s.amount != null ? String(s.amount) : prev.amount,
          description: prev.description || (s.vendor ?? prev.description),
          invoiceNumber: prev.invoiceNumber || (s.invoiceNumber ?? prev.invoiceNumber),
        }))} />
        <span className="text-xs text-muted">{t("Optional — reads a ZATCA QR or the invoice text to fill the amount, date and invoice number.")}</span>
      </div>
      <Field label={t("Date")}><Input type="date" required value={f.date} onChange={set("date")} /></Field>
      <Field label={t("Expense details / what was bought")} className="lg:col-span-3"><Input required value={f.description} onChange={set("description")} dir="auto" /></Field>
      <Field label={t("Category")}>
        <Select value={f.category} onChange={set("category")}>
          <option value="">{t("Guess from the details")}</option>
          {categories.map((c) => <option key={c} value={c}>{t(categoryLabel[c])}</option>)}
        </Select>
      </Field>
      <Field label={t("Amount")}><Input type="number" min="0" step="0.01" required value={f.amount} onChange={set("amount")} className="text-end" /></Field>
      <Field label={t("Payment method")}><MethodSelect value={f.method} onChange={set("method")} /></Field>
      <Field label={t("Paid by")} className="lg:col-span-2"><NameInput value={f.paidBy} onChange={set("paidBy")} options={people?.paidBy} placeholder={t("Who made the payment")} /></Field>
      <Field label={t("Invoice / Fatoora no.")} className="lg:col-span-2"><Input value={f.invoiceNumber} onChange={set("invoiceNumber")} dir="ltr" /></Field>
      <div className="flex items-end gap-2 lg:col-span-6">
        <Button type="submit" loading={busy}>{initial ? t("Save") : t("Add expense")}</Button>
        <Button type="button" variant="secondary" onClick={onCancel}>{t("Cancel")}</Button>
      </div>
      {error && <div className="sm:col-span-2 lg:col-span-6"><Alert tone="bad">{error}</Alert></div>}
    </form>
  );
}

function ExpenseRow({ e, onEdit, onChanged }: { e: AccountExpense; onEdit: () => void; onChanged: () => void }) {
  const t = useT();
  const [confirm, setConfirm] = useState(false);
  const { busy, error, run } = useAction(onChanged);
  const auto = e.id == null;
  return (
    <tr className={`align-top ${auto ? "bg-canvas/60" : "hover:bg-canvas"}`}>
      <td className="whitespace-nowrap px-4 py-2.5 text-muted">{auto ? "—" : date(e.date)}</td>
      <td className="px-4 py-2.5">
        <p dir="auto">{auto ? t("Worker payments (from Workers)") : e.description}</p>
        {!auto && (e.paidBy || e.invoiceNumber) && (
          <p className="text-xs text-muted" dir="auto">
            {e.paidBy && t("Paid by {name}", { name: e.paidBy })}
            {e.paidBy && e.invoiceNumber && " · "}
            {e.invoiceNumber && t("Invoice {no}", { no: e.invoiceNumber })}
          </p>
        )}
        {!auto && <div className="mt-1"><MethodBadge method={e.method} /></div>}
        <WhoLine e={e} />
      </td>
      <td className="px-4 py-2.5"><span className="whitespace-nowrap rounded-full bg-canvas px-2 py-0.5 text-[11px] font-medium text-muted">{t(categoryLabel[e.category])}</span></td>
      <td className="whitespace-nowrap px-4 py-2.5 text-end">
        {auto ? <span className="tabular">{money(e.amount)}</span>
          : <AmountCell value={e.amount} label={t("Amount")} onSave={async (v) => { await api("PUT", `/accounts/expenses/${e.id}`, { ...expenseBody(e), amount: v }); onChanged(); }} />}
      </td>
      <td className="px-4 py-2.5">
        {auto ? <p className="text-end text-[11px] text-muted">{t("Automatic")}</p> : (
          <div className="flex justify-end gap-1.5">
            <button className={small} onClick={onEdit} aria-label={t("Edit")}><Pencil className="size-3.5" aria-hidden /></button>
            {confirm
              ? <button className={`${small} border-bad/40 text-bad`} disabled={busy} onClick={() => run(() => api("DELETE", `/accounts/expenses/${e.id}`))}>{t("Confirm delete")}</button>
              : <button className={small} onClick={() => setConfirm(true)} aria-label={t("Delete")}><Trash2 className="size-3.5" aria-hidden /></button>}
          </div>
        )}
        {error && <p className="mt-1 text-end text-xs text-bad">{error}</p>}
      </td>
    </tr>
  );
}

function CategoryBars({ rows }: { rows: { category: ExpenseCategory | "WORKERS"; amount: string }[] }) {
  const t = useT();
  if (rows.length === 0) return <p className="py-6 text-center text-sm text-muted">{t("No expenses")}</p>;
  const max = Math.max(...rows.map((r) => num(r.amount)));
  return (
    <ul className="space-y-2.5">
      {rows.map((r) => (
        <li key={r.category} className="text-sm">
          <div className="mb-1 flex justify-between gap-2"><span>{t(categoryLabel[r.category])}</span><span className="tabular font-semibold">{money(r.amount)}</span></div>
          <div className="h-2 overflow-hidden rounded-full bg-canvas" aria-hidden>
            <div className="h-full rounded-full bg-brand/70" style={{ width: `${Math.max(3, (num(r.amount) / max) * 100)}%` }} />
          </div>
        </li>
      ))}
    </ul>
  );
}

/** One month of the cash book: figures, income, expenses, customers and expense categories. */
export function MonthView({ data, year, onChanged }: { data: AccountMonth; year?: AccountYear; onChanged: () => void }) {
  const t = useT();
  const [editing, setEditing] = useState<string | null>(null);
  const first = `${data.year}-${String(data.month).padStart(2, "0")}-01`;
  const today = new Date().toLocaleDateString("en-CA");
  const defaultDate = today.startsWith(first.slice(0, 7)) ? today : first;
  const done = () => { setEditing(null); onChanged(); };
  const tt = data.totals;
  const receivedSplit = methodSplit(data.income, (e) => e.paid);
  const expenseSplit = methodSplit(data.expenses.filter((e) => e.id != null), (e) => e.amount);
  const partners = year?.partners ?? [];
  const monthWithdrawals = partners.flatMap((p) => p.withdrawals).filter((w) => w.date.startsWith(first.slice(0, 7)));

  return (
    <>
      <Tiles tiles={[
        { label: t("Total bill / work"), value: money(tt.billed), icon: CircleDollarSign, hint: t("{n} jobs", { n: data.income.length }) },
        { label: t("Received"), value: money(tt.received), icon: Banknote, tone: "text-ok" },
        { label: t("Customer due"), value: money(tt.due), icon: Hourglass, tone: num(tt.due) > 0 ? "text-bad" : "", hint: t("Still to collect") },
        { label: t("Expenses"), value: money(tt.expense), icon: Receipt },
        { label: t("Cash after expenses"), value: money(tt.cashAfterExpense), icon: Wallet, tone: num(tt.cashAfterExpense) < 0 ? "text-bad" : "text-ok", hint: t("Received minus expenses") },
      ]} />
      <MethodBreakdown received={receivedSplit} expense={expenseSplit} />

      <Card className="mt-6">
        <CardHeader title={t("Income")} subtitle={t("Jobs and bills for this month; due is what the customer still owes.")}
                    action={<Button variant="secondary" onClick={() => setEditing("income:new")}><Plus className="size-4" aria-hidden />{t("Add income")}</Button>} />
        {editing === "income:new" && <IncomeForm defaultDate={defaultDate} onDone={done} onCancel={() => setEditing(null)} />}
        {data.income.length === 0 && editing !== "income:new" ? <Empty title={t("No income this month")} /> : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[760px] text-sm">
              <thead>
                <tr className="border-b border-line text-xs text-muted">
                  <th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Customer")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Work / details")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Bill")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Paid")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Due")}</th>
                  <th className="px-4 py-2.5"><span className="sr-only">{t("Actions")}</span></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {data.income.map((e) => editing === `income:${e.id}` ? (
                  <tr key={e.id}><td colSpan={7} className="p-0"><IncomeForm initial={e} defaultDate={defaultDate} onDone={done} onCancel={() => setEditing(null)} /></td></tr>
                ) : <IncomeRow key={e.id} e={e} onEdit={() => setEditing(`income:${e.id}`)} onChanged={onChanged} />)}
              </tbody>
              <tfoot>
                <tr className="border-t-2 border-line bg-canvas/60 font-semibold">
                  <td className="px-4 py-2.5" colSpan={3}>{t("Month total")}</td>
                  <td className="tabular whitespace-nowrap px-4 py-2.5 text-end">{money(tt.billed)}</td>
                  <td className="tabular whitespace-nowrap px-4 py-2.5 text-end text-ok">{money(tt.received)}</td>
                  <td className={`tabular whitespace-nowrap px-4 py-2.5 text-end ${num(tt.due) > 0 ? "text-bad" : ""}`}>{money(tt.due)}</td>
                  <td />
                </tr>
              </tfoot>
            </table>
          </div>
        )}
      </Card>

      <div className="mt-6 grid gap-6 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader title={t("Expenses")} subtitle={t("What was spent this month; worker payments are added from Workers.")}
                      action={<Button variant="secondary" onClick={() => setEditing("expense:new")}><Plus className="size-4" aria-hidden />{t("Add expense")}</Button>} />
          {editing === "expense:new" && <ExpenseForm defaultDate={defaultDate} onDone={done} onCancel={() => setEditing(null)} />}
          {data.expenses.length === 0 && editing !== "expense:new" ? <Empty title={t("No expenses this month")} /> : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[600px] text-sm">
                <thead>
                  <tr className="border-b border-line text-xs text-muted">
                    <th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                    <th className="px-4 py-2.5 text-start font-medium">{t("Expense")}</th>
                    <th className="px-4 py-2.5 text-start font-medium">{t("Category")}</th>
                    <th className="px-4 py-2.5 text-end font-medium">{t("Amount")}</th>
                    <th className="px-4 py-2.5"><span className="sr-only">{t("Actions")}</span></th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {data.expenses.map((e, i) => e.id != null && editing === `expense:${e.id}` ? (
                    <tr key={e.id}><td colSpan={5} className="p-0"><ExpenseForm initial={e} defaultDate={defaultDate} onDone={done} onCancel={() => setEditing(null)} /></td></tr>
                  ) : <ExpenseRow key={e.id ?? `auto-${i}`} e={e} onEdit={() => setEditing(`expense:${e.id}`)} onChanged={onChanged} />)}
                </tbody>
                <tfoot>
                  <tr className="border-t-2 border-line bg-canvas/60 font-semibold">
                    <td className="px-4 py-2.5" colSpan={3}>{t("Month total")}</td>
                    <td className="tabular whitespace-nowrap px-4 py-2.5 text-end">{money(tt.expense)}</td>
                    <td />
                  </tr>
                </tfoot>
              </table>
            </div>
          )}
        </Card>
        <Card>
          <CardHeader title={t("Spending by category")} subtitle={t("Largest first")} />
          <div className="p-4"><CategoryBars rows={data.categories} /></div>
        </Card>
      </div>

      {year && (
        <div className="mt-6">
          <WithdrawalsCard partners={partners} withdrawals={monthWithdrawals} defaultDate={defaultDate} onChanged={onChanged}
                           subtitle={t("Money partners took out this month.")} />
        </div>
      )}

      {data.customers.length > 0 && (
        <Card className="mt-6">
          <CardHeader title={t("Customer-wise bill · paid · due")} />
          <div className="overflow-x-auto">
            <table className="w-full min-w-[640px] text-sm">
              <thead>
                <tr className="border-b border-line text-xs text-muted">
                  <th className="px-4 py-2.5 text-start font-medium">{t("Customer")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Mobile")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Jobs")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Bill")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Paid")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Due")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Status")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {data.customers.map((c) => (
                  <tr key={c.name}>
                    <td className="px-4 py-2.5 font-medium" dir="auto">{c.name}</td>
                    <td className="px-4 py-2.5 text-muted" dir="ltr">{c.mobile ?? "—"}</td>
                    <td className="tabular whitespace-nowrap px-4 py-2.5 text-end">{c.jobs}</td>
                    <td className="tabular whitespace-nowrap px-4 py-2.5 text-end">{money(c.billed)}</td>
                    <td className="tabular whitespace-nowrap px-4 py-2.5 text-end text-ok">{money(c.paid)}</td>
                    <td className={`tabular whitespace-nowrap px-4 py-2.5 text-end ${num(c.due) > 0 ? "font-semibold text-bad" : "text-muted"}`}>{money(c.due)}</td>
                    <td className="px-4 py-2.5 text-end">
                      <span className={`rounded-full px-2 py-0.5 text-[11px] font-semibold ${num(c.due) > 0 ? "bg-bad/10 text-bad" : "bg-ok/10 text-ok"}`}>
                        {num(c.due) > 0 ? t("Due") : t("Paid")}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </>
  );
}

// --- year and partners ----------------------------------------------------------------------------

/** Adds a withdrawal for a partner, or changes one (amount, date, reference, approval, note). */
function WithdrawalForm({ partnerId, partners, initial, defaultDate, onDone, onCancel }: {
  partnerId?: number; partners?: { id: number; name: string }[]; initial?: PartnerWithdrawal; defaultDate: string;
  onDone: () => void; onCancel: () => void;
}) {
  const t = useT();
  const pickPartner = partnerId == null && !initial && !!partners;
  const [pname, setPname] = useState("");
  const [pickError, setPickError] = useState<string | null>(null);
  const [w, setW] = useState({
    date: initial?.date ?? defaultDate, amount: initial?.amount ?? "", reference: initial?.reference ?? "",
    approvedBy: initial?.approvedBy ?? "", notes: initial?.notes ?? "",
  });
  const { busy, error, run } = useAction(onDone);
  async function submit(e: FormEvent) {
    e.preventDefault();
    let chosen = partnerId ?? initial?.partnerId ?? null;
    if (!chosen && pickPartner) {
      const name = pname.trim();
      if (!name) { setPickError(t("Choose a partner, or type a new name to add them")); return; }
      const match = partners?.find((p) => p.name.trim().toLowerCase() === name.toLowerCase());
      if (match) {
        chosen = match.id;
      } else {
        // A new name: add the partner first, then record the withdrawal against the new id.
        try {
          const created = await api("POST", "/accounts/partners", { name }) as { id: number };
          chosen = created.id;
        } catch (err) { setPickError(err instanceof ApiError ? err.message : t("Failed")); return; }
      }
    }
    if (!chosen) { setPickError(t("Choose a partner, or type a new name to add them")); return; }
    setPickError(null);
    const body = { ...w, partnerId: chosen };
    await run(() => initial ? api("PUT", `/accounts/withdrawals/${initial.id}`, body) : api("POST", "/accounts/withdrawals", body));
  }
  const set = (k: keyof typeof w) => (e: { target: { value: string } }) => setW({ ...w, [k]: e.target.value });
  return (
    <form onSubmit={submit} className="grid gap-3 border-t border-line bg-canvas/60 p-4 sm:grid-cols-4">
      {pickPartner && (
        <Field label={t("Partner")} className="sm:col-span-4" hint={t("Pick a partner, or type a new name to add them")}>
          <NameInput value={pname} onChange={(e) => setPname(e.target.value)} options={partners?.map((p) => p.name)} placeholder={t("Partner name")} />
        </Field>
      )}
      <Field label={t("Date")}><Input type="date" required value={w.date} onChange={set("date")} /></Field>
      <Field label={t("Amount")}><Input type="number" min="0" step="0.01" required autoFocus className="text-end" value={w.amount} onChange={set("amount")} /></Field>
      <Field label={t("Reference")}><Input value={w.reference} onChange={set("reference")} /></Field>
      <Field label={t("Approved by")}><Input value={w.approvedBy} onChange={set("approvedBy")} /></Field>
      <Field label={t("Notes")} className="sm:col-span-4"><Input value={w.notes} onChange={set("notes")} dir="auto" /></Field>
      <div className="flex gap-2 sm:col-span-4">
        <Button type="submit" loading={busy}>{initial ? t("Save") : t("Add withdrawal")}</Button>
        <Button type="button" variant="secondary" onClick={onCancel}>{t("Cancel")}</Button>
      </div>
      {(pickError || error) && <div className="sm:col-span-4"><Alert tone="bad">{pickError ?? error}</Alert></div>}
    </form>
  );
}

/** Lists the withdrawals of one period (a day or a month) with add / edit / delete, choosing a partner on add. */
function WithdrawalsCard({ partners, withdrawals, defaultDate, onChanged, subtitle }: {
  partners: { id: number; name: string }[];
  withdrawals: PartnerWithdrawal[];
  defaultDate: string;
  onChanged: () => void;
  subtitle: string;
}) {
  const t = useT();
  const [editing, setEditing] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<number | null>(null);
  const { busy, error, run } = useAction(onChanged);
  const partnerName = new Map(partners.map((p) => [p.id, p.name]));
  const done = () => { setEditing(null); onChanged(); };
  return (
    <Card>
      <CardHeader title={t("Partner withdrawals")} subtitle={subtitle}
                  action={
                    <Button variant="secondary" onClick={() => { setEditing("new"); setConfirm(null); }}>
                      <Plus className="size-4" aria-hidden />{t("Add withdrawal")}
                    </Button>
                  } />
      {editing === "new" && <WithdrawalForm partners={partners} defaultDate={defaultDate} onDone={done} onCancel={() => setEditing(null)} />}
      {withdrawals.length === 0 && editing !== "new" ? (
        <p className="py-6 text-center text-sm text-muted">{t("None in this period")}</p>
      ) : (
        <ul className="divide-y divide-line text-sm">
          {withdrawals.map((w) => editing === `w:${w.id}` ? (
            <li key={w.id}><WithdrawalForm partnerId={w.partnerId} initial={w} defaultDate={w.date} onDone={done} onCancel={() => setEditing(null)} /></li>
          ) : (
            <li key={w.id} className="flex items-start justify-between gap-3 px-4 py-2.5">
              <span>
                <span className="font-medium" dir="auto">{partnerName.get(w.partnerId) ?? "—"}</span>
                <span className="block text-xs text-muted">{date(w.date)}{(w.reference || w.notes) ? ` · ${[w.reference, w.notes].filter(Boolean).join(" · ")}` : ""}</span>
                <WhoLine e={w} />
              </span>
              <span className="flex items-center gap-2">
                <span className="tabular font-medium">{money(w.amount)}</span>
                <button className={small} onClick={() => setEditing(`w:${w.id}`)} aria-label={t("Edit")}><Pencil className="size-3.5" aria-hidden /></button>
                {confirm === w.id
                  ? <button className={`${small} border-bad/40 text-bad`} disabled={busy} onClick={() => run(() => api("DELETE", `/accounts/withdrawals/${w.id}`))}>{t("Confirm delete")}</button>
                  : <button className={small} onClick={() => setConfirm(w.id)} aria-label={t("Delete")}><Trash2 className="size-3.5" aria-hidden /></button>}
              </span>
            </li>
          ))}
        </ul>
      )}
      {error && <div className="px-4 pb-3"><Alert tone="bad">{error}</Alert></div>}
    </Card>
  );
}

function PartnerCard({ p, year, onChanged }: { p: PartnerRow; year: number; onChanged: () => void }) {
  const t = useT();
  const [ent, setEnt] = useState(p.entitlement ?? "");
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<number | null>(null);
  const [confirm, setConfirm] = useState<number | null>(null);
  const { busy, error, run } = useAction(onChanged);

  async function saveEnt() {
    const unchanged = ent === "" ? p.entitlement == null : p.entitlement != null && Number(ent) === Number(p.entitlement);
    if (unchanged) return;
    await run(() => api("PUT", `/accounts/partners/${p.id}/entitlement/${year}`, { amount: ent === "" ? null : ent }));
  }


  return (
    <div className="rounded-xl border border-line bg-white">
      <div className="flex flex-wrap items-start justify-between gap-3 border-b border-line p-4">
        <div>
          <p className="font-semibold" dir="auto">{p.name}</p>
          <p className="mt-1 text-xs text-muted">{t("Withdrawn in {year}", { year })}: <span className="tabular font-semibold text-ink">{money(p.withdrawn)}</span></p>
        </div>
        <div className="flex items-end gap-3">
          <Field label={t("Annual entitlement")}>
            <Input type="number" min="0" step="0.01" className="w-36 text-end" placeholder="—" value={ent} onChange={(e) => setEnt(e.target.value)} onBlur={saveEnt} />
          </Field>
          <div className="pb-2 text-end text-xs">
            <p className="text-muted">{t("Balance")}</p>
            <p className={`tabular text-sm font-semibold ${p.balance != null && num(p.balance) < 0 ? "text-bad" : ""}`}>{p.balance == null ? "—" : money(p.balance)}</p>
          </div>
        </div>
      </div>
      {p.withdrawals.length > 0 && (
        <ul className="divide-y divide-line text-sm">
          {p.withdrawals.map((x) => editing === x.id ? (
            <li key={x.id}><WithdrawalForm partnerId={p.id} initial={x} defaultDate={x.date}
                                           onDone={() => { setEditing(null); onChanged(); }} onCancel={() => setEditing(null)} /></li>
          ) : (
            <li key={x.id} className="flex items-center justify-between gap-3 px-4 py-2">
              <span className="text-muted">
                {date(x.date)}{x.reference && ` · ${x.reference}`}{x.notes && ` · ${x.notes}`}
                <WhoLine e={x} />
              </span>
              <span className="flex items-center gap-2">
                <AmountCell value={x.amount} label={t("Amount")} className="font-medium"
                            onSave={async (v) => { await api("PUT", `/accounts/withdrawals/${x.id}`, { partnerId: x.partnerId, date: x.date, reference: x.reference, approvedBy: x.approvedBy, notes: x.notes, amount: v }); onChanged(); }} />
                <button className={small} onClick={() => setEditing(x.id)} aria-label={t("Edit")}><Pencil className="size-3.5" aria-hidden /></button>
                {confirm === x.id
                  ? <button className={`${small} border-bad/40 text-bad`} disabled={busy} onClick={() => run(() => api("DELETE", `/accounts/withdrawals/${x.id}`))}>{t("Confirm delete")}</button>
                  : <button className={small} onClick={() => setConfirm(x.id)} aria-label={t("Delete")}><Trash2 className="size-3.5" aria-hidden /></button>}
              </span>
            </li>
          ))}
        </ul>
      )}
      {adding ? (
        <WithdrawalForm partnerId={p.id} defaultDate={new Date().toLocaleDateString("en-CA")}
                        onDone={() => { setAdding(false); onChanged(); }} onCancel={() => setAdding(false)} />
      ) : (
        <div className="border-t border-line p-3">
          <button className={small} onClick={() => setAdding(true)}><Plus className="size-3.5" aria-hidden />{t("Add withdrawal")}</button>
        </div>
      )}
      {error && <div className="p-3"><Alert tone="bad">{error}</Alert></div>}
    </div>
  );
}

/** The year: cash flow by month, partner drawings and what stays in the company. */
export function YearView({ data, onChanged, onOpenMonth }: { data: AccountYear; onChanged: () => void; onOpenMonth: (m: number) => void }) {
  const t = useT();
  const [name, setName] = useState("");
  const { busy, error, run } = useAction(onChanged);
  const tt = data.totals;
  const today = new Date().toLocaleDateString("en-CA");
  const withdrawalDate = today.startsWith(String(data.year)) ? today : `${data.year}-01-01`;
  const allWithdrawals = data.partners.flatMap((p) => p.withdrawals).slice().sort((a, b) => a.date.localeCompare(b.date));

  return (
    <>
      <Tiles tiles={[
        { label: t("Income received"), value: money(tt.received), icon: Banknote, tone: "text-ok", hint: t("Customer due {amount}", { amount: money(tt.due) }) },
        { label: t("Expenses"), value: money(tt.expense), icon: Receipt, hint: t("Including worker payments") },
        { label: t("Net profit"), value: money(tt.net), icon: CircleDollarSign, tone: num(tt.net) < 0 ? "text-bad" : "text-ok", hint: t("Income minus expenses") },
        { label: t("Partner drawings"), value: money(tt.drawings), icon: Users, hint: t("{n} partners", { n: data.partners.length }) },
        { label: t("Kept in company"), value: money(tt.retained), icon: PiggyBank, tone: num(tt.retained) < 0 ? "text-bad" : "", hint: t("Net profit minus drawings") },
      ]} />

      <div className="mt-6 grid gap-6 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader title={t("Income and expenses by month")} subtitle={t("Hover a month for its figures")} />
          <div className="p-4">
            <MonthlyChart positiveIsGood
                          months={data.months.map((m) => ({ work: m.received, paid: m.expense, due: m.net }))}
                          labels={{ a: t("Income received"), b: t("Expenses"), diff: t("Net"), title: t("Income and expenses by month") }} />
          </div>
        </Card>
        <Card>
          <CardHeader title={t("Spending by category")} subtitle={String(data.year)} />
          <div className="max-h-80 overflow-y-auto p-4"><CategoryBars rows={data.categories} /></div>
        </Card>
      </div>

      <Card className="mt-6">
        <CardHeader title={t("Month by month")} subtitle={t("Open a month to see and edit its entries.")} />
        <div className="overflow-x-auto">
          <table className="w-full min-w-[720px] text-sm">
            <thead>
              <tr className="border-b border-line text-xs text-muted">
                <th className="px-4 py-2.5 text-start font-medium">{t("Month")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Income received")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Expenses")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Net")}</th>
                {data.partners.map((p) => <th key={p.id} className="px-4 py-2.5 text-end font-medium" dir="auto">{p.name}</th>)}
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {data.months.map((m) => {
                const empty = num(m.received) === 0 && num(m.expense) === 0;
                return (
                  <tr key={m.month} className="hover:bg-canvas">
                    <td className="px-4 py-2.5"><button className="font-medium hover:underline" onClick={() => onOpenMonth(m.month)}>{t(MONTH_NAMES[m.month - 1])}</button></td>
                    <td className="tabular whitespace-nowrap px-4 py-2.5 text-end">{empty ? "—" : money(m.received)}</td>
                    <td className="tabular whitespace-nowrap px-4 py-2.5 text-end">{empty ? "—" : money(m.expense)}</td>
                    <td className={`tabular whitespace-nowrap px-4 py-2.5 text-end font-semibold ${num(m.net) < 0 ? "text-bad" : num(m.net) > 0 ? "text-ok" : "text-muted"}`}>{empty ? "—" : money(m.net)}</td>
                    {data.partners.map((p) => <td key={p.id} className="tabular whitespace-nowrap px-4 py-2.5 text-end">{num(m.withdrawals[p.id]) ? money(m.withdrawals[p.id]) : "—"}</td>)}
                  </tr>
                );
              })}
            </tbody>
            <tfoot>
              <tr className="border-t-2 border-line bg-canvas/60 font-semibold">
                <td className="px-4 py-2.5">{t("Year total")}</td>
                <td className="tabular whitespace-nowrap px-4 py-2.5 text-end">{money(tt.received)}</td>
                <td className="tabular whitespace-nowrap px-4 py-2.5 text-end">{money(tt.expense)}</td>
                <td className={`tabular whitespace-nowrap px-4 py-2.5 text-end ${num(tt.net) < 0 ? "text-bad" : "text-ok"}`}>{money(tt.net)}</td>
                {data.partners.map((p) => <td key={p.id} className="tabular whitespace-nowrap px-4 py-2.5 text-end">{money(p.withdrawn)}</td>)}
              </tr>
            </tfoot>
          </table>
        </div>
      </Card>

      <div className="mt-6">
        <WithdrawalsCard partners={data.partners} withdrawals={allWithdrawals} defaultDate={withdrawalDate} onChanged={onChanged}
                         subtitle={t("All partner withdrawals this year; add, edit or delete any of them.")} />
      </div>

      <Card className="mt-6">
        <CardHeader title={t("Partners' withdrawal account")} subtitle={t("Money each partner took out, against what they may draw this year.")} />
        <div className="grid gap-4 p-4 lg:grid-cols-2">
          {data.partners.map((p) => <PartnerCard key={`${p.id}-${data.year}`} p={p} year={data.year} onChanged={onChanged} />)}
          <form className="flex items-end gap-2 rounded-xl border border-dashed border-line p-4"
                onSubmit={async (e) => { e.preventDefault(); if (await run(() => api("POST", "/accounts/partners", { name }))) setName(""); }}>
            <Field label={t("New partner")} className="flex-1"><Input required value={name} onChange={(e) => setName(e.target.value)} dir="auto" /></Field>
            <Button type="submit" variant="secondary" loading={busy}><Plus className="size-4" aria-hidden />{t("Add partner")}</Button>
          </form>
        </div>
        {error && <div className="px-4 pb-4"><Alert tone="bad">{error}</Alert></div>}
      </Card>
    </>
  );
}

// --- day ------------------------------------------------------------------------------------------

/**
 * One day of the cash book: that day's income, expenses and partner withdrawals with day totals, entries added
 * for that date, and the month day by day so any date can be opened.
 */
export function DayView({ day, month, year, onDay, onChanged }: {
  day: string; month: AccountMonth; year?: AccountYear; onDay: (d: string) => void; onChanged: () => void;
}) {
  const t = useT();
  const [editing, setEditing] = useState<string | null>(null);
  const done = () => { setEditing(null); onChanged(); };

  const income = month.income.filter((e) => e.date === day);
  const expenses = month.expenses.filter((e) => e.id != null && e.date === day);
  const partners = year?.partners ?? [];
  const drawn = partners.flatMap((p) => p.withdrawals).filter((w) => w.date === day);

  const billed = income.reduce((s, e) => s + num(e.amount), 0);
  const received = income.reduce((s, e) => s + num(e.paid), 0);
  const due = income.reduce((s, e) => s + num(e.due), 0);
  const spent = expenses.reduce((s, e) => s + num(e.amount), 0);
  const taken = drawn.reduce((s, w) => s + num(w.amount), 0);
  const cashToday = received - spent - taken;
  const receivedSplit = methodSplit(income, (e) => e.paid);
  const expenseSplit = methodSplit(expenses, (e) => e.amount);

  // The month day by day, from the dated entries (the automatic worker-payments line has no day).
  type DayTotal = { received: number; billed: number; expense: number; drawn: number; n: number };
  const days = new Map<string, DayTotal>();
  const at = (d: string) => {
    let r = days.get(d);
    if (!r) { r = { received: 0, billed: 0, expense: 0, drawn: 0, n: 0 }; days.set(d, r); }
    return r;
  };
  for (const e of month.income) { const r = at(e.date); r.billed += num(e.amount); r.received += num(e.paid); r.n++; }
  for (const e of month.expenses) if (e.id != null && e.date) { const r = at(e.date); r.expense += num(e.amount); r.n++; }
  const monthKey = `${month.year}-${String(month.month).padStart(2, "0")}`;
  for (const w of (year?.partners ?? []).flatMap((p) => p.withdrawals))
    if (w.date.startsWith(monthKey)) { const r = at(w.date); r.drawn += num(w.amount); r.n++; }
  const dayRows = [...days.entries()].sort(([a], [b]) => a.localeCompare(b));

  return (
    <>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm font-semibold">{date(day)}</p>
        <DayPicker value={day} onChange={onDay} />
      </div>

      <Tiles tiles={[
        { label: t("Bill / work today"), value: money(billed), icon: CircleDollarSign, hint: t("{n} jobs", { n: income.length }) },
        { label: t("Received"), value: money(received), icon: Banknote, tone: "text-ok", hint: t("Customer due {amount}", { amount: money(due) }) },
        { label: t("Expenses"), value: money(spent), icon: Receipt, hint: t("{n} entries", { n: expenses.length }) },
        { label: t("Partner drawings"), value: money(taken), icon: Users, hint: t("{n} withdrawals", { n: drawn.length }) },
        { label: t("Cash for the day"), value: money(cashToday), icon: Wallet, tone: cashToday < 0 ? "text-bad" : "text-ok", hint: t("Received minus expenses and drawings") },
      ]} />
      <MethodBreakdown received={receivedSplit} expense={expenseSplit} />

      <Card className="mt-6">
        <CardHeader title={t("Income")} subtitle={t("Jobs and bills on this day. Click an amount to change it.")}
                    action={<Button variant="secondary" onClick={() => setEditing("income:new")}><Plus className="size-4" aria-hidden />{t("Add income")}</Button>} />
        {editing === "income:new" && <IncomeForm defaultDate={day} onDone={done} onCancel={() => setEditing(null)} />}
        {income.length === 0 && editing !== "income:new" ? <Empty title={t("No income on this day")} /> : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[760px] text-sm">
              <thead>
                <tr className="border-b border-line text-xs text-muted">
                  <th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Customer")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Work / details")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Bill")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Paid")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Due")}</th>
                  <th className="px-4 py-2.5"><span className="sr-only">{t("Actions")}</span></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {income.map((e) => editing === `income:${e.id}` ? (
                  <tr key={e.id}><td colSpan={7} className="p-0"><IncomeForm initial={e} defaultDate={day} onDone={done} onCancel={() => setEditing(null)} /></td></tr>
                ) : <IncomeRow key={e.id} e={e} onEdit={() => setEditing(`income:${e.id}`)} onChanged={onChanged} />)}
              </tbody>
              <tfoot>
                <tr className="border-t-2 border-line bg-canvas/60 font-semibold">
                  <td className="px-4 py-2.5" colSpan={3}>{t("Day total")}</td>
                  <td className="tabular whitespace-nowrap px-4 py-2.5 text-end">{money(billed)}</td>
                  <td className="tabular whitespace-nowrap px-4 py-2.5 text-end text-ok">{money(received)}</td>
                  <td className={`tabular whitespace-nowrap px-4 py-2.5 text-end ${due > 0 ? "text-bad" : ""}`}>{money(due)}</td>
                  <td />
                </tr>
              </tfoot>
            </table>
          </div>
        )}
      </Card>

      <div className="mt-6 grid gap-6 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader title={t("Expenses")} subtitle={t("What was spent on this day. Click an amount to change it.")}
                      action={<Button variant="secondary" onClick={() => setEditing("expense:new")}><Plus className="size-4" aria-hidden />{t("Add expense")}</Button>} />
          {editing === "expense:new" && <ExpenseForm defaultDate={day} onDone={done} onCancel={() => setEditing(null)} />}
          {expenses.length === 0 && editing !== "expense:new" ? <Empty title={t("No expenses on this day")} /> : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[600px] text-sm">
                <thead>
                  <tr className="border-b border-line text-xs text-muted">
                    <th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                    <th className="px-4 py-2.5 text-start font-medium">{t("Expense")}</th>
                    <th className="px-4 py-2.5 text-start font-medium">{t("Category")}</th>
                    <th className="px-4 py-2.5 text-end font-medium">{t("Amount")}</th>
                    <th className="px-4 py-2.5"><span className="sr-only">{t("Actions")}</span></th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {expenses.map((e) => editing === `expense:${e.id}` ? (
                    <tr key={e.id}><td colSpan={5} className="p-0"><ExpenseForm initial={e} defaultDate={day} onDone={done} onCancel={() => setEditing(null)} /></td></tr>
                  ) : <ExpenseRow key={e.id} e={e} onEdit={() => setEditing(`expense:${e.id}`)} onChanged={onChanged} />)}
                </tbody>
                <tfoot>
                  <tr className="border-t-2 border-line bg-canvas/60 font-semibold">
                    <td className="px-4 py-2.5" colSpan={3}>{t("Day total")}</td>
                    <td className="tabular whitespace-nowrap px-4 py-2.5 text-end">{money(spent)}</td>
                    <td />
                  </tr>
                </tfoot>
              </table>
            </div>
          )}
        </Card>
        {year
          ? <WithdrawalsCard partners={partners} withdrawals={drawn} defaultDate={day} onChanged={onChanged}
                             subtitle={t("Money partners took out on this day.")} />
          : (
            <Card>
              <CardHeader title={t("Partner withdrawals")} subtitle={t("Add them from the Year view.")} />
              <p className="py-6 text-center text-sm text-muted">{t("None on this day")}</p>
            </Card>
          )}
      </div>

      <Card className="mt-6">
        <CardHeader title={t("Day by day")}
                    subtitle={t("Every day of {month} with entries; open a date to see or change it.", { month: `${t(MONTH_NAMES[month.month - 1])} ${month.year}` })} />
        {dayRows.length === 0 ? <Empty title={t("No dated entries this month")} /> : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[680px] text-sm">
              <thead>
                <tr className="border-b border-line text-xs text-muted">
                  <th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Entries")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Bill")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Received")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Expenses")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Partner drawings")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Cash")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {dayRows.map(([d, r]) => {
                  const cash = r.received - r.expense - r.drawn;
                  return (
                    <tr key={d} className={`hover:bg-canvas ${d === day ? "bg-brand/5" : ""}`}>
                      <td className="whitespace-nowrap px-4 py-2.5"><button className="font-medium hover:underline" onClick={() => onDay(d)}>{date(d)}</button></td>
                      <td className="tabular px-4 py-2.5 text-end text-muted">{r.n}</td>
                      <td className="tabular whitespace-nowrap px-4 py-2.5 text-end">{money(r.billed)}</td>
                      <td className="tabular whitespace-nowrap px-4 py-2.5 text-end text-ok">{money(r.received)}</td>
                      <td className="tabular whitespace-nowrap px-4 py-2.5 text-end">{money(r.expense)}</td>
                      <td className="tabular whitespace-nowrap px-4 py-2.5 text-end">{r.drawn ? money(r.drawn) : "—"}</td>
                      <td className={`tabular whitespace-nowrap px-4 py-2.5 text-end font-semibold ${cash < 0 ? "text-bad" : "text-ok"}`}>{money(cash)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        <p className="border-t border-line px-4 py-2.5 text-xs text-muted">{t("Worker payments are counted in the month total, not on a day.")}</p>
      </Card>
    </>
  );
}
