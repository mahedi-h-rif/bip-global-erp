package com.fatoora.api.settings;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Per-user access: only ticked features, only ticked actions; lookups and sign-in always work. */
class PerUserAccessTest {

    /** Invoices: view and create; quotations: view and create (as the old single action list gave). */
    private static final PermissionInterceptor.Access SALES_VIEW_CREATE = new PermissionInterceptor.Access(
            AccessCodec.parse("invoices,quotations", "view,create"), false);

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
    void actionsAreControlledPerFeature() {
        var access = new PermissionInterceptor.Access(AccessCodec.parse("invoices:view|create|update,quotations:view", null), false);
        assertNull(PermissionInterceptor.check(access, "POST", "invoices/5/issue".split("/")), "update on invoices");
        assertNotNull(PermissionInterceptor.check(access, "POST", "quotations".split("/")), "quotations are view-only");
        assertNull(PermissionInterceptor.check(access, "GET", "quotations".split("/")));
        assertNotNull(PermissionInterceptor.check(access, "DELETE", "invoices/5".split("/")), "no delete on invoices");
    }

    @Test
    void codecRoundTripsAndAlwaysKeepsView() {
        var p = AccessCodec.clean(java.util.Map.of("invoices", java.util.List.of("create"), "nonsense", java.util.List.of("view")));
        org.junit.jupiter.api.Assertions.assertEquals("invoices:create|view", AccessCodec.encode(p));
        org.junit.jupiter.api.Assertions.assertEquals(p, AccessCodec.parse(AccessCodec.encode(p), null));
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
