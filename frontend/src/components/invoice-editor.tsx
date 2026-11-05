"use client";

import Link from "next/link";
import { Fragment, useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import { Banknote, CheckCircle2, Clock, PackagePlus, Plus, Save, Send, Trash2, UserPlus } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { Customer, InvoiceDetail, InvoiceKind, Line, PaymentMethod, Product, SendMode, VatCategory } from "@/lib/types";
import { exemptionCodes, lineTotals, money, todayKsa, typeLabel, unitCodes } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Field, Input, RequiredLegend, RequiredMark, Select } from "@/components/ui";
import { Trans, useLanguage, useT } from "@/lib/i18n";
import { CustomerPicker, DictateButton } from "@/components/voice";
import { parseInvoiceSpeech, takePendingInvoice, VOICE_INVOICE_EVENT } from "@/lib/voice-parse";

const emptyLine = (): Line => ({ name: "", quantity: 1, unitCode: "PCE", unitPrice: "", vatCategory: "S", vatRate: 15 });

const paymentMeans = [
  { code: "10", label: "Cash" },
  { code: "48", label: "Bank card" },
  { code: "30", label: "Credit transfer" },
  { code: "42", label: "Payment to bank account" },
  { code: "1", label: "Not defined" },
];

/** How the payment means on the invoice maps to the internal payment method. */
const methodFor: Record<string, PaymentMethod> = { "10": "CASH", "48": "CARD", "30": "BANK_TRANSFER", "42": "BANK_TRANSFER" };

/** Money received with the invoice: all of it, or part of it. Recorded when the invoice is issued. */
type PaidNow = { full: true } | { full: false; amount: number };

function PayNowCard({ total, paid, onChange }: { total: number; paid: PaidNow | null; onChange: (p: PaidNow | null) => void }) {
  const t = useT();
  const [amount, setAmount] = useState("");
  const value = Number(amount);
  const tooMuch = value > total;
  const paidAmount = paid == null ? 0 : paid.full ? total : Math.min(paid.amount, total);
  const due = Math.max(total - paidAmount, 0);

  return (
    <Card>
      <CardHeader title={t("Payment")} subtitle={t("Money received now. Recorded on the invoice when you issue it; nothing here goes to ZATCA.")} />
      <div className="space-y-4 p-5">
        <div className="flex flex-wrap items-end gap-3">
          <Button
            type="button"
            variant="success"
            className={paid?.full ? "ring-2 ring-ok/30 ring-offset-2" : ""}
            disabled={total <= 0}
            onClick={() => onChange(paid?.full ? null : { full: true })}
          >
            <CheckCircle2 className="size-4" aria-hidden /> {paid?.full ? `${t("Full paid")} ✓` : t("Full paid")}
          </Button>
          <span className="pb-2 text-sm text-muted">{t("or")}</span>
          <Field label={t("Amount received (SAR)")} optional hint={t("Up to {amount}", { amount: money(total) })} error={tooMuch ? t("More than the invoice total") : undefined} className="w-52">
            <Input type="number" min="0.01" step="0.01" max={total} value={amount} onChange={(e) => setAmount(e.target.value)} />
          </Field>
          <Button
            type="button"
            disabled={!(value > 0) || tooMuch}
            onClick={() => { onChange(value >= total ? { full: true } : { full: false, amount: value }); setAmount(""); }}
          >
            <Banknote className="size-4" aria-hidden /> {t("Paid now")}
          </Button>
          <Field label={t("Due (SAR)")} className="w-44">
            <Input readOnly tabIndex={-1} value={due.toFixed(2)} className={`tabular font-semibold ${due > 0 ? "text-bad" : "text-ok"}`} />
          </Field>
          {paid && <Button type="button" variant="ghost" onClick={() => onChange(null)}>{t("Reset")}</Button>}
        </div>
      </div>
    </Card>
  );
}

/**
 * Shown beside an item name that isn't in the product list yet: saves it with the line's unit price
 * and links the line to the new product.
 */
