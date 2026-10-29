"use client";

import { useState, type FormEvent } from "react";
import { Banknote, CircleDollarSign, Hourglass, Pencil, PiggyBank, Plus, Receipt, Trash2, Users, Wallet } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import type { AccountExpense, AccountIncome, AccountMonth, AccountYear, ExpenseCategory, PartnerRow } from "@/lib/types";
import { date, money } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Empty, Field, Input, Select } from "@/components/ui";
import { MonthlyChart } from "@/components/worker-charts";
import { useT } from "@/lib/i18n";

export const MONTH_NAMES = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];

export const categoryLabel: Record<ExpenseCategory | "WORKERS", string> = {
  MATERIALS: "Materials", VEHICLE: "Vehicle & fuel", FOOD: "Food", GOVERNMENT: "Government & fees", SALARY: "Salaries",
  RENT_UTILITIES: "Rent & utilities", OFFICE: "Office", OTHER: "Other", WORKERS: "Worker payments",
};
const categories = Object.keys(categoryLabel).filter((c) => c !== "WORKERS") as ExpenseCategory[];

const small = "inline-flex items-center gap-1 rounded-md border border-line bg-white px-2 py-1 text-xs font-medium hover:bg-canvas disabled:opacity-40";
const num = (v: string | null | undefined) => (v == null || v === "" ? 0 : Number(v));

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

// --- income ---------------------------------------------------------------------------------------

