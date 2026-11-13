import type { Metadata } from "next";
import { IBM_Plex_Sans_Arabic, Inter } from "next/font/google";
import { LanguageProvider } from "@/lib/i18n";
import "./globals.css";

const inter = Inter({ subsets: ["latin"], variable: "--font-inter" });
const plexArabic = IBM_Plex_Sans_Arabic({
  subsets: ["arabic"],
  weight: ["400", "500", "700"],
  variable: "--font-plex-arabic",
});

export const metadata: Metadata = {
  title: { default: "SoudaCore ERP", template: "%s · SoudaCore ERP" },
  description: "ZATCA-compliant e-invoicing portal",
  robots: { index: false, follow: false },
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    // lang and dir are set on the client from the saved language (see LanguageProvider).
    <html
      lang="en"
      suppressHydrationWarning
      className={`${inter.variable} ${plexArabic.variable}`}
    >
      <body className="min-h-screen">
        <LanguageProvider>{children}</LanguageProvider>
      </body>
    </html>
  );
}
