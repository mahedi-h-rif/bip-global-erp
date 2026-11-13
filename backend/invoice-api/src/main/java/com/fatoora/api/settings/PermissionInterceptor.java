package com.fatoora.api.settings;

import com.fatoora.api.auth.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

/**
 * Enforces department roles: a user with a custom role may only use the modules and actions it grants. The module
 * comes from the URL, the action from the HTTP method (GET view, POST create, PUT/PATCH edit, DELETE delete; approve,
 * cancel and pay endpoints need "approve"). Users without a custom role keep their built-in role's access.
 */
@Configuration
public class PermissionInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private static final Map<String, String> MODULE_BY_PREFIX = new LinkedHashMap<>();

    static {
        for (String p : List.of("quotations", "sales-orders", "delivery-notes", "invoices", "receipts", "customers", "pos", "crm", "fleet"))
            MODULE_BY_PREFIX.put(p, "sales");
        for (String p : List.of("suppliers", "purchase-requests", "purchase-orders", "goods-receipts", "goods-returns", "supplier-invoices", "supplier-credit-notes", "supplier-payments", "reorder"))
            MODULE_BY_PREFIX.put(p, "purchasing");
        for (String p : List.of("products", "warehouses", "stock")) MODULE_BY_PREFIX.put(p, "inventory");
        for (String p : List.of("hr", "payroll", "workers", "documents")) MODULE_BY_PREFIX.put(p, "hr");
        for (String p : List.of("accounts", "dues", "payments", "reports", "audit", "erp-reports")) MODULE_BY_PREFIX.put(p, "finance");
    }

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public PermissionInterceptor(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CurrentUser user)) return true;
        String[] parts = req.getRequestURI().replaceFirst("^/api/v1/", "").split("/");
        Access access = accessOf(jdbc, user.userId());
        if (access != null) {
            String denied = check(access, req.getMethod(), parts);
            if (denied == null) return true;
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("application/json");
            res.getWriter().write("{\"code\":\"forbidden\",\"message\":\"" + denied + "\",\"fields\":null}");
            return false;
        }
        String module = MODULE_BY_PREFIX.get(parts[0]);
        if (module == null) return true;
        Map<String, List<String>> perms = permissionsOf(jdbc, json, user.userId());
        if (perms == null) return true;
        String action = action(req.getMethod(), parts);
        if (perms.getOrDefault(module, List.of()).contains(action)) return true;
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("application/json");
        res.getWriter().write("{\"code\":\"forbidden\",\"message\":\"Your role does not allow " + action + " in " + module + "\",\"fields\":null}");
        return false;
    }

    /** A user's admin-chosen access: ticked features, allowed actions. */
    public record Access(Set<String> features, Set<String> actions, boolean ownDataOnly) {}

    /** Null when the user is not on per-user access (admins, the provider, logins made before it existed). */
    public static Access accessOf(JdbcTemplate jdbc, Long userId) {
        if (userId == null) return null;
        List<Access> a = jdbc.query("""
                SELECT features, actions, own_data_only FROM app_user
                WHERE id = ? AND features IS NOT NULL AND role NOT IN ('ADMIN','OPERATOR')""",
                (rs, i) -> new Access(split(rs.getString(1)), split(rs.getString(2)), rs.getBoolean(3)), userId);
        return a.isEmpty() ? null : a.getFirst();
    }

    static Set<String> split(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        Set<String> out = new TreeSet<>();
        for (String x : csv.split(",")) if (!x.isBlank()) out.add(x.trim());
        return out;
    }

    /** Null when allowed; otherwise the message for the user. */
    static String check(Access access, String method, String[] parts) {
        String segment = parts[0];
        if (FeatureCatalog.ALWAYS.contains(segment)) return null;
        if ("settings".equals(segment) && parts.length > 1 && "my-permissions".equals(parts[1])) return null;
        boolean read = Set.of("GET", "HEAD", "OPTIONS").contains(method);
        if (read && FeatureCatalog.LOOKUPS.contains(segment)) return null;
        String feature = FeatureCatalog.featureOf(segment);
        if (feature == null || !access.features().contains(feature))
            return "You don't have access to this part of the system.";
        String act = crud(method, parts);
        if (!access.actions().contains(act))
            return "You don't have permission to " + act + " here.";
        return null;
    }

    /** View, create, update or delete; issuing, approving, cancelling and paying change an existing record. */
    static String crud(String method, String[] parts) {
        String last = parts[parts.length - 1];
        if ("POST".equals(method) && Set.of("approve", "cancel", "pay", "issue", "submit", "close", "deliver", "retry").contains(last))
            return "update";
        return switch (method) {
            case "GET", "HEAD", "OPTIONS" -> "view";
            case "POST" -> "create";
            case "PUT", "PATCH" -> "update";
            case "DELETE" -> "delete";
            default -> "view";
        };
    }

    static String action(String method, String[] parts) {
        String last = parts[parts.length - 1];
        if ("POST".equals(method) && Set.of("approve", "cancel", "pay", "issue", "submit").contains(last)) return "approve";
        return switch (method) {
            case "GET", "HEAD", "OPTIONS" -> "view";
            case "POST" -> "create";
            case "PUT", "PATCH" -> "edit";
            case "DELETE" -> "delete";
            default -> "view";
        };
    }

    /** Null when the user has no custom role (unrestricted by the matrix). */
    static Map<String, List<String>> permissionsOf(JdbcTemplate jdbc, JsonMapper json, Long userId) {
        if (userId == null) return null;
        List<String> p = jdbc.queryForList("""
                SELECT r.permissions FROM app_user u JOIN custom_role r ON r.id = u.custom_role_id
                WHERE u.id = ? AND u.role NOT IN ('ADMIN','OPERATOR')""", String.class, userId);
        return p.isEmpty() ? null : parse(json, p.getFirst());
    }

    @SuppressWarnings("unchecked")
    static Map<String, List<String>> parse(JsonMapper json, String s) {
        if (s == null || s.isBlank()) return new TreeMap<>();
        Map<String, Object> raw = json.readValue(s, Map.class);
        Map<String, List<String>> out = new TreeMap<>();
        raw.forEach((k, v) -> out.put(k, v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of()));
        return out;
    }
}
