"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import useSWR from "swr";
import {
  BellRing,
  ClipboardList,
  Cpu,
  FileSignature,
  FileText,
  FolderLock,
  HardHat,
  Landmark,
  LayoutDashboard,
  LogOut,
  NotebookPen,
  Package,
  ScrollText,
  Settings,
  ShieldCheck,
  UserRound,
  Users,
  Wallet,
} from "lucide-react";
import { api, fetcher } from "@/lib/api";
import type { Me, Organization } from "@/lib/types";
import { Spinner } from "@/components/ui";
import { LanguagePrompt, LanguageSwitcher } from "@/components/language";
import { NotificationBell } from "@/components/notification-bell";
import { useT } from "@/lib/i18n";

type NavItem = { href: string; label: string; icon: typeof FileText; exact?: boolean };

// The dashboard (sales figures) is for the client's admin; normal staff start at Invoices.
const dashboardNav: NavItem = { href: "/", label: "Dashboard", icon: LayoutDashboard };

const nav: NavItem[] = [
  { href: "/invoices", label: "Invoices", icon: FileText },
  { href: "/quotations", label: "Quotations", icon: ClipboardList },
  { href: "/agreements", label: "Agreements", icon: FileSignature },
  { href: "/pad", label: "Letterhead pad", icon: NotebookPen },
  { href: "/customers", label: "Customers", icon: Users },
  { href: "/products", label: "Products", icon: Package },
];

const dueNav: NavItem[] = [
  { href: "/dues", label: "All dues", icon: Wallet, exact: true },
  { href: "/dues/customers", label: "Customer-wise due", icon: UserRound },
  { href: "/dues/reminders", label: "Reminder log", icon: BellRing },
];

const workforceNav: NavItem[] = [
  { href: "/workers", label: "Workers", icon: HardHat },
  { href: "/documents", label: "Documents", icon: FolderLock },
];

// Company money and partners' withdrawals: admins only.
const financeNav: NavItem[] = [{ href: "/accounts", label: "Accounts", icon: Landmark }];

const roleLabel: Record<Me["role"], string> = {
  OPERATOR: "Provider",
  ADMIN: "Admin",
  ACCOUNTANT: "Staff",
  VIEWER: "Viewer",
};

const adminNav: NavItem[] = [
  { href: "/settings", label: "Company", icon: Settings, exact: true },
  { href: "/settings/agreement", label: "Agreement template", icon: FileSignature },
  { href: "/settings/audit", label: "Audit log", icon: ScrollText },
];

// E-invoicing provider only: the client's staff never see ZATCA setup.
const providerNav: NavItem[] = [{ href: "/settings/devices", label: "ZATCA devices", icon: Cpu }];

export default function AppLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const t = useT();
  const { data: me } = useSWR<Me>("/auth/me", fetcher);
  const { data: org } = useSWR<Organization>(me ? "/organization" : null, fetcher);

  async function logout() {
    await api("POST", "/auth/logout");
    router.replace("/login");
  }

  if (!me) return <Spinner />;

  const isAdmin = me.role === "ADMIN" || me.role === "OPERATOR";
  const active = (item: NavItem) =>
    item.href === "/" || item.exact ? pathname === item.href : pathname === item.href || pathname.startsWith(item.href + "/");
  const link = (item: NavItem) => (
    <Link
      key={item.href}
      href={item.href}
      aria-current={active(item) ? "page" : undefined}
      className={`flex shrink-0 items-center gap-3 rounded-lg px-3 py-2 text-sm transition ${
        active(item) ? "bg-white/15 font-medium text-white" : "text-white/70 hover:bg-white/10 hover:text-white"
      }`}
    >
      <item.icon className="size-4" aria-hidden />
      {t(item.label)}
    </Link>
  );

  return (
    <div className="min-h-screen md:flex" style={org?.brandColor ? ({ "--color-brand": org.brandColor } as React.CSSProperties) : undefined}>
      <aside className="relative z-30 flex flex-col bg-brand text-white md:sticky md:top-0 md:h-screen md:w-60 md:shrink-0">
        <div className="flex items-center gap-2.5 px-5 py-5">
          {org?.logoUrl ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={org.logoUrl} alt="" className="h-11 w-auto max-w-16 shrink-0 rounded-md bg-white object-contain p-1" />
          ) : (
            <ShieldCheck className="size-6 shrink-0" aria-hidden />
          )}
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-semibold">{org?.legalNameEn ?? t("Invoice Portal")}</p>
            <p className="ar truncate text-xs text-white/70" dir="rtl">{org?.legalNameAr}</p>
          </div>
          <NotificationBell className="shrink-0" />
          {/* Phones: the account panel below is hidden, so the language menu sits up here. */}
          <LanguageSwitcher tone="dark" compact className="shrink-0 md:hidden" />
        </div>
        <nav className="flex gap-1 overflow-x-auto px-3 pb-3 md:flex-1 md:flex-col md:overflow-visible" aria-label={t("Main")}>
          {isAdmin && link(dashboardNav)}
          {nav.map(link)}
          <p className="mt-4 hidden px-3 pb-1 text-[11px] font-semibold uppercase tracking-wider text-white/50 md:block">{t("Due")}</p>
          {dueNav.map(link)}
          <p className="mt-4 hidden px-3 pb-1 text-[11px] font-semibold uppercase tracking-wider text-white/50 md:block">{t("Workforce")}</p>
          {workforceNav.map(link)}
          {isAdmin && (
            <>
              <p className="mt-4 hidden px-3 pb-1 text-[11px] font-semibold uppercase tracking-wider text-white/50 md:block">{t("Finance")}</p>
              {financeNav.map(link)}
            </>
          )}
          {isAdmin && (
            <>
              <p className="mt-4 hidden px-3 pb-1 text-[11px] font-semibold uppercase tracking-wider text-white/50 md:block">{t("Settings")}</p>
              {adminNav.map(link)}
            </>
          )}
          {me.role === "OPERATOR" && (
            <>
              <p className="mt-4 hidden px-3 pb-1 text-[11px] font-semibold uppercase tracking-wider text-white/50 md:block">{t("Provider")}</p>
              {providerNav.map(link)}
            </>
          )}
        </nav>
        <div className="hidden border-t border-white/10 px-5 py-4 md:block">
          <LanguageSwitcher tone="dark" placement="up" className="mb-3" />
          <p className="truncate text-sm">{me.fullName} <span className="text-xs text-white/60">· {t(roleLabel[me.role])}</span></p>
          <p className="truncate text-xs text-white/60">{me.email}</p>
          <button onClick={logout} className="mt-3 inline-flex items-center gap-2 text-xs text-white/70 hover:text-white">
            <LogOut className="size-3.5" aria-hidden /> {t("Sign out")}
          </button>
          {org && me.role === "OPERATOR" && (
            <p className="mt-3 inline-block rounded bg-white/10 px-2 py-0.5 text-[10px] font-semibold tracking-wider">
              ZATCA · {org.zatcaEnvironment}
            </p>
          )}
        </div>
      </aside>
      <main className="min-w-0 flex-1 px-4 py-6 md:px-8 md:py-8">
        <div className="mx-auto max-w-6xl">{children}</div>
      </main>
      <LanguagePrompt />
    </div>
  );
}
