"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { Camera, FileText, ScanText, Upload } from "lucide-react";
import { api, ApiError, uploadFile } from "@/lib/api";
import type { CompanyDocument, CompanyDocumentType, DocumentExtraction } from "@/lib/types";
import { Alert, Button, Field, Input, Select } from "@/components/ui";
import { useT } from "@/lib/i18n";

export const documentTypes: { value: CompanyDocumentType; label: string; owner: "worker" | "company" | "any" }[] = [
  { value: "IQAMA", label: "Iqama (residence permit)", owner: "worker" },
  { value: "PASSPORT", label: "Passport", owner: "worker" },
  { value: "WORK_PERMIT", label: "Work permit", owner: "worker" },
  { value: "DRIVING_LICENSE", label: "Driving licence", owner: "worker" },
  { value: "CR", label: "Commercial Registration (CR)", owner: "company" },
  { value: "MUNICIPAL_LICENSE", label: "Municipal licence (Balady)", owner: "company" },
  { value: "VAT_CERTIFICATE", label: "VAT certificate", owner: "company" },
  { value: "CHAMBER", label: "Chamber of Commerce", owner: "company" },
  { value: "INSURANCE", label: "Insurance", owner: "any" },
  { value: "OTHER", label: "Other document", owner: "any" },
];

export const typeName = (type: CompanyDocumentType) => documentTypes.find((d) => d.value === type)?.label ?? type;

const ACCEPT = "image/jpeg,image/png,image/bmp,image/tiff,application/pdf";

type Fields = {
  title: string; number: string; holderName: string; nationality: string; issueDate: string; expiryDate: string; notes: string;
  remindersEnabled: boolean; remindAfterExpiry: boolean;
};

function fromDoc(d?: CompanyDocument): Fields {
  return {
    title: d?.title ?? "", number: d?.number ?? "", holderName: d?.holderName ?? "", nationality: d?.nationality ?? "",
    issueDate: d?.issueDate ?? "", expiryDate: d?.expiryDate ?? "", notes: d?.notes ?? "",
    remindersEnabled: d?.remindersEnabled ?? true, remindAfterExpiry: d?.remindAfterExpiry ?? true,
  };
}

/**
 * Upload or photograph a document: it is read automatically (OCR) and the fields are filled in for checking.
 * Also edits an existing document, with an optional replacement scan.
 */
