"use client";

import { Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import useSWR from "swr";
import { FileDown, FileSpreadsheet } from "lucide-react";
import { download, fetcher, openFile } from "@/lib/api";
import type { Warehouse } from "@/lib/types";
import { money } from "@/lib/format";
import { Alert, Button, Card, Field, Input, PageHeader, Select, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

interface Table { title: string; subtitle: string | null; columns: string[]; rows: (string | number | null)[][]; totals: (string | number | null)[] | null }

const REPORTS: { id: string; label: string; groups?: [string, string][]; dates?: boolean; warehouse?: boolean; year?: boolean }[] = [
  { id: "sales", label: "Sales", dates: true, groups: [["day", "By day"], ["month", "By month"], ["product", "By product"], ["customer", "By customer"], ["user", "By user"]] },
  { id: "stock", label: "Stock valuation", warehouse: true },
  { id: "purchases", label: "Purchases", dates: true, groups: [["supplier", "By supplier"], ["month", "By month"], ["product", "By item"]] },
  { id: "expenses", label: "Expenses", dates: true, groups: [["category", "By category"], ["month", "By month"]] },
  { id: "vat", label: "VAT summary", dates: true },
  { id: "gross-profit", label: "Gross profit", dates: true, groups: [["invoice", "By invoice"], ["customer", "By customer"], ["product", "By item"], ["month", "By month"]] },
  { id: "pnl", label: "Profit & loss", year: true },
  { id: "receivables", label: "Receivables aging" },
  { id: "payables", label: "Payables aging" },
];

const isNum = (v: unknown) => typeof v === "number" || (typeof v === "string" && v !== "" && !isNaN(Number(v)) && /^-?\d+(\.\d+)?$/.test(v));

function Reports() {
  const t = useT();
  const params = useSearchParams();
  const [id, setId] = useState(params.get("r") ?? "sales");
  const def = REPORTS.find((r) => r.id === id)!;
  const today = new Date().toISOString().slice(0, 10);
  const [from, setFrom] = useState(today.slice(0, 8) + "01");
  const [to, setTo] = useState(today);
  const [groupBy, setGroupBy] = useState("");
  const [warehouseId, setWarehouseId] = useState("");
  const [year, setYear] = useState(new Date().getFullYear());
  const { data: warehouses } = useSWR<Warehouse[]>(def.warehouse ? "/warehouses" : null, fetcher);
  const qs = new URLSearchParams();
  if (def.dates) { qs.set("from", from); qs.set("to", to); }
  if (def.groups) qs.set("groupBy", groupBy || def.groups[0][0]);
  if (def.warehouse && warehouseId) qs.set("warehouseId", warehouseId);
  if (def.year) qs.set("year", String(year));
  const { data, error } = useSWR<Table>(`/erp-reports/${id}?${qs}`, fetcher, { keepPreviousData: true });
  const cell = (v: unknown, i: number) => (i > 0 && isNum(v) && !String(data?.columns[i]).match(/Code|Unit|Documents|Entries|Invoices|Quantity|On hand/) ? money(Number(v)) : String(v ?? ""));

  return (
    <>
      <PageHeader title={t("Reports")} subtitle={t("Every report exports to Excel and PDF.")} />
      <Card className="mb-4">
        <div className="flex flex-wrap items-end gap-3 p-4">
          <Field label={t("Report")}>
            <Select className="w-56" value={id} onChange={(e) => { setId(e.target.value); setGroupBy(""); }}>{REPORTS.map((r) => <option key={r.id} value={r.id}>{t(r.label)}</option>)}</Select>
          </Field>
          {def.groups && <Field label={t("Group by")}><Select value={groupBy || def.groups[0][0]} onChange={(e) => setGroupBy(e.target.value)}>{def.groups.map(([k, l]) => <option key={k} value={k}>{t(l)}</option>)}</Select></Field>}
          {def.dates && <><Field label={t("From")}><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field><Field label={t("To")}><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field></>}
          {def.warehouse && <Field label={t("Warehouse")}><Select className="w-52" value={warehouseId} onChange={(e) => setWarehouseId(e.target.value)}><option value="">{t("All warehouses")}</option>{warehouses?.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}</Select></Field>}
          {def.year && <Field label={t("Year")}><Input type="number" className="w-24" value={year} onChange={(e) => setYear(Number(e.target.value))} /></Field>}
          <span className="ms-auto flex gap-2">
            <Button variant="secondary" onClick={() => download(`/erp-reports/${id}.xlsx?${qs}`, `${id}-report.xlsx`)}><FileSpreadsheet className="size-4" aria-hidden /> Excel</Button>
            <Button variant="secondary" onClick={() => openFile(`/erp-reports/${id}.pdf?${qs}`)}><FileDown className="size-4" aria-hidden /> PDF</Button>
          </span>
        </div>
      </Card>
      <Card>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data && (
          <div className="overflow-x-auto">
            <p className="px-4 pt-3 text-sm font-semibold">{data.title} {data.subtitle && <span className="font-normal text-muted">· {data.subtitle}</span>}</p>
            <table className="mt-2 w-full text-sm">
              <thead><tr className="border-b border-line text-xs text-muted">{data.columns.map((c, i) => <th key={c} className={`px-4 py-2.5 font-medium ${i === 0 ? "text-start" : "text-end"}`}>{t(c)}</th>)}</tr></thead>
              <tbody className="divide-y divide-line">
                {data.rows.length === 0 && <tr><td colSpan={data.columns.length} className="px-4 py-6 text-center text-muted">{t("Nothing in this period")}</td></tr>}
                {data.rows.map((r, ri) => <tr key={ri}>{r.map((v, i) => <td key={i} className={`px-4 py-2 ${i === 0 ? "" : "tabular text-end"}`} dir={i === 0 ? "auto" : undefined}>{cell(v, i)}</td>)}</tr>)}
              </tbody>
              {data.totals && <tfoot><tr className="border-t border-line font-semibold">{data.totals.map((v, i) => <td key={i} className={`px-4 py-2.5 ${i === 0 ? "" : "tabular text-end"}`}>{i === 0 ? t(String(v)) : cell(v, i)}</td>)}</tr></tfoot>}
            </table>
          </div>
        )}
      </Card>
    </>
  );
}

export default function ErpReportsPage() {
  return <Suspense fallback={<Spinner />}><Reports /></Suspense>;
}
