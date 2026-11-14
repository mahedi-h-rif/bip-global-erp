"use client";

import Link from "next/link";
import { Suspense, useCallback, useEffect, useMemo, useState, type ComponentType, type MouseEvent, type ReactNode } from "react";
import { ExternalLink, Eye, X } from "lucide-react";
import { ApiError } from "@/lib/api";
import { Alert, Button, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

/**
 * Preview state for a list: `row(x)` makes a table row open the preview on click (clicks on links, buttons and
 * inputs inside the row keep doing their own thing), `view(x)` is for the eye button.
 */
export function usePreview<T>() {
  const [current, setCurrent] = useState<T | null>(null);
  const close = useCallback(() => setCurrent(null), []);
  const row = (x: T, className = "") => ({
    className: `${className} cursor-pointer`,
    onClick: (e: MouseEvent<HTMLElement>) => {
      if ((e.target as HTMLElement).closest("a, button, input, select, textarea, label, [role=button]")) return;
      if (window.getSelection()?.toString()) return; // the user is selecting text, not opening
      setCurrent(x);
    },
  });
  return { current, open: setCurrent, close, row };
}

/** The eye button that opens a row's preview. */
export function ViewButton({ onClick, className }: { onClick: () => void; className?: string }) {
  const t = useT();
  return (
    <button type="button" onClick={onClick} title={t("Preview")}
      className={className ?? "inline-flex items-center gap-1 rounded-md border border-line bg-white px-2 py-1 text-xs font-medium text-ink hover:bg-canvas"}>
      <Eye className="size-3.5" aria-hidden /> {t("View")}
    </button>
  );
}

/** A panel that slides over the list from the side. Escape or a click outside closes it. */
export function PreviewDrawer({ title, subtitle, href, wide, onClose, children }: {
  title: ReactNode; subtitle?: ReactNode; href?: string; wide?: boolean; onClose: () => void; children: ReactNode;
}) {
  const t = useT();
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div className="fixed inset-0 z-50 flex justify-end" role="dialog" aria-modal="true" aria-label={t("Preview")}>
      <button type="button" className="absolute inset-0 bg-black/30" aria-label={t("Close")} onClick={onClose} />
      <aside className={`relative flex h-full w-full flex-col bg-white shadow-xl ${wide ? "max-w-4xl" : "max-w-lg"}`}>
        <div className="flex items-start justify-between gap-3 border-b border-line px-5 py-4">
          <div className="min-w-0">
            <h2 className="truncate text-lg font-semibold" dir="auto">{title}</h2>
            {subtitle && <p className="truncate text-sm text-muted" dir="auto">{subtitle}</p>}
          </div>
          <div className="flex shrink-0 items-center gap-1">
            {href && (
              <Link href={href} className="inline-flex items-center gap-1.5 rounded-lg px-3 py-2 text-sm font-medium text-brand hover:bg-canvas">
                <ExternalLink className="size-4" aria-hidden /> {t("Open")}
              </Link>
            )}
            <Button variant="ghost" onClick={onClose} aria-label={t("Close")}><X className="size-5" aria-hidden /></Button>
          </div>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto">{children}</div>
      </aside>
    </div>
  );
}

/** A PDF the API builds, shown inline. */
export function PdfPreview({ path }: { path: string }) {
  const t = useT();
  // Keyed by path, so switching rows shows the spinner until the new file arrives.
  const [loaded, setLoaded] = useState<{ path: string; url?: string; error?: string } | null>(null);
  const url = loaded?.path === path ? loaded.url : undefined;
  const error = loaded?.path === path ? loaded.error : undefined;
  useEffect(() => {
    let objectUrl: string | null = null;
    let cancelled = false;
    fetch(`/api/v1${path}`, { credentials: "same-origin" })
      .then(async (res) => {
        if (!res.ok) {
          const data = await res.json().catch(() => undefined);
          throw new ApiError(res.status, data?.code ?? "error", data?.message ?? t("Could not load the document"));
        }
        return res.blob();
      })
      .then((blob) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setLoaded({ path, url: objectUrl });
      })
      .catch((e) => { if (!cancelled) setLoaded({ path, error: e instanceof Error ? e.message : t("Could not reach the server") }); });
    return () => { cancelled = true; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [path, t]);
  if (error) return <div className="p-5"><Alert tone="bad">{error}</Alert></div>;
  if (!url) return <Spinner />;
  return <iframe src={url} title={t("Preview")} className="h-full min-h-[80vh] w-full border-0" />;
}

/** A document's own detail page (read-only screens), rendered inside the panel. */
export function PagePreview({ page: Page, id }: { page: ComponentType<{ params: Promise<{ id: string }> }>; id: number | string }) {
  const params = useMemo(() => Promise.resolve({ id: String(id) }), [id]);
  return (
    <div className="p-5">
      <Suspense fallback={<Spinner />}><Page params={params} /></Suspense>
    </div>
  );
}

/** Label / value pairs. Empty values are left out. */
export function Details({ items }: { items: [string, ReactNode][] }) {
  const t = useT();
  const shown = items.filter(([, v]) => v !== null && v !== undefined && v !== "" && v !== false);
  return (
    <dl className="divide-y divide-line px-5 py-2">
      {shown.map(([label, value]) => (
        <div key={label} className="flex justify-between gap-4 py-2 text-sm">
          <dt className="shrink-0 text-muted">{t(label)}</dt>
          <dd className="min-w-0 text-end font-medium break-words" dir="auto">{value}</dd>
        </div>
      ))}
    </dl>
  );
}
