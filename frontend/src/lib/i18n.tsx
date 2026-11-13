"use client";

/**
 * Lightweight client-side translations.
 *
 * - Keys are the English UI text itself, so English needs no dictionary and any key missing from a
 *   language falls back to English.
 * - Placeholders are written as {name} and filled from the `vars` argument: t("Page {n} of {total}", { n, total }).
 * - The chosen language is saved per device in localStorage. No saved choice means "ask after login"
 *   (see LanguagePrompt), with English shown meanwhile.
 *
 * To add text: wrap it in t("...") and add the same English string as a key to the Arabic dictionary
 * in src/lib/translations/ar.ts.
 */

import { createContext, Fragment, useCallback, useContext, useEffect, useMemo, useSyncExternalStore, type ReactNode } from "react";
import type { FlagCode } from "@/components/flags";
import { ar } from "@/lib/translations/ar";

export type Lang = "en" | "ar";

export type LanguageInfo = {
  code: Lang;
  /** The language's name in its own script. */
  name: string;
  /** The name in English, shown under the native name. */
  english: string;
  flags: readonly FlagCode[];
  dir: "ltr" | "rtl";
};

export const languages: readonly LanguageInfo[] = [
  { code: "en", name: "English", english: "English", flags: ["gb"], dir: "ltr" },
  { code: "ar", name: "العربية", english: "Arabic", flags: ["sa"], dir: "rtl" },
];

const dictionaries: Record<Lang, Record<string, string>> = { en: {}, ar };

const STORAGE_KEY = "fatoora.language";
const CHANGE_EVENT = "fatoora-language-change";

function isLang(v: unknown): v is Lang {
  return v === "en" || v === "ar";
}

/** The saved language on this device, or null when none was chosen yet (or storage is blocked). */
function readStored(): Lang | null {
  try {
    const v = window.localStorage.getItem(STORAGE_KEY);
    return isLang(v) ? v : null;
  } catch {
    return null;
  }
}

function writeStored(lang: Lang) {
  try {
    window.localStorage.setItem(STORAGE_KEY, lang);
  } catch {
    // Storage blocked (private mode etc.): the choice still applies until the page is closed.
  }
  memory = lang;
  window.dispatchEvent(new Event(CHANGE_EVENT));
}

/** Fallback when localStorage can't be written, so the choice still works in this tab. */
let memory: Lang | null = null;

function subscribe(onChange: () => void) {
  window.addEventListener(CHANGE_EVENT, onChange);
  window.addEventListener("storage", onChange);
  return () => {
    window.removeEventListener(CHANGE_EVENT, onChange);
    window.removeEventListener("storage", onChange);
  };
}

const getSnapshot = () => readStored() ?? memory;
// The server (and the first client render) has no saved language: English, no prompt yet.
const getServerSnapshot = () => undefined;

export type Vars = Record<string, string | number | null | undefined>;
export type TFunction = (key: string, vars?: Vars) => string;

function translate(lang: Lang, key: string, vars?: Vars) {
  const text = dictionaries[lang][key] ?? key;
  if (!vars) return text;
  return text.replace(/\{(\w+)\}/g, (m, name: string) => (name in vars ? String(vars[name] ?? "") : m));
}

type Ctx = {
  lang: Lang;
  info: LanguageInfo;
  /** null: this device has no saved language yet. undefined: not known yet (server render / hydration). */
  saved: Lang | null | undefined;
  setLang: (lang: Lang) => void;
  t: TFunction;
};

const LanguageContext = createContext<Ctx | null>(null);

export function LanguageProvider({ children }: { children: ReactNode }) {
  const saved = useSyncExternalStore(subscribe, getSnapshot, getServerSnapshot);
  const lang: Lang = saved ?? "en";
  const info = languages.find((l) => l.code === lang)!;

  // Direction and language on <html>, so Arabic lays out right-to-left and fonts pick the script.
  useEffect(() => {
    const html = document.documentElement;
    html.lang = lang;
    html.dir = info.dir;
  }, [lang, info.dir]);

  const t = useCallback<TFunction>((key, vars) => translate(lang, key, vars), [lang]);
  const value = useMemo<Ctx>(() => ({ lang, info, saved, setLang: writeStored, t }), [lang, info, saved, t]);
  return <LanguageContext.Provider value={value}>{children}</LanguageContext.Provider>;
}

export function useLanguage(): Ctx {
  const ctx = useContext(LanguageContext);
  if (!ctx) throw new Error("useLanguage must be used inside <LanguageProvider>");
  return ctx;
}

/** The translate function for the current language. */
export function useT(): TFunction {
  return useLanguage().t;
}

/**
 * Translated text with React elements in the placeholders, e.g.
 * <Trans k="Renew it under {link}." values={{ link: <Link href="/x">ZATCA devices</Link> }} />
 * Also usable from server components for plain text: <Trans k="New invoice" />.
 */
export function Trans({ k, values }: { k: string; values?: Record<string, ReactNode> }) {
  const { lang } = useLanguage();
  const text = dictionaries[lang][k] ?? k;
  if (!values) return <>{text}</>;
  const parts = text.split(/\{(\w+)\}/g);
  return (
    <>
      {parts.map((part, i) => (i % 2 === 1 ? <Fragment key={i}>{part in values ? values[part] : `{${part}}`}</Fragment> : part))}
    </>
  );
}
