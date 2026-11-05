"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import { Check, Mic, Search, Square, X } from "lucide-react";
import type { Customer } from "@/lib/types";
import { languages, useT } from "@/lib/i18n";
import { useSpeech, useSpeechSupported, useVoiceLanguage } from "@/lib/speech";
import { parseCommand, searchCustomers, sendToInvoiceEditor } from "@/lib/voice-parse";
import { Button, Input } from "@/components/ui";

/**
 * A small mic inside an input: tap, speak, and the words go to `onText`. Hidden in browsers without
 * speech recognition, so the input still works by typing.
 */
export function MicButton({ onText, label, className = "" }: { onText: (text: string) => void; label?: string; className?: string }) {
  const t = useT();
  const supported = useSpeechSupported();
  const { listening, interim, error, toggle } = useSpeech(onText);
  if (!supported) return null;
  return (
    <span className={`absolute end-1.5 top-1/2 -translate-y-1/2 ${className}`}>
      <button
        type="button"
        onClick={toggle}
        aria-pressed={listening}
        aria-label={listening ? t("Stop listening") : label ?? t("Speak")}
        title={listening ? t("Stop listening") : label ?? t("Speak")}
        className={`grid size-7 place-items-center rounded-md transition ${listening ? "animate-pulse bg-bad text-white" : "text-muted hover:bg-canvas hover:text-brand"}`}
      >
        {listening ? <Square className="size-3.5" aria-hidden /> : <Mic className="size-4" aria-hidden />}
      </button>
      {(listening || error) && (
        <span
          role="status"
          className={`absolute end-0 top-full z-40 mt-1 w-max max-w-64 rounded-md px-2 py-1 text-xs shadow-lg ${error ? "bg-bad text-white" : "bg-ink text-white"}`}
        >
          {error ? t(error) : interim || t("Listening…")}
        </span>
      )}
    </span>
  );
}

/** The search box used on list pages, with a mic to search by voice. */
export function SearchInput({ value, onChange, placeholder }: { value: string; onChange: (q: string) => void; placeholder: string }) {
  const t = useT();
  return (
    <div className="relative">
      <Search className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted" aria-hidden />
      <Input className="ps-9 pe-10" type="search" placeholder={placeholder} value={value} onChange={(e) => onChange(e.target.value)} aria-label={placeholder} />
      <MicButton onText={onChange} label={t("Search by voice")} />
    </div>
  );
}

/** A full-size button that listens to one sentence, showing the words as they are heard. */
export function DictateButton({ onText, children }: { onText: (text: string) => void; children: React.ReactNode }) {
  const t = useT();
  const supported = useSpeechSupported();
  const { listening, interim, error, toggle } = useSpeech(onText);
  if (!supported) return null;
  return (
    <span className="inline-flex flex-wrap items-center gap-2">
      <Button type="button" variant={listening ? "danger" : "secondary"} aria-pressed={listening} onClick={toggle}>
        {listening ? <Square className="size-4 animate-pulse" aria-hidden /> : <Mic className="size-4" aria-hidden />}
        {listening ? t("Stop listening") : children}
      </Button>
      {(listening || error) && (
        <span role="status" className={`text-xs ${error ? "text-bad" : "text-muted"}`} dir="auto">
          {error ? t(error) : interim || t("Listening…")}
        </span>
      )}
    </span>
  );
}

const customerName = (c: Customer) => c.nameEn || c.nameAr || "";

/**
 * Pick a customer by typing or speaking part of the name, VAT number, phone or city.
 * `value` is the customer id as a string, "" for none.
 */
