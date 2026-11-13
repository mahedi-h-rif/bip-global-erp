"use client";

import { useState, type FormEvent } from "react";
import useSWR from "swr";
import { CalendarDays, HandCoins, Pencil, Plane, Plus, Trash2, Users } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { Employee, Holiday, LeaveEntry, SalaryAdvance } from "@/lib/types";
import { date, money, todayKsa } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Empty, Field, Input, PageHeader, Select, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";
import { CountrySelect, SuggestInput } from "@/components/suggest";

type Tab = "employees" | "leave" | "holidays" | "advances";
const errText = (e: unknown, f: string) => (e instanceof ApiError ? (e.fields ? Object.values(e.fields).join(", ") : e.message) : f);

function EmployeeForm({ initial, onDone }: { initial: Employee; onDone: () => void }) {
  const t = useT();
  const [e, setE] = useState<Employee>(initial);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const f = (k: keyof Employee) => (ev: { target: { value: string } }) => setE({ ...e, [k]: ev.target.value });
  async function submit(ev: FormEvent) {
    ev.preventDefault();
    setBusy(true);
    setError(null);
    const num = (v: unknown) => (v === "" || v == null ? 0 : Number(v));
    const body = { ...e, basic: num(e.basic), housing: num(e.housing), transport: num(e.transport), otherAllowance: num(e.otherAllowance),
      gosiPct: e.gosiPct === "" || e.gosiPct == null ? null : Number(e.gosiPct), annualLeaveDays: num(e.annualLeaveDays ?? 21),
      nationality: (e.nationality || "SA").toUpperCase(), idExpiry: e.idExpiry || null, passportExpiry: e.passportExpiry || null, endDate: e.endDate || null };
    try {
      if (e.id) await api("PUT", `/hr/employees/${e.id}`, body); else await api("POST", "/hr/employees", body);
      onDone();
    } catch (err) { setError(errText(err, t("Could not reach the server"))); setBusy(false); }
  }
  return (
    <Card className="mb-6">
      <CardHeader title={e.id ? t("Edit employee") : t("New employee")} />
      <form onSubmit={submit} className="space-y-4 p-5">
        {error && <Alert tone="bad" title={t("Not saved")}>{error}</Alert>}
        <div className="grid gap-4 md:grid-cols-4">
          <Field label={t("Code")} optional><Input dir="ltr" value={e.code ?? ""} onChange={f("code")} placeholder="E0001" /></Field>
          <Field label={t("Name")}><SuggestInput field="person" required dir="auto" value={e.name} onChange={f("name")} /></Field>
          <Field label={t("Arabic name")} optional><Input dir="rtl" value={e.nameAr ?? ""} onChange={f("nameAr")} /></Field>
          <Field label={t("Nationality")}><CountrySelect value={e.nationality ?? "SA"} onChange={(v) => f("nationality")({ target: { value: v } })} /></Field>
          <Field label={t("Job title")} optional><Input dir="auto" value={e.jobTitle ?? ""} onChange={f("jobTitle")} /></Field>
          <Field label={t("Department")} optional><Input dir="auto" value={e.department ?? ""} onChange={f("department")} /></Field>
          <Field label={t("Join date")}><Input required type="date" value={e.joinDate} onChange={f("joinDate")} /></Field>
          <Field label={t("End date")} optional hint={t("Set when the employee leaves")}><Input type="date" value={e.endDate ?? ""} onChange={f("endDate")} /></Field>
          <Field label={t("Mobile")} optional><Input dir="ltr" value={e.phone ?? ""} onChange={f("phone")} /></Field>
          <Field label={t("Email")} optional><Input type="email" dir="ltr" value={e.email ?? ""} onChange={f("email")} /></Field>
          <Field label={t("Bank")} optional><Input value={e.bankName ?? ""} onChange={f("bankName")} /></Field>
          <Field label={t("IBAN")} optional><Input dir="ltr" value={e.iban ?? ""} onChange={f("iban")} /></Field>
        </div>
        <div className="grid gap-4 md:grid-cols-6">
          <Field label={t("Basic (SAR)")}><Input required type="number" min="0" step="0.01" value={e.basic} onChange={f("basic")} /></Field>
          <Field label={t("Housing")}><Input type="number" min="0" step="0.01" value={e.housing ?? ""} onChange={f("housing")} /></Field>
          <Field label={t("Transport")}><Input type="number" min="0" step="0.01" value={e.transport ?? ""} onChange={f("transport")} /></Field>
          <Field label={t("Other allowances")}><Input type="number" min="0" step="0.01" value={e.otherAllowance ?? ""} onChange={f("otherAllowance")} /></Field>
          <Field label={t("GOSI % (employee)")} optional hint={t("Default 9.75% Saudi, 0% others")}><Input type="number" min="0" max="100" step="0.01" value={e.gosiPct ?? ""} onChange={f("gosiPct")} /></Field>
          <Field label={t("Annual leave days")}><Input type="number" min="0" step="0.5" value={e.annualLeaveDays ?? 21} onChange={f("annualLeaveDays")} /></Field>
        </div>
        <div className="flex gap-2"><Button type="submit" loading={busy}>{t("Save employee")}</Button><Button type="button" variant="ghost" onClick={onDone}>{t("Cancel")}</Button></div>
      </form>
    </Card>
  );
}

