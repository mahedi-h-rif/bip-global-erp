"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import useSWR from "swr";
import {
  BellRing,
  ClipboardCheck,
  ClipboardList,
  Cpu,
  FileSignature,
  FileText,
  FolderLock,
  HardHat,
  Landmark,
  LayoutDashboard,
  LogOut,
  Menu,
  MessageCircle,
  NotebookPen,
  Package,
  ScrollText,
  Settings,
  ShieldCheck,
  UserRound,
  UserCog,
  Users,
  Wallet,
  Boxes,
  Handshake,
  RefreshCcw,
  Route,
  Store,
  BarChart3,
  CreditCard,
  IdCard,
  WalletCards as Wallet2,
  Truck,
  ShoppingCart,
  PackageCheck,
  Factory,
  Receipt as ReceiptIcon,
  Undo2,
  FileMinus,
  Banknote,
  HandCoins,
  X,
} from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { Me, Organization } from "@/lib/types";
import { Alert, Button, Spinner } from "@/components/ui";
import { LanguagePrompt, LanguageSwitcher } from "@/components/language";
import { NotificationBell } from "@/components/notification-bell";
import { OrgFavicon } from "@/components/favicon";
import { SoudaCoreCredit } from "@/components/souda-credit";
import { WhatsAppBanner, WHATSAPP_ENABLED } from "@/components/whatsapp";
import { VoiceAssistant } from "@/components/voice";
import { useT } from "@/lib/i18n";
import { QuickSearch } from "@/components/erp-dashboard";

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

const salesFlowNav: NavItem[] = [
  { href: "/crm", label: "Customers & CRM", icon: Handshake },
  { href: "/pos", label: "Point of sale", icon: Store },
  { href: "/sales-orders", label: "Sales orders", icon: ClipboardCheck },
  { href: "/delivery-notes", label: "Delivery notes", icon: Truck },
  { href: "/receipts", label: "Incoming payments", icon: HandCoins },
];

const purchasingNav: NavItem[] = [
  { href: "/suppliers", label: "Suppliers", icon: Factory },
  { href: "/purchase-requests", label: "Purchase requests", icon: ClipboardList },
  { href: "/purchase-orders", label: "Purchase orders", icon: ShoppingCart },
  { href: "/goods-receipts", label: "Goods receipts", icon: PackageCheck },
  { href: "/goods-returns", label: "Goods returns", icon: Undo2 },
  { href: "/supplier-invoices", label: "Supplier invoices", icon: ReceiptIcon },
  { href: "/supplier-credit-notes", label: "A/P credit memos", icon: FileMinus },
  { href: "/supplier-payments", label: "Supplier payments", icon: Banknote },
  { href: "/reorder", label: "Reorder suggestions", icon: RefreshCcw },
];

const logisticsNav: NavItem[] = [
  { href: "/fleet", label: "Fleet & trips", icon: Route },
];

const inventoryNav: NavItem[] = [
  { href: "/inventory", label: "Stock & warehouses", icon: Boxes },
];

const dueNav: NavItem[] = [
  { href: "/dues", label: "All dues", icon: Wallet, exact: true },
  { href: "/dues/customers", label: "Customer-wise due", icon: UserRound },
  { href: "/dues/reminders", label: "Reminder log", icon: BellRing },
];

const workforceNav: NavItem[] = [
  { href: "/hr", label: "HR", icon: IdCard },
  { href: "/payroll", label: "Payroll", icon: Wallet2 },
  { href: "/workers", label: "Workers", icon: HardHat },
  // Hidden for now; the /documents page still works. Uncomment to show it again.
  // { href: "/documents", label: "Documents", icon: FolderLock },
];

// Company money, partners' withdrawals and the business audit: admins only.
const financeNav: NavItem[] = [
  { href: "/erp-reports", label: "Reports", icon: BarChart3 },
  { href: "/accounts", label: "Accounts", icon: Landmark, exact: true },
  { href: "/audit", label: "Business audit", icon: ClipboardCheck },
];

const roleLabel: Record<Me["role"], string> = {
  OPERATOR: "Provider",
  ADMIN: "Admin",
  ACCOUNTANT: "Staff",
  VIEWER: "Viewer",
  BRANCH: "Branch",
};