export function CustomerPicker({
  customers,
  value,
  onChange,
  emptyLabel,
  disabled,
}: {
  customers: Customer[] | undefined;
  value: string;
  onChange: (id: string) => void;
  emptyLabel: string;
  disabled?: boolean;
}) {
  const t = useT();
  const listId = useId();
  const box = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState<string | null>(null);
  const [active, setActive] = useState(0);
  const selected = customers?.find((c) => String(c.id) === value);
  const open = query != null;
  const matches = useMemo(() => searchCustomers(customers ?? [], query ?? "").slice(0, 8), [customers, query]);

  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => { if (!box.current?.contains(e.target as Node)) setQuery(null); };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, [open]);

  function choose(c: Customer | null) {
    onChange(c ? String(c.id) : "");
    setQuery(null);
  }

  /** Spoken name: picks the customer straight away when only one matches. */
  function spoken(text: string) {
    const found = searchCustomers(customers ?? [], text);
    if (found.length === 1) choose(found[0]);
    else {
      setQuery(text);
      setActive(0);
      input.current?.focus();
    }
  }

  function onKey(e: React.KeyboardEvent) {
    if (e.key === "ArrowDown") { e.preventDefault(); if (!open) setQuery(""); setActive((a) => Math.min(a + 1, matches.length - 1)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setActive((a) => Math.max(a - 1, 0)); }
    else if (e.key === "Enter" && open) { e.preventDefault(); if (matches[active]) choose(matches[active]); }
    else if (e.key === "Escape") setQuery(null);
  }

  const shown = open ? query : selected ? `${customerName(selected)}${selected.vatNumber ? ` · ${t("VAT")} ${selected.vatNumber}` : ""}` : "";

  return (
    <div ref={box} className="relative">
      <Search className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted" aria-hidden />
      <Input
        ref={input}
        role="combobox"
        aria-expanded={open}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={open && matches[active] ? `${listId}-${active}` : undefined}
        className={`ps-9 ${selected && !disabled ? "pe-16" : "pe-10"}`}
        placeholder={selected ? customerName(selected) : emptyLabel}
        value={shown}
        disabled={disabled}
        onFocus={() => { if (!open) { setQuery(""); setActive(0); } }}
        onChange={(e) => { setQuery(e.target.value); setActive(0); }}
        onKeyDown={onKey}
      />
      {!disabled && (
        <>
          {selected && (
            <button
              type="button"
              onClick={() => choose(null)}
              aria-label={t("Clear customer")}
              className="absolute end-9 top-1/2 grid size-7 -translate-y-1/2 place-items-center rounded-md text-muted hover:bg-canvas hover:text-ink"
            >
              <X className="size-4" aria-hidden />
            </button>
          )}
          <MicButton onText={spoken} label={t("Say the customer's name")} />
        </>
      )}
      {open && (
        <ul id={listId} role="listbox" className="absolute inset-x-0 top-full z-30 mt-1 max-h-80 overflow-y-auto rounded-lg border border-line bg-white py-1 shadow-xl">
          <li
            role="option"
            aria-selected={value === ""}
            onMouseDown={(e) => { e.preventDefault(); choose(null); }}
            className="cursor-pointer px-3 py-2 text-sm text-muted hover:bg-canvas"
          >
            {emptyLabel}
          </li>
          {matches.length === 0 && <li className="px-3 py-3 text-sm text-muted">{t("No customer matches “{q}”", { q: query })}</li>}
          {matches.map((c, i) => (
            <li
              key={c.id}
              id={`${listId}-${i}`}
              role="option"
              aria-selected={String(c.id) === value}
              onMouseDown={(e) => { e.preventDefault(); choose(c); }}
              onMouseEnter={() => setActive(i)}
              className={`flex cursor-pointer items-start justify-between gap-3 px-3 py-2 text-sm ${i === active ? "bg-brand/5" : ""}`}
            >
              <span className="min-w-0">
                <span className="block truncate font-medium">
                  {customerName(c)} {c.nameEn && c.nameAr && <span className="ar font-normal text-muted" dir="rtl">{c.nameAr}</span>}
                </span>
                <span className="block truncate text-xs text-muted">
                  {[c.vatNumber ? `${t("VAT")} ${c.vatNumber}` : c.otherId ? [c.otherIdScheme, c.otherId].filter(Boolean).join(" ") : t("No tax ID"), c.phone, c.city].filter(Boolean).join(" · ")}
                </span>
              </span>
              {String(c.id) === value && <Check className="mt-0.5 size-4 shrink-0 text-brand" aria-hidden />}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

const examples = [
  "New invoice for Al Noor, 5 cement at 20 and 2 sand, cash, full paid",
  "Find customer Al Noor",
  "فاتورة جديدة لشركة النور ٥ اسمنت بسعر ٢٠",
  "ابحث عن عميل النور",
];

/**
 * Astha voice: the mic in the sidebar header. Speak a command to start a new invoice already
 * filled in, or to find a customer. Nothing is issued or sent to ZATCA by voice; staff check the
 * filled-in invoice and issue it themselves.
 */
export function VoiceAssistant({ className = "" }: { className?: string }) {
  const t = useT();
  const router = useRouter();
  const pathname = usePathname();
  const supported = useSpeechSupported();
  const [open, setOpen] = useState(false);
  const [heard, setHeard] = useState("");
  const [notUnderstood, setNotUnderstood] = useState(false);
  const [voiceLang, setVoiceLang] = useVoiceLanguage();
  const box = useRef<HTMLDivElement>(null);

  const { listening, interim, error, start, stop } = useSpeech((text) => {
    setHeard(text);
    run(text);
  });

  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => { if (!box.current?.contains(e.target as Node)) setOpen(false); };
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", esc);
    return () => { document.removeEventListener("mousedown", close); document.removeEventListener("keydown", esc); };
  }, [open]);

  function run(text: string) {
    const cmd = parseCommand(text);
    setNotUnderstood(false);
    if (cmd.intent === "newInvoice") {
      sendToInvoiceEditor(cmd.text);
      if (pathname !== "/invoices/new") router.push("/invoices/new");
      setOpen(false);
    } else if (cmd.intent === "searchCustomer") {
      router.push(`/customers?q=${encodeURIComponent(cmd.query)}`);
      setOpen(false);
    } else {
      setNotUnderstood(true);
    }
  }

  if (!supported) return null;

  return (
    <div ref={box} className={`relative ${className}`}>
      <button
        type="button"
        onClick={() => { setOpen(!open); setHeard(""); setNotUnderstood(false); }}
        aria-expanded={open}
        aria-label={t("Astha voice")}
        title={t("Astha voice")}
        className={`grid size-11 place-items-center rounded-lg text-white/80 hover:bg-white/10 hover:text-white focus-visible:outline-white lg:size-9 ${listening ? "bg-bad/80 text-white" : ""}`}
      >
        <Mic className="size-5" aria-hidden />
      </button>
      {open && (
        <div className="fixed inset-x-2 top-14 z-50 overflow-hidden lg:absolute lg:inset-x-auto lg:start-0 lg:top-11 lg:w-88 rounded-xl border border-line bg-white text-ink shadow-xl">
          <div className="flex items-center justify-between gap-2 border-b border-line px-4 py-3">
            <p className="text-sm font-semibold">{t("Astha voice")}</p>
            <label className="inline-flex items-center gap-1.5 text-xs text-muted">
              {t("Voice language")}
              <select
                value={voiceLang}
                onChange={(e) => setVoiceLang(e.target.value as typeof voiceLang)}
                className="rounded-md border border-line bg-white px-1.5 py-1 text-xs text-ink"
              >
                {languages.map((l) => <option key={l.code} value={l.code}>{l.name}</option>)}
              </select>
            </label>
          </div>
          <div className="space-y-4 p-4">
            <div className="flex flex-col items-center gap-2 text-center">
              <button
                type="button"
                onClick={listening ? stop : start}
                aria-pressed={listening}
                aria-label={listening ? t("Stop listening") : t("Speak")}
                className={`grid size-16 place-items-center rounded-full text-white shadow-lg transition ${listening ? "animate-pulse bg-bad" : "bg-brand hover:brightness-110"}`}
              >
                {listening ? <Square className="size-6" aria-hidden /> : <Mic className="size-7" aria-hidden />}
              </button>
              <p role="status" className="min-h-10 text-sm" dir="auto">
                {error ? <span className="text-bad">{t(error)}</span>
                  : listening ? (interim || <span className="text-muted">{t("Listening…")}</span>)
                  : heard ? <span className="text-muted">“{heard}”</span>
                  : <span className="text-muted">{t("Tap the mic and say what you need.")}</span>}
              </p>
              {notUnderstood && (
                <p className="text-xs text-warn">{t("Say “new invoice …” to make a fatoora, or “find customer …” to search.")}</p>
              )}
            </div>
            <div>
              <p className="mb-1.5 text-xs font-medium text-muted">{t("For example")}</p>
              <ul className="space-y-1">
                {examples.map((ex) => (
                  <li key={ex}>
                    <button
                      type="button"
                      onClick={() => { setHeard(ex); run(ex); }}
                      className="w-full rounded-md bg-canvas px-2.5 py-1.5 text-start text-xs hover:bg-brand/5"
                      dir="auto"
                    >
                      {ex}
                    </button>
                  </li>
                ))}
              </ul>
              <p className="mt-3 text-[11px] leading-snug text-muted">
                {t("Voice only fills in the invoice. Check it, then issue it yourself. Your browser's speech service turns the audio into text.")}
              </p>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