function AddProductButton({ line, onCreated }: { line: Line; onCreated: (p: Product) => void }) {
  const t = useT();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const priced = line.unitPrice !== "" && Number(line.unitPrice) >= 0;

  async function save() {
    setBusy(true);
    setError(null);
    try {
      onCreated(await api<Product>("POST", "/products", { name: line.name.trim(), unitPrice: Number(line.unitPrice) }));
    } catch (e) {
      const err = e instanceof ApiError ? e : null;
      setError(err?.fields ? Object.values(err.fields).join(", ") : err?.message ?? t("Could not reach the server"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Button
        type="button"
        className="shrink-0 whitespace-nowrap px-2.5 text-xs"
        loading={busy}
        disabled={!priced}
        title={priced ? t("Save this item to the product list") : t("Enter the unit price first")}
        onClick={save}
      >
        <PackagePlus className="size-3.5" aria-hidden /> {t("Add product")}
      </Button>
      {error && <span className="mt-1 block w-full text-[11px] text-bad">{error}</span>}
    </>
  );
}

export function InvoiceEditor({ initial }: { initial?: InvoiceDetail }) {
  const router = useRouter();
  const t = useT();
  const { lang } = useLanguage();
  const { data: customers } = useSWR<Customer[]>("/customers", fetcher);
  const { data: products, mutate: mutateProducts } = useSWR<Product[]>("/products", fetcher);
  const isNote = initial != null && initial.type !== "INVOICE";

  const [kind, setKind] = useState<InvoiceKind>(initial?.kind ?? "SIMPLIFIED");
  const [customerId, setCustomerId] = useState<string>(initial?.customerId ? String(initial.customerId) : "");
  const [supplyDate, setSupplyDate] = useState(initial?.supplyDate ?? todayKsa());
  const [paidNow, setPaidNow] = useState<PaidNow | null>(null);
  const [payment, setPayment] = useState(initial?.paymentMeans ?? "10");
  const [reason, setReason] = useState(initial?.reason ?? "");
  const [lines, setLines] = useState<Line[]>(initial?.lines.length ? initial.lines : [emptyLine()]);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<{ err: ApiError; saved: boolean } | null>(null);
  // Once saved, retries update the same draft instead of creating another one.
  const [draftId, setDraftId] = useState<number | null>(initial?.id ?? null);
  /** What the last spoken invoice filled in, shown until the next one. */
  const [voiceNote, setVoiceNote] = useState<{ heard: string; filled: string[] } | null>(null);

  const totals = useMemo(() => lineTotals(lines), [lines]);
  const customer = customers?.find((c) => String(c.id) === customerId);

  function setLine(i: number, patch: Partial<Line>) {
    setLines((ls) => ls.map((l, idx) => (idx === i ? { ...l, ...patch } : l)));
  }

  /** Typing or picking a description: an exact product name or item code fills in its price; anything else is free text. */
  function setDescription(i: number, name: string) {
    const key = name.trim().toLowerCase();
    const p = products?.find((x) => x.name.toLowerCase() === key || (x.number != null && x.number.toLowerCase() === key));
    if (p && p.id !== lines[i].productId) setLine(i, { name: p.name, unitPrice: p.unitPrice, productId: p.id });
    else setLine(i, { name, productId: p ? p.id : null });
  }

  function productCreated(i: number, p: Product) {
    mutateProducts();
    setLine(i, { name: p.name, unitPrice: p.unitPrice, productId: p.id });
  }

  /**
   * A spoken (or example) invoice: sets what it names and adds its items after the lines already
   * typed. Nothing is saved or issued; staff check the invoice and issue it themselves.
   */
  const applyVoice = useCallback((text: string) => {
    const v = parseInvoiceSpeech(text, customers ?? [], products ?? []);
    const filled: string[] = [];
    if (!isNote) {
      if (v.kind) { setKind(v.kind); filled.push(t(v.kind === "STANDARD" ? "Business customer" : "Individual customer")); }
      if (v.customer) { setCustomerId(String(v.customer.id)); filled.push(v.customer.nameEn || v.customer.nameAr || ""); }
      if (v.fullPaid) { setPaidNow({ full: true }); filled.push(t("Full paid")); }
    }
    if (v.paymentMeans) { setPayment(v.paymentMeans); filled.push(t(paymentMeans.find((p) => p.code === v.paymentMeans)!.label)); }
    if (v.lines.length) {
      const added = v.lines.map((l): Line => ({
        ...emptyLine(),
        name: l.name,
        quantity: l.quantity,
        unitPrice: l.unitPrice ?? l.product?.unitPrice ?? "",
        productId: l.product?.id ?? null,
      }));
      setLines((ls) => [...ls.filter((l) => l.name.trim() || l.unitPrice !== ""), ...added]);
      filled.push(t("{n} items", { n: added.length }));
    }
    setVoiceNote({ heard: text, filled });
  }, [customers, products, isNote, t]);

  // An invoice spoken in the voice assistant: waiting when this page opens, or arriving while it is open.
  const listsLoaded = customers != null && products != null;
  useEffect(() => {
    if (!listsLoaded || initial) return;
    const pick = () => { const text = takePendingInvoice(); if (text) applyVoice(text); };
    pick();
    window.addEventListener(VOICE_INVOICE_EVENT, pick);
    return () => window.removeEventListener(VOICE_INVOICE_EVENT, pick);
  }, [listsLoaded, initial, applyVoice]);

  const isKnownProduct = (name: string) => products?.some((p) => p.name.toLowerCase() === name.trim().toLowerCase()) ?? true;

  function payload() {
    return {
      kind,
      type: initial?.type ?? "INVOICE",
      customerId: customerId ? Number(customerId) : null,
      supplyDate: supplyDate || null,
      paymentMeans: payment,
      sendMode: "NOW",
      originalInvoiceId: initial?.originalInvoiceId ?? null,
      reason: reason || null,
      internalNote: initial?.internalNote ?? null,
      dueDate: initial?.dueDate ?? null,
      lines: lines.map((l) => ({
        ...l,
        quantity: Number(l.quantity),
        unitPrice: Number(l.unitPrice),
        vatRate: l.vatCategory === "S" ? Number(l.vatRate) : 0,
        exemptionCode: l.vatCategory === "S" ? null : l.exemptionCode,
        exemptionReason: l.vatCategory === "S" ? null : exemptionCodes.find((e) => e.code === l.exemptionCode)?.label ?? l.exemptionReason,
      })),
    };
  }

  async function save(): Promise<number> {
    const body = payload();
    const saved = draftId
      ? await api<InvoiceDetail>("PUT", `/invoices/${draftId}`, body)
      : await api<InvoiceDetail>("POST", "/invoices", body);
    setDraftId(saved.id);
    return saved.id;
  }

  /** After issuing: the full amount (whatever ZATCA rounding gives) or the part received. */
  async function recordPayment(id: number) {
    const inv = await api<InvoiceDetail>("GET", `/invoices/${id}`);
    const amount = paidNow!.full ? Number(inv.dueAmount ?? inv.totalInclVat) : Math.min(paidNow!.amount, Number(inv.dueAmount ?? inv.totalInclVat));
    if (!(amount > 0)) return;
    await api("POST", `/invoices/${id}/payments`, {
      amount: Number(amount.toFixed(2)),
      paidOn: todayKsa(),
      method: methodFor[payment] ?? "OTHER",
      reference: null,
      note: paidNow!.full ? "Full paid" : "Partial payment",
    });
  }

  async function run(action: "draft" | SendMode) {
    setBusy(action);
    setError(null);
    let saved = false;
    try {
      const id = await save();
      saved = true;
      if (action !== "draft") {
        await api("POST", `/invoices/${id}/issue`, { sendMode: action });
        if (paidNow && !isNote) {
          // The invoice is issued either way; if the payment can't be saved, its page says so.
          const ok = await recordPayment(id).then(() => true, () => false);
          if (!ok) {
            router.push(`/invoices/${id}?payment=failed`);
            return;
          }
        }
      }
      router.push(`/invoices/${id}`);
      router.refresh();
    } catch (e) {
      const err = e instanceof ApiError ? e : new ApiError(0, "network", t("Could not reach the server"));
      setError({ err, saved });
      setBusy(null);
      window.scrollTo({ top: 0, behavior: "smooth" });
    }
  }

  const standard = kind === "STANDARD";

  return (
    <div className="space-y-6">
      {error && (
        <Alert
          tone={error.saved ? "warn" : "bad"}
          title={error.saved ? t("Saved as draft, but not issued") : t("Not saved")}
        >
          {error.err.message}
          {error.err.fields && (
            <ul className="mt-1 list-disc ps-5">
              {Object.entries(error.err.fields).map(([f, m]) => <li key={f}>{f}: {m}</li>)}
            </ul>
          )}
          {error.saved && error.err.code === "not_issuable" && /Customer/.test(error.err.message) && (
            <p className="mt-2">
              <Trans
                k="Nothing was sent to ZATCA and no invoice number was used. Fix the customer under {link}, then issue this draft again."
                values={{ link: <Link href="/customers" className="font-medium underline">{t("Customers")}</Link> }}
              />
            </p>
          )}
        </Alert>
      )}

      {voiceNote && (
        <Alert tone={voiceNote.filled.length ? "ok" : "warn"} title={voiceNote.filled.length ? t("Filled in by voice") : t("Nothing was filled in")}>
          <span className="block" dir="auto">“{voiceNote.heard}”</span>
          {voiceNote.filled.length > 0
            ? <span className="mt-1 block">{voiceNote.filled.join(" · ")}. {t("Check the customer, items and prices before issuing.")}</span>
            : <span className="mt-1 block">{t("Name the customer and the items, e.g. “for Al Noor, 5 cement at 20 and 2 sand”.")}</span>}
        </Alert>
      )}

      <Card>
        <CardHeader title={t("Document")} subtitle={isNote ? t("{type} for invoice {number}", { type: t(typeLabel[initial!.type]), number: initial!.billingReference }) : undefined} />
        <RequiredLegend className="px-5 pt-4" />
        <div className="grid gap-5 p-5 md:grid-cols-2">
          <fieldset disabled={isNote} className="md:col-span-2">
            <legend className="mb-2 text-xs font-medium text-muted">{t("Invoice type")}</legend>
            <div className="grid gap-3 sm:grid-cols-2">
              {([
                ["SIMPLIFIED", "Individual customer", "عميل فرد"],
                ["STANDARD", "Business customer", "عميل شركة"],
              ] as const).map(([value, en, ar]) => (
                <label
                  key={value}
                  className={`cursor-pointer rounded-lg border p-4 transition ${kind === value ? "border-brand bg-brand/5 ring-2 ring-brand/15" : "border-line hover:border-brand/40"}`}
                >
                  <input type="radio" name="kind" value={value} checked={kind === value} onChange={() => setKind(value)} className="sr-only" />
                  <span className="flex items-baseline justify-between gap-2">
                    <span className="text-sm font-semibold">{t(en)}</span>
                    {lang !== "ar" && <span className="ar text-sm text-muted" dir="rtl">{ar}</span>}
                  </span>
                </label>
              ))}
            </div>
          </fieldset>

          <Field
            label={t("Customer")}
            required={standard}
            optional={!standard}
            hint={
              <Link
                href="/customers?new=1"
                className="mt-1 inline-flex items-center gap-1 rounded-md bg-brand px-2.5 py-1 text-xs font-medium text-white shadow-sm hover:brightness-110"
              >
                <UserPlus className="size-3.5" aria-hidden /> {t("Add a customer")}
              </Link>
            }
          >
            <CustomerPicker
              customers={customers}
              value={customerId}
              onChange={setCustomerId}
              emptyLabel={standard ? t("Select a customer…") : t("Walk-in customer")}
              disabled={isNote}
            />
          </Field>
          <div className="grid grid-cols-2 gap-4">
            <Field label={t("Supply date")} optional hint={t("Leave empty for the issue date")}>
              <Input type="date" value={supplyDate} onChange={(e) => setSupplyDate(e.target.value)} />
            </Field>
            <Field label={t("Payment method")}>
              <Select value={payment} onChange={(e) => setPayment(e.target.value)}>
                {paymentMeans.map((p) => <option key={p.code} value={p.code}>{t(p.label)}</option>)}
              </Select>
            </Field>
          </div>
          {standard && customer && !customer.vatNumber && !customer.otherId && (
            <div className="md:col-span-2">
              <Alert tone="warn">{t("This customer has no VAT number or other ID. ZATCA requires one for a tax invoice. Edit the customer first.")}</Alert>
            </div>
          )}
          {isNote && (
            <Field label={t("Reason for this note")} className="md:col-span-2">
              <Input value={reason} onChange={(e) => setReason(e.target.value)} placeholder={t("e.g. Returned goods, price correction")} required />
            </Field>
          )}
        </div>
      </Card>

      <Card>
        <CardHeader title={t("Lines")} subtitle={t("Prices exclude VAT. Amounts are rounded exactly as ZATCA validates them.")} />
        <div className="overflow-x-auto">
          <table className="w-full min-w-[860px] text-sm">
            <thead>
              <tr className="border-b border-line text-xs text-muted">
                <th className="px-3 py-2 text-start font-medium">{t("Item name")}<RequiredMark /></th>
                <th className="w-24 px-2 py-2 text-start font-medium">{t("Qty")}<RequiredMark /></th>
                <th className="w-28 px-2 py-2 text-start font-medium">{t("Unit")}</th>
                <th className="w-32 px-2 py-2 text-start font-medium">{t("Unit price")}<RequiredMark /></th>
                <th className="w-44 px-2 py-2 text-start font-medium">{t("VAT")}</th>
                <th className="w-32 px-3 py-2 text-end font-medium">{t("Line total")}</th>
                <th className="w-10" />
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {lines.map((l, i) => (
                <tr key={i} className="align-top">
                  <td className="px-3 py-2">
                    <div className="flex flex-wrap items-start gap-2">
                      <Input
                        list="product-options"
                        dir="auto"
                        className="min-w-0 flex-1"
                        value={l.name}
                        onChange={(e) => setDescription(i, e.target.value)}
                        placeholder={t("Pick a product or type an item name")}
                        aria-label={t("Item {n} name", { n: i + 1 })}
                      />
                      {l.name.trim() && !isKnownProduct(l.name) && (
                        <AddProductButton line={l} onCreated={(p) => productCreated(i, p)} />
                      )}
                    </div>
                    {l.productId != null && <span className="mt-1 block text-[11px] text-muted">{t("From products · price still editable")}</span>}
                  </td>
                  <td className="px-2 py-2">
                    <Input type="number" min="0" step="any" value={l.quantity} onChange={(e) => setLine(i, { quantity: e.target.value })} aria-label={t("Quantity")} />
                  </td>
                  <td className="px-2 py-2">
                    <Select value={l.unitCode} onChange={(e) => setLine(i, { unitCode: e.target.value })} aria-label={t("Unit")}>
                      {unitCodes.map((u) => <option key={u.code} value={u.code}>{t(u.label)}</option>)}
                    </Select>
                  </td>
                  <td className="px-2 py-2">
                    <Input type="number" min="0" step="any" value={l.unitPrice} onChange={(e) => setLine(i, { unitPrice: e.target.value })} aria-label={t("Unit price")} />
                  </td>
                  <td className="space-y-1.5 px-2 py-2">
                    <Select
                      value={l.vatCategory}
                      onChange={(e) => {
                        const cat = e.target.value as VatCategory;
                        setLine(i, { vatCategory: cat, vatRate: cat === "S" ? 15 : 0, exemptionCode: exemptionCodes.find((x) => x.category === cat)?.code ?? null });
                      }}
                      aria-label={t("VAT category")}
                    >
                      <option value="S">{t("Standard 15%")}</option>
                      <option value="Z">{t("Zero-rated 0%")}</option>
                      <option value="E">{t("Exempt")}</option>
                      <option value="O">{t("Out of scope")}</option>
                    </Select>
                    {l.vatCategory !== "S" && (
                      <Select value={l.exemptionCode ?? ""} onChange={(e) => setLine(i, { exemptionCode: e.target.value })} aria-label={t("Exemption reason")}>
                        {exemptionCodes.filter((x) => x.category === l.vatCategory).map((x) => (
                          <option key={x.code} value={x.code}>{t(x.label)}</option>
                        ))}
                      </Select>
                    )}
                  </td>
                  <td className="tabular px-3 py-2 text-end">
                    <span className="block pt-2 font-medium">{money(totals.rows[i]?.gross)}</span>
                    <span className="block text-xs text-muted">{t("VAT")} {money(totals.rows[i]?.vat)}</span>
                  </td>
                  <td className="py-2 pe-2">
                    <button
                      type="button"
                      onClick={() => setLines((ls) => (ls.length > 1 ? ls.filter((_, idx) => idx !== i) : ls))}
                      className="mt-1.5 rounded p-1.5 text-muted hover:bg-canvas hover:text-bad disabled:opacity-30"
                      disabled={lines.length === 1}
                      aria-label={t("Remove line {n}", { n: i + 1 })}
                    >
                      <Trash2 className="size-4" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <datalist id="product-options">
          {products?.map((p) => (
            <Fragment key={p.id}>
              <option value={p.name}>{p.number ? `${p.number} · ${money(p.unitPrice)}` : money(p.unitPrice)}</option>
              {p.number && <option value={p.number}>{p.name}</option>}
            </Fragment>
          ))}
        </datalist>
        <div className="flex flex-wrap items-start justify-between gap-6 border-t border-line p-5">
          <div className="flex max-w-2xl flex-1 flex-wrap gap-2">
            <Button variant="secondary" onClick={() => setLines((ls) => [...ls, emptyLine()])}>
              <Plus className="size-4" aria-hidden /> {t("Add item")}
            </Button>
            <DictateButton onText={applyVoice}>{t("Speak the invoice")}</DictateButton>
          </div>
          <dl className="tabular w-full max-w-xs space-y-1.5 text-sm">
            <div className="flex justify-between"><dt className="text-muted">{t("Total excl. VAT")}</dt><dd>{money(totals.net)}</dd></div>
            <div className="flex justify-between"><dt className="text-muted">{t("VAT")}</dt><dd>{money(totals.vat)}</dd></div>
            <div className="flex justify-between border-t border-line pt-2 text-base font-semibold"><dt>{t("Total incl. VAT")}</dt><dd>{money(totals.total)}</dd></div>
          </dl>
        </div>
      </Card>

      {!isNote && <PayNowCard total={totals.total} paid={paidNow} onChange={setPaidNow} />}

      <div className="sticky bottom-0 -mx-4 flex flex-wrap items-center justify-end gap-2 border-t border-line bg-canvas/95 px-4 py-4 backdrop-blur md:-mx-8 md:px-8">
        <p className="me-auto text-xs text-muted">
          {standard
            ? t("Standard invoices stay a draft until you send them. ZATCA must clear them before they are valid.")
            : t("Issuing assigns the legal number and signs the invoice. Choose when it goes to ZATCA.")}
        </p>
        <Button variant="secondary" loading={busy === "draft"} disabled={busy != null} onClick={() => run("draft")}>
          <Save className="size-4" aria-hidden /> {t("Save draft")}
        </Button>
        {!standard && (
          <Button variant="secondary" loading={busy === "LATER"} disabled={busy != null} onClick={() => run("LATER")}>
            <Clock className="size-4" aria-hidden /> {t("Issue · send to ZATCA later")}
          </Button>
        )}
        <Button loading={busy === "NOW"} disabled={busy != null} onClick={() => run("NOW")}>
          <Send className="size-4" aria-hidden /> {standard ? t("Send to ZATCA for clearance") : t("Issue · send to ZATCA now")}
        </Button>
      </div>
    </div>
  );
}
