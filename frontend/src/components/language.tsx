"use client";

import { useEffect, useRef, useState } from "react";
import { Check, ChevronDown, Languages } from "lucide-react";
import { Flags } from "@/components/flags";
import { languages, useLanguage, type Lang } from "@/lib/i18n";

/**
 * The language menu: the current flag and name; opens the four languages with their flags.
 * `tone` matches the background (the dark sidebar or a light page), `placement` opens it up or down.
 */
export function LanguageSwitcher({
  tone = "light",
  placement = "down",
  compact = false,
  className = "",
}: {
  tone?: "light" | "dark";
  placement?: "up" | "down";
  /** Flags only, for tight spaces such as the mobile header. */
  compact?: boolean;
  className?: string;
}) {
  const { lang, info, setLang, t } = useLanguage();
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (root.current && !root.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  function choose(code: Lang) {
    setLang(code);
    setOpen(false);
  }

  const button =
    tone === "dark"
      ? "border-white/20 bg-white/10 text-white hover:bg-white/15"
      : "border-line bg-white text-ink hover:bg-canvas shadow-sm";

  return (
    <div ref={root} className={`relative ${className}`}>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={`${t("Language")}: ${info.name}`}
        className={`inline-flex w-full items-center gap-2 rounded-lg border px-2.5 py-1.5 text-sm transition ${button}`}
      >
        {compact ? <Languages className="size-4 shrink-0 opacity-70" aria-hidden /> : null}
        <Flags codes={info.flags} />
        {!compact && <span className="min-w-0 flex-1 truncate text-start">{info.name}</span>}
        <ChevronDown className={`size-3.5 shrink-0 opacity-70 transition ${open ? "rotate-180" : ""}`} aria-hidden />
      </button>
      {open && (
        <ul
          role="listbox"
          aria-label={t("Language")}
          className={`absolute end-0 z-50 w-56 overflow-hidden rounded-lg border border-line bg-white py-1 text-ink shadow-lg ${
            placement === "up" ? "bottom-full mb-1.5" : "top-full mt-1.5"
          }`}
        >
          {languages.map((l) => (
            <li key={l.code} role="option" aria-selected={l.code === lang}>
              <button
                type="button"
                lang={l.code}
                dir={l.dir}
                onClick={() => choose(l.code)}
                className={`flex w-full items-center gap-3 px-3 py-2 text-start text-sm hover:bg-canvas ${l.code === lang ? "font-medium" : ""}`}
              >
                <Flags codes={l.flags} />
                <span className="min-w-0 flex-1">
                  {l.name}
                  {l.name !== l.english && <span className="ms-1.5 text-xs text-muted" lang="en">{l.english}</span>}
                </span>
                {l.code === lang && <Check className="size-4 text-brand" aria-hidden />}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/**
 * Asks for a language the first time someone signs in on this device. Shown only while the device has no
 * saved language; once one is chosen (here or in the menu) it never appears again on this device.
 */
export function LanguagePrompt() {
  const { saved, setLang } = useLanguage();
  if (saved !== null) return null;

  return (
    <div className="fixed inset-0 z-[100] grid place-items-center bg-ink/50 px-4 backdrop-blur-sm">
      <div role="dialog" aria-modal="true" aria-labelledby="language-prompt-title" className="w-full max-w-md rounded-2xl bg-white p-6 shadow-xl">
        <div className="mb-5 text-center">
          <span className="mx-auto mb-3 grid size-11 place-items-center rounded-xl bg-brand/10 text-brand">
            <Languages className="size-5" aria-hidden />
          </span>
          <h2 id="language-prompt-title" className="text-lg font-semibold">Choose your language</h2>
          {/* Every language's own wording, since the user hasn't chosen one yet. */}
          <p className="mt-1 text-sm text-muted">
            <span lang="bn">আপনার ভাষা বেছে নিন</span> · <span lang="hi">अपनी भाषा चुनें</span> ·{" "}
            <span lang="ar" dir="rtl">اختر لغتك</span>
          </p>
        </div>
        <div className="grid gap-2.5 sm:grid-cols-2">
          {languages.map((l) => (
            <button
              key={l.code}
              type="button"
              lang={l.code}
              dir={l.dir}
              onClick={() => setLang(l.code)}
              className="flex items-center gap-3 rounded-xl border border-line px-4 py-3 text-start transition hover:border-brand hover:bg-brand/5 focus-visible:border-brand"
            >
              <Flags codes={l.flags} className="h-5 w-7" />
              <span className="min-w-0">
                <span className="block text-base font-semibold">{l.name}</span>
                <span className="block text-xs text-muted" lang="en">{l.english}</span>
              </span>
            </button>
          ))}
        </div>
        <p className="mt-4 text-center text-xs text-muted">You can change it any time from the menu.</p>
      </div>
    </div>
  );
}
