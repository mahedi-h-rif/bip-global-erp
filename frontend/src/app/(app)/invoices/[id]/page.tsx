"use client";

import { use, useState } from "react";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import { Truck, CheckCircle2, Clock, FileCode2, FileDown, FileMinus2, FilePlus2, Lock, Printer, Send, Trash2, XCircle } from "lucide-react";
import { api, ApiError, download, fetcher, printFile } from "@/lib/api";
import type { InvoiceDetail, Me } from "@/lib/types";
import { countdown, date, dateTime, kindLabel, money, typeLabel } from "@/lib/format";
import { Alert, Button, ButtonLink, Card, CardHeader, Field, Input, PageHeader, Spinner, StatusBadge } from "@/components/ui";
import { InvoiceEditor } from "@/components/invoice-editor";
import { PaymentsCard } from "@/components/payments-card";
import { EditDeleteActions } from "@/components/invoice-actions";
import { WhatsAppInvoiceButton } from "@/components/whatsapp";
import { useT } from "@/lib/i18n";

type ZatcaMessage = { code?: string; message?: string; category?: string };

function parseMessages(json: string | null): ZatcaMessage[] {
  if (!json) return [];
  try {
    const v = JSON.parse(json);
    return Array.isArray(v) ? v : [{ message: String(json) }];
  } catch {
    return [{ message: json }];
  }
}

