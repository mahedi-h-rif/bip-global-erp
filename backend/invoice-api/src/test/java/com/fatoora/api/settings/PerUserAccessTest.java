package com.fatoora.api.settings;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Per-user access: only ticked features, only ticked actions; lookups and sign-in always work. */
class PerUserAccessTest {

    private static final PermissionInterceptor.Access SALES_VIEW_CREATE =
            new PermissionInterceptor.Access(Set.of("invoices", "quotations"), Set.of("view", "create"), false);

    private static String check(String method, String path) {
        return PermissionInterceptor.check(SALES_VIEW_CREATE, method, path.split("/"));
    }

    @Test
    void tickedFeatureAndActionAllowed() {
        assertNull(check("GET", "invoices"));
        assertNull(check("POST", "invoices"));
        assertNull(check("GET", "quotations/5/pdf"));
    }

    @Test
    void untickedActionRefused() {
        assertNotNull(check("PUT", "invoices/5"), "update not ticked");
        assertNotNull(check("DELETE", "quotations/5"), "delete not ticked");
        assertNotNull(check("POST", "invoices/5/issue"), "issuing changes an invoice: update");
    }

    @Test
    void untickedFeatureRefused() {
        assertNotNull(check("GET", "sales-orders"));
        assertNotNull(check("GET", "accounts"));
        assertNotNull(check("GET", "users"));
        assertNotNull(check("GET", "documents"), "paths outside the catalogue are refused");
    }

    @Test
    void lookupsAndSignInAlwaysWork() {
        assertNull(check("GET", "customers"), "pickers need the customer list");
        assertNull(check("GET", "products"));
        assertNull(check("GET", "auth/me"));
        assertNull(check("GET", "settings/my-permissions"));
        assertNotNull(check("POST", "customers"), "but creating a customer needs the Customers feature");
    }
}
