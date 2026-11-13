"use client";

import { forwardRef, useDeferredValue, useId, useMemo, useState, type InputHTMLAttributes } from "react";
import useSWR from "swr";
import { fetcher } from "@/lib/api";
import { Input, Select } from "@/components/ui";
import { useLanguage, useT } from "@/lib/i18n";

/** What a suggestion list is built from on the server (see SuggestController). */
export type SuggestField = "party" | "person" | "address" | "street" | "district" | "city" | "nationality";

type SuggestProps = InputHTMLAttributes<HTMLInputElement> & { field: SuggestField; extra?: string[] };

/**
 * A text box that suggests, as the user types, the values already entered for this kind of field (names,
 * addresses, cities), so the same name is spelled the same way everywhere. The user can still type anything new.
 */
export const SuggestInput = forwardRef<HTMLInputElement, SuggestProps>(function SuggestInput({ field, extra, ...rest }, ref) {
  const id = useId();
  const [focused, setFocused] = useState(false);
  const typed = useDeferredValue(String(rest.value ?? "").trim());
  const { data } = useSWR<string[]>(focused && typed.length >= 1 ? `/suggest?field=${field}&q=${encodeURIComponent(typed)}` : null,
    fetcher, { keepPreviousData: true, dedupingInterval: 30_000 });
  const options = useMemo(() => {
    const fromExtra = (extra ?? []).filter((x) => !typed || x.toLowerCase().includes(typed.toLowerCase()));
    return [...new Set([...(data ?? []), ...fromExtra])].filter((x) => x !== typed).slice(0, 12);
  }, [data, extra, typed]);
  return (
    <>
      <Input ref={ref} list={id} autoComplete="off" {...rest}
        onFocus={(e) => { setFocused(true); rest.onFocus?.(e); }}
        onBlur={(e) => { setFocused(false); rest.onBlur?.(e); }} />
      <datalist id={id}>{options.map((o) => <option key={o} value={o} />)}</datalist>
    </>
  );
});

/** ISO 3166 two-letter country codes; names come from the browser in the user's language. */
const COUNTRIES = ("AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ CA CC " +
  "CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR GA GB GD GE GF GG GH GI " +
  "GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB " +
  "LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ " +
  "OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD " +
  "TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW").split(" ");

/** Gulf countries first, then the rest by name. */
const FIRST = ["SA", "AE", "BH", "KW", "OM", "QA"];

export function CountrySelect({ value, onChange, className, required, id }: {
  value: string; onChange: (code: string) => void; className?: string; required?: boolean; id?: string;
}) {
  const t = useT();
  const { lang } = useLanguage();
  const list = useMemo(() => {
    let names: Intl.DisplayNames | null = null;
    try { names = new Intl.DisplayNames([lang, "en"], { type: "region" }); } catch { names = null; }
    const name = (c: string) => names?.of(c) ?? c;
    const rest = COUNTRIES.filter((c) => !FIRST.includes(c)).sort((a, b) => name(a).localeCompare(name(b), lang));
    return [...FIRST, ...rest].map((c) => ({ code: c, name: name(c) }));
  }, [lang]);
  const v = (value ?? "").toUpperCase();
  return (
    <Select id={id} className={className} required={required} value={v} onChange={(e) => onChange(e.target.value)}>
      <option value="">{t("Select country…")}</option>
      {v && !COUNTRIES.includes(v) && <option value={v}>{v}</option>}
      {list.map((c) => <option key={c.code} value={c.code}>{c.name} ({c.code})</option>)}
    </Select>
  );
}

/** Main Saudi cities in English and Arabic: offered in the city box when the country is Saudi Arabia. */
export const SAUDI_CITIES = [
  "Riyadh", "الرياض", "Jeddah", "جدة", "Makkah", "مكة المكرمة", "Madinah", "المدينة المنورة", "Dammam", "الدمام",
  "Khobar", "الخبر", "Dhahran", "الظهران", "Jubail", "الجبيل", "Qatif", "القطيف", "Al Ahsa", "الأحساء", "Hofuf", "الهفوف",
  "Taif", "الطائف", "Tabuk", "تبوك", "Buraidah", "بريدة", "Unaizah", "عنيزة", "Al Rass", "الرس", "Hail", "حائل",
  "Abha", "أبها", "Khamis Mushait", "خميس مشيط", "Najran", "نجران", "Jazan", "جازان", "Sabya", "صبيا", "Yanbu", "ينبع",
  "Rabigh", "رابغ", "Al Kharj", "الخرج", "Al Majmaah", "المجمعة", "Al Zulfi", "الزلفي", "Shaqra", "شقراء", "Dawadmi", "الدوادمي",
  "Arar", "عرعر", "Sakaka", "سكاكا", "Al Qurayyat", "القريات", "Hafr Al Batin", "حفر الباطن", "Al Khafji", "الخفجي",
  "Ras Tanura", "رأس تنورة", "Al Baha", "الباحة", "Bisha", "بيشة", "Al Qunfudhah", "القنفذة", "Al Ula", "العلا", "Duba", "ضباء",
];

/** City box: a dropdown of Saudi cities (for Saudi addresses) plus cities already used; any other city can be typed. */
export const CityInput = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement> & { country?: string | null }>(
  function CityInput({ country, ...rest }, ref) {
    const saudi = !country || country.toUpperCase() === "SA";
    return <SuggestInput ref={ref} field="city" extra={saudi ? SAUDI_CITIES : []} {...rest} />;
  });
