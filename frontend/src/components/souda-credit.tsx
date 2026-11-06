"use client";

import { useT } from "@/lib/i18n";

/**
 * "Developed by SoudaCore IT Solutions" credit (the firm that builds this portal), shown on the login page and
 * at the foot of the app menu. The logo sits on a white chip so it reads on the dark sidebar too.
 */
export function SoudaCoreCredit({ tone = "light", className = "" }: { tone?: "light" | "dark"; className?: string }) {
  const t = useT();
  const onDark = tone === "dark";
  return (
    <div className={`flex flex-col items-center gap-1.5 ${className}`}>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src="/souda-core-logo.jpg"
        alt="SoudaCore IT Solutions"
        className="h-9 w-auto rounded-md bg-white p-1 shadow-sm ring-1 ring-black/5"
      />
      <span className={`text-center text-[11px] ${onDark ? "text-white/70" : "text-muted"}`}>
        {t("Developed by")}{" "}
        <span className={`font-semibold ${onDark ? "text-white/90" : "text-ink/80"}`}>SoudaCore IT Solutions</span>
      </span>
    </div>
  );
}
