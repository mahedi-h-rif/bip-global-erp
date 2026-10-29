"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import useSWR from "swr";
import {
  AlignCenter,
  AlignJustify,
  AlignLeft,
  AlignRight,
  Bold,
  CalendarDays,
  Eraser,
  FileDown,
  FileText,
  ImagePlus,
  Italic,
  List,
  ListOrdered,
  Minus,
  Printer,
  Redo2,
  Save,
  Share2,
  Strikethrough,
  Table,
  Trash2,
  Underline,
  Undo2,
} from "lucide-react";
import { api, ApiError, download, fetcher, openFile, printFile, shareFile } from "@/lib/api";
import type { Organization, PadDocument } from "@/lib/types";
import { Alert, Button, Input } from "@/components/ui";
import { useT } from "@/lib/i18n";

const DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

/** File name from the document title, as the server builds it. */
export function padFileName(title: string) {
  const s = title.trim().replace(/[\\/:*?"<>|\r\n]+/g, " ").replace(/\s+/g, "-");
  return (s || "letter").slice(0, 80);
}

function today() {
  return new Date().toLocaleDateString("en-GB");
}

/** Starting text for a new letter. */
export function letterStart(signatory?: string | null, title?: string | null) {
  return `<p style="text-align:right">Date: ${today()}</p>`
    + "<p>To,<br>Name<br>Company / address</p>"
    + "<p><b>Subject: </b>…</p>"
    + "<p>Dear Sir,</p><p>Write your letter here.</p>"
    + `<p><br>Regards,<br><br><br>${signatory ?? ""}${title ? `<br>${title}` : ""}</p>`;
}

/**
 * A document written straight on the letterhead: the page shows the company header and footer around a
 * Word-like editing area with a formatting toolbar. Saved, it downloads as PDF or Word and can be shared.
 */
export function PadEditor({ initial, start }: { initial?: PadDocument; start?: "letter" | "blank" }) {
  const t = useT();
  const router = useRouter();
  const { data: org } = useSWR<Organization>("/organization", fetcher);
  const editor = useRef<HTMLDivElement>(null);
  const [title, setTitle] = useState(initial?.title ?? "");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [dirty, setDirty] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const started = useRef(false);

  // The editable area is filled once; after that the browser owns its content.
  useEffect(() => {
    if (started.current || !editor.current) return;
    if (!initial && start !== "blank" && !org) return;
    started.current = true;
    editor.current.innerHTML = initial?.body ?? (start === "blank" ? "<p><br></p>" : letterStart(org?.signatoryName, org?.signatoryTitle));
    document.execCommand("styleWithCSS", false, "false");
  }, [initial, start, org]);

  // Warn before leaving with unsaved changes.
  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  function cmd(name: string, value?: string) {
    editor.current?.focus();
    document.execCommand(name, false, value);
    setDirty(true);
  }

  /** Sets the text direction of the paragraph the cursor is in. */
  function direction(dir: "ltr" | "rtl") {
    const sel = window.getSelection();
    let node = sel?.anchorNode ?? null;
    while (node && node !== editor.current && !(node instanceof HTMLElement && /^(P|DIV|H[1-3]|LI|TD|TH|BLOCKQUOTE)$/.test(node.tagName))) {
      node = node.parentNode;
    }
    if (node instanceof HTMLElement && node !== editor.current) {
      node.setAttribute("dir", dir);
      node.style.textAlign = dir === "rtl" ? "right" : "left";
      setDirty(true);
    } else {
      cmd("formatBlock", "p");
      direction(dir);
    }
  }

  function insertTable() {
    const cells = (tag: string) => `<tr>${`<${tag}><br></${tag}>`.repeat(3)}</tr>`;
    cmd("insertHTML", `<table><tbody>${cells("th")}${cells("td")}${cells("td")}</tbody></table><p><br></p>`);
  }

  function insertImage(file: File) {
    if (file.size > 3 * 1024 * 1024) {
      setError(t("Use an image smaller than 3 MB."));
      return;
    }
    const reader = new FileReader();
    reader.onload = () => cmd("insertImage", String(reader.result));
    reader.readAsDataURL(file);
  }

  async function act(name: string, fn: () => Promise<void>) {
    setBusy(name);
    setError(null);
    setNotice(null);
    try {
      await fn();
    } catch (e) {
      setError(e instanceof ApiError ? (e.fields ? Object.values(e.fields).join(", ") : e.message) : t("Could not reach the server"));
    } finally {
      setBusy(null);
    }
  }

  /** Saves and returns the document's id; files are always made from the saved version. */
  async function save(): Promise<number> {
    const body = { title, body: editor.current?.innerHTML ?? "" };
    const saved = initial
      ? await api<PadDocument>("PUT", `/pads/${initial.id}`, body)
      : await api<PadDocument>("POST", "/pads", body);
    setDirty(false);
    if (!initial) router.replace(`/pad/${saved.id}`);
    return saved.id;
  }

  const name = padFileName(title);

  return (
    <div className="space-y-4">
      {error && <Alert tone="bad" title={t("Not saved")}>{error}</Alert>}
      {notice && <Alert tone="ok">{notice}</Alert>}

      <div className="flex flex-wrap items-end gap-2">
        <label className="min-w-0 flex-1 basis-64">
          <span className="mb-1 block text-xs font-medium text-muted">{t("Title")}</span>
          <Input dir="auto" value={title} placeholder={t("e.g. Completion letter")} onChange={(e) => { setTitle(e.target.value); setDirty(true); }} />
        </label>
        <Button loading={busy === "save"} disabled={busy != null} onClick={() => act("save", async () => { await save(); setNotice(t("Saved")); })}>
          <Save className="size-4" aria-hidden /> {t("Save")}
        </Button>
        <Button variant="secondary" loading={busy === "pdf"} disabled={busy != null} onClick={() => act("pdf", async () => openFile(`/pads/${await save()}/pdf`))}>
          <FileDown className="size-4" aria-hidden /> PDF
        </Button>
        <Button variant="secondary" loading={busy === "word"} disabled={busy != null} onClick={() => act("word", async () => download(`/pads/${await save()}/docx`, `${name}.docx`))}>
          <FileText className="size-4" aria-hidden /> Word
        </Button>
        <Button variant="secondary" loading={busy === "print"} disabled={busy != null} onClick={() => act("print", async () => printFile(`/pads/${await save()}/pdf`))}>
          <Printer className="size-4" aria-hidden /> {t("Print")}
        </Button>
        <Button variant="secondary" loading={busy === "share"} disabled={busy != null}
          onClick={() => act("share", async () => {
            const how = await shareFile(`/pads/${await save()}/pdf`, `${name}.pdf`, "application/pdf");
            if (how === "downloaded") setNotice(t("Downloaded. Attach the file in WhatsApp to send it."));
          })}>
          <Share2 className="size-4" aria-hidden /> {t("Share PDF")}
        </Button>
        <Button variant="secondary" loading={busy === "shareWord"} disabled={busy != null}
          onClick={() => act("shareWord", async () => {
            const how = await shareFile(`/pads/${await save()}/docx`, `${name}.docx`, DOCX);
            if (how === "downloaded") setNotice(t("Downloaded. Attach the file in WhatsApp to send it."));
          })}>
          <Share2 className="size-4" aria-hidden /> {t("Share Word")}
        </Button>
        {initial && (!confirmDelete ? (
          <Button variant="danger" onClick={() => setConfirmDelete(true)}><Trash2 className="size-4" aria-hidden /> {t("Delete")}</Button>
        ) : (
          <>
            <Button variant="danger" loading={busy === "delete"}
              onClick={() => act("delete", async () => { await api("DELETE", `/pads/${initial.id}`); setDirty(false); router.push("/pad"); })}>
              {t("Confirm delete")}
            </Button>
            <Button variant="ghost" onClick={() => setConfirmDelete(false)}>{t("Cancel")}</Button>
          </>
        ))}
      </div>

      {/* Toolbar */}
      <div className="sticky top-0 z-20 flex flex-wrap items-center gap-1 rounded-xl border border-line bg-white/95 p-1.5 shadow-sm backdrop-blur">
        <Tool label={t("Undo")} onClick={() => cmd("undo")}><Undo2 className="size-4" /></Tool>
        <Tool label={t("Redo")} onClick={() => cmd("redo")}><Redo2 className="size-4" /></Tool>
        <Sep />
        <select aria-label={t("Style")} className="h-8 rounded-md border border-line bg-white px-1.5 text-xs"
          defaultValue="" onChange={(e) => { if (e.target.value) cmd("formatBlock", e.target.value); e.target.value = ""; }}>
          <option value="">{t("Style")}</option>
          <option value="p">{t("Normal text")}</option>
          <option value="h1">{t("Heading 1")}</option>
          <option value="h2">{t("Heading 2")}</option>
          <option value="h3">{t("Heading 3")}</option>
          <option value="blockquote">{t("Indented")}</option>
        </select>
        <select aria-label={t("Size")} className="h-8 rounded-md border border-line bg-white px-1.5 text-xs"
          defaultValue="" onChange={(e) => { if (e.target.value) cmd("fontSize", e.target.value); e.target.value = ""; }}>
          <option value="">{t("Size")}</option>
          <option value="2">{t("Small")}</option>
          <option value="3">{t("Normal")}</option>
          <option value="4">{t("Medium")}</option>
          <option value="5">{t("Large")}</option>
          <option value="6">{t("Very large")}</option>
        </select>
        <Sep />
        <Tool label={t("Bold")} onClick={() => cmd("bold")}><Bold className="size-4" /></Tool>
        <Tool label={t("Italic")} onClick={() => cmd("italic")}><Italic className="size-4" /></Tool>
        <Tool label={t("Underline")} onClick={() => cmd("underline")}><Underline className="size-4" /></Tool>
        <Tool label={t("Strikethrough")} onClick={() => cmd("strikeThrough")}><Strikethrough className="size-4" /></Tool>
        <label title={t("Text colour")} className="relative inline-flex size-8 cursor-pointer items-center justify-center rounded-md hover:bg-canvas">
          <span className="text-sm font-bold underline decoration-[3px] decoration-red-600">A</span>
          <input type="color" aria-label={t("Text colour")} className="absolute inset-0 cursor-pointer opacity-0"
            onMouseDown={(e) => e.preventDefault()} onChange={(e) => cmd("foreColor", e.target.value)} />
        </label>
        <Sep />
        <Tool label={t("Bullets")} onClick={() => cmd("insertUnorderedList")}><List className="size-4" /></Tool>
        <Tool label={t("Numbering")} onClick={() => cmd("insertOrderedList")}><ListOrdered className="size-4" /></Tool>
        <Sep />
        <Tool label={t("Align left")} onClick={() => cmd("justifyLeft")}><AlignLeft className="size-4" /></Tool>
        <Tool label={t("Center")} onClick={() => cmd("justifyCenter")}><AlignCenter className="size-4" /></Tool>
        <Tool label={t("Align right")} onClick={() => cmd("justifyRight")}><AlignRight className="size-4" /></Tool>
        <Tool label={t("Justify")} onClick={() => cmd("justifyFull")}><AlignJustify className="size-4" /></Tool>
        <Tool label={t("Left to right")} onClick={() => direction("ltr")}><span className="text-[11px] font-semibold">LTR</span></Tool>
        <Tool label={t("Right to left (Arabic)")} onClick={() => direction("rtl")}><span className="text-[11px] font-semibold">RTL</span></Tool>
        <Sep />
        <Tool label={t("Insert table")} onClick={insertTable}><Table className="size-4" /></Tool>
        <label title={t("Insert image")} className="inline-flex size-8 cursor-pointer items-center justify-center rounded-md hover:bg-canvas">
          <ImagePlus className="size-4" />
          <input type="file" accept="image/png,image/jpeg" className="hidden"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) insertImage(f); e.target.value = ""; }} />
        </label>
        <Tool label={t("Horizontal line")} onClick={() => cmd("insertHorizontalRule")}><Minus className="size-4" /></Tool>
        <Tool label={t("Insert today's date")} onClick={() => cmd("insertText", today())}><CalendarDays className="size-4" /></Tool>
        <Tool label={t("Clear formatting")} onClick={() => cmd("removeFormat")}><Eraser className="size-4" /></Tool>
      </div>

      {/* The page */}
      <div className="overflow-x-auto rounded-xl bg-slate-200/70 p-3 md:p-6">
        <div className="mx-auto flex min-h-[1000px] w-full min-w-[320px] max-w-[794px] flex-col bg-white shadow-lg">
          <PadHeader org={org} />
          <div
            ref={editor}
            contentEditable
            suppressContentEditableWarning
            spellCheck
            dir="auto"
            onInput={() => setDirty(true)}
            className={[
              "flex-1 px-6 py-6 text-[15px] leading-relaxed text-[#1b2233] outline-none md:px-[60px]",
              "[&_p]:mb-2 [&_h1]:mb-2 [&_h1]:text-2xl [&_h1]:font-bold [&_h2]:mt-3 [&_h2]:mb-1.5 [&_h2]:text-xl [&_h2]:font-semibold",
              "[&_h3]:mt-2 [&_h3]:mb-1 [&_h3]:text-base [&_h3]:font-semibold [&_ul]:mb-2 [&_ul]:list-disc [&_ul]:ps-6 [&_ol]:mb-2 [&_ol]:list-decimal [&_ol]:ps-6",
              "[&_blockquote]:ms-8 [&_hr]:my-3 [&_hr]:border-slate-400 [&_img]:max-w-full [&_img]:inline-block",
              "[&_table]:mb-2 [&_table]:w-full [&_table]:border-collapse [&_td]:border [&_td]:border-slate-400 [&_td]:px-2 [&_td]:py-1 [&_td]:align-top",
              "[&_th]:border [&_th]:border-slate-400 [&_th]:bg-slate-50 [&_th]:px-2 [&_th]:py-1 [&_th]:text-start",
            ].join(" ")}
          />
          <PadFooter org={org} />
        </div>
      </div>
      <p className="text-xs text-muted">
        {t("The letterhead header and footer are added to every page of the PDF and Word file. The PDF prints Arabic and English; for other languages use the Word file.")}
      </p>
    </div>
  );
}