export default function InvoicePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const router = useRouter();
  const t = useT();
  const { data: inv, error, mutate } = useSWR<InvoiceDetail>(`/invoices/${id}`, fetcher);
  const { data: me } = useSWR<Me>("/auth/me", fetcher);
  const canWrite = me != null && me.role !== "VIEWER";
  const [busy, setBusy] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [noteType, setNoteType] = useState<"CREDIT_NOTE" | "DEBIT_NOTE" | null>(null);
  const [noteReason, setNoteReason] = useState("");
  // Set by the invoice form when the invoice was issued but the payment entered there could not be saved.
  const [paymentFailed] = useState(() => typeof window !== "undefined" && new URLSearchParams(window.location.search).get("payment") === "failed");

  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!inv) return <Spinner />;

  async function act(name: string, fn: () => Promise<void>) {
    setBusy(name);
    setActionError(null);
    try {
      await fn();
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : t("Could not reach the server"));
    } finally {
      setBusy(null);
    }
  }

  if (inv.status === "DRAFT") {
    return (
      <>
        <PageHeader
          title={inv.type === "INVOICE" ? t("Draft #{id}", { id: inv.id }) : t("{type} draft #{id}", { type: t(typeLabel[inv.type]), id: inv.id })}
          subtitle={t("Drafts have no number and are not sent to ZATCA until you issue them.")}
          actions={
            <Button
              variant="danger"
              loading={busy === "delete"}
              onClick={() => act("delete", async () => {
                await api("DELETE", `/invoices/${inv.id}`);
                router.push("/invoices");
              })}
            >
              <Trash2 className="size-4" aria-hidden /> {t("Delete draft")}
            </Button>
          }
        />
        {actionError && <div className="mb-4"><Alert tone="bad">{actionError}</Alert></div>}
        <InvoiceEditor initial={inv} />
      </>
    );
  }

  const accepted = ["CLEARED", "CLEARED_WITH_WARNINGS", "REPORTED", "REPORTED_WITH_WARNINGS"].includes(inv.status);
  const warnings = parseMessages(inv.zatcaWarnings);
  const errors = parseMessages(inv.zatcaErrors);
  const deadline = countdown(inv.reportDeadlineAt);

  return (
    <>
      <PageHeader
        title={inv.number ?? `#${inv.id}`}
        subtitle={<span className="inline-flex flex-wrap items-center gap-2"><StatusBadge status={inv.status} /> {t(typeLabel[inv.type])} · {t(kindLabel[inv.kind])}</span>}
        actions={
          <>
            <Button
              variant="secondary"
              disabled={!inv.downloadable}
              title={inv.downloadable ? undefined : t("Available once ZATCA clears this invoice")}
              loading={busy === "pdf"}
              onClick={() => act("pdf", () => download(`/invoices/${inv.id}/pdf`, `${inv.number}.pdf`))}
            >
              {inv.downloadable ? <FileDown className="size-4" aria-hidden /> : <Lock className="size-4" aria-hidden />} PDF
            </Button>
            <Button
              variant="secondary"
              disabled={!inv.downloadable}
              loading={busy === "print"}
              onClick={() => act("print", () => printFile(`/invoices/${inv.id}/pdf`))}
            >
              <Printer className="size-4" aria-hidden /> {t("Print")}
            </Button>
            <Button
              variant="secondary"
              disabled={!inv.downloadable}
              loading={busy === "xml"}
              onClick={() => act("xml", () => download(`/invoices/${inv.id}/xml`, `${inv.number}.xml`))}
            >
              <FileCode2 className="size-4" aria-hidden /> XML
            </Button>
            {canWrite && <WhatsAppInvoiceButton invoiceId={inv.id} disabled={!inv.downloadable} />}
            {inv.status === "SIGNED" && (
              <Button loading={busy === "send"} onClick={() => act("send", async () => { await api("POST", `/invoices/${inv.id}/submit`); await mutate(); })}>
                <Send className="size-4" aria-hidden /> {t("Send to ZATCA now")}
              </Button>
            )}
            {canWrite && <EditDeleteActions doc={inv} onChanged={() => mutate()} />}
          </>
        }
      />

      <div className="space-y-4">
        {actionError && <Alert tone="bad">{actionError}</Alert>}
        {paymentFailed && Number(inv.paidAmount ?? 0) === 0 && (
          <Alert tone="warn" title={t("Payment not recorded")}>{t("The invoice was issued, but the payment you entered wasn't saved. Record it again under Payments below.")}</Alert>
        )}

        <p className="flex items-center gap-1.5 text-xs text-muted">
          <Lock className="size-3.5" aria-hidden />
          {inv.test
            ? t("Test invoice from the ZATCA sandbox: not a legal invoice. Delete it when you no longer need it.")
            : t("ZATCA keeps every issued invoice, so Edit and Delete work through a credit note that cancels it.")}
        </p>

        {inv.status === "SIGNED" && inv.kind === "SIMPLIFIED" && deadline && (
          <Alert tone={deadline.overdue ? "bad" : "warn"} title={deadline.overdue ? t("Reporting deadline passed") : t("Waiting to be sent to ZATCA")}>
            {deadline.overdue
              ? t("This invoice is past the 24-hour reporting limit. Send it now.")
              : inv.scheduledSubmitAt
                ? t("It is valid and can be given to the customer. It will be sent automatically around {when} (deadline in {time}), or send it now.", { when: dateTime(inv.scheduledSubmitAt), time: deadline.time })
                : t("It is valid and can be given to the customer. It goes to ZATCA only when you click “Send to ZATCA now”. ZATCA requires it within {time}.", { time: deadline.time })}
          </Alert>
        )}
        {inv.status === "SIGNED" && inv.lastError && (
          <Alert tone="warn" title={t("ZATCA could not be reached, retrying automatically")}>
            {t("Attempt {n}:", { n: inv.submitAttempts })} {inv.lastError}
          </Alert>
        )}
        {inv.status === "SIGNED" && inv.kind === "STANDARD" && (
          <Alert tone="warn" title={t("Not yet cleared")}>{t("Do not share this invoice with the buyer until ZATCA clears it.")}</Alert>
        )}
        {inv.status === "REJECTED" && (
          <Alert tone="bad" title={t("ZATCA rejected this document")}>
            <ul className="list-disc ps-5">
              {errors.map((m, i) => <li key={i}>{m.code && <b>{m.code}: </b>}{m.message}</li>)}
            </ul>
            <p className="mt-2">{t("It remains in the device's invoice chain. Use “Correct & reissue” to issue a fixed copy with a new number.")}</p>
          </Alert>
        )}
        {warnings.length > 0 && (
          <Alert tone="warn" title={t("Accepted with warnings")}>
            <ul className="list-disc ps-5">
              {warnings.map((m, i) => <li key={i}>{m.code && <b>{m.code}: </b>}{m.message}</li>)}
            </ul>
          </Alert>
        )}

        <div className="grid gap-4 lg:grid-cols-3">
          <Card className="lg:col-span-2">
            <CardHeader title={t("Lines")} />
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-line text-xs text-muted">
                    <th className="px-4 py-2 text-start font-medium">{t("Description")}</th>
                    <th className="px-4 py-2 text-end font-medium">{t("Qty")}</th>
                    <th className="px-4 py-2 text-end font-medium">{t("Unit price")}</th>
                    <th className="px-4 py-2 text-end font-medium">{t("VAT")}</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line">
                  {inv.lines.map((l, i) => (
                    <tr key={i}>
                      <td className="px-4 py-2.5" dir="auto">{l.name}</td>
                      <td className="tabular px-4 py-2.5 text-end">{Number(l.quantity)} {l.unitCode}</td>
                      <td className="tabular px-4 py-2.5 text-end">{money(l.unitPrice)}</td>
                      <td className="px-4 py-2.5 text-end">{l.vatCategory === "S" ? `${Number(l.vatRate)}%` : l.vatCategory}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <dl className="tabular ms-auto max-w-xs space-y-1.5 border-t border-line p-5 text-sm">
              <div className="flex justify-between"><dt className="text-muted">{t("Total excl. VAT")}</dt><dd>{money(inv.totalExclVat)}</dd></div>
              <div className="flex justify-between"><dt className="text-muted">{t("VAT")}</dt><dd>{money(inv.vatTotal)}</dd></div>
              <div className="flex justify-between border-t border-line pt-2 text-base font-semibold"><dt>{t("Total")}</dt><dd>{money(inv.totalInclVat)}</dd></div>
            </dl>
          </Card>

          <Card>
            <CardHeader title={t("Details")} />
            <dl className="space-y-3 p-5 text-sm">
              {[
                [t("Customer"), inv.customerName ?? t("Walk-in customer")],
                [t("Issued"), dateTime(inv.issuedAt)],
                [t("Supply date"), date(inv.supplyDate)],
                [t("Original invoice"), inv.billingReference],
                [t("Reason"), inv.reason],
                [t("Sent to ZATCA"), dateTime(inv.submittedAt)],
                [t("Invoice counter (ICV)"), inv.icv],
                ["UUID", inv.uuid],
                [t("Created by"), inv.createdBy],
                [t("Internal note"), inv.internalNote],
              ].filter(([, v]) => v != null && v !== "").map(([k, v]) => (
                <div key={k as string}>
                  <dt className="text-xs text-muted">{k}</dt>
                  <dd className="break-all">{v}</dd>
                </div>
              ))}
            </dl>
          </Card>
        </div>

        {inv.paymentStatus != null && <PaymentsCard key={`${inv.id}-${inv.dueDate}`} inv={inv} canWrite={canWrite} onChange={() => mutate()} />}

        {accepted && inv.type === "INVOICE" && canWrite && (
          <Card className="scroll-mt-6" id="adjust">
            <CardHeader title={t("Adjust this invoice")} subtitle={t("Issued invoices can't be edited. Raise a credit note (reduce) or debit note (increase) instead.")} />
            <div className="p-5">
              {noteType == null ? (
                <div className="flex flex-wrap gap-2">
                  <Button variant="secondary" onClick={() => setNoteType("CREDIT_NOTE")}><FileMinus2 className="size-4" aria-hidden /> {t("Credit note")}</Button>
                  <Button variant="secondary" onClick={() => setNoteType("DEBIT_NOTE")}><FilePlus2 className="size-4" aria-hidden /> {t("Debit note")}</Button>
                  <ButtonLink variant="secondary" href={`/delivery-notes/new?invoiceId=${inv.id}`}><Truck className="size-4" aria-hidden /> {t("Delivery note")}</ButtonLink>
                </div>
              ) : (
                <form
                  className="flex flex-wrap items-end gap-3"
                  onSubmit={(e) => {
                    e.preventDefault();
                    act("note", async () => {
                      const note = await api<InvoiceDetail>("POST", `/invoices/${inv.id}/notes`, { type: noteType, reason: noteReason });
                      router.push(`/invoices/${note.id}`);
                    });
                  }}
                >
                  <Field label={noteType === "CREDIT_NOTE" ? t("Reason for the credit note") : t("Reason for the debit note")} className="min-w-72 flex-1">
                    <Input required autoFocus value={noteReason} onChange={(e) => setNoteReason(e.target.value)} placeholder={t("e.g. 100 L returned")} />
                  </Field>
                  <Button type="submit" loading={busy === "note"}>{t("Create draft")}</Button>
                  <Button type="button" variant="ghost" onClick={() => setNoteType(null)}>{t("Cancel")}</Button>
                </form>
              )}
            </div>
          </Card>
        )}

        <Card>
          <CardHeader title={t("ZATCA history")} subtitle={t("Every call to the Fatoora platform, kept for audit")} />
          {inv.submissions.length === 0 ? (
            <p className="px-5 py-6 text-sm text-muted">{t("Not sent yet.")}</p>
          ) : (
            <ol className="divide-y divide-line">
              {inv.submissions.map((s, i) => {
                const ok = ["CLEARED", "REPORTED", "CLEARED_WITH_WARNINGS", "REPORTED_WITH_WARNINGS"].includes(s.outcome);
                const Icon = ok ? CheckCircle2 : s.outcome === "REJECTED" ? XCircle : Clock;
                return (
                  <li key={i} className="flex items-start gap-3 px-5 py-3 text-sm">
                    <Icon className={`mt-0.5 size-4 shrink-0 ${ok ? "text-ok" : s.outcome === "REJECTED" ? "text-bad" : "text-warn"}`} aria-hidden />
                    <div className="min-w-0 flex-1">
                      <p className="font-medium">{s.outcome} <span className="font-normal text-muted">· {s.endpoint} · HTTP {s.httpStatus ?? "—"}</span></p>
                      <p className="text-xs text-muted">{dateTime(s.at)}</p>
                    </div>
                  </li>
                );
              })}
            </ol>
          )}
        </Card>
      </div>
    </>
  );
}
