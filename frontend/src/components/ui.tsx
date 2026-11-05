"use client";

import Link from "next/link";
import {
  forwardRef,
  isValidElement,
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from "react";
import { Loader2 } from "lucide-react";
import type { InvoiceStatus, PaymentStatus } from "@/lib/types";
import { paymentStatusLabel, statusLabel } from "@/lib/format";
import { useT } from "@/lib/i18n";

type Variant = "primary" | "success" | "secondary" | "danger" | "ghost";

const variants: Record<Variant, string> = {
  primary: "bg-brand text-white hover:brightness-110 shadow-sm",
  success: "bg-ok text-white hover:brightness-110 shadow-sm",
  secondary: "bg-white text-ink border border-line hover:bg-canvas",
  danger: "bg-white text-bad border border-bad/30 hover:bg-bad/5",
  ghost: "text-muted hover:text-ink hover:bg-canvas",
};

export const Button = forwardRef<
  HTMLButtonElement,
  ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; loading?: boolean }
>(function Button({ variant = "primary", loading, className = "", children, disabled, ...rest }, ref) {
  return (
    <button
      ref={ref}
      disabled={disabled || loading}
      className={`inline-flex items-center justify-center gap-2 rounded-lg px-3.5 py-2 text-sm font-medium transition disabled:cursor-not-allowed disabled:opacity-50 ${variants[variant]} ${className}`}
      {...rest}
    >
      {loading && <Loader2 className="size-4 animate-spin" aria-hidden />}
      {children}
    </button>
  );
});

export function ButtonLink({ href, children, variant = "primary" }: { href: string; children: ReactNode; variant?: Variant }) {
  return (
    <Link
      href={href}
      className={`inline-flex items-center justify-center gap-2 rounded-lg px-3.5 py-2 text-sm font-medium transition ${variants[variant]}`}
    >
      {children}
    </Link>
  );
}

/** True when the field's control (the direct child) is marked `required`. */
function childRequired(children: ReactNode): boolean {
  return isValidElement<{ required?: boolean }>(children) && children.props.required === true;
}

/**
 * A labelled form field. Required fields get a red asterisk after the label (with "required" for screen
 * readers); optional ones get a muted "(optional)". A field is required when `required` is passed or its
 * control (the direct child) has `required`; pass `optional` to mark an optional field. Fields with neither
 * (filters, read-only values) show no marker.
 */
export function Field({ label, hint, error, children, className = "", required, optional }: {
  label: string;
  hint?: ReactNode;
  error?: string;
  children: ReactNode;
  className?: string;
  required?: boolean;
  optional?: boolean;
}) {
  const t = useT();
  const isRequired = required ?? childRequired(children);
  return (
    <label className={`block ${className}`}>
      <span className="mb-1 block text-xs font-medium text-muted">
        {label}
        {isRequired ? (
          <RequiredMark />
        ) : optional ? (
          <span className="ms-1 font-normal text-muted/80">({t("optional")})</span>
        ) : null}
      </span>
      {children}
      {error ? <span className="mt-1 block text-xs text-bad">{error}</span> : hint ? <span className="mt-1 block text-xs text-muted">{hint}</span> : null}
    </label>
  );
}

/** The required asterisk on its own, for column headers of line tables. */
export function RequiredMark() {
  const t = useT();
  return (
    <>
      <span aria-hidden className="ms-0.5 text-bad">*</span>
      <span className="sr-only"> ({t("required")})</span>
    </>
  );
}

/** One-line key for the markers on a form: "* Required field". */
export function RequiredLegend({ className = "" }: { className?: string }) {
  const t = useT();
  return (
    <p className={`text-xs text-muted ${className}`}>
      <span aria-hidden className="text-bad">*</span> {t("Required field")}
    </p>
  );
}

const inputClass =
  "w-full rounded-lg border border-line bg-white px-3 py-2 text-sm text-ink placeholder:text-muted/60 focus:border-brand focus:outline-none focus:ring-2 focus:ring-brand/15 disabled:bg-canvas";

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(function Input(
  { className = "", ...rest },
  ref,
) {
  return <input ref={ref} className={`${inputClass} ${className}`} {...rest} />;
});

export function Select({ className = "", children, ...rest }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select className={`${inputClass} ${className}`} {...rest}>
      {children}
    </select>
  );
}

export function Card({ children, className = "", id }: { children: ReactNode; className?: string; id?: string }) {
  return <section id={id} className={`rounded-xl border border-line bg-white shadow-[0_1px_2px_rgba(16,24,40,0.04)] ${className}`}>{children}</section>;
}

export function CardHeader({ title, action, subtitle }: { title: ReactNode; subtitle?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-4 border-b border-line px-5 py-4">
      <div>
        <h2 className="text-sm font-semibold">{title}</h2>
        {subtitle && <p className="mt-0.5 text-xs text-muted">{subtitle}</p>}
      </div>
      {action}
    </div>
  );
}

export function PageHeader({ title, subtitle, actions }: { title: ReactNode; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-muted">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

const statusStyle: Record<InvoiceStatus, string> = {
  DRAFT: "bg-slate-100 text-slate-700",
  SIGNED: "bg-amber-50 text-amber-800 ring-amber-200",
  CLEARED: "bg-emerald-50 text-emerald-800 ring-emerald-200",
  CLEARED_WITH_WARNINGS: "bg-emerald-50 text-emerald-800 ring-amber-300",
  REPORTED: "bg-sky-50 text-sky-800 ring-sky-200",
  REPORTED_WITH_WARNINGS: "bg-sky-50 text-sky-800 ring-amber-300",
  REJECTED: "bg-red-50 text-red-700 ring-red-200",
};

export function StatusBadge({ status }: { status: InvoiceStatus }) {
  const t = useT();
  return (
    <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-transparent ring-inset ${statusStyle[status]}`}>
      {t(statusLabel[status])}
    </span>
  );
}

const paymentStyle: Record<PaymentStatus, string> = {
  UNPAID: "bg-red-50 text-red-700",
  PARTIAL: "bg-amber-50 text-amber-800",
  PAID: "bg-emerald-50 text-emerald-800",
};

export function PaymentBadge({ status }: { status: PaymentStatus | null | undefined }) {
  const t = useT();
  if (!status) return <span className="text-xs text-muted">—</span>;
  return (
    <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${paymentStyle[status]}`}>
      {t(paymentStatusLabel[status])}
    </span>
  );
}

export function Textarea({ className = "", ...rest }: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className={`${inputClass} ${className}`} {...rest} />;
}

export function Alert({ tone = "info", title, children }: { tone?: "info" | "warn" | "bad" | "ok"; title?: string; children: ReactNode }) {
  const tones = {
    info: "border-sky-200 bg-sky-50 text-sky-900",
    warn: "border-amber-200 bg-amber-50 text-amber-900",
    bad: "border-red-200 bg-red-50 text-red-900",
    ok: "border-emerald-200 bg-emerald-50 text-emerald-900",
  };
  return (
    <div role={tone === "bad" ? "alert" : "status"} className={`rounded-lg border px-4 py-3 text-sm ${tones[tone]}`}>
      {title && <p className="font-semibold">{title}</p>}
      <div className={title ? "mt-1" : ""}>{children}</div>
    </div>
  );
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="px-6 py-14 text-center">
      <p className="text-sm font-medium">{title}</p>
      {children && <div className="mt-2 text-sm text-muted">{children}</div>}
    </div>
  );
}

export function Spinner() {
  const t = useT();
  return (
    <div className="flex justify-center py-16 text-muted" aria-label={t("Loading")}>
      <Loader2 className="size-6 animate-spin" />
    </div>
  );
}
