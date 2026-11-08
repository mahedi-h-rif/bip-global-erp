"use client";

import Link from "next/link";
import { useState } from "react";
import useSWR from "swr";
import { Network, X } from "lucide-react";
import { fetcher } from "@/lib/api";
import type { DocGraph, DocType, GraphNode } from "@/lib/types";
import { date, money } from "@/lib/format";
import { Alert, Button, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

/** Left-to-right position of each document type in the chain (sales and purchasing share the columns). */
const column: Record<DocType, number> = {
  QUOTATION: 0, PURCHASE_ORDER: 1, SALES_ORDER: 1, DELIVERY_NOTE: 2, GOODS_RECEIPT: 2,
  INVOICE: 3, SUPPLIER_INVOICE: 3, RECEIPT: 4, SUPPLIER_PAYMENT: 4,
};

const path: Partial<Record<DocType, string>> = {
  QUOTATION: "/quotations", SALES_ORDER: "/sales-orders", DELIVERY_NOTE: "/delivery-notes", INVOICE: "/invoices",
  RECEIPT: "/receipts", PURCHASE_ORDER: "/purchase-orders", GOODS_RECEIPT: "/goods-receipts",
  SUPPLIER_INVOICE: "/supplier-invoices", SUPPLIER_PAYMENT: "/supplier-payments",
};

const slug = (t: DocType) => t.toLowerCase().replace(/_/g, "-");

const W = 190, H = 92, GX = 70, GY = 22, PAD = 16;

function layout(g: DocGraph) {
  // Credit/debit notes sit one column right of their invoice so they don't overlap it.
  const col = (n: GraphNode) => (n.type === "INVOICE" && n.label !== "Invoice" ? 3.5 : column[n.type]);
  const cols = [...new Set(g.nodes.map(col))].sort((a, b) => a - b);
  const pos = new Map<string, { x: number; y: number }>();
  const height = Math.max(...cols.map((c) => g.nodes.filter((n) => col(n) === c).length));
  cols.forEach((c, ci) => {
    const inCol = g.nodes.filter((n) => col(n) === c).sort((a, b) => (a.date ?? "").localeCompare(b.date ?? "") || a.id - b.id);
    const offset = ((height - inCol.length) * (H + GY)) / 2;
    inCol.forEach((n, i) => pos.set(`${n.type}:${n.id}`, { x: PAD + ci * (W + GX), y: PAD + offset + i * (H + GY) }));
  });
  return { pos, width: PAD * 2 + cols.length * W + (cols.length - 1) * GX, height: PAD * 2 + height * H + (height - 1) * GY };
}

function DocMap({ type, id }: { type: DocType; id: number }) {
  const t = useT();
  const { data, error } = useSWR<DocGraph>(`/relations/${slug(type)}/${id}`, fetcher);
  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!data) return <Spinner />;
  const { pos, width, height } = layout(data);
  const party = data.nodes.find((n) => n.party)?.party;
  return (
    <div className="overflow-auto rounded-lg border border-line bg-slate-50">
      {party && <p className="px-4 pt-3 text-xs text-muted">{t("Business partner")}: <span className="font-medium text-ink" dir="auto">{party}</span></p>}
      <svg width={width} height={height} role="img" aria-label={t("Relationship map")} className="block">
        <defs>
          <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" fill="#94a3b8" />
          </marker>
        </defs>
        {data.edges.map((e, i) => {
          const a = pos.get(`${e.fromType}:${e.fromId}`), b = pos.get(`${e.toType}:${e.toId}`);
          if (!a || !b) return null;
          const x1 = a.x + W, y1 = a.y + H / 2, x2 = b.x, y2 = b.y + H / 2, mx = (x1 + x2) / 2;
          const paid = e.toType === "RECEIPT" || e.toType === "SUPPLIER_PAYMENT";
          return <path key={i} d={`M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2 - 2},${y2}`} fill="none"
                       stroke={paid ? "#eab308" : "#94a3b8"} strokeWidth={1.6} markerEnd="url(#arrow)" />;
        })}
      </svg>
      <div className="relative" style={{ width, height, marginTop: -height }}>
        {data.nodes.map((n) => {
          const p = pos.get(`${n.type}:${n.id}`)!;
          const root = n.type === data.rootType && n.id === data.rootId;
          const unpaid = (n.type === "INVOICE" || n.type === "SUPPLIER_INVOICE") && n.open;
          const href = path[n.type] ? `${path[n.type]}/${n.id}` : "#";
          return (
            <Link
              key={`${n.type}:${n.id}`}
              href={href}
              className={`absolute flex flex-col rounded-md border bg-white px-2.5 py-1.5 text-[11px] leading-tight shadow-sm hover:border-brand ${root ? "border-amber-400 ring-2 ring-amber-200" : "border-line"} ${unpaid ? "border-b-4 border-b-red-500" : ""}`}
              style={{ left: p.x, top: p.y, width: W, height: H }}
              title={n.status}
            >
              <span className="font-semibold text-brand">{t(n.label)}</span>
              <span className="tabular font-medium">{n.number}</span>
              <span className="text-muted">{date(n.date)}{n.ref ? ` · ${n.ref}` : ""}</span>
              <span className="tabular mt-auto font-medium">{n.total != null ? money(n.total) : ""}</span>
            </Link>
          );
        })}
      </div>
    </div>
  );
}

/** "Relationship map" button that opens the linked-documents graph of a document. */
export function RelationshipMapButton({ type, id }: { type: DocType; id: number }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button variant="secondary" onClick={() => setOpen(true)}><Network className="size-4" aria-hidden /> {t("Relationship map")}</Button>
      {open && (
        <div className="fixed inset-0 z-50 flex items-start justify-center overflow-auto bg-black/40 p-4 pt-16" onClick={() => setOpen(false)}>
          <div className="w-full max-w-6xl rounded-xl bg-white p-4 shadow-xl" onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true" aria-label={t("Relationship map")}>
            <div className="mb-3 flex items-center justify-between">
              <h2 className="text-base font-semibold">{t("Relationship map")}</h2>
              <Button variant="ghost" onClick={() => setOpen(false)} aria-label={t("Close")}><X className="size-4" aria-hidden /></Button>
            </div>
            <DocMap type={type} id={id} />
            <p className="mt-2 text-xs text-muted">{t("Yellow lines are payments. A red bottom edge marks an invoice that is not fully paid.")}</p>
          </div>
        </div>
      )}
    </>
  );
}