// A branch login: its own summary, documents and cash book, nothing else.
const branchNav: NavItem[] = [
  dashboardNav,
  { href: "/invoices", label: "Invoices", icon: FileText },
  { href: "/quotations", label: "Quotations", icon: ClipboardList },
  { href: "/agreements", label: "Agreements", icon: FileSignature },
  { href: "/accounts", label: "My cash book", icon: Landmark },
];

const adminNav: NavItem[] = [
  { href: "/settings", label: "Company", icon: Settings, exact: true },
  { href: "/settings/users", label: "Users", icon: UserCog },
  { href: "/settings/roles", label: "Departments & permissions", icon: ShieldCheck },
  { href: "/settings/bank", label: "Bank details", icon: CreditCard },
  { href: "/settings/agreement", label: "Agreement template", icon: FileSignature },
  ...(WHATSAPP_ENABLED ? [{ href: "/settings/whatsapp", label: "WhatsApp", icon: MessageCircle }] : []),
  { href: "/settings/audit", label: "Audit log", icon: ScrollText },
];

// E-invoicing provider only: the client's staff never see ZATCA setup.
const providerNav: NavItem[] = [{ href: "/settings/devices", label: "ZATCA devices", icon: Cpu }];

/** The brand background is dark, so the theme's brand-coloured focus ring would not show on it. */
const darkFocus = "focus-visible:outline-white";

