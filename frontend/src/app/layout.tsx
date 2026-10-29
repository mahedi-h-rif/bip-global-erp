import type { Metadata } from "next";
import { IBM_Plex_Sans_Arabic, Inter, Noto_Sans_Bengali, Noto_Sans_Devanagari } from "next/font/google";
import { LanguageProvider } from "@/lib/i18n";
import "./globals.css";

const inter = Inter({ subsets: ["latin"], variable: "--font-inter" });
const plexArabic = IBM_Plex_Sans_Arabic({
  subsets: ["arabic"],
  weight: ["400", "500", "700"],
  variable: "--font-plex-arabic",
});
// Bangla and Hindi scripts. Not preloaded: the browser fetches them only when that script is on the page.
const notoBengali = Noto_Sans_Bengali({ subsets: ["bengali"], variable: "--font-noto-bengali", preload: false });
const notoDevanagari = Noto_Sans_Devanagari({ subsets: ["devanagari"], variable: "--font-noto-devanagari", preload: false });

export const metadata: Metadata = {
  title: { default: "Invoice Portal", template: "%s · Invoice Portal" },
  description: "ZATCA-compliant e-invoicing portal",
  robots: { index: false, follow: false },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    // lang and dir are set on the client from the saved language (see LanguageProvider).
    <html
      lang="en"
      suppressHydrationWarning
      className={`${inter.variable} ${plexArabic.variable} ${notoBengali.variable} ${notoDevanagari.variable}`}
    >
      <body className="min-h-screen">
        <LanguageProvider>{children}</LanguageProvider>
      </body>
    </html>
  );
}
