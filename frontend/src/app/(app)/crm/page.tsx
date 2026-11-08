"use client";

import Link from "next/link";
import { useState } from "react";
import useSWR from "swr";
import { CalendarClock, Gauge, Handshake, Play, Plus, Trash2 } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { Customer } from "@/lib/types";
import { date, money, todayKsa } from "@/lib/format";
import { Alert, Button, ButtonLink, Card, CardHeader, Empty, Field, Input, PageHeader, Select, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

interface Lead { id?: number; title: string; company?: string | null; contactName?: string | null; phone?: string | null; email?: string | null; source?: string | null;
  stage?: string; expectedValue?: number | string | null; expectedClose?: string | null; owner?: string; notes?: string | null; lostReason?: string | null; customerId?: number | null; customerName?: string | null }
interface Credit { customerId: number; name: string; creditLimit: number | null; paymentTermsDays: number | null; outstanding: number; available: number | null }
interface RecLine { name: string; quantity: number | string; unitPrice: number | string; vatRate?: number }
interface Recurring { id?: number; customerId: number; customerName?: string; name: string; kind: string; lines: RecLine[]; intervalMonths: number; nextDate: string; endDate?: string | null; active?: boolean; lastInvoiceId?: number | null; amount?: number }

const STAGES = ["NEW", "QUALIFIED", "PROPOSAL", "WON", "LOST"];
type Tab = "pipeline" | "credit" | "recurring";
const errText = (e: unknown, f: string) => (e instanceof ApiError ? (e.fields ? Object.values(e.fields).join(", ") : e.message) : f);

function Pipeline() {
  const t = useT();
  const { data, mutate } = useSWR<Lead[]>("/crm/leads", fetcher);
  const [l, setL] = useState<Lead>({ title: "" });
  const [error, setError] = useState<string | null>(null);
  const save = async (x: Lead) => { setError(null); try { if (x.id) await api("PUT", `/crm/leads/${x.id}`, x); else await api("POST", "/crm/leads", x); mutate(); return true; } catch (e) { setError(errText(e, t("Could not reach the server"))); return false; } };
  const total = (s: string) => (data ?? []).filter((x) => x.stage === s).reduce((a, x) => a + Number(x.expectedValue ?? 0), 0);
  return (
    <>
      <Card className="mb-4"><form className="flex flex-wrap items-end gap-3 p-4" onSubmit={async (e) => { e.preventDefault(); if (await save({ ...l, expectedValue: l.expectedValue ? Number(l.expectedValue) : null })) setL({ title: "" }); }}>
        <Field label={t("Opportunity")}><Input required className="w-64" value={l.title} onChange={(e) => setL({ ...l, title: e.target.value })} placeholder={t("e.g. Monthly freight Riyadh-Jeddah")} /></Field>
        <Field label={t("Company")} optional><Input value={l.company ?? ""} onChange={(e) => setL({ ...l, company: e.target.value })} /></Field>
        <Field label={t("Contact")} optional><Input value={l.contactName ?? ""} onChange={(e) => setL({ ...l, contactName: e.target.value })} /></Field>
        <Field label={t("Mobile")} optional><Input dir="ltr" value={l.phone ?? ""} onChange={(e) => setL({ ...l, phone: e.target.value })} /></Field>
        <Field label={t("Expected value (SAR)")} optional><Input type="number" className="w-32" value={l.expectedValue ?? ""} onChange={(e) => setL({ ...l, expectedValue: e.target.value })} /></Field>
        <Field label={t("Source")} optional><Input className="w-32" value={l.source ?? ""} onChange={(e) => setL({ ...l, source: e.target.value })} /></Field>
        <Button type="submit"><Plus className="size-4" aria-hidden /> {t("Add lead")}</Button>
      </form></Card>
      {error && <div className="mb-4"><Alert tone="bad">{error}</Alert></div>}
      {!data ? <Spinner /> : (
        <div className="grid gap-3 lg:grid-cols-5">
          {STAGES.map((s) => (
            <div key={s} className="rounded-xl border border-line bg-slate-50 p-2">
              <p className="mb-2 flex justify-between px-1 text-xs font-semibold uppercase text-muted"><span>{t(s.charAt(0) + s.slice(1).toLowerCase())}</span><span className="tabular">{money(total(s))}</span></p>
              {data.filter((x) => x.stage === s).map((x) => (
                <div key={x.id} className="mb-2 rounded-lg border border-line bg-white p-2 text-sm">
                  <p className="font-medium" dir="auto">{x.title}</p>
                  <p className="text-xs text-muted">{[x.company, x.contactName, x.phone].filter(Boolean).join(" · ")}</p>
                  {x.expectedValue && <p className="tabular text-xs">{money(x.expectedValue)}</p>}
                  {x.lostReason && <p className="text-xs text-bad">{x.lostReason}</p>}
                  <div className="mt-1 flex flex-wrap gap-1">
                    <Select className="h-7 py-0 text-xs" value={x.stage} onChange={async (e) => {
                      const stage = e.target.value;
                      const lostReason = stage === "LOST" ? (window.prompt(t("Why was it lost?")) ?? "") : x.lostReason;
                      if (stage === "LOST" && !lostReason) return;
                      await save({ ...x, stage, lostReason });
                    }} aria-label={t("Stage")}>{STAGES.map((st) => <option key={st} value={st}>{t(st.charAt(0) + st.slice(1).toLowerCase())}</option>)}</Select>
                    {x.customerId ? <ButtonLink variant="secondary" href="/quotations/new">{t("Quotation")}</ButtonLink>
                      : x.stage !== "LOST" && <Button variant="secondary" className="h-7 px-2 text-xs" onClick={async () => { try { await api("POST", `/crm/leads/${x.id}/convert`); mutate(); } catch (e) { setError(errText(e, "")); } }}>{t("Make customer")}</Button>}
                  </div>
                </div>
              ))}
            </div>
          ))}
        </div>
      )}
    </>
  );
}

function CreditControl() {
  const t = useT();
  const { data, mutate } = useSWR<Credit[]>("/crm/credit", fetcher);
  const [edit, setEdit] = useState<Record<number, { limit: string; terms: string }>>({});
  const [error, setError] = useState<string | null>(null);
  return (
    <Card>
      <CardHeader title={t("Credit limits & payment terms")} subtitle={t("Orders and invoices that would take a customer over the limit are refused. Terms set the invoice due date.")} />
      {error && <div className="p-4"><Alert tone="bad">{error}</Alert></div>}
      {!data ? <Spinner /> : data.length === 0 ? <Empty title={t("No customers yet")} /> : (
        <div className="overflow-x-auto"><table className="w-full text-sm">
          <thead><tr className="border-b border-line text-xs text-muted"><th className="px-4 py-2 text-start font-medium">{t("Customer")}</th><th className="px-4 py-2 text-end font-medium">{t("Owes")}</th><th className="w-36 px-4 py-2 text-end font-medium">{t("Credit limit")}</th><th className="w-28 px-4 py-2 text-end font-medium">{t("Terms (days)")}</th><th className="px-4 py-2 text-end font-medium">{t("Available")}</th><th className="w-20" /></tr></thead>
          <tbody className="divide-y divide-line">{data.map((c) => {
            const e = edit[c.customerId] ?? { limit: c.creditLimit == null ? "" : String(Number(c.creditLimit)), terms: c.paymentTermsDays == null ? "" : String(c.paymentTermsDays) };
            return (
              <tr key={c.customerId}><td className="px-4 py-2" dir="auto">{c.name}</td><td className="tabular px-4 py-2 text-end">{money(c.outstanding)}</td>
                <td className="px-4 py-2"><Input type="number" min="0" className="text-end" value={e.limit} onChange={(x) => setEdit({ ...edit, [c.customerId]: { ...e, limit: x.target.value } })} placeholder={t("No limit")} /></td>
                <td className="px-4 py-2"><Input type="number" min="0" max="365" className="text-end" value={e.terms} onChange={(x) => setEdit({ ...edit, [c.customerId]: { ...e, terms: x.target.value } })} /></td>
                <td className={`tabular px-4 py-2 text-end ${c.available != null && Number(c.available) < 0 ? "font-semibold text-bad" : ""}`}>{c.available == null ? "—" : money(c.available)}</td>
                <td className="px-2">{edit[c.customerId] && <Button variant="secondary" onClick={async () => {
                  setError(null);
                  try { await api("PUT", `/crm/credit/${c.customerId}`, { creditLimit: e.limit ? Number(e.limit) : null, paymentTermsDays: e.terms ? Number(e.terms) : null }); const n = { ...edit }; delete n[c.customerId]; setEdit(n); mutate(); }
                  catch (err) { setError(errText(err, t("Could not reach the server"))); }
                }}>{t("Save")}</Button>}</td></tr>
            );
          })}</tbody></table></div>
      )}
    </Card>
  );
}

function RecurringTab() {
  const t = useT();
  const { data, mutate } = useSWR<Recurring[]>("/crm/recurring", fetcher);
  const { data: customers } = useSWR<Customer[]>("/customers", fetcher);
  const [r, setR] = useState<Recurring>({ customerId: 0, name: "", kind: "STANDARD", intervalMonths: 1, nextDate: todayKsa(), lines: [{ name: "", quantity: 1, unitPrice: "" }] });
  const [msg, setMsg] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);
  return (
    <>
      <Card className="mb-4">
        <CardHeader title={t("New contract billing")} subtitle={t("Each period a draft invoice is made automatically (06:00 KSA); staff review and issue it.")} />
        <form className="space-y-3 p-4" onSubmit={async (e) => {
          e.preventDefault(); setMsg(null);
          try { await api("POST", "/crm/recurring", { ...r, customerId: Number(r.customerId), lines: r.lines.map((l) => ({ ...l, quantity: Number(l.quantity), unitPrice: Number(l.unitPrice) })) }); setR({ ...r, name: "", lines: [{ name: "", quantity: 1, unitPrice: "" }] }); mutate(); }
          catch (err) { setMsg({ tone: "bad", text: errText(err, t("Could not reach the server")) }); }
        }}>
          <div className="flex flex-wrap items-end gap-3">
            <Field label={t("Customer")}><Select required value={r.customerId || ""} onChange={(e) => setR({ ...r, customerId: Number(e.target.value) })}><option value="">{t("Select…")}</option>{customers?.map((c) => <option key={c.id} value={c.id}>{c.nameEn || c.nameAr}</option>)}</Select></Field>
            <Field label={t("Contract name")}><Input required value={r.name} onChange={(e) => setR({ ...r, name: e.target.value })} placeholder={t("e.g. Warehouse rent")} /></Field>
            <Field label={t("Invoice type")}><Select value={r.kind} onChange={(e) => setR({ ...r, kind: e.target.value })}><option value="STANDARD">{t("Standard (B2B)")}</option><option value="SIMPLIFIED">{t("Simplified (B2C)")}</option></Select></Field>
            <Field label={t("Every (months)")}><Input type="number" min="1" max="12" className="w-20" value={r.intervalMonths} onChange={(e) => setR({ ...r, intervalMonths: Number(e.target.value) })} /></Field>
            <Field label={t("First invoice date")}><Input type="date" value={r.nextDate} onChange={(e) => setR({ ...r, nextDate: e.target.value })} /></Field>
            <Field label={t("Until")} optional><Input type="date" value={r.endDate ?? ""} onChange={(e) => setR({ ...r, endDate: e.target.value || null })} /></Field>
          </div>
          {r.lines.map((l, i) => (
            <div key={i} className="flex gap-2">
              <Input required className="flex-1" value={l.name} onChange={(e) => setR({ ...r, lines: r.lines.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)) })} placeholder={t("Service")} />
              <Input type="number" min="0" step="any" className="w-24" value={l.quantity} onChange={(e) => setR({ ...r, lines: r.lines.map((x, j) => (j === i ? { ...x, quantity: e.target.value } : x)) })} aria-label={t("Quantity")} />
              <Input required type="number" min="0" step="0.01" className="w-32" value={l.unitPrice} onChange={(e) => setR({ ...r, lines: r.lines.map((x, j) => (j === i ? { ...x, unitPrice: e.target.value } : x)) })} placeholder={t("Price excl. VAT")} />
              <Button type="button" variant="ghost" disabled={r.lines.length === 1} onClick={() => setR({ ...r, lines: r.lines.filter((_, j) => j !== i) })} aria-label={t("Remove")}><Trash2 className="size-4" aria-hidden /></Button>
            </div>
          ))}
          <div className="flex gap-2"><Button type="button" variant="secondary" onClick={() => setR({ ...r, lines: [...r.lines, { name: "", quantity: 1, unitPrice: "" }] })}><Plus className="size-4" aria-hidden /> {t("Add line")}</Button><Button type="submit">{t("Save contract")}</Button></div>
        </form>
      </Card>
      {msg && <div className="mb-4"><Alert tone={msg.tone}>{msg.text}</Alert></div>}
      <Card>
        <CardHeader title={t("Contracts")} action={<Button variant="secondary" onClick={async () => { const x = await api<{ created: number[] }>("POST", "/crm/recurring/run"); setMsg({ tone: "ok", text: t("{n} draft invoice(s) made", { n: String(x.created.length) }) }); mutate(); }}><Play className="size-4" aria-hidden /> {t("Make due invoices now")}</Button>} />
        {!data ? <Spinner /> : data.length === 0 ? <Empty title={t("No contracts yet")} /> : (
          <table className="w-full text-sm"><tbody className="divide-y divide-line">{data.map((x) => (
            <tr key={x.id} className={x.active ? "" : "text-muted"}><td className="px-4 py-2 font-medium" dir="auto">{x.name}</td><td className="px-4 py-2" dir="auto">{x.customerName}</td>
              <td className="tabular px-4 py-2 text-end">{money(x.amount)} / {x.intervalMonths === 1 ? t("month") : t("{n} months", { n: String(x.intervalMonths) })}</td>
              <td className="tabular px-4 py-2">{t("Next")}: {date(x.nextDate)}</td>
              <td className="px-4 py-2">{x.lastInvoiceId && <Link className="text-brand hover:underline" href={`/invoices/${x.lastInvoiceId}`}>{t("Last invoice")}</Link>}</td>
              <td className="px-2"><Button variant="ghost" onClick={async () => { await api("PUT", `/crm/recurring/${x.id}/active?active=${!x.active}`); mutate(); }}>{x.active ? t("Pause") : t("Resume")}</Button></td></tr>
          ))}</tbody></table>
        )}
      </Card>
    </>
  );
}

export default function CrmPage() {
  const t = useT();
  const [tab, setTab] = useState<Tab>("pipeline");
  const tabs: { id: Tab; label: string; icon: typeof Handshake }[] = [{ id: "pipeline", label: "Pipeline", icon: Handshake }, { id: "credit", label: "Credit control", icon: Gauge }, { id: "recurring", label: "Contract billing", icon: CalendarClock }];
  return (
    <>
      <PageHeader title={t("Customers & CRM")} subtitle={t("Opportunities, credit limits and recurring contract invoices.")} />
      <div className="mb-4 flex flex-wrap gap-2">{tabs.map((x) => <button key={x.id} onClick={() => setTab(x.id)} className={`inline-flex items-center gap-1.5 rounded-lg border px-3 py-2 text-sm font-medium ${tab === x.id ? "border-brand bg-brand text-white" : "border-line bg-white"}`}><x.icon className="size-4" aria-hidden /> {t(x.label)}</button>)}</div>
      {tab === "pipeline" ? <Pipeline /> : tab === "credit" ? <CreditControl /> : <RecurringTab />}
    </>
  );
}
