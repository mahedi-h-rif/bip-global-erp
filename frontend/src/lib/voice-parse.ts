/**
 * Understanding spoken (or typed) text in English, Arabic, Bangla and Hindi: customer search, and
 * filling a new invoice from a sentence such as
 *   "new invoice for Al Noor, 5 cement at 20 and 2 sand, cash, full paid"
 *   "فاتورة جديدة لشركة النور ٥ اسمنت بسعر ٢٠ و ٢ رمل نقدا مدفوعة"
 *
 * Matching is by words, tolerant of one wrong letter in longer words, because speech recognition
 * often spells names slightly differently from how they were typed.
 */

import type { Customer, InvoiceKind, Product } from "@/lib/types";

const isNumber = (w: string) => /^\d+(\.\d+)?$/.test(w);

const digitBlocks = [0x0660, 0x06f0, 0x09e6, 0x0966]; // Arabic-Indic, Persian, Bangla, Devanagari zero

/** Lower case, ASCII digits, no Arabic diacritics, one spelling for the Arabic letters speech mixes up. */
export function normalize(s: string): string {
  let out = "";
  for (const ch of s.normalize("NFKC").toLowerCase()) {
    const code = ch.codePointAt(0)!;
    const block = digitBlocks.find((z) => code >= z && code <= z + 9);
    out += block != null ? String(code - block) : ch;
  }
  return out
    .replace(/[ً-ٰٟـ]/g, "") // harakat, dagger alef, tatweel
    .replace(/[أإآٱ]/g, "ا")
    .replace(/ة/g, "ه")
    .replace(/ى/g, "ي")
    .replace(/(\d)[٫,](\d)/g, "$1.$2")
    .replace(/(\p{L})(\d)/gu, "$1 $2") // "ب20" → "ب 20"
    .replace(/(\d)(\p{L})/gu, "$1 $2")
    .replace(/[^\p{L}\p{M}\p{N}.@+]+/gu, " ")
    .replace(/(?<!\d)\.|\.(?!\d)/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

/** Digits only, without a leading Saudi country code or 0, so 05x, 9665x and +9665x compare equal. */
function phoneDigits(s: string) {
  return normalize(s).replace(/\D/g, "").replace(/^(00)?966/, "").replace(/^0+/, "");
}

const numberWordList: Record<string, number> = {
  // English
  one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10,
  eleven: 11, twelve: 12, fifteen: 15, twenty: 20, thirty: 30, forty: 40, fifty: 50, hundred: 100,
  // Arabic (after normalize: ة→ه, أ→ا)
  واحد: 1, واحده: 1, اثنين: 2, اثنان: 2, ثنتين: 2, ثلاث: 3, ثلاثه: 3, اربع: 4, اربعه: 4, خمس: 5, خمسه: 5,
  ست: 6, سته: 6, سبع: 7, سبعه: 7, ثمان: 8, ثمانيه: 8, تسع: 9, تسعه: 9, عشر: 10, عشره: 10, عشرين: 20, خمسين: 50, ميه: 100, مايه: 100, مائه: 100,
  // Bangla
  এক: 1, দুই: 2, দু: 2, তিন: 3, চার: 4, পাঁচ: 5, ছয়: 6, সাত: 7, আট: 8, নয়: 9, দশ: 10, বিশ: 20, পঞ্চাশ: 50, একশ: 100,
  // Hindi
  एक: 1, दो: 2, तीन: 3, चार: 4, पांच: 5, पाँच: 5, छह: 6, छः: 6, सात: 7, आठ: 8, नौ: 9, दस: 10, बीस: 20, पचास: 50, सौ: 100,
};

/** A word list spelled the way normalize() spells input, so lookups match. */
const wordSet = (words: string[]) => new Set(words.map(normalize));
const numberWords = new Map(Object.entries(numberWordList).map(([w, n]) => [normalize(w), n]));

function tokens(s: string): string[] {
  return normalize(s)
    .split(" ")
    .filter(Boolean)
    .flatMap((w) => {
      const n = numberWords.get(w);
      if (n != null) return [String(n)];
      // Arabic joins "and" to the next word: "و5", "وخمسه".
      const rest = w.length > 1 && w.startsWith("و") ? w.slice(1) : null;
      if (rest && (isNumber(rest) || numberWords.has(rest))) return ["و", String(numberWords.get(rest) ?? rest)];
      return [w];
    });
}

/** Edit distance, stopping early once it is over `max`. */
function distance(a: string, b: string, max: number): number {
  if (Math.abs(a.length - b.length) > max) return max + 1;
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    let best = i;
    for (let j = 1; j <= b.length; j++) {
      cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
      best = Math.min(best, cur[j]);
    }
    if (best > max) return max + 1;
    prev = cur;
  }
  return prev[b.length];
}

/** A Latin name without its vowels after the first letter: "noor" and "nur" → "nr", "raheem" → "rhm". */
const skeleton = (w: string) => /^[a-z]+$/.test(w) ? (w[0] + w.slice(1).replace(/[aeiouy]/g, "")).replace(/(.)+/g, "$1") : null;

/**
 * Same word, allowing one wrong letter in words of 4+ letters (two in 8+). Arabic "ال" is ignored.
 * `names`: also equal when the Latin spellings differ only in vowels, as transliterated Arabic
 * names often do (Noor / Nur, Rahim / Raheem).
 */
function sameWord(a: string, b: string, names = false): boolean {
  if (a === b) return true;
  const strip = (w: string) => (w.length > 3 && w.startsWith("ال") ? w.slice(2) : w);
  const x = strip(a);
  const y = strip(b);
  if (x === y) return true;
  if (isNumber(x) || isNumber(y)) return false;
  if (names) {
    const sx = skeleton(x);
    if (sx && sx.length >= 2 && sx === skeleton(y)) return true;
  }
  const len = Math.min(x.length, y.length);
  if (len < 4) return false;
  return distance(x, y, len >= 8 ? 2 : 1) <= (len >= 8 ? 2 : 1);
}

/** Where `name` appears as consecutive words in `words` (not already used), or null. */
function findWords(words: string[], used: boolean[], name: string[], names = false): number | null {
  if (!name.length) return null;
  outer: for (let i = 0; i + name.length <= words.length; i++) {
    for (let k = 0; k < name.length; k++) {
      if (used[i + k] || !sameWord(words[i + k], name[k], names)) continue outer;
    }
    return i;
  }
  return null;
}

/** Words in company names that say nothing about which company: "Al Noor" is enough for "Al Noor Trading Est.". */
const genericNameWords = new Set(
  ["al", "el", "co", "company", "trading", "trade", "est", "establishment", "group", "contracting", "contractors", "services", "llc", "ltd", "factory",
    "شركه", "مؤسسه", "للتجاره", "التجاريه", "للمقاولات", "مجموعه", "مصنع", "ال", "و"].map(normalize),
);

// ---------------------------------------------------------------------------------------------
// Customer search

function customerText(c: Customer) {
  return normalize([c.nameEn, c.nameAr, c.vatNumber, c.otherId, c.phone, c.email, c.city].filter(Boolean).join(" "));
}

/**
 * Customers matching a typed or spoken query: by name (English or Arabic), VAT number, other ID,
 * phone, email or city. Exact word matches come first, then names that are only close (one
 * wrong letter), so a slightly mis-heard name still finds the customer.
 */
export function searchCustomers(customers: Customer[], query: string): Customer[] {
  const q = tokens(query);
  if (!q.length) return customers;
  const digits = phoneDigits(query);
  const scored: { c: Customer; score: number }[] = [];
  for (const c of customers) {
    const text = customerText(c);
    const words = text.split(" ");
    let score = 0;
    if (digits.length >= 3 && [c.phone, c.vatNumber, c.otherId].some((v) => v && phoneDigits(v).includes(digits))) score = 3;
    else if (q.every((w) => text.includes(w))) {
      const name = normalize(c.nameEn || "") + " " + normalize(c.nameAr || "");
      score = name.startsWith(q[0]) || name.includes(" " + q[0]) ? 2.5 : 2;
    } else {
      const close = q.filter((w) => !isNumber(w) && words.some((x) => sameWord(x, w, true))).length;
      if (close && close === q.filter((w) => !isNumber(w)).length) score = 1;
    }
    if (score) scored.push({ c, score });
  }
  return scored.sort((a, b) => b.score - a.score).map((s) => s.c);
}

// ---------------------------------------------------------------------------------------------
// Commands for the voice assistant

/** Words that ask to search; the rest of the sentence is the query. */
const searchWords = wordSet([
  "find", "search", "look", "lookup", "show", "where", "open",
  "ابحث", "بحث", "دور", "دورلي", "وين", "اعرض", "افتح",
  "খুঁজুন", "খুঁজো", "খোঁজ", "খুঁজে", "সার্চ", "দেখাও", "দেখান",
  "खोजें", "खोजो", "खोज", "ढूंढो", "ढूंढें", "ढूँढो", "सर्च", "दिखाओ", "दिखाएं",
]);

const invoiceWords = wordSet([
  "invoice", "bill", "fatoora", "fatura", "fatoorah", "faktura",
  "فاتوره", "فاتورة", "فواتير",
  "ইনভয়েস", "ইনভয়েস", "চালান", "বিল", "ফাতুরা", "ফাতোরা",
  "इनवॉइस", "इनवॉयस", "बिल", "फातूरा", "फातोरा", "चालान",
]);

/** Filler words dropped from searches and invoice item names. */
const fillers = wordSet([
  "new", "create", "make", "please", "for", "to", "the", "a", "an", "of", "up", "customer", "customers", "client", "clients", "called", "named", "is", "me", "item", "items", "with",
  "جديد", "جديده", "انشاء", "انشئ", "اعمل", "سوي", "سو", "ل", "لل", "عميل", "العميل", "للعميل", "زبون", "الزبون", "اسم", "باسم", "لي", "عن", "من",
  "নতুন", "তৈরি", "করো", "করুন", "বানাও", "বানান", "গ্রাহক", "ক্লায়েন্ট", "ক্লায়েন্ট", "কাস্টমার", "জন্য", "এর", "কে", "নাম",
  "नया", "नई", "बनाओ", "बनाएं", "बनाइए", "ग्राहक", "क्लाइंट", "कस्टमर", "के", "लिए", "का", "की", "को", "नाम",
]);

export type VoiceCommand =
  | { intent: "searchCustomer"; query: string }
  | { intent: "newInvoice"; text: string }
  | { intent: "unknown"; text: string };

/** What the speaker wants: an invoice, or a customer search. Anything else is unknown. */
export function parseCommand(text: string): VoiceCommand {
  const words = tokens(text);
  if (words.some((w) => searchWords.has(w)) && !words.some((w) => invoiceWords.has(w))) {
    const query = words.filter((w) => !searchWords.has(w) && !fillers.has(w)).join(" ");
    return query ? { intent: "searchCustomer", query } : { intent: "unknown", text };
  }
  if (words.some((w) => invoiceWords.has(w))) return { intent: "newInvoice", text };
  return { intent: "unknown", text };
}

// ---------------------------------------------------------------------------------------------
// Invoice from speech

export type VoiceLine = { name: string; quantity: number; unitPrice?: number; product?: Product };

export type VoiceInvoice = {
  customer?: Customer;
  kind?: InvoiceKind;
  /** Payment means code, as on the invoice: 10 cash, 48 card, 30 transfer. */
  paymentMeans?: string;
  fullPaid?: boolean;
  lines: VoiceLine[];
};

const keywordGroups: { words: Set<string>; apply: (v: VoiceInvoice) => void }[] = [
  {
    words: wordSet(["business", "company", "standard", "tax", "شركه", "ضريبيه", "قياسيه", "ব্যবসায়িক", "কোম্পানি", "ট্যাক্স", "व्यापारिक", "कंपनी", "टैक्स"]),
    apply: (v) => (v.kind = "STANDARD"),
  },
  {
    words: wordSet(["individual", "simplified", "walk", "فرد", "مبسطه", "ব্যক্তি", "व्यक्तिगत"]),
    apply: (v) => (v.kind = "SIMPLIFIED"),
  },
  { words: wordSet(["cash", "نقد", "نقدا", "نقدي", "كاش", "নগদ", "ক্যাশ", "नकद", "कैश"]), apply: (v) => (v.paymentMeans = "10") },
  { words: wordSet(["card", "mada", "بطاقه", "شبكه", "مدى", "كارت", "কার্ড", "कार्ड"]), apply: (v) => (v.paymentMeans = "48") },
  { words: wordSet(["transfer", "bank", "تحويل", "بنكي", "ট্রান্সফার", "ব্যাংক", "ट्रांसफर", "बैंक"]), apply: (v) => (v.paymentMeans = "30") },
  { words: wordSet(["paid", "مدفوع", "مدفوعه", "خالص", "পরিশোধিত", "পেইড", "পরিশোধ", "भुगतान", "पेड", "चुकता"]), apply: (v) => (v.fullPaid = true) },
];

/** Words that separate one item from the next. */
const separators = wordSet(["and", "plus", "also", "then", "و", "ثم", "كمان", "এবং", "আর", "ও", "और", "तथा", "फिर"]);

/** A number after one of these is the unit price. */
const priceWords = wordSet([
  "at", "@", "price", "each", "per", "riyal", "riyals", "sar", "sr",
  "بسعر", "سعر", "السعر", "ب", "ريال", "رس", "الحبه",
  "দাম", "দামে", "টাকা", "রিয়াল", "রিয়াল", "প্রতি", "করে",
  "दाम", "कीमत", "रियाल", "रुपये", "प्रति", "भाव",
]);

/** Unit words between a quantity and the item name ("5 bags of cement"). */
const unitWords = wordSet([
  "pcs", "pc", "piece", "pieces", "bag", "bags", "box", "boxes", "kg", "kilo", "kilos", "unit", "units", "hour", "hours", "day", "days", "x", "times",
  "حبه", "حبات", "كيس", "اكياس", "كرتون", "كيلو", "ساعه", "ساعات", "يوم", "ايام",
  "টি", "টা", "পিস", "বস্তা", "কেজি", "ঘন্টা", "দিন",
  "पीस", "बोरी", "किलो", "घंटे", "दिन", "नग",
]);

/**
 * Fills an invoice from a sentence: the customer (by name), invoice type, payment method, "full
 * paid", and the items. Each item is a product name (from the product list) or any other words,
 * with an optional quantity before it and an optional price after "at"/"بسعر"/"দাম"/"दाम".
 */
export function parseInvoiceSpeech(text: string, customers: Customer[], products: Product[]): VoiceInvoice {
  const words = tokens(text);
  const used = words.map(() => false);
  const result: VoiceInvoice = { lines: [] };

  // The customer whose name covers the most words wins ("Al Noor Trading" over "Noor"). The start
  // of a name is enough ("Al Noor" for "Al Noor Trading") if it has a telling word and only one
  // customer's name starts that way.
  type Hit = { c: Customer; at: number; len: number; full: boolean };
  const hits: Hit[] = [];
  for (const c of customers) {
    for (const name of [c.nameEn, c.nameAr]) {
      const nameWords = tokens(name ?? "");
      for (let len = nameWords.length; len >= 1; len--) {
        const part = nameWords.slice(0, len);
        if (!part.some((w) => !genericNameWords.has(w) && w.length >= 3)) break;
        const at = findWords(words, used, part, true);
        if (at != null) {
          hits.push({ c, at, len, full: len === nameWords.length });
          break;
        }
      }
    }
  }
  const rank = (h: Hit) => h.len + (h.full ? 0.5 : 0);
  hits.sort((a, b) => rank(b) - rank(a));
  const top = hits[0];
  const best = top && (top.full || !hits.some((h) => h.c !== top.c && rank(h) === rank(top))) ? top : null;
  if (best) {
    result.customer = best.c;
    for (let k = 0; k < best.len; k++) used[best.at + k] = true;
  }

  // Product names next, longest first, so "white cement" is not read as "cement".
  const productWords = products
    .filter((p) => p.active !== false)
    .map((p) => ({ p, w: tokens(p.name) }))
    .filter((x) => x.w.length)
    .sort((a, b) => b.w.length - a.w.length);
  const productAt = new Map<number, { p: Product; len: number }>();
  for (const { p, w } of productWords) {
    let at = findWords(words, used, w);
    while (at != null) {
      productAt.set(at, { p, len: w.length });
      for (let k = 0; k < w.length; k++) used[at + k] = true;
      at = findWords(words, used, w);
    }
  }

  // Keywords only where they are not part of a customer or product name.
  words.forEach((w, i) => {
    if (used[i]) return;
    const group = keywordGroups.find((g) => g.words.has(w));
    if (!group) return;
    const prev = words[i - 1];
    if (group.words.has("paid") && (prev === "not" || prev === "un" || prev === "غير" || prev === "না" || prev === "नहीं")) return;
    if (w === "paid" && prev === "full") used[i - 1] = true;
    group.apply(result);
    used[i] = true;
  });
  if (words.includes("unpaid")) result.fullPaid = false;

  // Walk the words left to right, building one item at a time.
  type Draft = { name: string[]; product?: Product; quantity?: number; unitPrice?: number; priceNext: boolean };
  let cur: Draft = { name: [], priceNext: false };
  const flush = () => {
    const name = cur.product?.name ?? cur.name.join(" ");
    if (name && (cur.product || cur.quantity != null || cur.unitPrice != null)) {
      result.lines.push({ name, quantity: cur.quantity ?? 1, unitPrice: cur.unitPrice, product: cur.product });
    }
    cur = { name: [], priceNext: false };
  };
  const hasItem = () => cur.product != null || cur.name.length > 0;

  for (let i = 0; i < words.length; i++) {
    const w = words[i];
    const product = productAt.get(i);
    if (product) {
      if (hasItem()) flush();
      cur.product = product.p;
      i += product.len - 1;
      continue;
    }
    if (used[i]) continue;
    if (separators.has(w)) {
      flush();
      continue;
    }
    if (priceWords.has(w)) {
      cur.priceNext = true;
      continue;
    }
    if (isNumber(w)) {
      const n = Number(w);
      if (cur.priceNext || (hasItem() && cur.quantity != null && cur.unitPrice == null)) {
        cur.unitPrice = n;
        cur.priceNext = false;
      } else if (hasItem()) {
        // "cement 5" or the start of the next item ("cement at 20 3 sand"): a new item if this one is priced.
        if (cur.unitPrice != null) {
          flush();
          cur.quantity = n;
        } else cur.quantity = n;
      } else {
        cur.quantity = n;
      }
      continue;
    }
    if (unitWords.has(w) || fillers.has(w) || invoiceWords.has(w)) continue;
    if (cur.product || cur.unitPrice != null) flush();
    cur.name.push(w);
  }
  flush();
  return result;
}

/** Hands a spoken invoice to the invoice editor, which may not be open yet. */
const PENDING_KEY = "fatoora.voiceInvoice";
export const VOICE_INVOICE_EVENT = "fatoora-voice-invoice";

export function sendToInvoiceEditor(text: string) {
  try {
    window.sessionStorage.setItem(PENDING_KEY, text);
  } catch {
    pending = text;
  }
  window.dispatchEvent(new Event(VOICE_INVOICE_EVENT));
}

let pending: string | null = null;

/** The spoken invoice waiting for the editor, removed once read. */
export function takePendingInvoice(): string | null {
  let text = pending;
  pending = null;
  try {
    text = window.sessionStorage.getItem(PENDING_KEY) ?? text;
    window.sessionStorage.removeItem(PENDING_KEY);
  } catch {
    // Storage blocked: the in-memory copy above is used.
  }
  return text;
}
