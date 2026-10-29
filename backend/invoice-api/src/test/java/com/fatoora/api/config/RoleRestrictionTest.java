package com.fatoora.api.config;

import com.fatoora.api.common.MiscController;
import com.fatoora.api.customer.CustomerController;
import com.fatoora.api.due.DueController;
import com.fatoora.api.payment.PaymentController;
import com.fatoora.api.product.ProductController;
import com.fatoora.api.report.ReportController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** The normal staff user (ACCOUNTANT) works with invoices and dues but never sees business figures. */
class RoleRestrictionTest {

    private final RoleHierarchy hierarchy = SecurityConfig.roleHierarchy();

    private Set<String> reachable(String role) {
        return hierarchy.getReachableGrantedAuthorities(List.of(new SimpleGrantedAuthority("ROLE_" + role))).stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    @Test
    void staffUserIsNotAnAdmin() {
        assertFalse(reachable("ACCOUNTANT").contains("ROLE_ADMIN"));
        assertTrue(reachable("ADMIN").contains("ROLE_ACCOUNTANT"), "admins can do everything staff can");
        assertTrue(reachable("OPERATOR").contains("ROLE_ADMIN"));
        assertFalse(reachable("VIEWER").contains("ROLE_ACCOUNTANT"));
    }

    @Test
    void salesReportAndDashboardAreAdminOnly() throws Exception {
        assertEquals("hasRole('ADMIN')", ReportController.class.getAnnotation(PreAuthorize.class).value());
        Method dashboard = MiscController.class.getDeclaredMethod("dashboard");
        assertEquals("hasRole('ADMIN')", dashboard.getAnnotation(PreAuthorize.class).value());
    }

    @Test
    void everyWriteEndpointRequiresStaffOrAdmin() {
        for (Class<?> c : List.of(ProductController.class, CustomerController.class, PaymentController.class, DueController.class)) {
            for (Method m : c.getDeclaredMethods()) {
                boolean writes = Arrays.stream(m.getAnnotations()).anyMatch(a -> Set.of("PostMapping", "PutMapping", "DeleteMapping")
                        .contains(a.annotationType().getSimpleName()));
                if (!writes) continue;
                PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
                assertNotNull(pa, c.getSimpleName() + "." + m.getName() + " must be restricted");
                assertEquals("hasAnyRole('ADMIN','ACCOUNTANT')", pa.value(), c.getSimpleName() + "." + m.getName());
            }
        }
    }
}
