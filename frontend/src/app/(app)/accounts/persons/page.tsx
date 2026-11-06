"use client";

import { useState } from "react";
import useSWR from "swr";
import { ArrowLeft, Coins, Receipt, UserRound } from "lucide-react";
import { fetcher } from "@/lib/api";
import type { AccountPerson, AccountPersonHistory } from "@/lib/types";
import { date, money, paymentMethodLabel, todayKsa } from "@/lib/format";
import { Alert, Card, CardHeader, Empty, PageHeader, Select, Spinner } from "@/components/ui";
import { categoryLabel } from "@/components/accounts";
import { useT } from "@/lib/i18n";

const num = (v: string | null | undefined) => (v == null || v === "" ? 0 : Number(v));

/**
 * Income and expense grouped by person: who received money (income) and who paid it (expense). The table
 * shows each person's totals for the year; opening a person shows their income and expenses on their own.
 */
export default function PersonsPage() {
  const t = useT();
  const thisYear = Number(todayKsa().slice(0, 4));
  const [year, setYear] = useState(thisYear);
  const [person, setPerson] = useState<string | null>(null);

  const { data, error } = useSWR<AccountPerson[]>(`/accounts/persons?year=${year}`, fetcher, { keepPreviousData: true });

  return (
    <>
      <PageHeader
        title={t("Income / expense by person")}
        subtitle={t("Who received money and who paid it, grouped by name (SAR)")}
        actions={
          <div className="w-24">
            <Select value={year} onChange={(e) => { setYear(Number(e.target.value)); setPerson(null); }} aria-label={t("Year")}>
              {[thisYear - 2, thisYear - 1, thisYear, thisYear + 1].map((y) => <option key={y} value={y}>{y}</option>)}
            </Select>
          </div>
        }
      />

      {error && <Alert tone="bad">{error.message}</Alert>}
      {!data && !error && <Spinner />}

      {data && !person && (
        data.length === 0 ? <Empty title={t("No people yet")}>{t("Set \"Received by\" on income and \"Paid by\" on expenses to see them here.")}</Empty> : (
          <Card>
            <CardHeader title={t("People")} subtitle={t("Open a person to see their income and expenses.")} />
            <div className="overflow-x-auto">
              <table className="w-full min-w-[640px] text-sm">
                <thead>
                  <tr className="border-b border-line text-xs text-muted">
                    <th className="px-4 py-2.5 text-start font-medium">{t("Person")}</th>
                    <th className="px-4 py-2.5 text-end font-medium">{t("Income received")}</th>
                    <th className="px-4 py-2.5 text-end font-medium">{t("Expenses")}</th>
                    <th className="px-4 py-2.5 text-end font-medium">{t("Net")}</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {data.map((p) => (
                    <tr key={p.name} className="hover:bg-canvas">
                      <td className="px-4 py-2.5">
                        <button className="flex items-center gap-2 font-medium hover:underline" onClick={() => setPerson(p.name)}>
                          <UserRound className="size-4 text-muted" aria-hidden />
                          <span dir="auto">{p.name}</span>
                        </button>
                      </td>
                      <td className="tabular whitespace-nowrap px-4 py-2.5 text-end text-ok">
                        {money(p.income)}{p.incomeCount > 0 && <span className="ms-1 text-xs text-muted">({p.incomeCount})</span>}
                      </td>
                      <td className="tabular whitespace-nowrap px-4 py-2.5 text-end">
                        {money(p.expense)}{p.expenseCount > 0 && <span className="ms-1 text-xs text-muted">({p.expenseCount})</span>}
                      </td>
                      <td className={`tabular whitespace-nowrap px-4 py-2.5 text-end font-semibold ${num(p.net) < 0 ? "text-bad" : "text-ok"}`}>{money(p.net)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
        )
      )}

      {person && <PersonDetail name={person} year={year} onBack={() => setPerson(null)} />}
    </>
  );
}

/** One person's income and expense entries for the year, shown independently. */
function PersonDetail({ name, year, onBack }: { name: string; year: number; onBack: () => void }) {
  const t = useT();
  const { data, error } = useSWR<AccountPersonHistory>(`/accounts/persons/history?name=${encodeURIComponent(name)}&year=${year}`, fetcher);

  return (
    <>
      <button className="mb-4 inline-flex items-center gap-1.5 text-sm font-medium text-muted hover:text-ink" onClick={onBack}>
        <ArrowLeft className="size-4 rtl:rotate-180" aria-hidden />{t("Back to all people")}
      </button>
      <h2 className="mb-4 text-lg font-semibold" dir="auto">{name}</h2>

      {error && <Alert tone="bad">{error.message}</Alert>}
      {!data && !error && <Spinner />}
      {data && (
        <>
          <div className="mb-6 grid gap-4 sm:grid-cols-2">
            <div className="rounded-xl border border-line bg-white p-4 shadow-sm">
              <div className="flex items-center justify-between text-muted"><span className="text-xs font-medium">{t("Income received")}</span><Coins className="size-4" aria-hidden /></div>
              <p className="tabular mt-2 text-xl font-semibold text-ok">{money(data.incomeTotal)}</p>
            </div>
            <div className="rounded-xl border border-line bg-white p-4 shadow-sm">
              <div className="flex items-center justify-between text-muted"><span className="text-xs font-medium">{t("Expenses")}</span><Receipt className="size-4" aria-hidden /></div>
              <p className="tabular mt-2 text-xl font-semibold">{money(data.expenseTotal)}</p>
            </div>
          </div>

          <Card className="mb-6">
            <CardHeader title={t("Income received")} subtitle={t("Entries where this person is the one who received the amount.")} />
            {data.income.length === 0 ? <Empty title={t("No income")} /> : (
              <div className="overflow-x-auto">
                <table className="w-full min-w-[640px] text-sm">
                  <thead>
                    <tr className="border-b border-line text-xs text-muted">
                      <th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                      <th className="px-4 py-2.5 text-start font-medium">{t("Customer")}</th>
                      <th className="px-4 py-2.5 text-start font-medium">{t("Work / details")}</th>
                      <th className="px-4 py-2.5 text-end font-medium">{t("Paid")}</th>
                      <th className="px-4 py-2.5 text-start font-medium">{t("Method")}</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-line">
                    {data.income.map((e) => (
                      <tr key={e.id}>
                        <td className="whitespace-nowrap px-4 py-2.5 text-muted">{date(e.date)}</td>
                        <td className="px-4 py-2.5" dir="auto">{e.customerName ?? "—"}</td>
                        <td className="px-4 py-2.5 text-muted" dir="auto">{e.details ?? "—"}</td>
                        <td className="tabular whitespace-nowrap px-4 py-2.5 text-end text-ok">{money(e.paid)}</td>
                        <td className="px-4 py-2.5 text-muted">{e.method ? t(paymentMethodLabel[e.method]) : "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    <tr className="border-t-2 border-line bg-canvas/60 font-semibold">
                      <td className="px-4 py-2.5" colSpan={3}>{t("Total")}</td>
                      <td className="tabular whitespace-nowrap px-4 py-2.5 text-end text-ok">{money(data.incomeTotal)}</td>
                      <td />
                    </tr>
                  </tfoot>
                </table>
              </div>
            )}
          </Card>

          <Card>
            <CardHeader title={t("Expenses")} subtitle={t("Entries where this person is the one who made the payment.")} />
            {data.expenses.length === 0 ? <Empty title={t("No expenses")} /> : (
              <div className="overflow-x-auto">
                <table className="w-full min-w-[640px] text-sm">
                  <thead>
                    <tr className="border-b border-line text-xs text-muted">
                      <th className="px-4 py-2.5 text-start font-medium">{t("Date")}</th>
                      <th className="px-4 py-2.5 text-start font-medium">{t("Expense")}</th>
                      <th className="px-4 py-2.5 text-start font-medium">{t("Category")}</th>
                      <th className="px-4 py-2.5 text-start font-medium">{t("Invoice / Fatoora no.")}</th>
                      <th className="px-4 py-2.5 text-end font-medium">{t("Amount")}</th>
                      <th className="px-4 py-2.5 text-start font-medium">{t("Method")}</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-line">
                    {data.expenses.map((e) => (
                      <tr key={e.id}>
                        <td className="whitespace-nowrap px-4 py-2.5 text-muted">{e.date ? date(e.date) : "—"}</td>
                        <td className="px-4 py-2.5" dir="auto">{e.description}</td>
                        <td className="px-4 py-2.5"><span className="whitespace-nowrap rounded-full bg-canvas px-2 py-0.5 text-[11px] font-medium text-muted">{t(categoryLabel[e.category])}</span></td>
                        <td className="px-4 py-2.5 text-muted" dir="ltr">{e.invoiceNumber ?? "—"}</td>
                        <td className="tabular whitespace-nowrap px-4 py-2.5 text-end">{money(e.amount)}</td>
                        <td className="px-4 py-2.5 text-muted">{e.method ? t(paymentMethodLabel[e.method]) : "—"}</td>
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    <tr className="border-t-2 border-line bg-canvas/60 font-semibold">
                      <td className="px-4 py-2.5" colSpan={4}>{t("Total")}</td>
                      <td className="tabular whitespace-nowrap px-4 py-2.5 text-end">{money(data.expenseTotal)}</td>
                      <td />
                    </tr>
                  </tfoot>
                </table>
              </div>
            )}
          </Card>
        </>
      )}
    </>
  );
}
