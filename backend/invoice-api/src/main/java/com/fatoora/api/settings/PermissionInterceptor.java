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
        for (String p : List.of("suppliers", "purchase-orders", "goods-receipts", "supplier-invoices", "supplier-payments", "reorder"))
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
