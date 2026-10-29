"use client";

import { api, ApiError } from "@/lib/api";
import type { InvoiceDetail } from "@/lib/types";

/**
 * Issued invoices can't be changed or removed (ZATCA keeps every one), so "delete" and "edit" are done the
 * legal way: a full credit note cancels the invoice, and for an edit a corrected copy opens as a new draft.
 */

const accepted = ["CLEARED", "CLEARED_WITH_WARNINGS", "REPORTED", "REPORTED_WITH_WARNINGS"];

/** Cancels an issued invoice with a full credit note sent to ZATCA. Returns the credit note. */
export async function cancelInvoice(id: number, reason: string): Promise<InvoiceDetail> {
  let inv = await api<InvoiceDetail>("GET", `/invoices/${id}`);
  if (inv.type !== "INVOICE") throw new ApiError(409, "not_invoice", "Only invoices can be cancelled");
  if (Number(inv.adjustedTotal ?? inv.totalInclVat) <= 0)
    throw new ApiError(409, "already_cancelled", "This invoice is already fully cancelled by a credit note");
  // A credit note must point to an invoice ZATCA has accepted, so a queued one is sent first.
  if (inv.status === "SIGNED") inv = await api<InvoiceDetail>("POST", `/invoices/${id}/submit`);
  if (!accepted.includes(inv.status))
    throw new ApiError(409, "not_accepted", "ZATCA hasn't accepted this invoice yet, so it can't be cancelled. Try again in a few minutes.");
  const note = await api<InvoiceDetail>("POST", `/invoices/${id}/notes`, { type: "CREDIT_NOTE", reason });
  return api<InvoiceDetail>("POST", `/invoices/${note.id}/issue`, { sendMode: "NOW" });
}

/** Cancels the invoice, then opens a copy as a new draft to fix and issue. Returns the new draft. */
export async function correctInvoice(id: number, reason: string): Promise<InvoiceDetail> {
  await cancelInvoice(id, reason);
  return api<InvoiceDetail>("POST", `/invoices/${id}/duplicate`);
}
