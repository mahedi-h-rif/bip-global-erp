"use client";

import { useSearchParams } from "next/navigation";
import { InvoiceEditor } from "@/components/invoice-editor";

/** The new-invoice form; /invoices/new?customerId=… starts with that customer picked. */
export function NewInvoice() {
  const id = useSearchParams().get("customerId");
  return <InvoiceEditor defaultCustomerId={id && /^\d+$/.test(id) ? Number(id) : undefined} />;
}