function Tool({ label, onClick, children }: { label: string; onClick: () => void; children: ReactNode }) {
  return (
    <button type="button" title={label} aria-label={label}
      // Keep the text selection in the page while clicking the toolbar.
      onMouseDown={(e) => e.preventDefault()} onClick={onClick}
      className="inline-flex size-8 items-center justify-center rounded-md text-ink hover:bg-canvas">
      {children}
    </button>
  );
}

function Sep() {
  return <span className="mx-0.5 h-5 w-px bg-line" aria-hidden />;
}

/** The letterhead header as it prints: logo, English name and contacts, Arabic name. */
export function PadHeader({ org }: { org?: Organization }) {
  const color = org?.brandColor || "#0B2A6F";
  return (
    <div className="select-none px-6 pt-6 md:px-[60px]" contentEditable={false}>
      <div className="flex items-center gap-3">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src="/api/v1/organization/logo" alt="" className="h-14 w-auto max-w-[130px] object-contain" onError={(e) => (e.currentTarget.style.display = "none")} />
        <div className="min-w-0 flex-1">
          <div className="truncate text-lg font-bold" style={{ color }}>{org?.legalNameEn}</div>
          <div className="truncate text-[11px] text-[#5a6275]">{[org?.email, org?.website].filter(Boolean).join(" · ")}</div>
          {org?.phone && <div className="text-[11px] text-[#5a6275]">Mobile {org.phone}</div>}
        </div>
        <div dir="rtl" className="hidden text-right sm:block">
          <div className="text-lg font-bold" style={{ color }}>{org?.legalNameAr}</div>
          <div className="text-[11px] text-[#5a6275]">{org?.city}</div>
        </div>
      </div>
      <div className="mt-2 border-b-[3px]" style={{ borderColor: color }} />
    </div>
  );
}

function PadFooter({ org }: { org?: Organization }) {
  const color = org?.brandColor || "#0B2A6F";
  const ids = [
    org?.buildingNumber ? `Bldg ${org.buildingNumber}` : null,
    org?.postalCode ? `Postal ${org.postalCode}` : null,
    org?.vatNumber ? `VAT ${org.vatNumber}` : null,
    org?.otherId ? `CR ${org.otherId}` : null,
  ].filter(Boolean).join(" · ");
  return (
    <div className="select-none px-6 pb-5 md:px-[60px]" contentEditable={false}>
      <div className="border-t-2 pt-1.5 text-[10px] leading-snug text-[#5a6275]" style={{ borderColor: color }}>
        <div dir="rtl" className="text-left">{[org?.street, org?.district, org?.city].filter(Boolean).join("، ")}</div>
        <div>{ids}</div>
      </div>
    </div>
  );
}
