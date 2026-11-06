"use client";

import { useRef, useState } from "react";
import useSWR from "swr";
import { FileDown, FileSpreadsheet, Printer, Upload } from "lucide-react";
import { ApiError, download, fetcher, openFile, printFile, uploadFile } from "@/lib/api";
import type { AccountImportResult, AccountMonth, AccountYear, Me } from "@/lib/types";
import { todayKsa } from "@/lib/format";
import { Alert, Button, PageHeader, Select, Spinner } from "@/components/ui";
import { DayView, MONTH_NAMES, MonthView, YearView } from "@/components/accounts";
import { useT } from "@/lib/i18n";

/**
 * The company cash book: a day's or month's income and expenses, and the year with partner withdrawals (admin).
 * A branch login sees its own cash book only: the day and month views of the entries it created.
 */
export default function AccountsPage() {
  const t = useT();
  const { data: me } = useSWR<Me>("/auth/me", fetcher);
  const isBranch = me?.role === "BRANCH";
  const thisYear = Number(todayKsa().slice(0, 4));
  const [view, setView] = useState<"day" | "month" | "year">("day");
  const [day, setDayState] = useState(todayKsa());
  const [year, setYear] = useState(thisYear);
  const [month, setMonth] = useState(Number(todayKsa().slice(5, 7)));
  // The day view follows the chosen date; month and year follow it so exports and the other views line up.
  const setDay = (d: string) => { setDayState(d); setYear(Number(d.slice(0, 4))); setMonth(Number(d.slice(5, 7))); };
  const [busy, setBusy] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ tone: "ok" | "bad"; text: string; details?: string[] } | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const monthData = useSWR<AccountMonth>(view !== "year" ? `/accounts/month?year=${year}&month=${month}` : null, fetcher, { keepPreviousData: true });
  const yearData = useSWR<AccountYear>(me && !isBranch ? `/accounts/year?year=${year}` : null, fetcher, { keepPreviousData: true });
  const current = view === "year" ? yearData : monthData;
  const reload = () => { void monthData.mutate(); void yearData.mutate(); };

  const base = view !== "year" ? `/accounts/month` : `/accounts/year`;
  const q = view !== "year" ? `?year=${year}&month=${month}` : `?year=${year}`;
  const fileName = view !== "year" ? `accounts-${year}-${String(month).padStart(2, "0")}` : `company-cashflow-${year}`;

  async function run(key: string, fn: () => Promise<void>) {
    setBusy(key);
    setNotice(null);
    try {
      await fn();
    } catch (e) {
      setNotice({ tone: "bad", text: e instanceof ApiError ? e.message : t("Failed") });
    } finally {
      setBusy(null);
    }
  }

  async function importFile(file: File) {
    await run("import", async () => {
      const r = await uploadFile<AccountImportResult>(`/accounts/import?year=${year}`, file);
      reload();
      const details = [
        ...r.months.map((m) => t("{month} {year}: {income} income and {expenses} expenses{guessed}", {
          month: t(MONTH_NAMES[m.month - 1]), year: m.year, income: m.income, expenses: m.expenses,
          guessed: m.datesGuessed ? t(" ({n} dates could not be read; set to the 1st of the month)", { n: m.datesGuessed }) : "",
        })),
        ...(r.yearMonths.length ? [t("Yearly sheet: month totals for {months}", { months: r.yearMonths.map((m) => t(MONTH_NAMES[m - 1])).join(", ") })] : []),
        ...(r.yearMonthsSkipped.length ? [t("Yearly totals skipped for {months}: those months already have their own entries", { months: r.yearMonthsSkipped.map((m) => t(MONTH_NAMES[m - 1])).join(", ") })] : []),
        ...(r.withdrawals ? [t("{n} partner withdrawals ({created} new partners)", { n: r.withdrawals, created: r.partnersCreated })] : []),
        ...(r.memos.length ? [t("Notes without an amount were not imported: {notes}", { notes: r.memos.join(" · ") })] : []),
      ];
      setNotice({ tone: "ok", text: t("Import finished. Earlier imports of the same months were replaced; entries typed in by hand were kept."), details });
    });
  }

  return (
    <>
      <PageHeader
        title={isBranch ? t("My cash book") : t("Accounts")}
        subtitle={isBranch ? t("Your income and expenses: only the entries you added (SAR)") : t("Company cash book: income, expenses and partners' withdrawals (SAR)")}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <div className="inline-flex rounded-lg border border-line bg-white p-0.5" role="tablist">
              {(isBranch ? (["day", "month"] as const) : (["day", "month", "year"] as const)).map((v) => (
                <button key={v} role="tab" aria-selected={view === v} onClick={() => setView(v)}
                        className={`rounded-md px-3 py-1.5 text-sm font-medium ${view === v ? "bg-brand text-white" : "text-muted hover:text-ink"}`}>
                  {v === "day" ? t("Day") : v === "month" ? t("Month") : t("Year")}
                </button>
              ))}
            </div>
            {view === "month" && (
              <div className="w-36">
                <Select value={month} onChange={(e) => setMonth(Number(e.target.value))} aria-label={t("Month")}>
                  {MONTH_NAMES.map((m, i) => <option key={m} value={i + 1}>{t(m)}</option>)}
                </Select>
              </div>
            )}
            {view !== "day" && <div className="w-24">
              <Select value={year} onChange={(e) => setYear(Number(e.target.value))} aria-label={t("Year")}>
                {[thisYear - 2, thisYear - 1, thisYear, thisYear + 1].map((y) => <option key={y} value={y}>{y}</option>)}
              </Select>
            </div>}
            <input ref={fileRef} type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" className="hidden"
                   onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ""; if (f) void importFile(f); }} />
            {!isBranch && <Button variant="secondary" loading={busy === "import"} onClick={() => fileRef.current?.click()}><Upload className="size-4" aria-hidden />{t("Import Excel")}</Button>}
            <Button variant="secondary" loading={busy === "xlsx"} onClick={() => run("xlsx", () => download(`${base}.xlsx${q}`, `${fileName}.xlsx`))}>
              <FileSpreadsheet className="size-4" aria-hidden />{t("Excel")}
            </Button>
            <Button variant="secondary" loading={busy === "pdf"} onClick={() => run("pdf", () => openFile(`${base}/pdf${q}`))}>
              <FileDown className="size-4" aria-hidden />{t("PDF")}
            </Button>
            <Button variant="secondary" loading={busy === "print"} onClick={() => run("print", () => printFile(`${base}/pdf${q}`))}>
              <Printer className="size-4" aria-hidden />{t("Print")}
            </Button>
          </div>
        }
      />

      {notice && (
        <div className="mb-4">
          <Alert tone={notice.tone}>
            {notice.text}
            {notice.details && <ul className="mt-2 list-disc space-y-0.5 ps-5 text-xs">{notice.details.map((d) => <li key={d}>{d}</li>)}</ul>}
          </Alert>
        </div>
      )}
      {current.error && <Alert tone="bad">{current.error.message}</Alert>}
      {!current.data && !current.error && <Spinner />}
      {view === "day" && monthData.data && (
        <DayView day={day} month={monthData.data} year={yearData.data} onDay={setDay} onChanged={reload} />
      )}
      {view === "month" && monthData.data && <MonthView data={monthData.data} year={yearData.data} onChanged={reload} />}
      {view === "year" && yearData.data && (
        <YearView data={yearData.data} onChanged={reload} onOpenMonth={(m) => { setMonth(m); setView("month"); }} />
      )}
    </>
  );
}
