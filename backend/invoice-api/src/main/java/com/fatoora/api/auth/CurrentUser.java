package com.fatoora.api.auth;

import com.fatoora.api.user.AppUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** The authenticated staff member, resolved from the session cookie on every request. */
public record CurrentUser(Long userId, Long organizationId, String email, AppUser.Role role) {

    public static CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CurrentUser user) return user;
        throw new IllegalStateException("No authenticated user");
    }

    public static Long orgId() {
        return get().organizationId();
    }

    /**
     * For a branch login, the login whose records it may see (records are matched on their created-by);
     * null for every other role and for background jobs, which see the whole organization.
     */
    public static String owner() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof CurrentUser u && u.role() == AppUser.Role.BRANCH ? u.email() : null;
    }

    /** Whether the current user may see a record created by {@code createdBy}. */
    public static boolean owns(String createdBy) {
        String o = owner();
        return o == null || o.equalsIgnoreCase(createdBy);
    }

    /** True for the e-invoicing provider; false for the client's staff and for background jobs. */
    public static boolean isOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof CurrentUser u && u.role() == AppUser.Role.OPERATOR;
    }
}
