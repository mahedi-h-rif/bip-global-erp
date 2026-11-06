"use client";

import { useEffect } from "react";
import type { Organization } from "@/lib/types";

/**
 * Sets the browser-tab icon to the signed-in organization's identity: a monogram of its name on a rounded
 * tile in its brand colour. The app is white-label, so this gives each client its own favicon at runtime
 * (Astha shows "A" in navy) rather than baking one icon into the shared build. Renders nothing.
 */
export function OrgFavicon({ org }: { org?: Organization }) {
  useEffect(() => {
    if (!org) return;
    const letter = (org.legalNameEn || org.legalNameAr || "").trim().charAt(0).toUpperCase() || "•";
    const color = org.brandColor || "#0b2a6f";
    const svg =
      `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">` +
      `<rect width="64" height="64" rx="14" fill="${color}"/>` +
      `<text x="32" y="34" font-family="'Segoe UI',Inter,Arial,sans-serif" font-size="38" font-weight="700" ` +
      `fill="#ffffff" text-anchor="middle" dominant-baseline="central">${letter}</text></svg>`;
    const href = `data:image/svg+xml,${encodeURIComponent(svg)}`;

    // Reuse our own link if already added; otherwise create one. Appended last so it wins over the static default.
    let link = document.querySelector<HTMLLinkElement>('link[rel="icon"][data-org]');
    if (!link) {
      link = document.createElement("link");
      link.rel = "icon";
      link.setAttribute("data-org", "");
      document.head.appendChild(link);
    }
    link.type = "image/svg+xml";
    link.href = href;
  }, [org]);

  return null;
}