function EmployeesTab() {
  const t = useT();
  const [all, setAll] = useState(false);
  const { data, error, mutate } = useSWR<Employee[]>(`/hr/employees?all=${all}`, fetcher, { keepPreviousData: true });
  const [editing, setEditing] = useState<Employee | null>(null);
  return (
    <>
      {editing && <EmployeeForm key={editing.id ?? "new"} initial={editing} onDone={() => { setEditing(null); mutate(); }} />}
      <Card>
        <div className="flex items-center justify-between border-b border-line p-4">
          <label className="inline-flex items-center gap-2 text-sm text-muted"><input type="checkbox" className="size-4 accent-[var(--color-brand)]" checked={all} onChange={(e) => setAll(e.target.checked)} />{t("Show former employees")}</label>
          {!editing && <Button onClick={() => setEditing({ name: "", joinDate: todayKsa(), basic: "", nationality: "SA", annualLeaveDays: 21 })}><Plus className="size-4" aria-hidden /> {t("New employee")}</Button>}
        </div>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("No employees yet")} />}
        {data && data.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead><tr className="border-b border-line text-xs text-muted">
                <th className="px-4 py-2.5 text-start font-medium">{t("Employee")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Job")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Gross salary")}</th>
                <th className="px-4 py-2.5 text-end font-medium">{t("Leave balance")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Advance due")}</th><th className="w-12" />
              </tr></thead>
              <tbody className="divide-y divide-line">
                {data.map((e) => (
                  <tr key={e.id} className={e.status === "TERMINATED" ? "text-muted" : ""}>
                    <td className="px-4 py-3"><span className="font-medium" dir="auto">{e.name}</span> <span className="text-xs text-muted">{e.code} · {e.nationality}</span></td>
                    <td className="px-4 py-3">{[e.jobTitle, e.department].filter(Boolean).join(" · ") || "—"}</td>
                    <td className="tabular px-4 py-3 text-end">{money(e.grossSalary)}</td>
                    <td className="tabular px-4 py-3 text-end">{Number(e.leaveBalance ?? 0)}</td>
                    <td className="tabular px-4 py-3 text-end">{Number(e.advanceOutstanding) > 0 ? money(e.advanceOutstanding) : "—"}</td>
                    <td className="px-2 py-2"><Button variant="ghost" onClick={() => setEditing(e)} aria-label={t("Edit {name}", { name: e.name })}><Pencil className="size-4" aria-hidden /></Button></td>
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

function LeaveTab({ employees }: { employees: Employee[] }) {
  const t = useT();
  const { data, mutate } = useSWR<LeaveEntry[]>("/hr/leaves", fetcher);
  const [l, setL] = useState<LeaveEntry>({ employeeId: employees[0]?.id ?? 0, type: "ANNUAL", startDate: todayKsa(), endDate: todayKsa() });
  const [error, setError] = useState<string | null>(null);
  async function add(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try { await api("POST", "/hr/leaves", { ...l, employeeId: Number(l.employeeId) }); mutate(); }
    catch (err) { setError(errText(err, t("Could not reach the server"))); }
  }
  return (
    <>
      <Card className="mb-4">
        <CardHeader title={t("Record leave")} subtitle={t("Days are working days: Friday, Saturday and company holidays are not counted. Unpaid leave is deducted in payroll.")} />
        <form onSubmit={add} className="flex flex-wrap items-end gap-3 p-5">
          {error && <div className="w-full"><Alert tone="bad">{error}</Alert></div>}
          <Field label={t("Employee")}><Select required value={l.employeeId} onChange={(e) => setL({ ...l, employeeId: Number(e.target.value) })}>{employees.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}</Select></Field>
          <Field label={t("Type")}><Select value={l.type} onChange={(e) => setL({ ...l, type: e.target.value as LeaveEntry["type"] })}>
            <option value="ANNUAL">{t("Annual")}</option><option value="SICK">{t("Sick")}</option><option value="UNPAID">{t("Unpaid")}</option><option value="OTHER">{t("Other")}</option>
          </Select></Field>
          <Field label={t("From")}><Input type="date" value={l.startDate} onChange={(e) => setL({ ...l, startDate: e.target.value })} /></Field>
          <Field label={t("To")}><Input type="date" value={l.endDate} onChange={(e) => setL({ ...l, endDate: e.target.value })} /></Field>
          <Field label={t("Notes")} optional><Input value={l.notes ?? ""} onChange={(e) => setL({ ...l, notes: e.target.value })} /></Field>
          <Button type="submit"><Plane className="size-4" aria-hidden /> {t("Record")}</Button>
        </form>
      </Card>
      <Card>
        {!data ? <Spinner /> : data.length === 0 ? <Empty title={t("No leave recorded")} /> : (
          <table className="w-full text-sm">
            <thead><tr className="border-b border-line text-xs text-muted"><th className="px-4 py-2.5 text-start font-medium">{t("Employee")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Type")}</th><th className="px-4 py-2.5 text-start font-medium">{t("From → To")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Days")}</th><th className="w-12" /></tr></thead>
            <tbody className="divide-y divide-line">
              {data.map((x) => (
                <tr key={x.id}><td className="px-4 py-3" dir="auto">{x.employeeName}</td><td className="px-4 py-3">{t(x.type.charAt(0) + x.type.slice(1).toLowerCase())}</td>
                  <td className="tabular px-4 py-3">{date(x.startDate)} → {date(x.endDate)}</td><td className="tabular px-4 py-3 text-end">{Number(x.days)}</td>
                  <td className="px-2"><Button variant="ghost" onClick={async () => { await api("DELETE", `/hr/leaves/${x.id}`); mutate(); }} aria-label={t("Delete")}><Trash2 className="size-4" aria-hidden /></Button></td></tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </>
  );
}

function HolidaysTab() {
  const t = useT();
  const [year, setYear] = useState(new Date().getFullYear());
  const { data, mutate } = useSWR<Holiday[]>(`/hr/holidays?year=${year}`, fetcher);
  const [h, setH] = useState<Holiday>({ date: todayKsa(), name: "" });
  return (
    <Card>
      <form className="flex flex-wrap items-end gap-3 border-b border-line p-5" onSubmit={async (e) => { e.preventDefault(); await api("POST", "/hr/holidays", h); setH({ date: h.date, name: "" }); mutate(); }}>
        <Field label={t("Year")}><Input type="number" className="w-24" value={year} onChange={(e) => setYear(Number(e.target.value))} /></Field>
        <Field label={t("Date")}><Input type="date" value={h.date} onChange={(e) => setH({ ...h, date: e.target.value })} /></Field>
        <Field label={t("Holiday")}><Input required value={h.name} onChange={(e) => setH({ ...h, name: e.target.value })} placeholder={t("e.g. Eid al-Fitr")} /></Field>
        <Button type="submit"><Plus className="size-4" aria-hidden /> {t("Add holiday")}</Button>
      </form>
      {!data ? <Spinner /> : data.length === 0 ? <Empty title={t("No holidays for {y}", { y: String(year) })} /> : (
        <ul className="divide-y divide-line">{data.map((x) => (
          <li key={x.id} className="flex items-center justify-between px-5 py-2 text-sm"><span><span className="tabular">{date(x.date)}</span> · {x.name}</span>
            <Button variant="ghost" onClick={async () => { await api("DELETE", `/hr/holidays/${x.id}`); mutate(); }} aria-label={t("Delete")}><Trash2 className="size-4" aria-hidden /></Button></li>
        ))}</ul>
      )}
    </Card>
  );
}

function AdvancesTab({ employees }: { employees: Employee[] }) {
  const t = useT();
  const { data, mutate } = useSWR<SalaryAdvance[]>("/hr/advances", fetcher);
  const [a, setA] = useState<SalaryAdvance>({ employeeId: employees[0]?.id ?? 0, date: todayKsa(), amount: "", installment: "" });
  const [error, setError] = useState<string | null>(null);
  return (
    <>
      <Card className="mb-4">
        <CardHeader title={t("Give a salary advance")} subtitle={t("The installment is deducted from each approved payroll until the advance is repaid.")} />
        <form className="flex flex-wrap items-end gap-3 p-5" onSubmit={async (e) => {
          e.preventDefault(); setError(null);
          try { await api("POST", "/hr/advances", { ...a, employeeId: Number(a.employeeId), amount: Number(a.amount), installment: Number(a.installment) }); mutate(); }
          catch (err) { setError(errText(err, t("Could not reach the server"))); }
        }}>
          {error && <div className="w-full"><Alert tone="bad">{error}</Alert></div>}
          <Field label={t("Employee")}><Select value={a.employeeId} onChange={(e) => setA({ ...a, employeeId: Number(e.target.value) })}>{employees.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}</Select></Field>
          <Field label={t("Date")}><Input type="date" value={a.date} onChange={(e) => setA({ ...a, date: e.target.value })} /></Field>
          <Field label={t("Amount (SAR)")}><Input required type="number" min="0.01" step="0.01" value={a.amount} onChange={(e) => setA({ ...a, amount: e.target.value })} /></Field>
          <Field label={t("Per month")}><Input required type="number" min="0.01" step="0.01" value={a.installment} onChange={(e) => setA({ ...a, installment: e.target.value })} /></Field>
          <Button type="submit"><HandCoins className="size-4" aria-hidden /> {t("Save advance")}</Button>
        </form>
      </Card>
      <Card>
        {!data ? <Spinner /> : data.length === 0 ? <Empty title={t("No salary advances")} /> : (
          <table className="w-full text-sm">
            <thead><tr className="border-b border-line text-xs text-muted"><th className="px-4 py-2.5 text-start font-medium">{t("Employee")}</th><th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Amount")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Per month")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Repaid")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Outstanding")}</th></tr></thead>
            <tbody className="divide-y divide-line">{data.map((x) => (
              <tr key={x.id}><td className="px-4 py-3" dir="auto">{x.employeeName}</td><td className="tabular px-4 py-3">{date(x.date)}</td><td className="tabular px-4 py-3 text-end">{money(x.amount)}</td>
                <td className="tabular px-4 py-3 text-end">{money(x.installment)}</td><td className="tabular px-4 py-3 text-end">{money(x.repaid)}</td>
                <td className={`tabular px-4 py-3 text-end ${Number(x.outstanding) > 0 ? "font-semibold" : "text-muted"}`}>{money(x.outstanding)}</td></tr>
            ))}</tbody>
          </table>
        )}
      </Card>
    </>
  );
}

export default function HrPage() {
  const t = useT();
  const [tab, setTab] = useState<Tab>("employees");
  const { data: employees } = useSWR<Employee[]>("/hr/employees", fetcher);
  const tabs: { id: Tab; label: string; icon: typeof Users }[] = [
    { id: "employees", label: "Employees", icon: Users }, { id: "leave", label: "Leave", icon: Plane },
    { id: "holidays", label: "Holidays", icon: CalendarDays }, { id: "advances", label: "Salary advances", icon: HandCoins },
  ];
  return (
    <>
      <PageHeader title={t("HR")} subtitle={t("Employees, ID expiry, leave, holidays and salary advances. Salaries are paid from Payroll.")} />
      <div className="mb-4 flex flex-wrap gap-2" role="tablist">
        {tabs.map((x) => (
          <button key={x.id} role="tab" aria-selected={tab === x.id} onClick={() => setTab(x.id)}
                  className={`inline-flex items-center gap-1.5 rounded-lg border px-3 py-2 text-sm font-medium ${tab === x.id ? "border-brand bg-brand text-white" : "border-line bg-white hover:bg-slate-50"}`}>
            <x.icon className="size-4" aria-hidden /> {t(x.label)}
          </button>
        ))}
      </div>
      {tab === "employees" ? <EmployeesTab /> : !employees ? <Spinner /> : employees.length === 0 ? <Alert tone="info">{t("Add an employee first.")}</Alert>
        : tab === "leave" ? <LeaveTab employees={employees} /> : tab === "holidays" ? <HolidaysTab /> : <AdvancesTab employees={employees} />}
    </>
  );
}
