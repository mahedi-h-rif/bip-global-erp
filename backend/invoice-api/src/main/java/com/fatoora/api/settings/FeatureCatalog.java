package com.fatoora.api.settings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every feature an admin can tick for a user: its key, the menu section and pages it shows in the portal, and the
 * first segments of the API paths it covers. A user with per-user access may only call the API of ticked features.
 */
public final class FeatureCatalog {

    private FeatureCatalog() {}

    public record Feature(String key, String label, String section, List<String> pages, List<String> api) {}

    public static final List<Feature> FEATURES = List.of(
            f("dashboard", "Dashboard", "General", List.of("/"), "dashboard", "erp-dashboard"),
            f("invoices", "Invoices", "Sales", List.of("/invoices"), "invoices", "payments"),
            f("quotations", "Quotations", "Sales", List.of("/quotations"), "quotations"),
            f("sales-orders", "Sales orders", "Sales", List.of("/sales-orders"), "sales-orders"),
            f("delivery-notes", "Delivery notes", "Sales", List.of("/delivery-notes"), "delivery-notes"),
            f("receipts", "Incoming payments", "Sales", List.of("/receipts"), "receipts"),
            f("customers", "Customers & CRM", "Sales", List.of("/customers", "/crm"), "customers", "crm"),
            f("pos", "Point of sale", "Sales", List.of("/pos"), "pos"),
            f("agreements", "Agreements", "Sales", List.of("/agreements"), "agreements", "agreement-template"),
            f("pad", "Letterhead pad", "Sales", List.of("/pad"), "pads"),
            f("dues", "Dues & reminders", "Sales", List.of("/dues"), "dues", "notifications"),
            f("suppliers", "Suppliers", "Purchasing", List.of("/suppliers"), "suppliers"),
            f("purchase-requests", "Purchase requests", "Purchasing", List.of("/purchase-requests"), "purchase-requests"),
            f("purchase-orders", "Purchase orders", "Purchasing", List.of("/purchase-orders"), "purchase-orders"),
            f("goods-receipts", "Goods receipts", "Purchasing", List.of("/goods-receipts"), "goods-receipts"),
            f("goods-returns", "Goods returns", "Purchasing", List.of("/goods-returns"), "goods-returns"),
            f("supplier-invoices", "Supplier invoices", "Purchasing", List.of("/supplier-invoices"), "supplier-invoices"),
            f("supplier-credit-notes", "A/P credit memos", "Purchasing", List.of("/supplier-credit-notes"), "supplier-credit-notes"),
            f("supplier-payments", "Supplier payments", "Purchasing", List.of("/supplier-payments"), "supplier-payments"),
            f("reorder", "Reorder suggestions", "Purchasing", List.of("/reorder"), "reorder"),
            f("products", "Products", "Inventory", List.of("/products"), "products"),
            f("inventory", "Stock & warehouses", "Inventory", List.of("/inventory"), "stock", "warehouses"),
            f("fleet", "Fleet & trips", "Inventory", List.of("/fleet"), "fleet"),
            f("hr", "HR", "Workforce", List.of("/hr"), "hr"),
            f("payroll", "Payroll", "Workforce", List.of("/payroll"), "payroll"),
            f("workers", "Workers", "Workforce", List.of("/workers"), "workers"),
            f("reports", "Reports", "Finance", List.of("/erp-reports"), "erp-reports", "reports"),
            f("vat", "VAT & ZATCA returns", "Finance", List.of("/vat"), "vat"),
            f("accounts", "Accounts", "Finance", List.of("/accounts"), "accounts"),
            f("business-audit", "Business audit", "Finance", List.of("/audit"), "business-audit"),
            f("settings", "Company settings", "Administration", List.of("/settings", "/settings/bank", "/settings/agreement", "/settings/devices", "/settings/whatsapp"),
                    "organization", "settings", "egs-units", "whatsapp"),
            f("users", "Users", "Administration", List.of("/settings/users"), "users"),
            f("audit-log", "Audit log", "Administration", List.of("/settings/audit"), "audit"));

    public static final List<String> ACTIONS = List.of("view", "create", "update", "delete");

    /** Paths anyone signed in may call: sign-in, the public share links, cross-document lookups. */
    static final Set<String> ALWAYS = Set.of("auth", "public", "health", "relations", "search", "suggest");

    /** Lists other pages need to fill their pickers (a customer on an invoice, a product on an order): read-only. */
    static final Set<String> LOOKUPS = Set.of("customers", "products", "warehouses", "stock", "suppliers", "organization", "agreement-template");

    private static final Map<String, String> BY_SEGMENT = new HashMap<>();

    static {
        for (Feature f : FEATURES)
            for (String s : f.api()) BY_SEGMENT.put(s, f.key());
    }

    public static boolean isFeature(String key) {
        return FEATURES.stream().anyMatch(f -> f.key().equals(key));
    }

    /** The feature that owns an API path's first segment, or null when none does. */
    static String featureOf(String segment) {
        return BY_SEGMENT.get(segment);
    }

    private static Feature f(String key, String label, String section, List<String> pages, String... api) {
        return new Feature(key, label, section, pages, List.of(api));
    }
}
