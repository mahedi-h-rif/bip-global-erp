"use client";

import { useT } from "@/lib/i18n";

/**
 * "Developed by SoudaCore" credit (the firm that builds this portal), shown on the login page and at the foot
 * of the app menu. On the dark sidebar the logo sits on a white chip so its navy parts stay visible.
 */
export function SoudaCoreCredit({ tone = "light", className = "" }: { tone?: "light" | "dark"; className?: string }) {
  const t = useT();
  const onDark = tone === "dark";
  return (
    <div className={`flex items-center justify-center gap-2 text-[11px] ${onDark ? "text-white/60" : "text-muted"} ${className}`}>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src="/souda-core-logo.png"
        alt="SoudaCore"
        className={`h-6 w-auto shrink-0 object-contain ${onDark ? "rounded bg-white p-0.5" : ""}`}
      />
      <span>
        {t("Developed by")} <span className={`font-semibold ${onDark ? "text-white/85" : "text-ink/80"}`}>SoudaCore</span>
      </span>
    </div>
  );
}
