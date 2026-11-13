"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import { FileDown, FilePlus2, FileText, Plus, Printer, Save, Trash2, UserPlus } from "lucide-react";
import { api, ApiError, fetcher, openFile, printFile } from "@/lib/api";
import type { Customer, Product, Quotation, QuotationLine, QuotationStatus } from "@/lib/types";
import { money, todayKsa, unitCodes } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Field, Input, RequiredLegend, RequiredMark, Select } from "@/components/ui";
import { UnitSelect, defaultUnit, useItemUnits } from "@/components/units";
import { useT } from "@/lib/i18n";
import { SuggestInput } from "@/components/suggest";

const emptyLine = (): QuotationLine => ({ name: "", quantity: 1, unitCode: "PCE", unitPrice: "", vatRate: 15 });

export const quotationStatusLabel: Record<QuotationStatus, string> = {
  DRAFT: "Draft",
  SENT: "Sent to client",
  ACCEPTED: "Accepted",
  DECLINED: "Declined",
  INVOICED: "Invoiced",
};

const defaultTerms = [
  "Prices are in Saudi Riyals (SAR); VAT is shown separately.",
  "This quotation is valid until the date shown above.",
  "Delivery and payment as agreed on confirmation of the order.",
].join("\n");

/** The same day next month (or the month's last day, e.g. 31 Jan → 28/29 Feb). */
function addMonth(iso: string) {
  const d = new Date(iso + "T00:00:00");
  const day = d.getDate();
  d.setDate(1);
  d.setMonth(d.getMonth() + 1);
  d.setDate(Math.min(day, new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate()));
  return d.toLocaleDateString("en-CA");
}

const r2 = (n: number) => Math.round((n + Number.EPSILON) * 100) / 100;

/** Same rounding as the server: each line's net and VAT to 2 decimals. */
function totals(lines: QuotationLine[]) {
  let net = 0, vat = 0;
  const rows = lines.map((l) => {
    const n = r2(Number(l.quantity || 0) * Number(l.unitPrice || 0));
    const v = r2((n * Number(l.vatRate || 0)) / 100);
    net += n;
    vat += v;
    return { net: n, vat: v, gross: n + v };
  });
  return { rows, net: r2(net), vat: r2(vat), total: r2(net + vat) };
}

