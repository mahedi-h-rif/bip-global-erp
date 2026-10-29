"use client";

import { useRef, useState } from "react";
import { ArrowDown, ArrowUp, Plus, Trash2 } from "lucide-react";
import { Button, Card, CardHeader, Input } from "@/components/ui";
import { useT } from "@/lib/i18n";

/**
 * The agreement text as a form: an opening section (parties and preamble) and numbered clauses, each with
 * a heading and its text. Stored as plain text ("## 1. Heading" lines start clauses, "- " lines are bullets),
 * which the server turns into the PDF; clause numbers are set from the order here.
 */

export type Clause = { key: number; heading: string; text: string };
export type AgreementText = { opening: string; clauses: Clause[] };

let nextKey = 1;

export function parseAgreement(body: string): AgreementText {
  const opening: string[] = [];
  const clauses: Clause[] = [];
  for (const line of body.replace(/\r\n/g, "\n").split("\n")) {
    const heading = /^\s*##\s+(?:\d+\.\s*)?(.*)$/.exec(line);
    if (heading) clauses.push({ key: nextKey++, heading: heading[1].trim(), text: "" });
    else if (clauses.length) {
      const c = clauses[clauses.length - 1];
      c.text = c.text ? `${c.text}\n${line}` : line;
    } else opening.push(line);
  }
  return { opening: opening.join("\n").trim(), clauses: clauses.map((c) => ({ ...c, text: c.text.trim() })) };
}

export function serializeAgreement({ opening, clauses }: AgreementText): string {
  return [opening.trim(), ...clauses.map((c, i) => `## ${i + 1}. ${c.heading.trim() || "Clause"}\n${c.text.trim()}`)]
    .filter(Boolean)
    .join("\n\n");
}

/** Details filled in automatically on every agreement. */
const placeholders: [string, string][] = [
  ["Client name", "{{client_name}}"],
  ["Client details", "{{client_details}}"],
  ["Date", "{{date}}"],
  ["Agreement no.", "{{agreement_number}}"],
  ["Company name", "{{company_name}}"],
  ["Company name (Arabic)", "{{company_name_ar}}"],
  ["Company CR", "{{company_cr}}"],
  ["Company VAT no.", "{{company_vat}}"],
  ["Company address", "{{company_address}}"],
];

const areaClass =
  "w-full rounded-lg border border-line bg-white px-3 py-2 text-sm leading-relaxed outline-none focus:border-brand";

function rows(text: string, min: number) {
  return Math.max(min, text.split("\n").length + 1);
}

export function AgreementTextForm({ value, onChange }: { value: AgreementText; onChange: (v: AgreementText) => void }) {
  // The text box the admin last worked in, so the detail buttons insert where the cursor is.
  const t = useT();
  const focused = useRef<{ el: HTMLTextAreaElement; target: "opening" | number } | null>(null);
  const [hint, setHint] = useState(false);

  function setClause(i: number, patch: Partial<Clause>) {
    onChange({ ...value, clauses: value.clauses.map((c, j) => (j === i ? { ...c, ...patch } : c)) });
  }

  function move(i: number, by: number) {
    const clauses = [...value.clauses];
    [clauses[i], clauses[i + by]] = [clauses[i + by], clauses[i]];
    onChange({ ...value, clauses });
  }

  function insert(token: string) {
    const f = focused.current;
    if (!f) {
      setHint(true);
      return;
    }
    const { el, target } = f;
    const start = el.selectionStart ?? el.value.length;
    const end = el.selectionEnd ?? start;
    const text = el.value.slice(0, start) + token + el.value.slice(end);
    if (target === "opening") onChange({ ...value, opening: text });
    else setClause(target, { text });
    requestAnimationFrame(() => {
      el.focus();
      el.setSelectionRange(start + token.length, start + token.length);
    });
  }

  function track(el: HTMLTextAreaElement, target: "opening" | number) {
    focused.current = { el, target };
    setHint(false);
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader
          title={t("Opening and parties")}
          subtitle={t("Who the agreement is between. Client and company details are filled in automatically.")}
        />
        <div className="space-y-3 p-5">
          <textarea dir="auto" rows={rows(value.opening, 8)} className={areaClass} value={value.opening}
            onFocus={(e) => track(e.currentTarget, "opening")} onChange={(e) => onChange({ ...value, opening: e.target.value })} />
          <div>
            <p className={`mb-2 text-xs ${hint ? "text-bad" : "text-muted"}`}>
              {hint ? t("Click in a text box first, then choose the detail to insert.") : t("Insert a detail at the cursor:")}
            </p>
            <div className="flex flex-wrap gap-1.5">
              {placeholders.map(([label, token]) => (
                <button key={token} type="button" onMouseDown={(e) => e.preventDefault()} onClick={() => insert(token)}
                  className="rounded-full border border-line bg-white px-2.5 py-1 text-xs text-ink hover:border-brand hover:text-brand">
                  + {t(label)}
                </button>
              ))}
            </div>
          </div>
        </div>
      </Card>

      <Card>
        <CardHeader
          title={t("Terms and conditions")}
          subtitle={t("One box per clause. Clause numbers follow the order. Start a line with - for a bullet point. Do not put amounts or payment terms here; they belong on quotations and invoices.")}
        />
        <ol className="divide-y divide-line">
          {value.clauses.map((c, i) => (
            <li key={c.key} className="grid gap-2 p-5 md:grid-cols-[2.5rem_1fr]">
              <span className="grid size-8 place-items-center rounded-full bg-brand/10 text-sm font-semibold text-brand">{i + 1}</span>
              <div className="space-y-2">
                <div className="flex flex-wrap items-center gap-2">
                  <Input dir="auto" className="min-w-0 flex-1 font-semibold" placeholder={t("Clause heading")} value={c.heading}
                    onChange={(e) => setClause(i, { heading: e.target.value })} />
                  <Button type="button" variant="ghost" aria-label={t("Move up")} disabled={i === 0} onClick={() => move(i, -1)}>
                    <ArrowUp className="size-4" aria-hidden />
                  </Button>
                  <Button type="button" variant="ghost" aria-label={t("Move down")} disabled={i === value.clauses.length - 1} onClick={() => move(i, 1)}>
                    <ArrowDown className="size-4" aria-hidden />
                  </Button>
                  <Button type="button" variant="ghost" aria-label={t("Remove clause")}
                    onClick={() => onChange({ ...value, clauses: value.clauses.filter((_, j) => j !== i) })}>
                    <Trash2 className="size-4 text-bad" aria-hidden />
                  </Button>
                </div>
                <textarea dir="auto" rows={rows(c.text, 3)} className={areaClass} placeholder={t("Clause text")} value={c.text}
                  onFocus={(e) => track(e.currentTarget, i)} onChange={(e) => setClause(i, { text: e.target.value })} />
              </div>
            </li>
          ))}
        </ol>
        <div className="border-t border-line p-5">
          <Button type="button" variant="secondary"
            onClick={() => onChange({ ...value, clauses: [...value.clauses, { key: nextKey++, heading: "", text: "" }] })}>
            <Plus className="size-4" aria-hidden /> {t("Add clause")}
          </Button>
        </div>
      </Card>
    </div>
  );
}