export default function AppLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const t = useT();
  const { data: me, error: meError, mutate: retryMe } = useSWR<Me>("/auth/me", fetcher);
  // A session that is no longer valid here (expired, or another instance's cookie): sign in again.
  useEffect(() => {
    if (meError instanceof ApiError && meError.status === 401)
      window.location.replace(`/login?next=${encodeURIComponent(window.location.pathname)}`);
  }, [meError]);
  const { data: org } = useSWR<Organization>(me ? "/organization" : null, fetcher);
  // Department roles: hide the modules the user's role does not grant (the API enforces the same rules).
  const { data: perms } = useSWR<{ restricted: boolean; permissions: Record<string, string[]> }>(me ? "/settings/my-permissions" : null, fetcher);
  const [menuOpen, setMenuOpen] = useState(false);
  const navRef = useRef<HTMLElement>(null);
  const menuButton = useRef<HTMLButtonElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);
  const loaded = !!me;

  // The menu scrolls on its own, so keep the current page in view. The company name loading changes the
  // header's height, and with it how much of the menu shows, so this runs again then.
  const orgLoaded = !!org;
  useEffect(() => {
    navRef.current?.querySelector('[aria-current="page"]')?.scrollIntoView({ block: "nearest" });
  }, [pathname, loaded, orgLoaded, menuOpen]);

  // While the phone menu is open: the page behind stays still, Escape closes it, and focus moves in and back.
  useEffect(() => {
    if (!menuOpen) return;
    const opener = menuButton.current;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setMenuOpen(false); };
    const wide = window.matchMedia("(min-width: 64rem)");
    const onResize = () => { if (wide.matches) setMenuOpen(false); };
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    document.addEventListener("keydown", onKey);
    wide.addEventListener("change", onResize);
    closeButton.current?.focus();
    return () => {
      document.body.style.overflow = overflow;
      document.removeEventListener("keydown", onKey);
      wide.removeEventListener("change", onResize);
      opener?.focus();
    };
  }, [menuOpen]);

  async function logout() {
    await api("POST", "/auth/logout");
    router.replace("/login");
  }

  if (!me) {
    if (!meError) return <Spinner />;
    if (meError instanceof ApiError && meError.status === 401) return <Spinner />;
    return (
      <main className="mx-auto max-w-md px-4 py-16">
        <Alert tone="bad">{t("Could not reach the server")}</Alert>
        <Button variant="secondary" className="mt-3" onClick={() => void retryMe()}>{t("Try again")}</Button>
      </main>
    );
  }

  const isAdmin = me.role === "ADMIN" || me.role === "OPERATOR";
  const isBranch = me.role === "BRANCH";
  const active = (item: NavItem) =>
    item.href === "/" || item.exact ? pathname === item.href : pathname === item.href || pathname.startsWith(item.href + "/");
  const moduleOf = (href: string): string | null => {
    const p = href.split("/")[1];
    if (["quotations", "sales-orders", "delivery-notes", "invoices", "receipts", "customers", "pos", "crm", "fleet"].includes(p)) return "sales";
    if (["suppliers", "purchase-requests", "purchase-orders", "goods-receipts", "goods-returns", "supplier-invoices", "supplier-credit-notes",
         "supplier-payments", "reorder"].includes(p)) return "purchasing";
    if (["products", "inventory"].includes(p)) return "inventory";
    if (["hr", "payroll", "workers", "documents"].includes(p)) return "hr";
    if (["accounts", "dues", "erp-reports", "audit"].includes(p)) return "finance";
    return null;
  };
  const allowed = (item: NavItem) => {
    const m = moduleOf(item.href);
    return !perms?.restricted || m == null || (perms.permissions[m] ?? []).includes("view");
  };
  const link = (item: NavItem) => !allowed(item) ? null : (
    <Link
      key={item.href}
      href={item.href}
      onClick={() => setMenuOpen(false)}
      aria-current={active(item) ? "page" : undefined}
      className={`relative flex min-h-11 shrink-0 items-center gap-3 rounded-lg px-3 text-sm transition lg:min-h-9 ${darkFocus} ${
        active(item)
          ? "bg-white/15 font-medium text-white before:absolute before:inset-y-1.5 before:start-0 before:w-0.5 before:rounded-full before:bg-white"
          : "text-white/75 hover:bg-white/10 hover:text-white"
      }`}
    >
      <item.icon className="size-4 shrink-0" aria-hidden />
      <span className="min-w-0 truncate">{t(item.label)}</span>
    </Link>
  );
  const heading = (label: string) => (
    <p className="mt-4 px-3 pb-1 text-[11px] font-semibold uppercase tracking-wider text-white/60">{t(label)}</p>
  );
  const logo = org?.logoUrl ? (
    // eslint-disable-next-line @next/next/no-img-element
    <img src={org.logoUrl} alt="" className="h-10 w-auto max-w-16 shrink-0 rounded-md bg-white object-contain p-1" />
  ) : (
    <ShieldCheck className="size-6 shrink-0" aria-hidden />
  );
  const name = org?.legalNameEn || org?.legalNameAr || t("SoudaCore ERP");
  const arabicName = org?.legalNameEn && org.legalNameAr ? org.legalNameAr : null;
  const tools = !isBranch && (
    <>
      <VoiceAssistant className="shrink-0" />
      <NotificationBell className="shrink-0" />
    </>
  );

  return (
    <div className="min-h-dvh lg:flex" style={org?.brandColor ? ({ "--color-brand": org.brandColor } as React.CSSProperties) : undefined}>
      <OrgFavicon org={org} />
      {/* Phones and tablets: a top bar with the company and quick tools; everything else is in the menu. */}
      <header inert={menuOpen} className="sticky top-0 z-30 flex h-14 items-center gap-1 bg-brand px-1.5 text-white shadow-sm lg:hidden">
        <button
          ref={menuButton}
          type="button"
          onClick={() => setMenuOpen(true)}
          aria-label={t("Open menu")}
          aria-expanded={menuOpen}
          aria-controls="app-menu"
          className={`grid size-11 shrink-0 place-items-center rounded-lg hover:bg-white/10 ${darkFocus}`}
        >
          <Menu className="size-5" aria-hidden />
        </button>
        <div className="flex min-w-0 flex-1 items-center gap-2">
          {logo}
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold" dir="auto">{name}</p>
            {arabicName && <p className="ar truncate text-xs text-white/70" dir="rtl">{arabicName}</p>}
          </div>
        </div>
        {tools}
      </header>

      {menuOpen && <div className="fixed inset-0 z-40 bg-ink/50 lg:hidden" aria-hidden onClick={() => setMenuOpen(false)} />}

      <aside
        id="app-menu"
        // Visibility only animates on closing: on opening it must apply at once, or focus can't move into the menu.
        className={`fixed inset-y-0 start-0 z-50 flex w-[min(18rem,85vw)] flex-col bg-brand text-white duration-200 motion-reduce:transition-none lg:sticky lg:top-0 lg:z-30 lg:h-dvh lg:w-60 lg:shrink-0 ${
          menuOpen ? "transition-[translate]" : "transition-[translate,visibility] max-lg:invisible max-lg:ltr:-translate-x-full max-lg:rtl:translate-x-full"
        }`}
      >
        <div className="shrink-0 border-b border-white/10 px-4 py-3 lg:py-4">
          <div className="flex items-start gap-2.5">
            {logo}
            <div className="min-w-0 flex-1">
              <p className="line-clamp-2 text-sm font-semibold leading-snug" dir="auto" title={name}>{name}</p>
              {arabicName && <p className="ar mt-0.5 truncate text-xs text-white/70" dir="rtl" title={arabicName}>{arabicName}</p>}
            </div>
            <button
              ref={closeButton}
              type="button"
              onClick={() => setMenuOpen(false)}
              aria-label={t("Close menu")}
              className={`-me-1.5 -mt-1.5 grid size-11 shrink-0 place-items-center rounded-lg hover:bg-white/10 lg:hidden ${darkFocus}`}
            >
              <X className="size-5" aria-hidden />
            </button>
          </div>
          {!isBranch && <div className="-mx-1 mt-2 hidden items-center gap-1 lg:flex">{tools}</div>}
        </div>

        {!isBranch && <QuickSearch />}
        <nav
          ref={navRef}
          aria-label={t("Main")}
          className="flex min-h-0 flex-1 flex-col gap-0.5 overflow-y-auto overscroll-contain p-3 [scrollbar-color:rgb(255_255_255/0.25)_transparent] [scrollbar-width:thin]"
        >
          {isBranch && branchNav.map(link)}
          {!isBranch && <>
          {isAdmin && link(dashboardNav)}
          {nav.map(link)}
          {heading("Sales flow")}
          {salesFlowNav.map(link)}
          {heading("Purchasing")}
          {purchasingNav.map(link)}
          {heading("Logistics")}
          {logisticsNav.map(link)}
          {heading("Inventory")}
          {inventoryNav.map(link)}
          {heading("Due")}
          {dueNav.map(link)}
          {heading("Workforce")}
          {workforceNav.map(link)}
          {isAdmin && (
            <>
              {heading("Finance")}
              {financeNav.map(link)}
            </>
          )}
          {isAdmin && (
            <>
              {heading("Settings")}
              {adminNav.map(link)}
            </>
          )}
          {me.role === "OPERATOR" && (
            <>
              {heading("Provider")}
              {providerNav.map(link)}
            </>
          )}
          </>}
        </nav>

        <div className="shrink-0 space-y-2 border-t border-white/10 p-3">
          <LanguageSwitcher tone="dark" placement="up" />
          <div className="px-1 pt-1">
            <p className="truncate text-sm font-medium" title={me.fullName}>
              {me.fullName} <span className="text-xs font-normal text-white/70">· {t(roleLabel[me.role])}</span>
            </p>
            <p className="truncate text-xs text-white/70 rtl:text-right" dir="ltr" title={me.email}>{me.email}</p>
            {org && me.role === "OPERATOR" && (
              <p className="mt-1.5 inline-block rounded bg-white/10 px-2 py-0.5 text-[10px] font-semibold tracking-wider">
                ZATCA · {org.zatcaEnvironment}
              </p>
            )}
          </div>
          <button
            type="button"
            onClick={logout}
            className={`flex min-h-11 w-full items-center gap-3 rounded-lg px-3 text-sm text-white/85 transition hover:bg-white/10 hover:text-white lg:min-h-9 ${darkFocus}`}
          >
            <LogOut className="size-4 shrink-0 rtl:rotate-180" aria-hidden /> {t("Sign out")}
          </button>
          <SoudaCoreCredit tone="dark" className="pt-1" />
        </div>
      </aside>

      <main inert={menuOpen} className="min-w-0 flex-1 px-4 py-6 lg:px-8 lg:py-8">
        <div className="mx-auto max-w-6xl">
          {WHATSAPP_ENABLED && isAdmin && pathname !== "/settings/whatsapp" && <WhatsAppBanner />}
          {children}
        </div>
      </main>
      <LanguagePrompt />
    </div>
  );
}
