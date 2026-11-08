package com.fatoora.api.settings;

import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

/** Bank details for invoices, and custom roles (departments) with their permission matrix. */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    /** Modules of the permission matrix and the actions each can grant. */
    public static final List<String> MODULES = List.of("sales", "purchasing", "inventory", "hr", "finance");
    public static final List<String> ACTIONS = List.of("view", "create", "edit", "delete", "approve");

    public record Bank(@Size(max = 150) String bankName, @Size(max = 200) String bankAccountName, @Size(max = 60) String bankAccountNo,
                       @Size(max = 40) String bankIban, @Size(max = 1000) String invoiceFooter) {}

    public record Role(Long id, @NotBlank @Size(max = 100) String name, @Size(max = 500) String description,
                       Map<String, List<String>> permissions, List<String> users) {}

    public record Assign(Long customRoleId) {}

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final AuditService audit;

    public SettingsController(JdbcTemplate jdbc, JsonMapper json, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    @GetMapping("/bank")
    Bank bank() {
        return jdbc.queryForObject("SELECT bank_name, bank_account_name, bank_account_no, bank_iban, invoice_footer FROM organization WHERE id = ?",
                (rs, i) -> new Bank(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), CurrentUser.orgId());
    }

    @PutMapping("/bank")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    Bank saveBank(@Valid @RequestBody Bank b) {
        jdbc.update("UPDATE organization SET bank_name = ?, bank_account_name = ?, bank_account_no = ?, bank_iban = ?, invoice_footer = ? WHERE id = ?",
                n(b.bankName()), n(b.bankAccountName()), n(b.bankAccountNo()), n(b.bankIban() == null ? null : b.bankIban().replace(" ", "").toUpperCase()),
                n(b.invoiceFooter()), CurrentUser.orgId());
        audit.record(CurrentUser.orgId(), "BANK_DETAILS_UPDATED", "organization", CurrentUser.orgId(), b.bankName());
        return bank();
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    List<Role> roles() {
        return jdbc.query("SELECT * FROM custom_role WHERE organization_id = ? ORDER BY lower(name)", (rs, i) -> new Role(rs.getLong("id"),
                rs.getString("name"), rs.getString("description"), parse(rs.getString("permissions")),
                jdbc.queryForList("SELECT email FROM app_user WHERE custom_role_id = ? ORDER BY email", String.class, rs.getLong("id"))),
                CurrentUser.orgId());
    }

    @PostMapping("/roles")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Role createRole(@Valid @RequestBody Role r) {
        Long id = jdbc.queryForObject("INSERT INTO custom_role (organization_id, name, description, permissions) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, CurrentUser.orgId(), r.name().trim(), n(r.description()), json.writeValueAsString(clean(r.permissions())));
        audit.record(CurrentUser.orgId(), "ROLE_CREATED", "custom_role", id, r.name());
        return role(id);
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    Role updateRole(@PathVariable Long id, @Valid @RequestBody Role r) {
        role(id);
        jdbc.update("UPDATE custom_role SET name = ?, description = ?, permissions = ? WHERE id = ?", r.name().trim(), n(r.description()),
                json.writeValueAsString(clean(r.permissions())), id);
        audit.record(CurrentUser.orgId(), "ROLE_UPDATED", "custom_role", id, r.name() + " " + clean(r.permissions()));
        return role(id);
    }

    @DeleteMapping("/roles/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    void deleteRole(@PathVariable Long id) {
        role(id);
        jdbc.update("DELETE FROM custom_role WHERE id = ?", id);
    }

    /** Gives a user a department role (or removes it with null). Admins and the provider can't be restricted. */
    @PutMapping("/users/{userId}/role")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    Map<String, Object> assign(@PathVariable Long userId, @RequestBody Assign a) {
        Long orgId = CurrentUser.orgId();
        String builtIn = jdbc.queryForList("SELECT role FROM app_user WHERE id = ? AND organization_id = ?", String.class, userId, orgId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("User"));
        if ("ADMIN".equals(builtIn) || "OPERATOR".equals(builtIn))
            throw ApiException.badRequest("role_not_restrictable", "Admins and the provider always have full access");
        if (a.customRoleId() != null) role(a.customRoleId());
        jdbc.update("UPDATE app_user SET custom_role_id = ? WHERE id = ?", a.customRoleId(), userId);
        audit.record(orgId, "USER_ROLE_ASSIGNED", "user", userId, String.valueOf(a.customRoleId()));
        return Map.of("userId", userId, "customRoleId", a.customRoleId() == null ? "" : a.customRoleId());
    }

    /** What the signed-in user may do; the portal hides menus and buttons from it. Null permissions = unrestricted. */
    @GetMapping("/my-permissions")
    Map<String, Object> mine() {
        Map<String, List<String>> p = PermissionInterceptor.permissionsOf(jdbc, json, CurrentUser.get().userId());
        Map<String, Object> out = new HashMap<>();
        out.put("restricted", p != null);
        out.put("permissions", p == null ? Map.of() : p);
        return out;
    }

    private Role role(Long id) {
        return roles().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Role"));
    }

    private Map<String, List<String>> parse(String s) {
        return PermissionInterceptor.parse(json, s);
    }

    private static Map<String, List<String>> clean(Map<String, List<String>> p) {
        Map<String, List<String>> out = new TreeMap<>();
        if (p == null) return out;
        p.forEach((m, acts) -> {
            if (!MODULES.contains(m) || acts == null) return;
            List<String> a = acts.stream().filter(ACTIONS::contains).distinct().toList();
            if (!a.isEmpty()) out.put(m, a.contains("view") ? a : new ArrayList<>(new TreeSet<>(concat(a, "view"))));
        });
        return out;
    }

    private static List<String> concat(List<String> a, String x) {
        List<String> l = new ArrayList<>(a);
        l.add(x);
        return l;
    }

    private static String n(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
