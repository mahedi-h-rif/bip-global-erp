"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { Pencil, Trash2 } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { cancelInvoice, correctInvoice } from "@/lib/invoice-actions";
import type { InvoiceDetail, InvoiceStatus, InvoiceSummary } from "@/lib/types";
import { useT } from "@/lib/i18n";

type Doc = Pick<InvoiceSummary, "id" | "number" | "type" | "status"> & { test?: boolean };

const cancellable = new Set<InvoiceStatus>(["SIGNED", "CLEARED", "CLEARED_WITH_WARNINGS", "REPORTED", "REPORTED_WITH_WARNINGS"]);

const small = "inline-flex items-center gap-1 rounded-md border border-line bg-white px-2 py-1 text-xs font-medium hover:bg-canvas disabled:opacity-40";

/**
 * Edit and Delete for every invoice:
 * - drafts and sandbox test invoices are edited or deleted directly;
 * - a real issued invoice is "deleted" by cancelling it with a full credit note sent to ZATCA, and "edited"
 *   by cancelling it and opening a corrected copy as a new draft (ZATCA never allows changing the original);
 * - a rejected invoice is edited by opening a corrected copy; it is already invalid, so there is nothing to delete.
 */
export function EditDeleteActions({ doc, onChanged }: { doc: Doc; onChanged?: () => void }) {
  const router = useRouter();
  const t = useT();
  const [ask, setAsk] = useState<"edit" | "delete" | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (doc.type !== "INVOICE") return null; // credit and debit notes can't be changed
  const draft = doc.status === "DRAFT";
  const real = !draft && !doc.test && cancellable.has(doc.status);
  const rejected = doc.status === "REJECTED";
  const label = doc.number ?? t("draft #{id}", { id: doc.id });

  async function run(fn: () => Promise<void>) {
    setBusy(true);
    setError(null);
    try {
      await fn();
      setAsk(null);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t("Could not reach the server"));
    } finally {
      setBusy(false);
    }
  }

  const copyToDraft = async () => {
    const copy = await api<InvoiceDetail>("POST", `/invoices/${doc.id}/duplicate`);
    router.push(`/invoices/${copy.id}`);
  };

  const confirm = () =>
    run(async () => {
      const why = reason.trim() || (ask === "delete" ? "Cancelled / refunded" : "Correction");
      if (ask === "delete") {
        if (real) {
          const note = await cancelInvoice(doc.id, why);
          onChanged?.();
          router.push(`/invoices/${note.id}`);
        } else {
          await api("DELETE", `/invoices/${doc.id}`);
          if (onChanged) onChanged();
          else router.push("/invoices");
        }
      } else if (real) {
        const copy = await correctInvoice(doc.id, why);
        router.push(`/invoices/${copy.id}`);
      } else {
        await copyToDraft();
      }
    });

  const message =
    ask === "delete"
      ? real
        ? t("{label} will be cancelled with a credit note sent to ZATCA. Its total becomes 0; any payment shows as a refund.", { label })
        : doc.test
          ? t("{label} is a test invoice from the ZATCA sandbox and will be deleted permanently.", { label })
          : t("Draft {label} will be deleted.", { label })
      : real
        ? t("{label} will be cancelled with a credit note sent to ZATCA, and a copy opens as a new draft for you to fix and issue.", { label })
        : t("A copy of {label} opens as a new draft for you to fix and issue.", { label });

  return (
    <div className="flex flex-col items-end gap-1">
      <div className="flex flex-wrap justify-end gap-1.5">
        {draft ? (
          <Link href={`/invoices/${doc.id}`} className={small}><Pencil className="size-3.5" aria-hidden /> {t("Edit")}</Link>
        ) : (
          <button type="button" className={small} onClick={() => { setAsk("edit"); setReason(""); }}>
            <Pencil className="size-3.5" aria-hidden /> {t("Edit")}
          </button>
        )}
        <button
          type="button"
          className={`${small} text-bad`}
          disabled={rejected}
          title={rejected ? t("ZATCA rejected this invoice, so it is already invalid") : undefined}
          onClick={() => { setAsk("delete"); setReason(""); }}
        >
          <Trash2 className="size-3.5" aria-hidden /> {t("Delete")}
        </button>
      </div>
      {ask && (
        <div className="mt-1 w-72 space-y-2 rounded-lg border border-line bg-white p-3 text-start text-xs shadow-sm">
          <p>{message}</p>
          {real && (
            <input
              className="w-full rounded-md border border-line px-2 py-1.5"
              placeholder={ask === "delete" ? t("Reason, e.g. customer refund") : t("Reason, e.g. wrong price")}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          )}
          {error && <p className="text-bad">{error}</p>}
          <div className="flex justify-end gap-1.5">
            <button type="button" className={small} onClick={() => setAsk(null)} disabled={busy}>{t("Cancel")}</button>
            <button
              type="button"
              disabled={busy}
              onClick={confirm}
              className={`inline-flex items-center rounded-md px-2.5 py-1 font-medium text-white ${ask === "delete" ? "bg-bad" : "bg-brand"}`}
            >
              {busy ? t("Working…") : ask === "delete" ? t("Confirm delete") : t("Confirm edit")}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