function IncomeForm({ initial, defaultDate, onDone, onCancel }: { initial?: AccountIncome; defaultDate: string; onDone: () => void; onCancel: () => void }) {
  const t = useT();
  const [f, setF] = useState({
    date: initial?.date ?? defaultDate, customerName: initial?.customerName ?? "", mobile: initial?.mobile ?? "",
    details: initial?.details ?? "", amount: initial?.amount ?? "", paid: initial?.paid ?? "",
  });
  const { busy, error, run } = useAction(onDone);
  async function submit(e: FormEvent) {
    e.preventDefault();
    await run(() => initial ? api("PUT", `/accounts/income/${initial.id}`, f) : api("POST", "/accounts/income", f));
  }
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  return (
    <form onSubmit={submit} className="grid gap-3 border-b border-line bg-canvas/60 p-4 sm:grid-cols-2 lg:grid-cols-6">
      <Field label={t("Date")}><Input type="date" required value={f.date} onChange={set("date")} /></Field>
      <Field label={t("Customer name")} className="lg:col-span-2"><Input value={f.customerName} onChange={set("customerName")} dir="auto" /></Field>
      <Field label={t("Mobile")}><Input value={f.mobile} onChange={set("mobile")} inputMode="tel" dir="ltr" /></Field>
      <Field label={t("Total amount")}><Input type="number" min="0" step="0.01" required value={f.amount} onChange={set("amount")} className="text-end" /></Field>
      <Field label={t("Paid")}><Input type="number" min="0" step="0.01" value={f.paid} onChange={set("paid")} className="text-end" /></Field>
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
      <td className="px-4 py-2.5 text-muted" dir="auto">{e.details}</td>
      <td className="tabular whitespace-nowrap px-4 py-2.5 text-end">{money(e.amount)}</td>
      <td className="tabular whitespace-nowrap px-4 py-2.5 text-end text-ok">{money(e.paid)}</td>
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
  const [f, setF] = useState({
    date: initial?.date ?? defaultDate, description: initial?.description ?? "",
    category: (initial?.category === "WORKERS" ? "OTHER" : initial?.category) ?? "", amount: initial?.amount ?? "",
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
      <Field label={t("Date")}><Input type="date" required value={f.date} onChange={set("date")} /></Field>
      <Field label={t("Expense details / what was bought")} className="lg:col-span-3"><Input required value={f.description} onChange={set("description")} dir="auto" /></Field>
      <Field label={t("Category")}>
        <Select value={f.category} onChange={set("category")}>
          <option value="">{t("Guess from the details")}</option>
          {categories.map((c) => <option key={c} value={c}>{t(categoryLabel[c])}</option>)}
        </Select>
      </Field>
      <Field label={t("Amount")}><Input type="number" min="0" step="0.01" required value={f.amount} onChange={set("amount")} className="text-end" /></Field>
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
      <td className="px-4 py-2.5" dir="auto">{auto ? t("Worker payments (from Workers)") : e.description}</td>
      <td className="px-4 py-2.5"><span className="whitespace-nowrap rounded-full bg-canvas px-2 py-0.5 text-[11px] font-medium text-muted">{t(categoryLabel[e.category])}</span></td>
      <td className="tabular whitespace-nowrap px-4 py-2.5 text-end">{money(e.amount)}</td>
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
export function MonthView({ data, onChanged }: { data: AccountMonth; onChanged: () => void }) {
  const t = useT();
  const [editing, setEditing] = useState<string | null>(null);
  const first = `${data.year}-${String(data.month).padStart(2, "0")}-01`;
  const today = new Date().toLocaleDateString("en-CA");
  const defaultDate = today.startsWith(first.slice(0, 7)) ? today : first;
  const done = () => { setEditing(null); onChanged(); };
  const tt = data.totals;

  return (
    <>
      <Tiles tiles={[
        { label: t("Total bill / work"), value: money(tt.billed), icon: CircleDollarSign, hint: t("{n} jobs", { n: data.income.length }) },
        { label: t("Received"), value: money(tt.received), icon: Banknote, tone: "text-ok", hint: t("Money in from customers") },
        { label: t("Customer due"), value: money(tt.due), icon: Hourglass, tone: num(tt.due) > 0 ? "text-bad" : "", hint: t("Still to collect") },
        { label: t("Expenses"), value: money(tt.expense), icon: Receipt, hint: t("Including worker payments") },
        { label: t("Cash after expenses"), value: money(tt.cashAfterExpense), icon: Wallet, tone: num(tt.cashAfterExpense) < 0 ? "text-bad" : "text-ok", hint: t("Received minus expenses") },
      ]} />

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

function PartnerCard({ p, year, onChanged }: { p: PartnerRow; year: number; onChanged: () => void }) {
  const t = useT();
  const [ent, setEnt] = useState(p.entitlement ?? "");
  const [adding, setAdding] = useState(false);
  const [w, setW] = useState({ date: new Date().toLocaleDateString("en-CA"), amount: "", reference: "", approvedBy: "", notes: "" });
  const [confirm, setConfirm] = useState<number | null>(null);
  const { busy, error, run } = useAction(onChanged);

  async function saveEnt() {
    const unchanged = ent === "" ? p.entitlement == null : p.entitlement != null && Number(ent) === Number(p.entitlement);
    if (unchanged) return;
    await run(() => api("PUT", `/accounts/partners/${p.id}/entitlement/${year}`, { amount: ent === "" ? null : ent }));
  }

  async function add(e: FormEvent) {
    e.preventDefault();
    if (await run(() => api("POST", "/accounts/withdrawals", { ...w, partnerId: p.id }))) {
      setAdding(false);
      setW({ ...w, amount: "", reference: "", notes: "" });
    }
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
          {p.withdrawals.map((x) => (
            <li key={x.id} className="flex items-center justify-between gap-3 px-4 py-2">
              <span className="text-muted">{date(x.date)}{x.reference && ` · ${x.reference}`}{x.notes && ` · ${x.notes}`}</span>
              <span className="flex items-center gap-2">
                <span className="tabular font-medium">{money(x.amount)}</span>
                {confirm === x.id
                  ? <button className={`${small} border-bad/40 text-bad`} disabled={busy} onClick={() => run(() => api("DELETE", `/accounts/withdrawals/${x.id}`))}>{t("Confirm delete")}</button>
                  : <button className={small} onClick={() => setConfirm(x.id)} aria-label={t("Delete")}><Trash2 className="size-3.5" aria-hidden /></button>}
              </span>
            </li>
          ))}
        </ul>
      )}
      {adding ? (
        <form onSubmit={add} className="grid gap-3 border-t border-line bg-canvas/60 p-4 sm:grid-cols-4">
          <Field label={t("Date")}><Input type="date" required value={w.date} onChange={(e) => setW({ ...w, date: e.target.value })} /></Field>
          <Field label={t("Amount")}><Input type="number" min="0" step="0.01" required className="text-end" value={w.amount} onChange={(e) => setW({ ...w, amount: e.target.value })} /></Field>
          <Field label={t("Reference")}><Input value={w.reference} onChange={(e) => setW({ ...w, reference: e.target.value })} /></Field>
          <Field label={t("Approved by")}><Input value={w.approvedBy} onChange={(e) => setW({ ...w, approvedBy: e.target.value })} /></Field>
          <div className="flex gap-2 sm:col-span-4">
            <Button type="submit" loading={busy}>{t("Add withdrawal")}</Button>
            <Button type="button" variant="secondary" onClick={() => setAdding(false)}>{t("Cancel")}</Button>
          </div>
        </form>
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