export function DocumentForm({ initial, workers, workerId: presetWorker, onDone, onCancel }: {
  initial?: CompanyDocument;
  workers: { id: number; name: string }[];
  workerId?: number | null;
  onDone: (doc: CompanyDocument) => void;
  onCancel: () => void;
}) {
  const t = useT();
  const [type, setType] = useState<CompanyDocumentType>(initial?.type ?? (presetWorker ? "IQAMA" : "CR"));
  const [workerId, setWorkerId] = useState<string>(String(initial?.workerId ?? presetWorker ?? ""));
  const [f, setF] = useState<Fields>(() => fromDoc(initial));
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [scan, setScan] = useState<DocumentExtraction | null>(null);
  const [scanning, setScanning] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const fileRef = useRef<HTMLInputElement>(null);
  const cameraRef = useRef<HTMLInputElement>(null);
  const previewRef = useRef<string | null>(null);

  useEffect(() => () => { if (previewRef.current) URL.revokeObjectURL(previewRef.current); }, []);

  const owner = documentTypes.find((d) => d.value === type)?.owner;

  async function read(next: File, kind: CompanyDocumentType) {
    setScanning(true);
    setScan(null);
    setError(null);
    try {
      const r = await uploadFile<DocumentExtraction>(`/documents/scan?type=${kind}`, next);
      setScan(r);
      // Fill only what was found, keeping anything already typed for fields the scan missed.
      setF((cur) => ({
        ...cur,
        number: r.number ?? cur.number,
        holderName: r.holderName ?? cur.holderName,
        nationality: r.nationality ?? cur.nationality,
        issueDate: r.issueDate ?? cur.issueDate,
        expiryDate: r.expiryDate ?? cur.expiryDate,
      }));
      if (r.holderName && !workerId && owner !== "company") {
        const match = workers.find((w) => w.name.toLowerCase().split(" ")[0] === r.holderName!.toLowerCase().split(" ")[0]);
        if (match) setWorkerId(String(match.id));
      }
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t("Failed"));
    } finally {
      setScanning(false);
    }
  }

  function pick(next: File | undefined) {
    if (!next) return;
    if (previewRef.current) URL.revokeObjectURL(previewRef.current);
    const url = next.type.startsWith("image/") ? URL.createObjectURL(next) : null;
    previewRef.current = url;
    setPreview(url);
    setFile(next);
    void read(next, type);
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setFields({});
    try {
      const body = { type, workerId: workerId ? Number(workerId) : null, ...f };
      let doc = initial
        ? await api<CompanyDocument>("PUT", `/documents/${initial.id}`, body)
        : await api<CompanyDocument>("POST", "/documents", body);
      if (file) doc = await uploadFile<CompanyDocument>(`/documents/${doc.id}/file?name=${encodeURIComponent(file.name)}`, file);
      onDone(doc);
    } catch (err) {
      if (err instanceof ApiError && err.fields) setFields(err.fields);
      setError(err instanceof ApiError ? err.message : t("Failed"));
    } finally {
      setBusy(false);
    }
  }

  const set = (k: keyof Fields) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });

  return (
    <form onSubmit={submit} className="grid gap-6 border-b border-line bg-canvas/50 p-4 lg:grid-cols-[minmax(0,280px)_1fr]">
      <div className="space-y-3">
        <Field label={t("Document type")}>
          <Select value={type} onChange={(e) => {
            const next = e.target.value as CompanyDocumentType;
            setType(next);
            if (documentTypes.find((d) => d.value === next)?.owner === "company") setWorkerId("");
            if (file) void read(file, next);
          }}>
            {documentTypes.map((d) => <option key={d.value} value={d.value}>{t(d.label)}</option>)}
          </Select>
        </Field>
        <Field label={t("Belongs to")}>
          <Select value={workerId} onChange={(e) => setWorkerId(e.target.value)} disabled={owner === "company"}>
            <option value="">{t("The company")}</option>
            {workers.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
          </Select>
        </Field>

        <div className="overflow-hidden rounded-lg border border-dashed border-line bg-white">
          {preview ? (
            // eslint-disable-next-line @next/next/no-img-element -- a local object URL of the chosen photo
            <img src={preview} alt={t("Selected document")} className="max-h-56 w-full object-contain" />
          ) : (
            <div className="flex h-36 flex-col items-center justify-center gap-2 px-4 text-center text-xs text-muted">
              <FileText className="size-7" aria-hidden />
              {file ? file.name : initial?.fileName ? t("Current file: {name}", { name: initial.fileName }) : t("Upload a scan or take a photo; the details are read automatically.")}
            </div>
          )}
        </div>
        <input ref={fileRef} type="file" accept={ACCEPT} className="hidden" onChange={(e) => { pick(e.target.files?.[0]); e.target.value = ""; }} />
        <input ref={cameraRef} type="file" accept="image/*" capture="environment" className="hidden" onChange={(e) => { pick(e.target.files?.[0]); e.target.value = ""; }} />
        <div className="grid grid-cols-2 gap-2">
          <Button type="button" variant="secondary" onClick={() => fileRef.current?.click()}><Upload className="size-4" aria-hidden />{t("Upload")}</Button>
          <Button type="button" variant="secondary" onClick={() => cameraRef.current?.click()}><Camera className="size-4" aria-hidden />{t("Take photo")}</Button>
        </div>
        {scanning && <p className="flex items-center gap-2 text-xs text-muted"><ScanText className="size-4 animate-pulse" aria-hidden />{t("Reading the document… this can take up to 30 seconds.")}</p>}
        {scan && (
          scan.ocrAvailable && scan.warnings.length === 0
            ? <Alert tone="ok">{t("Details read from the document. Please check them before saving.")}</Alert>
            : <Alert tone="warn">{scan.warnings.map((w) => <span key={w} className="block">{t(w)}</span>)}</Alert>
        )}
      </div>

      <div className="grid content-start gap-3 sm:grid-cols-2">
        <Field label={t("Title")} hint={t("Leave empty to use the document type")} className="sm:col-span-2">
          <Input value={f.title} onChange={set("title")} placeholder={t(typeName(type))} />
        </Field>
        <Field label={type === "IQAMA" ? t("Iqama number") : type === "PASSPORT" ? t("Passport number") : t("Document number")} error={fields.number}>
          <Input value={f.number} onChange={set("number")} dir="ltr" />
        </Field>
        <Field label={t("Name on document")}>
          <Input value={f.holderName} onChange={set("holderName")} dir="auto" />
        </Field>
        <Field label={t("Issue date")} error={fields.issueDate}>
          <Input type="date" value={f.issueDate} onChange={set("issueDate")} />
        </Field>
        <Field label={t("Expiry date")} error={fields.expiryDate} hint={t("Reminders start two months before this date")}>
          <Input type="date" value={f.expiryDate} onChange={set("expiryDate")} />
        </Field>
        {(owner !== "company") && (
          <Field label={t("Nationality")}>
            <Input value={f.nationality} onChange={set("nationality")} />
          </Field>
        )}
        <Field label={t("Notes")} className={owner !== "company" ? "" : "sm:col-span-2"}>
          <Input value={f.notes} onChange={set("notes")} />
        </Field>
        <fieldset className="space-y-2 rounded-lg border border-line bg-white p-3 text-sm sm:col-span-2">
          <legend className="px-1 text-xs font-medium text-muted">{t("Expiry reminders")}</legend>
          <label className="flex items-start gap-2">
            <input type="checkbox" className="mt-0.5 size-4 accent-brand" checked={f.remindersEnabled} onChange={(e) => setF({ ...f, remindersEnabled: e.target.checked })} />
            <span>{t("Remind me: every 15 days from two months before expiry, then every 2 days in the last 15 days")}</span>
          </label>
          <label className={`flex items-start gap-2 ${f.remindersEnabled ? "" : "opacity-50"}`}>
            <input type="checkbox" className="mt-0.5 size-4 accent-brand" disabled={!f.remindersEnabled} checked={f.remindAfterExpiry} onChange={(e) => setF({ ...f, remindAfterExpiry: e.target.checked })} />
            <span>{t("Keep reminding every 2 days after it has expired, until it is renewed")}</span>
          </label>
        </fieldset>
        {error && <div className="sm:col-span-2"><Alert tone="bad">{error}</Alert></div>}
        <div className="flex gap-2 sm:col-span-2">
          <Button type="submit" loading={busy} disabled={scanning}>{initial ? t("Save") : t("Save document")}</Button>
          <Button type="button" variant="secondary" onClick={onCancel}>{t("Cancel")}</Button>
        </div>
      </div>
    </form>
  );
}
