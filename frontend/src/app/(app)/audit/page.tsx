"use client";

import { useMemo, useState, type ReactNode } from "react";
import useSWR from "swr";
import { AlertTriangle, CalendarClock, FileDown, Info, Printer, ShieldCheck } from "lucide-react";
import { ApiError, fetcher, openFile, printFile } from "@/lib/api";
import type { AuditFinding, AuditReportData, AuditSeverity } from "@/lib/types";
import { date, dateTime, money, todayKsa } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Field, Input, PageHeader, Select, Spinner } from "@/components/ui";
import { categoryLabel } from "@/components/accounts";
import { useLanguage, useT, type TFunction } from "@/lib/i18n";

type Preset = "thisQuarter" | "lastQuarter" | "thisYear" | "lastYear" | "custom";

const iso = (y: number, m: number, d: number) => `${y}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
const lastDay = (y: number, m: number) => new Date(Date.UTC(y, m, 0)).getUTCDate();

/** First and last day of a preset period. VAT returns are filed quarterly by most businesses. */
function range(p: Exclude<Preset, "custom">): [string, string] {
  const today = todayKsa();
  const y = Number(today.slice(0, 4));
  const q = Math.floor((Number(today.slice(5, 7)) - 1) / 3);
  if (p === "thisQuarter") return [iso(y, q * 3 + 1, 1), today];
  if (p === "lastQuarter") {
    const [ly, lq] = q === 0 ? [y - 1, 3] : [y, q - 1];
    return [iso(ly, lq * 3 + 1, 1), iso(ly, lq * 3 + 3, lastDay(ly, lq * 3 + 3))];
  }
  if (p === "thisYear") return [iso(y, 1, 1), today];
  return [iso(y - 1, 1, 1), iso(y - 1, 12, 31)];
}

const severityStyle: Record<AuditSeverity, { label: string; chip: string }> = {
  HIGH: { label: "High risk", chip: "bg-bad/10 text-bad" },
  MEDIUM: { label: "Medium", chip: "bg-warn/10 text-warn" },
  LOW: { label: "Low", chip: "bg-blue-50 text-blue-700" },
  INFO: { label: "Note", chip: "bg-canvas text-muted" },
};

/** A finding in the current language: Arabic from the server, other languages through the dictionaries. */
function findingText(f: AuditFinding, which: "text" | "action", lang: string, t: TFunction) {
  const fill = (s: string) => s.replace(/\{(\w+)\}/g, (m, k: string) => f.vars[k] ?? m);
  if (lang === "ar") return fill(which === "text" ? f.textAr : f.actionAr);
  const vars = { ...f.vars, ...(f.vars.doc ? { doc: t(f.vars.doc) } : {}) };
  return t(which === "text" ? f.text : f.action, vars);
}

function Stat({ label, value, tone = "" }: { label: string; value: ReactNode; tone?: string }) {
  return (
    <div className="rounded-lg border border-line bg-canvas/60 px-3 py-2.5">
      <p className="text-xs text-muted">{label}</p>
      <p className={`tabular mt-0.5 text-lg font-semibold ${tone}`}>{value}</p>
    </div>
  );
}

function Row({ label, value, bad }: { label: string; value: ReactNode; bad?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-4 py-2">
      <dt className="text-sm text-muted">{label}</dt>
      <dd className={`tabular text-sm font-semibold ${bad ? "text-bad" : ""}`}>{value}</dd>
    </div>
  );
}

/** Business audit of a period against Saudi requirements (VAT, Fatoora, documents, books). Admin only. */
export default function AuditPage() {
  const t = useT();
  const { lang } = useLanguage();
  const [preset, setPreset] = useState<Preset>("thisQuarter");
  const [custom, setCustom] = useState<[string, string]>(() => range("thisQuarter"));
  const [from, to] = useMemo(() => (preset === "custom" ? custom : range(preset)), [preset, custom]);
  const valid = from && to && from <= to;
  const { data: r, error, isLoading } = useSWR<AuditReportData>(valid ? `/business-audit?from=${from}&to=${to}` : null, fetcher, { keepPreviousData: true });
  const [fileError, setFileError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  async function file(kind: "open" | "print") {
    setBusy(kind);
    setFileError(null);
    try {
      await (kind === "open" ? openFile : printFile)(`/business-audit/pdf?from=${from}&to=${to}`);
    } catch (e) {
      setFileError(e instanceof ApiError ? e.message : t("Could not reach the server"));
    } finally {
      setBusy(null);
    }
  }

  const ar = lang === "ar";
  const scoreTone = !r ? "" : r.score.score >= 85 ? "text-ok border-ok" : r.score.score >= 60 ? "text-warn border-warn" : "text-bad border-bad";

  return (
    <>
      <PageHeader
        title={t("Business audit")}
        subtitle={t("VAT, e-invoicing, receivables, cash, documents and controls, checked against Saudi requirements")}
        actions={
          <>
            <Button variant="secondary" loading={busy === "print"} disabled={!r || busy != null} onClick={() => file("print")}>
              <Printer className="size-4" aria-hidden /> {t("Print")}
            </Button>
            <Button loading={busy === "open"} disabled={!r || busy != null} onClick={() => file("open")}>
              <FileDown className="size-4" aria-hidden /> {t("Audit report PDF")}
            </Button>
          </>
        }
      />

      <Card className="mb-6">
        <div className="flex flex-wrap items-end gap-4 p-4">
          <Field label={t("Period")} className="w-56">
            <Select value={preset} onChange={(e) => setPreset(e.target.value as Preset)}>
              <option value="thisQuarter">{t("This quarter")}</option>
              <option value="lastQuarter">{t("Last quarter")}</option>
              <option value="thisYear">{t("This year")}</option>
              <option value="lastYear">{t("Last year")}</option>
              <option value="custom">{t("Custom dates")}</option>
            </Select>
          </Field>
          {preset === "custom" ? (
            <>
              <Field label={t("From")} className="w-44"><Input type="date" value={custom[0]} onChange={(e) => setCustom([e.target.value, custom[1]])} /></Field>
              <Field label={t("To")} className="w-44"><Input type="date" value={custom[1]} onChange={(e) => setCustom([custom[0], e.target.value])} /></Field>
            </>
          ) : (
            <p className="pb-2 text-sm text-muted">{date(from)} – {date(to)}</p>
          )}
        </div>
      </Card>

      {fileError && <div className="mb-4"><Alert tone="bad">{fileError}</Alert></div>}
      {!valid && <Alert tone="warn">{t("'To' must not be before 'From'.")}</Alert>}
      {error && <Alert tone="bad">{error.message}</Alert>}
      {isLoading && !r && <Spinner />}

      {r && (
        <div className="space-y-6">
          <Card>
            <div className="grid gap-5 p-5 md:grid-cols-[13rem_1fr]">
              <div className={`flex flex-col items-center justify-center rounded-xl border-2 p-4 text-center ${scoreTone}`}>
                <ShieldCheck className="mb-1 size-6" aria-hidden />
                <p className="tabular text-4xl font-bold">{r.score.score}<span className="text-lg font-medium">/100</span></p>
                <p className="mt-1 font-semibold">{ar ? r.score.ratingAr : t(r.score.rating)}</p>
              </div>
              <div className="space-y-3">
                <div>
                  <p className="font-semibold">{r.company.nameEn} {r.company.nameAr && <span className="ar font-normal text-muted" dir="rtl">{r.company.nameAr}</span>}</p>
                  <p className="text-xs text-muted">
                    {t("VAT")} {r.company.vatNumber || "—"} · {t("CR number")} {r.company.crNumber || "—"} · {t("Prepared by {name}, {date}", { name: r.preparedBy, date: dateTime(r.generatedAt) })}
                  </p>
                </div>
                <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
                  <Stat label={t("High risk")} value={r.score.high} tone={r.score.high ? "text-bad" : ""} />
                  <Stat label={t("Medium")} value={r.score.medium} tone={r.score.medium ? "text-warn" : ""} />
                  <Stat label={t("Output VAT")} value={money(r.vat.total.vat)} />
                  <Stat label={t("Owed by customers")} value={money(r.receivables.totalDue)} tone={r.receivables.totalDue > 0 ? "text-bad" : ""} />
                </div>
              </div>
            </div>
          </Card>

          <Card>
            <CardHeader title={t("Findings and recommendations")} subtitle={t("Worst first. Each one says what to do about it.")} />
            {r.findings.length === 0 && <p className="p-5 text-sm text-muted">{t("No findings for this period.")}</p>}
            <ul className="divide-y divide-line">
              {r.findings.map((f, i) => (
                <li key={i} className="flex gap-3 px-5 py-4">
                  {f.severity === "INFO" ? <Info className="mt-0.5 size-4 shrink-0 text-muted" aria-hidden /> : <AlertTriangle className={`mt-0.5 size-4 shrink-0 ${f.severity === "HIGH" ? "text-bad" : f.severity === "MEDIUM" ? "text-warn" : "text-blue-700"}`} aria-hidden />}
                  <div className="min-w-0 flex-1">
                    <p className="flex flex-wrap items-center gap-2">
                      <span className={`rounded px-1.5 py-0.5 text-[11px] font-semibold ${severityStyle[f.severity].chip}`}>{t(severityStyle[f.severity].label)}</span>
                      <span className="text-xs text-muted">{t(f.area)}</span>
                    </p>
                    <p className="mt-1 text-sm font-medium">{findingText(f, "text", lang, t)}</p>
                    <p className="mt-0.5 text-sm text-muted">{findingText(f, "action", lang, t)}</p>
                  </div>
                </li>
              ))}
            </ul>
          </Card>

          <Card>
            <CardHeader title={t("VAT return summary (sales)")} subtitle={t("The sales boxes of the ZATCA VAT return. Due by {date}.", { date: date(r.vat.filingDeadline) })} />
            <div className="overflow-x-auto">
              <table className="w-full min-w-[640px] text-sm">
                <thead>
                  <tr className="border-b border-line text-xs text-muted">
                    <th className="w-16 px-5 py-2 text-start font-medium">{t("Box")}</th>
                    <th className="px-3 py-2 text-start font-medium">{t("Description")}</th>
                    <th className="px-3 py-2 text-end font-medium">{t("Amount (SAR)")}</th>
                    <th className="px-3 py-2 text-end font-medium">{t("Adjustments")}</th>
                    <th className="px-5 py-2 text-end font-medium">{t("VAT")}</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {[...r.vat.boxes, r.vat.total].map((b) => (
                    <tr key={b.box} className={b.box === r.vat.total.box ? "bg-canvas font-semibold" : ""}>
                      <td className="px-5 py-2">{b.box}</td>
                      <td className="px-3 py-2">{ar ? b.labelAr : t(b.label)}</td>
                      <td className="tabular px-3 py-2 text-end">{money(b.amount)}</td>
                      <td className={`tabular px-3 py-2 text-end ${b.adjustment < 0 ? "text-bad" : ""}`}>{money(b.adjustment)}</td>
                      <td className="tabular px-5 py-2 text-end">{money(b.vat)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="border-t border-line px-5 py-3 text-xs text-muted">
              {t("Amounts exclude VAT. Adjustments are credit notes (−) and debit notes (+) issued in the period. Purchases (boxes 7–13) are not recorded here.")}
              {r.vat.outOfScope !== 0 && ` ${t("Out of scope, not on the return: {amount}", { amount: money(r.vat.outOfScope) })}`}
            </p>
          </Card>

          <div className="grid gap-6 lg:grid-cols-2">
            <Card>
              <CardHeader title={t("E-invoicing compliance")} subtitle={t("ZATCA Fatoora Phase 2")} />
              <dl className="divide-y divide-line px-5">
                <Row label={t("Invoices / credit notes / debit notes")} value={`${r.einvoicing.invoices} / ${r.einvoicing.creditNotes} / ${r.einvoicing.debitNotes}`} />
                <Row label={t("Tax (B2B) / simplified (B2C)")} value={`${r.einvoicing.standard} / ${r.einvoicing.simplified}`} />
                <Row label={t("Waiting for ZATCA")} value={r.einvoicing.pending} />
                <Row label={t("Rejected by ZATCA")} value={r.einvoicing.rejected} bad={r.einvoicing.rejected > 0} />
                <Row label={t("Simplified reported after 24 hours")} value={r.einvoicing.lateReports} bad={r.einvoicing.lateReports > 0} />
                <Row label={t("Not accepted after 24 hours")} value={r.einvoicing.overdueUnreported} bad={r.einvoicing.overdueUnreported > 0} />
                <Row label={t("Tax invoices without buyer VAT or ID")} value={r.einvoicing.standardWithoutBuyerId} bad={r.einvoicing.standardWithoutBuyerId > 0} />
                <Row label={t("Invoice counter gaps")} value={r.einvoicing.chainProblems} bad={r.einvoicing.chainProblems > 0} />
                <Row label={t("Drafts older than 7 days")} value={r.einvoicing.oldDrafts} />
                <Row label={t("Credit notes as % of sales")} value={`${r.einvoicing.creditNotePct}%`} />
              </dl>
            </Card>

            <Card>
              <CardHeader title={t("Cash book and partners")} subtitle={t("From Accounts, worker payments included")} />
              <div className="grid grid-cols-2 gap-3 p-5">
                <Stat label={t("Received")} value={money(r.cash.received)} />
                <Stat label={t("Expenses")} value={money(r.cash.expenses)} />
                <Stat label={t("Net cash profit")} value={money(r.cash.net)} tone={r.cash.net < 0 ? "text-bad" : ""} />
                <Stat label={t("Partner drawings")} value={money(r.cash.drawings)} />
                <div className="col-span-2"><Stat label={t("Kept in company")} value={money(r.cash.retained)} tone={r.cash.retained < 0 ? "text-bad" : "text-ok"} /></div>
              </div>
              {r.cash.categories.length > 0 && (
                <dl className="divide-y divide-line border-t border-line px-5">
                  {r.cash.categories.map((c) => (
                    <Row key={c.category} label={t(categoryLabel[c.category as keyof typeof categoryLabel] ?? "Other")} value={money(c.amount)} />
                  ))}
                </dl>
              )}
            </Card>
          </div>

          <Card>
            <CardHeader title={t("Sales and receivables")} subtitle={t("Sales of the period and what customers owe today, by age")} />
            <div className="grid grid-cols-2 gap-3 p-5 lg:grid-cols-4">
              <Stat label={t("Sales incl. VAT")} value={money(r.receivables.sales)} />
              <Stat label={t("Collected")} value={money(r.receivables.collected)} />
              <Stat label={t("Collection rate")} value={`${r.receivables.collectionPct}%`} />
              <Stat label={t("Owed now")} value={money(r.receivables.totalDue)} tone={r.receivables.totalDue > 0 ? "text-bad" : ""} />
            </div>
            <div className="grid gap-6 border-t border-line p-5 lg:grid-cols-2">
              <div>
                <p className="mb-2 text-xs font-medium text-muted">{t("Age of what is owed")}</p>
                <dl className="divide-y divide-line">
                  {r.receivables.aging.map((b) => (
                    <Row key={b.label} label={`${ar ? b.labelAr : t(b.label)} · ${t("{n} invoices", { n: b.invoices })}`} value={money(b.amount)} bad={b.amount > 0 && /90|180/.test(b.label)} />
                  ))}
                </dl>
              </div>
              <div>
                <p className="mb-2 text-xs font-medium text-muted">{t("Largest debtors")}</p>
                {r.receivables.topDebtors.length === 0 && <p className="text-sm text-muted">{t("Nobody owes anything")}</p>}
                <dl className="divide-y divide-line">
                  {r.receivables.topDebtors.map((d) => (
                    <Row key={d.name} label={`${d.name === "Walk-in customers" ? t(d.name) : d.name} · ${t("oldest {n} days", { n: d.oldestDays })}`} value={money(d.due)} />
                  ))}
                </dl>
              </div>
            </div>
          </Card>

          <div className="grid gap-6 lg:grid-cols-2">
            <Card>
              <CardHeader title={t("Workforce")} />
              <div className="grid grid-cols-2 gap-3 p-5 sm:grid-cols-4">
                <Stat label={t("Active workers")} value={r.workforce.active} />
              </div>
            </Card>

            <Card>
              <CardHeader title={t("Internal control")} subtitle={t("From the audit log")} />
              <dl className="divide-y divide-line px-5">
                <Row label={t("Recorded actions")} value={r.controls.events} />
                <Row label={t("Users")} value={r.controls.users} />
                <Row label={t("Deletions")} value={r.controls.deletions} bad={r.controls.deletions > 0} />
                <Row label={t("Payment edits")} value={r.controls.paymentEdits} />
                <Row label={t("Failed sign-ins")} value={r.controls.failedLogins} />
              </dl>
              <div className="border-t border-line px-5 py-4">
                <p className="mb-2 flex items-center gap-1.5 text-xs font-medium text-muted"><CalendarClock className="size-3.5" aria-hidden /> {t("Compliance calendar")}</p>
                <ul className="space-y-1.5 text-sm">
                  {r.calendar.map((d, i) => (
                    <li key={i} className="flex justify-between gap-4">
                      <span>{ar ? d.labelAr : d.label}</span>
                      <span className={`tabular shrink-0 ${d.date < todayKsa() ? "font-semibold text-bad" : "text-muted"}`}>{date(d.date)}</span>
                    </li>
                  ))}
                </ul>
              </div>
            </Card>
          </div>

          <p className="text-xs leading-relaxed text-muted">
            {t("This is an internal management audit prepared from the records in this system, checked against ZATCA VAT and e-invoicing rules, IFRS as endorsed by SOCPA, and Saudi residency rules. It is not an independent auditor's opinion; statutory financial statements must be audited by a SOCPA-licensed auditor where the Companies Law requires it.")}
          </p>
        </div>
      )}
    </>
  );
}