export function QuotationEditor({ initial }: { initial?: Quotation }) {
  const router = useRouter();
  const t = useT();
  const itemUnits = useItemUnits();
  const { data: customers } = useSWR<Customer[]>("/customers", fetcher);
  const { data: products } = useSWR<Product[]>("/products", fetcher);

  const today = todayKsa();
  const [customerId, setCustomerId] = useState(initial?.customerId ? String(initial.customerId) : "");
  const [clientName, setClientName] = useState(initial?.clientName ?? "");
  const [issueDate, setIssueDate] = useState(initial?.issueDate ?? today);
  const [validUntil, setValidUntil] = useState(initial?.validUntil ?? addMonth(today));
  const [status, setStatus] = useState<QuotationStatus>(initial?.status ?? "DRAFT");
  const [notes, setNotes] = useState(initial?.notes ?? "");
  const [terms, setTerms] = useState(initial?.terms ?? defaultTerms);
  const [lines, setLines] = useState<QuotationLine[]>(initial?.lines.length ? initial.lines : [emptyLine()]);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const sum = useMemo(() => totals(lines), [lines]);

  function setLine(i: number, patch: Partial<QuotationLine>) {
    setLines((ls) => ls.map((l, idx) => (idx === i ? { ...l, ...patch } : l)));
  }

  /** An exact product name or item code fills in its price; anything else is free text. */
  function setItemName(i: number, name: string) {
    const key = name.trim().toLowerCase();
    const p = products?.find((x) => x.name.toLowerCase() === key || (x.number != null && (x.number.toLowerCase() === key || `${x.number} · ${x.name}`.toLowerCase() === key)));
    if (p && p.id !== lines[i].productId) {
      const du = defaultUnit(itemUnits, p.id);
      setLine(i, { name: p.name, unitPrice: du?.price ?? p.unitPrice, productId: p.id, unitCode: du?.unitCode ?? p.unitCode ?? "PCE" });
    }
    // Picked from the list again ("code · name"): keep only the name, so the item code never reaches the document.
    else setLine(i, { name: p ? p.name : name, productId: p ? p.id : null });
  }

  function payload() {
    return {
      customerId: customerId ? Number(customerId) : null,
      clientName: customerId ? null : clientName.trim() || null,
      issueDate: issueDate || null,
      validUntil: validUntil || null,
      status,
      notes: notes.trim() || null,
      terms: terms.trim() || null,
      lines: lines.filter((l) => l.name.trim()).map((l) => ({
        ...l,
        quantity: Number(l.quantity),
        unitPrice: Number(l.unitPrice),
        vatRate: Number(l.vatRate),
      })),
    };
  }

  async function act(name: string, fn: () => Promise<void>) {
    setBusy(name);
    setError(null);
    try {
      await fn();
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError(0, "network", t("Could not reach the server")));
      window.scrollTo({ top: 0, behavior: "smooth" });
    } finally {
      setBusy(null);
    }
  }

  /** Saves and returns the id (new quotations get their number on first save). */
  async function save(): Promise<number> {
    const saved = initial
      ? await api<Quotation>("PUT", `/quotations/${initial.id}`, payload())
      : await api<Quotation>("POST", "/quotations", payload());
    return saved.id;
  }

  return (
    <div className="space-y-6">
      {error && (
        <Alert tone="bad" title={t("Not saved")}>
          {error.fields && Object.keys(error.fields).length > 0
            ? <ul className="mt-1 list-disc ps-5">{Object.entries(error.fields).map(([f, m]) => <li key={f}>{m}</li>)}</ul>
            : error.message}
        </Alert>
      )}

      {initial && (
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" loading={busy === "pdf"} onClick={() => act("pdf", () => openFile(`/quotations/${initial.id}/pdf`))}>
            <FileDown className="size-4" aria-hidden /> PDF
          </Button>
          <Button variant="secondary" loading={busy === "print"} onClick={() => act("print", () => printFile(`/quotations/${initial.id}/pdf`))}>
            <Printer className="size-4" aria-hidden /> {t("Print")}
          </Button>
          <Button variant="secondary" title={t("For pre-printed letterhead paper")} onClick={() => act("print", () => printFile(`/quotations/${initial.id}/pdf?logo=false`))}>
            <Printer className="size-4" aria-hidden /> {t("Print without logo")}
          </Button>
          <Button variant="secondary" title={t("A request for payment before supply; not a tax invoice")} onClick={() => act("pdf", () => openFile(`/quotations/${initial.id}/pdf?proforma=true`))}>
            <FileText className="size-4" aria-hidden /> {t("Proforma invoice")}
          </Button>
          {/* Quotations go to a sales order (button in the page header), never straight to an invoice.
              Older quotations that were invoiced directly still link to that invoice. */}
          {initial.invoiceId != null && (
            <Link href={`/invoices/${initial.invoiceId}`} className="inline-flex items-center gap-2 rounded-lg border border-line bg-white px-3.5 py-2 text-sm font-medium">
              <FilePlus2 className="size-4" aria-hidden /> {t("Open its invoice")}
            </Link>
          )}
          <span className="ms-auto" />
          {!confirmDelete ? (
            <Button variant="danger" onClick={() => setConfirmDelete(true)}><Trash2 className="size-4" aria-hidden /> {t("Delete")}</Button>
          ) : (
            <>
              <Button variant="danger" loading={busy === "delete"}
                onClick={() => act("delete", async () => { await api("DELETE", `/quotations/${initial.id}`); router.push("/quotations"); })}>
                {t("Confirm delete")}
              </Button>
              <Button variant="ghost" onClick={() => setConfirmDelete(false)}>{t("Cancel")}</Button>
            </>
          )}
        </div>
      )}

      <Card>
        <CardHeader title={t("Client")} subtitle={t("A saved customer, or just the name of a new prospect.")} />
        <RequiredLegend className="px-5 pt-4" />
        <div className="grid gap-4 p-5 md:grid-cols-2">
          <Field
            label={t("Customer")}
            optional
            hint={
              <Link href="/customers?new=1" className="mt-1 inline-flex items-center gap-1 rounded-md bg-brand px-2.5 py-1 text-xs font-medium text-white shadow-sm hover:brightness-110">
                <UserPlus className="size-3.5" aria-hidden /> {t("Add a customer")}
              </Link>
            }
          >
            <Select value={customerId} onChange={(e) => setCustomerId(e.target.value)}>
              <option value="">{t("Not a saved customer")}</option>
              {customers?.map((c) => <option key={c.id} value={c.id}>{c.nameEn || c.nameAr}</option>)}
            </Select>
          </Field>
          {!customerId && (
            <Field label={t("Client name")}>
              <SuggestInput field="party" required value={clientName} onChange={(e) => setClientName(e.target.value)} placeholder={t("Company or person")} />
            </Field>
          )}
          <div className="grid grid-cols-3 gap-4 md:col-span-2">
            <Field label={t("Date")} optional hint={t("Leave empty for today")}><Input type="date" value={issueDate} onChange={(e) => setIssueDate(e.target.value)} /></Field>
            <Field label={t("Valid until")} optional><Input type="date" value={validUntil} onChange={(e) => setValidUntil(e.target.value)} /></Field>
            <Field label={t("Status")}>
              <Select value={status} onChange={(e) => setStatus(e.target.value as QuotationStatus)}>
                {(Object.keys(quotationStatusLabel) as QuotationStatus[]).map((s) => <option key={s} value={s}>{t(quotationStatusLabel[s])}</option>)}
              </Select>
            </Field>
          </div>
        </div>
      </Card>

      <Card>
        <CardHeader title={t("Items")} subtitle={t("Prices exclude VAT.")} />
        <div className="overflow-x-auto">
          <table className="w-full min-w-[760px] text-sm">
            <thead>
              <tr className="border-b border-line text-xs text-muted">
                <th className="px-3 py-2 text-start font-medium">{t("Item name")}<RequiredMark /></th>
                <th className="w-24 px-2 py-2 text-start font-medium">{t("Qty")}<RequiredMark /></th>
                <th className="w-28 px-2 py-2 text-start font-medium">{t("Unit")}</th>
                <th className="w-32 px-2 py-2 text-start font-medium">{t("Unit price")}<RequiredMark /></th>
                <th className="w-24 px-2 py-2 text-start font-medium">{t("VAT %")}</th>
                <th className="w-32 px-3 py-2 text-end font-medium">{t("Line total")}</th>
                <th className="w-10" />
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {lines.map((l, i) => (
                <tr key={i} className="align-top">
                  <td className="px-3 py-2">
                    <Input list="quotation-products" dir="auto" value={l.name} onChange={(e) => setItemName(i, e.target.value)} placeholder={t("Pick a product or type an item name")} aria-label={t("Item {n} name", { n: i + 1 })} />
                  </td>
                  <td className="px-2 py-2"><Input type="number" min="0" step="any" value={l.quantity} onChange={(e) => setLine(i, { quantity: e.target.value })} aria-label={t("Quantity")} /></td>
                  <td className="px-2 py-2">
                    <UnitSelect productId={l.productId} value={l.unitCode} onPick={(u, price) => setLine(i, price == null ? { unitCode: u } : { unitCode: u, unitPrice: price })} />
                  </td>
                  <td className="px-2 py-2"><Input type="number" min="0" step="any" value={l.unitPrice} onChange={(e) => setLine(i, { unitPrice: e.target.value })} aria-label={t("Unit price")} /></td>
                  <td className="px-2 py-2">
                    <Select value={String(Number(l.vatRate))} onChange={(e) => setLine(i, { vatRate: e.target.value })} aria-label={t("VAT rate")}>
                      <option value="15">15%</option>
                      <option value="0">0%</option>
                    </Select>
                  </td>
                  <td className="tabular px-3 py-2 text-end">
                    <span className="block pt-2 font-medium">{money(sum.rows[i]?.gross)}</span>
                    <span className="block text-xs text-muted">{t("VAT")} {money(sum.rows[i]?.vat)}</span>
                  </td>
                  <td className="py-2 pe-2">
                    <button type="button" onClick={() => setLines((ls) => (ls.length > 1 ? ls.filter((_, idx) => idx !== i) : ls))}
                      className="mt-1.5 rounded p-1.5 text-muted hover:bg-canvas hover:text-bad disabled:opacity-30" disabled={lines.length === 1} aria-label={t("Remove item {n}", { n: i + 1 })}>
                      <Trash2 className="size-4" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <datalist id="quotation-products">
          {products?.map((p) => (
            <option key={p.id} value={p.number ? `${p.number} · ${p.name}` : p.name}>{money(p.unitPrice)}</option>
          ))}
        </datalist>
        <div className="flex flex-wrap items-start justify-between gap-6 border-t border-line p-5">
          <Button variant="secondary" onClick={() => setLines((ls) => [...ls, emptyLine()])}><Plus className="size-4" aria-hidden /> {t("Add item")}</Button>
          <dl className="tabular w-full max-w-xs space-y-1.5 text-sm">
            <div className="flex justify-between"><dt className="text-muted">{t("Total excl. VAT")}</dt><dd>{money(sum.net)}</dd></div>
            <div className="flex justify-between"><dt className="text-muted">{t("VAT")}</dt><dd>{money(sum.vat)}</dd></div>
            <div className="flex justify-between border-t border-line pt-2 text-base font-semibold"><dt>{t("Total incl. VAT")}</dt><dd>{money(sum.total)}</dd></div>
          </dl>
        </div>
      </Card>

      <Card className="grid gap-4 p-5 md:grid-cols-2">
        <Field label={t("Notes")} optional hint={t("Printed on the quotation")}>
          <textarea dir="auto" rows={4} className="w-full rounded-lg border border-line bg-white px-3 py-2 text-sm outline-none focus:border-brand" value={notes} onChange={(e) => setNotes(e.target.value)} />
        </Field>
        <Field label={t("Terms & conditions")} optional hint={t("Printed on the quotation")}>
          <textarea dir="auto" rows={4} className="w-full rounded-lg border border-line bg-white px-3 py-2 text-sm outline-none focus:border-brand" value={terms} onChange={(e) => setTerms(e.target.value)} />
        </Field>
      </Card>

      <div className="sticky bottom-0 -mx-4 flex flex-wrap items-center justify-end gap-2 border-t border-line bg-canvas/95 px-4 py-4 backdrop-blur md:-mx-8 md:px-8">
        <p className="me-auto text-xs text-muted">{t("A quotation is a price offer only: it is never sent to ZATCA. Copy it to a sales order when the client confirms.")}</p>
        <Button loading={busy === "save"} disabled={busy != null}
          onClick={() => act("save", async () => { const id = await save(); router.push(`/quotations/${id}`); router.refresh(); })}>
          <Save className="size-4" aria-hidden /> {t("Save quotation")}
        </Button>
      </div>
    </div>
  );
}
