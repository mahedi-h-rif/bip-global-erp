"use client";

import Link from "next/link";
import { use, useRef, useState } from "react";
import useSWR from "swr";
import { CheckCircle2, Lock, Download, FileDown, PackageCheck, Paperclip, Printer, XCircle } from "lucide-react";
import { api, ApiError, download, fetcher, openFile, printFile, uploadFile } from "@/lib/api";
import type { GoodsReceipt, Me, PurchaseOrder } from "@/lib/types";
import { date, dateTime } from "@/lib/format";
import { Alert, Button, ButtonLink, Card, CardHeader, PageHeader, Spinner } from "@/components/ui";
import { fmtCur, StatusPill } from "@/components/purchase";
import { RelationshipMapButton } from "@/components/relationship-map";
import { useT } from "@/lib/i18n";

export default function PurchaseOrderPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const t = useT();
  const { data: me } = useSWR<Me>("/auth/me", fetcher);
  const { data: p, error, mutate } = useSWR<PurchaseOrder>(`/purchase-orders/${id}`, fetcher);
  const { data: grns } = useSWR<GoodsReceipt[]>(p ? `/goods-receipts?purchaseOrderId=${p.id}` : null, fetcher);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  if (error) return <Alert tone="bad">{error.message}</Alert>;
  if (!p) return <Spinner />;

  async function act(name: string, run: () => Promise<PurchaseOrder>) {
    setBusy(name);
    setProblem(null);
    try { await mutate(await run(), { revalidate: false }); }
    catch (e) { setProblem(e instanceof ApiError ? e.message : t("Could not reach the server")); }
    finally { setBusy(null); }
  }

  const receivable = p.status === "APPROVED" || p.status === "PARTIALLY_RECEIVED";
  return (
    <>
      <PageHeader title={t("Purchase order {number}", { number: p.number })}
        subtitle={<span className="flex flex-wrap items-center gap-2"><StatusPill status={p.status} />
          {p.receivingStatus && <span className="rounded bg-slate-100 px-2 py-0.5 text-xs">{t("Received")}: {t(({ NONE: "Not yet", PARTIAL: "Partly", FULL: "Fully" } as Record<string, string>)[p.receivingStatus])}</span>}
          {p.billingStatus && <span className="rounded bg-slate-100 px-2 py-0.5 text-xs">{t("Billed")}: {t(({ NONE: "Not yet", PARTIAL: "Partly", FULL: "Fully" } as Record<string, string>)[p.billingStatus])}</span>}
          <span dir="auto">{p.supplierName}</span></span>}
        actions={<span className="flex flex-wrap gap-2">
          {p.status === "PENDING" && me?.role === "ADMIN" && <Button loading={busy === "approve"} onClick={() => act("approve", () => api("POST", `/purchase-orders/${p.id}/approve`))}><CheckCircle2 className="size-4" aria-hidden /> {t("Approve")}</Button>}
          {receivable && <ButtonLink href={`/goods-receipts/new?purchaseOrderId=${p.id}`}><PackageCheck className="size-4" aria-hidden /> {t("Copy to goods receipt")}</ButtonLink>}
          {receivable && <Button variant="secondary" loading={busy === "close"} onClick={() => {
            const reason = window.prompt(t("Close {number}? What was received stays; the rest will not come. Reason:", { number: p.number }));
            if (reason) void act("close", () => api("POST", `/purchase-orders/${p.id}/close`, { reason }));
          }}><Lock className="size-4" aria-hidden /> {t("Close order")}</Button>}
          <RelationshipMapButton type="PURCHASE_ORDER" id={p.id} />
          <Button variant="secondary" onClick={() => printFile(`/purchase-orders/${p.id}/pdf`)}><Printer className="size-4" aria-hidden /> {t("Print")}</Button>
          <Button variant="secondary" onClick={() => openFile(`/purchase-orders/${p.id}/pdf`)}><FileDown className="size-4" aria-hidden /> PDF</Button>
        </span>} />
      {problem && <div className="mb-4"><Alert tone="bad">{problem}</Alert></div>}
      {p.closedReason && <div className="mb-4"><Alert tone="info">{t("Closed by hand by {who} on {date}: {reason}", { who: p.closedBy ?? "", date: date(p.closedAt ?? null), reason: p.closedReason })}</Alert></div>}
      {p.status === "PENDING" && me?.role !== "ADMIN" && <div className="mb-4"><Alert tone="info">{t("Waiting for an admin to approve this requisition.")}</Alert></div>}
      <Card className="mb-6">
        <dl className="grid gap-x-6 gap-y-3 p-5 text-sm md:grid-cols-4">
          <div><dt className="text-xs text-muted">{t("Order date")}</dt><dd>{date(p.orderDate)}</dd></div>
          <div><dt className="text-xs text-muted">{t("Delivery date")}</dt><dd>{date(p.deliveryDate) || "—"}</dd></div>
          <div><dt className="text-xs text-muted">{t("Supplier ref.")}</dt><dd>{p.supplierRef ?? "—"}</dd></div>
          <div><dt className="text-xs text-muted">{t("Warehouse")}</dt><dd>{p.warehouseName ?? t("Default warehouse")}</dd></div>
          <div><dt className="text-xs text-muted">{t("Currency")}</dt><dd>{p.currency}{p.currency !== "SAR" && ` · ${Number(p.exchangeRate)} SAR`}</dd></div>
          <div><dt className="text-xs text-muted">{t("Total")}</dt><dd className="font-semibold">{fmtCur(p.totalInclVat, p.currency)}{p.currency !== "SAR" && <span className="ms-1 text-muted">({fmtCur(p.totalSar, "SAR")})</span>}</dd></div>
          {p.approvedBy && <div><dt className="text-xs text-muted">{t("Approved")}</dt><dd>{p.approvedBy} · {dateTime(p.approvedAt)}</dd></div>}
          {p.remarks && <div className="md:col-span-4"><dt className="text-xs text-muted">{t("Remarks")}</dt><dd className="whitespace-pre-wrap" dir="auto">{p.remarks}</dd></div>}
        </dl>
        <table className="w-full border-t border-line text-sm">
          <thead><tr className="border-b border-line text-xs text-muted">
            <th className="px-4 py-2.5 text-start font-medium">{t("Item")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Ordered")}</th>
            <th className="px-4 py-2.5 text-end font-medium">{t("Received")}</th><th className="px-4 py-2.5 text-end font-medium">{t("Open")}</th>
            <th className="px-4 py-2.5 text-end font-medium">{t("Unit cost")}</th>
          </tr></thead>
          <tbody className="divide-y divide-line">
            {p.lines.map((l) => (
              <tr key={l.id}>
                <td className="px-4 py-3" dir="auto">{l.name}</td>
                <td className="tabular px-4 py-3 text-end">{Number(l.quantity)} {l.unitCode}</td>
                <td className="tabular px-4 py-3 text-end">{Number(l.received ?? 0)}</td>
                <td className={`tabular px-4 py-3 text-end ${Number(l.remaining) > 0 ? "font-semibold text-amber-700" : "text-muted"}`}>{Number(l.remaining ?? 0)}</td>
                <td className="tabular px-4 py-3 text-end">{fmtCur(l.unitCost, p.currency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>

      <Card className="mb-6">
        <CardHeader title={t("Attachments")} action={p.status !== "CANCELLED" && (
          <>
            <input ref={fileRef} type="file" className="hidden" onChange={async (e) => {
              const f = e.target.files?.[0];
              if (!f) return;
              setBusy("attach");
              try { await mutate(await uploadFile<PurchaseOrder>(`/purchase-orders/${p.id}/attachments?name=${encodeURIComponent(f.name)}`, f), { revalidate: false }); }
              catch (err) { setProblem(err instanceof ApiError ? err.message : t("Could not reach the server")); }
              finally { setBusy(null); e.target.value = ""; }
            }} />
            <Button variant="secondary" loading={busy === "attach"} onClick={() => fileRef.current?.click()}><Paperclip className="size-4" aria-hidden /> {t("Attach file")}</Button>
          </>
        )} />
        {p.attachments.length === 0 ? <p className="p-5 text-sm text-muted">{t("No attachments.")}</p> : (
          <ul className="divide-y divide-line">
            {p.attachments.map((a) => (
              <li key={a.id} className="flex items-center justify-between px-5 py-2 text-sm">
                <span>{a.fileName} <span className="text-xs text-muted">· {a.createdBy} · {dateTime(a.createdAt)}</span></span>
                <Button variant="ghost" onClick={() => download(`/purchase-orders/${p.id}/attachments/${a.id}`, a.fileName)} aria-label={t("Download")}><Download className="size-4" aria-hidden /></Button>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <Card className="mb-6">
        <CardHeader title={t("Goods receipts")} />
        {!grns?.length ? <p className="p-5 text-sm text-muted">{t("Nothing received yet.")}</p> : (
          <table className="w-full text-sm"><tbody className="divide-y divide-line">
            {grns.map((g) => (
              <tr key={g.id}>
                <td className="tabular px-4 py-3 font-medium"><Link className="text-brand hover:underline" href={`/goods-receipts/${g.id}`}>{g.number}</Link></td>
                <td className="tabular px-4 py-3">{date(g.receiptDate)}</td>
                <td className="px-4 py-3"><StatusPill status={g.status} /></td>
                <td className="px-4 py-3 text-muted">{g.lines.map((l) => `${l.name} × ${Number(l.quantity)}`).join(", ")}</td>
              </tr>
            ))}
          </tbody></table>
        )}
      </Card>

      {p.status !== "CANCELLED" && p.status !== "RECEIVED" && (
        <Button variant="ghost" loading={busy === "cancel"} onClick={() => { if (window.confirm(t("Cancel purchase order {number}?", { number: p.number }))) void act("cancel", () => api("POST", `/purchase-orders/${p.id}/cancel`)); }}>
          <XCircle className="size-4" aria-hidden /> {t("Cancel purchase order")}
        </Button>
      )}
    </>
  );
}
