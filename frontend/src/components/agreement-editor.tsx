"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import { FileDown, Printer, Save, Trash2 } from "lucide-react";
import { api, ApiError, fetcher, openFile, printFile } from "@/lib/api";
import type { Agreement, AgreementStatus, AgreementTemplate, Customer } from "@/lib/types";
import { todayKsa } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Field, Input, RequiredLegend, Select, Spinner } from "@/components/ui";
import { AgreementTextForm, parseAgreement, serializeAgreement } from "@/components/agreement-form";
import { useT } from "@/lib/i18n";
import { SuggestInput } from "@/components/suggest";

export const agreementStatusLabel: Record<AgreementStatus, string> = {
  DRAFT: "Draft",
  SENT: "Sent to client",
  SIGNED: "Signed",
  CANCELLED: "Cancelled",
};

const textareaClass = "w-full rounded-lg border border-line bg-white px-3 py-2 text-sm leading-relaxed outline-none focus:border-brand";

export function AgreementEditor({ initial }: { initial?: Agreement }) {
  const router = useRouter();
  const { data: customers } = useSWR<Customer[]>("/customers", fetcher);
  // New agreements start from the admin's standard agreement.
  const { data: template } = useSWR<AgreementTemplate>(initial ? null : "/agreement-template", fetcher);

  if (!initial && !template) return <Spinner />;
  return <Form initial={initial} template={template} customers={customers ?? []} router={router} />;
}

function Form({ initial, template, customers, router }: {
  initial?: Agreement;
  template?: AgreementTemplate;
  customers: Customer[];
  router: ReturnType<typeof useRouter>;
}) {
  const t = useT();
  const [customerId, setCustomerId] = useState(initial?.customerId ? String(initial.customerId) : "");
  const [clientName, setClientName] = useState(initial?.clientName ?? "");
  const [clientDetails, setClientDetails] = useState(initial?.clientDetails ?? "");
  const [title, setTitle] = useState(initial?.title ?? template?.title ?? "");
  const [text, setText] = useState(() => parseAgreement(initial?.body ?? template?.body ?? ""));
  const [agreementDate, setAgreementDate] = useState(initial?.agreementDate ?? todayKsa());
  const [status, setStatus] = useState<AgreementStatus>(initial?.status ?? "DRAFT");
  const [signedOn, setSignedOn] = useState(initial?.signedOn ?? todayKsa());
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);

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

  function pickCustomer(id: string) {
    setCustomerId(id);
    const c = customers.find((x) => String(x.id) === id);
    // The server fills the details from the customer when they are left empty.
    if (c) {
      setClientName(c.nameEn || c.nameAr || "");
      setClientDetails("");
    }
  }

  async function save(): Promise<number> {
    const body_ = {
      customerId: customerId ? Number(customerId) : null,
      clientName: clientName.trim() || null,
      clientDetails: clientDetails.trim() || null,
      title: title.trim() || null,
      body: serializeAgreement(text),
      agreementDate: agreementDate || null,
      status,
      signedOn: status === "SIGNED" ? signedOn || null : null,
    };
    const saved = initial
      ? await api<Agreement>("PUT", `/agreements/${initial.id}`, body_)
      : await api<Agreement>("POST", "/agreements", body_);
    return saved.id;
  }

  return (
    <div className="space-y-6">
      {error && <Alert tone="bad" title={t("Not saved")}>{error.fields ? Object.values(error.fields).join(", ") : error.message}</Alert>}

      {initial && (
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" loading={busy === "pdf"} onClick={() => act("pdf", () => openFile(`/agreements/${initial.id}/pdf`))}>
            <FileDown className="size-4" aria-hidden /> PDF
          </Button>
          <Button variant="secondary" loading={busy === "print"} onClick={() => act("print", () => printFile(`/agreements/${initial.id}/pdf`))}>
            <Printer className="size-4" aria-hidden /> {t("Print")}
          </Button>
          <span className="ms-auto" />
          {!confirmDelete ? (
            <Button variant="danger" onClick={() => setConfirmDelete(true)}><Trash2 className="size-4" aria-hidden /> {t("Delete")}</Button>
          ) : (
            <>
              <Button variant="danger" loading={busy === "delete"}
                onClick={() => act("delete", async () => { await api("DELETE", `/agreements/${initial.id}`); router.push("/agreements"); })}>
                {t("Confirm delete")}
              </Button>
              <Button variant="ghost" onClick={() => setConfirmDelete(false)}>{t("Cancel")}</Button>
            </>
          )}
        </div>
      )}

      <Card>
        <CardHeader title={t("Client")} subtitle={t("Printed as the second party. Leave the details empty to fill them from the customer.")} />
        <RequiredLegend className="px-5 pt-4" />
        <div className="grid gap-4 p-5 md:grid-cols-2">
          <Field label={t("Customer")} optional>
            <Select value={customerId} onChange={(e) => pickCustomer(e.target.value)}>
              <option value="">{t("Not a saved customer")}</option>
              {customers.map((c) => <option key={c.id} value={c.id}>{c.nameEn || c.nameAr}</option>)}
            </Select>
          </Field>
          <Field label={t("Client name")}><SuggestInput field="party" required value={clientName} onChange={(e) => setClientName(e.target.value)} /></Field>
          <Field label={t("Client details")} optional hint={t("CR or ID, address, mobile. One per line.")} className="md:col-span-2">
            <textarea dir="auto" rows={3} className={textareaClass} value={clientDetails} onChange={(e) => setClientDetails(e.target.value)} />
          </Field>
        </div>
      </Card>

      <Card>
        <CardHeader title={t("Agreement")} subtitle={t("Started from your standard agreement; change anything for this client.")} />
        <div className="grid gap-4 p-5 md:grid-cols-4">
          <Field label={t("Title")} optional className="md:col-span-2"><Input value={title} onChange={(e) => setTitle(e.target.value)} /></Field>
          <Field label={t("Date")} optional hint={t("Leave empty for today")}><Input type="date" value={agreementDate} onChange={(e) => setAgreementDate(e.target.value)} /></Field>
          <Field label={t("Status")}>
            <Select value={status} onChange={(e) => setStatus(e.target.value as AgreementStatus)}>
              {(Object.keys(agreementStatusLabel) as AgreementStatus[]).map((s) => <option key={s} value={s}>{t(agreementStatusLabel[s])}</option>)}
            </Select>
          </Field>
          {status === "SIGNED" && (
            <Field label={t("Signed on")} optional hint={t("Leave empty for today")}><Input type="date" value={signedOn} onChange={(e) => setSignedOn(e.target.value)} /></Field>
          )}
        </div>
      </Card>

      <AgreementTextForm value={text} onChange={setText} />

      <div className="sticky bottom-0 -mx-4 flex flex-wrap items-center justify-end gap-2 border-t border-line bg-canvas/95 px-4 py-4 backdrop-blur md:-mx-8 md:px-8">
        <p className="me-auto text-xs text-muted">{t("Printed on the company letterhead with signature spaces for both parties, to sign on paper.")}</p>
        <Button loading={busy === "save"} disabled={busy != null}
          onClick={() => act("save", async () => { const id = await save(); router.push(`/agreements/${id}`); router.refresh(); })}>
          <Save className="size-4" aria-hidden /> {t("Save agreement")}
        </Button>
      </div>
    </div>
  );
}
