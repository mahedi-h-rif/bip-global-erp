package com.fatoora.api.user;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.audit.AuditLogRepository;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * User management for the client's admin: every login of the company, what each one has created and done,
 * and adding, changing, disabling and resetting logins. The provider's own (OPERATOR) logins are listed, but
 * only the provider may change them.
 */
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    /** Records that carry a "created by" login, and how to count one login's records for one company. */
    private static final Map<String, String> CREATED = new LinkedHashMap<>();

    static {
        CREATED.put("invoices", "select count(*) from invoice where organization_id = ? and lower(created_by) = ?");
        CREATED.put("quotations", "select count(*) from quotation where organization_id = ? and lower(created_by) = ?");
        CREATED.put("agreements", "select count(*) from agreement where organization_id = ? and lower(created_by) = ?");
        CREATED.put("pads", "select count(*) from pad_document where organization_id = ? and lower(created_by) = ?");
        CREATED.put("payments", "select count(*) from invoice_payment where organization_id = ? and lower(created_by) = ?");
        CREATED.put("income", "select count(*) from account_income where organization_id = ? and lower(created_by) = ?");
        CREATED.put("expenses", "select count(*) from account_expense where organization_id = ? and lower(created_by) = ?");
        CREATED.put("withdrawals", "select count(*) from partner_withdrawal where organization_id = ? and lower(created_by) = ?");
        CREATED.put("documents", "select count(*) from document where organization_id = ? and lower(created_by) = ?");
        CREATED.put("workerDays", "select count(*) from worker_day d join worker w on w.id = d.worker_id "
                + "where w.organization_id = ? and lower(d.created_by) = ?");
        CREATED.put("workerLeaves", "select count(*) from worker_leave l join worker w on w.id = l.worker_id "
                + "where w.organization_id = ? and lower(l.created_by) = ?");
    }

    public record UserDto(Long id, String email, String fullName, String role, boolean enabled,
                          Instant lastLoginAt, Instant createdAt, long actionsThisMonth, Instant lastActiveAt,
                          /** Per-user access; null features = full access of the built-in role. */
                          List<String> features, List<String> actions, boolean ownDataOnly) {
    }

    /** What the user form offers: every feature (grouped by section) and the actions. */
    public record AccessOptions(List<com.fatoora.api.settings.FeatureCatalog.Feature> features, List<String> actions) {
    }

    /** {@code features} null keeps the built-in role's access; a list switches the user to per-user access. */
    public record CreateUser(@NotBlank @Email String email, @NotBlank String fullName, @NotNull AppUser.Role role,
                             @NotBlank @Size(min = 10, max = 100) String password,
                             List<String> features, List<String> actions, boolean ownDataOnly) {
    }

    public record UpdateUser(@NotBlank String fullName, @NotNull AppUser.Role role, boolean enabled,
                             List<String> features, List<String> actions, boolean ownDataOnly) {
    }

    public record PasswordReset(@NotBlank @Size(min = 10, max = 100) String password) {
    }

    /** One login's totals: records created (by kind) and, this month, invoices issued, their value and quotations. */
    public record Activity(UserDto user, Map<String, Long> created, long invoicesThisMonth,
                           BigDecimal issuedThisMonth, long quotationsThisMonth) {
    }

    private final AppUserRepository users;
    private final AuditLogRepository logs;
    private final AuditService audit;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;

    public UserController(AppUserRepository users, AuditLogRepository logs, AuditService audit,
                          PasswordEncoder encoder, JdbcTemplate jdbc) {
        this.users = users;
        this.logs = logs;
        this.audit = audit;
        this.encoder = encoder;
        this.jdbc = jdbc;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<UserDto> list() {
        Long org = CurrentUser.orgId();
        Map<String, Object[]> act = monthActivity(org);
        return users.findByOrganizationId(org).stream()
                .sorted(Comparator.comparing((AppUser u) -> !u.isEnabled()).thenComparing(u -> u.getRole().ordinal())
                        .thenComparing(AppUser::getFullName, String.CASE_INSENSITIVE_ORDER))
                .map(u -> dto(u, act)).toList();
    }

    @GetMapping("/{id}/activity")
    @Transactional(readOnly = true)
    public Activity activity(@PathVariable Long id) {
        Long org = CurrentUser.orgId();
        AppUser u = load(id);
        String email = u.getEmail().toLowerCase(Locale.ROOT);
        Map<String, Long> created = new LinkedHashMap<>();
        CREATED.forEach((k, sql) -> created.put(k, nz(jdbc.queryForObject(sql, Long.class, org, email))));
        java.sql.Timestamp month = java.sql.Timestamp.from(monthStart());
        Object[] inv = jdbc.queryForObject("select count(*), coalesce(sum(total_incl_vat), 0) from invoice "
                        + "where organization_id = ? and lower(created_by) = ? and issued_at >= ? "
                        + "and status not in ('DRAFT', 'REJECTED') and doc_type = 'INVOICE'",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getBigDecimal(2)}, org, email, month);
        Long quotes = jdbc.queryForObject("select count(*) from quotation where organization_id = ? "
                + "and lower(created_by) = ? and created_at >= ?", Long.class, org, email, month);
        return new Activity(dto(u, monthActivity(org)), created, (Long) inv[0], (BigDecimal) inv[1], nz(quotes));
    }

    /** One login's month on the admin dashboard. */
    public record UserMonth(Long id, String fullName, String email, String role, boolean enabled,
                            long invoices, BigDecimal issued, long quotations, long actions, Instant lastActiveAt) {
    }

    /**
     * This month for every login of the company: invoices issued and their value (credit and debit notes left
     * out, as on the dashboard total), quotations created and actions taken. Busiest first.
     */
    @GetMapping("/this-month")
    @Transactional(readOnly = true)
    public List<UserMonth> thisMonth() {
        Long org = CurrentUser.orgId();
        java.sql.Timestamp month = java.sql.Timestamp.from(monthStart());
        Map<String, Object[]> inv = new HashMap<>();
        jdbc.query("select lower(created_by), count(*), coalesce(sum(total_incl_vat), 0) from invoice "
                        + "where organization_id = ? and issued_at >= ? and status not in ('DRAFT', 'REJECTED') "
                        + "and doc_type = 'INVOICE' and created_by is not null group by lower(created_by)",
                rs -> {
                    inv.put(rs.getString(1), new Object[]{rs.getLong(2), rs.getBigDecimal(3)});
                }, org, month);
        Map<String, Long> quotes = new HashMap<>();
        jdbc.query("select lower(created_by), count(*) from quotation where organization_id = ? and created_at >= ? "
                + "and created_by is not null group by lower(created_by)", rs -> {
            quotes.put(rs.getString(1), rs.getLong(2));
        }, org, month);
        Map<String, Object[]> act = monthActivity(org);
        return users.findByOrganizationId(org).stream().map(u -> {
                    String e = u.getEmail().toLowerCase(Locale.ROOT);
                    Object[] i = inv.getOrDefault(e, new Object[]{0L, BigDecimal.ZERO});
                    Object[] a = act.get(e);
                    return new UserMonth(u.getId(), u.getFullName(), u.getEmail(), u.getRole().name(), u.isEnabled(),
                            (Long) i[0], (BigDecimal) i[1], quotes.getOrDefault(e, 0L),
                            a == null ? 0 : (Long) a[0], a == null ? null : (Instant) a[1]);
                })
                .sorted(Comparator.comparing(UserMonth::issued).reversed().thenComparing(UserMonth::actions, Comparator.reverseOrder()))
                .toList();
    }

    @GetMapping("/access-options")
    public AccessOptions accessOptions() {
        return new AccessOptions(com.fatoora.api.settings.FeatureCatalog.FEATURES, com.fatoora.api.settings.FeatureCatalog.ACTIONS);
    }

    @PostMapping
    @Transactional
    public UserDto create(@Valid @RequestBody CreateUser body) {
        allowedRole(body.role());
        noEscalation(body.role());
        String email = body.email().strip().toLowerCase(Locale.ROOT);
        if (users.findByEmailIgnoreCase(email).isPresent())
            throw new ApiException.Validation(Map.of("email", "A login with this email already exists"));
        AppUser u = new AppUser();
        u.setOrganizationId(CurrentUser.orgId());
        u.setEmail(email);
        u.setFullName(body.fullName().strip());
        u.setRole(body.role());
        applyAccess(u, body.features(), body.actions(), body.ownDataOnly());
        u.setPasswordHash(encoder.encode(body.password()));
        users.save(u);
        audit.record(u.getOrganizationId(), "USER_CREATED", "user", u.getId(), email + " · " + u.getRole());
        return dto(u, Map.of());
    }

    @PutMapping("/{id}")
    @Transactional
    public UserDto update(@PathVariable Long id, @Valid @RequestBody UpdateUser body) {
        AppUser u = load(id);
        changeable(u);
        allowedRole(body.role());
        noEscalation(body.role());
        if (u.getRole() == AppUser.Role.ADMIN) noEscalation(AppUser.Role.ADMIN);
        boolean self = u.getId().equals(CurrentUser.get().userId());
        if (self && (!body.enabled() || body.role() != u.getRole()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "self", "You cannot disable your own login or change your own role.");
        if (self && u.hasCustomAccess())
            throw new ApiException(HttpStatus.BAD_REQUEST, "self", "You cannot change your own access. Ask an admin.");
        boolean losesAdmin = u.getRole() == AppUser.Role.ADMIN && u.isEnabled()
                && (body.role() != AppUser.Role.ADMIN || !body.enabled());
        if (losesAdmin && users.findByOrganizationIdAndRoleAndEnabledTrue(u.getOrganizationId(), AppUser.Role.ADMIN).size() <= 1)
            throw new ApiException(HttpStatus.BAD_REQUEST, "last_admin", "The company needs at least one active admin.");
        List<String> changes = new ArrayList<>();
        String name = body.fullName().strip();
        if (!u.getFullName().equals(name)) changes.add("name " + u.getFullName() + " -> " + name);
        if (u.getRole() != body.role()) changes.add("role " + u.getRole() + " -> " + body.role());
        if (u.isEnabled() != body.enabled()) changes.add(body.enabled() ? "enabled" : "disabled");
        String before = accessText(u);
        u.setFullName(name);
        u.setRole(body.role());
        u.setEnabled(body.enabled());
        applyAccess(u, body.features(), body.actions(), body.ownDataOnly());
        if (!before.equals(accessText(u))) changes.add("access " + before + " -> " + accessText(u));
        if (!changes.isEmpty())
            audit.record(u.getOrganizationId(), body.enabled() ? "USER_UPDATED" : "USER_DISABLED", "user", u.getId(),
                    u.getEmail() + ": " + String.join(", ", changes));
        return dto(u, monthActivity(u.getOrganizationId()));
    }

    @PostMapping("/{id}/password")
    @Transactional
    public void resetPassword(@PathVariable Long id, @Valid @RequestBody PasswordReset body) {
        AppUser u = load(id);
        changeable(u);
        u.setPasswordHash(encoder.encode(body.password()));
        audit.record(u.getOrganizationId(), "USER_PASSWORD_RESET", "user", u.getId(), u.getEmail());
    }

    // --- helpers -------------------------------------------------------------------------------------

    private AppUser load(Long id) {
        return users.findById(id).filter(u -> u.getOrganizationId().equals(CurrentUser.orgId()))
                .orElseThrow(() -> ApiException.notFound("User"));
    }

    /** Only the provider creates or hands out the provider role. */
    private static void allowedRole(AppUser.Role role) {
        if (role == AppUser.Role.OPERATOR && !CurrentUser.isOperator())
            throw new ApiException(HttpStatus.FORBIDDEN, "provider_only", "Only your e-invoicing provider can manage provider logins.");
    }

    /** Only a full admin may create admins or change an admin: a user given the Users feature can't raise anyone above themselves. */
    private static void noEscalation(AppUser.Role role) {
        if (role != AppUser.Role.ADMIN) return;
        CurrentUser me = CurrentUser.get();
        if (me.role() != AppUser.Role.ADMIN && !CurrentUser.isOperator())
            throw new ApiException(HttpStatus.FORBIDDEN, "admin_only", "Only an admin can create or change admin logins.");
    }

    /**
     * Admins and the provider always have full access. Any other user given a feature list is on per-user access:
     * the role becomes Staff, "view" is always included, and the scope is all data or only their own.
     */
    private static void applyAccess(AppUser u, List<String> features, List<String> actions, boolean ownDataOnly) {
        if (u.getRole() == AppUser.Role.ADMIN || u.getRole() == AppUser.Role.OPERATOR || features == null) {
            u.setFeatures(null);
            u.setActions(null);
            u.setOwnDataOnly(false);
            return;
        }
        List<String> f = features.stream().filter(com.fatoora.api.settings.FeatureCatalog::isFeature).distinct().toList();
        if (f.isEmpty()) throw new ApiException.Validation(Map.of("features", "Tick at least one feature for this user"));
        java.util.Set<String> a = new java.util.TreeSet<>(actions == null ? List.of() : actions);
        a.retainAll(com.fatoora.api.settings.FeatureCatalog.ACTIONS);
        a.add("view");
        u.setRole(AppUser.Role.ACCOUNTANT);
        u.setFeatures(String.join(",", f));
        u.setActions(String.join(",", a));
        u.setOwnDataOnly(ownDataOnly);
    }

    private static String accessText(AppUser u) {
        return u.getFeatures() == null ? "full" : "[" + u.getFeatures() + "] [" + u.getActions() + "] " + (u.isOwnDataOnly() ? "own data" : "all data");
    }

    private static List<String> csv(String s) {
        return s == null ? null : java.util.Arrays.stream(s.split(",")).filter(x -> !x.isBlank()).toList();
    }

    private static void changeable(AppUser u) {
        if (u.getRole() == AppUser.Role.OPERATOR && !CurrentUser.isOperator())
            throw new ApiException(HttpStatus.FORBIDDEN, "provider_only", "Only your e-invoicing provider can manage provider logins.");
    }

    /** email (lower case) -> [log entries this month, time of the latest one]. */
    private Map<String, Object[]> monthActivity(Long org) {
        Map<String, Object[]> m = new HashMap<>();
        for (Object[] r : logs.activityByActor(org, monthStart())) m.put((String) r[0], new Object[]{r[1], r[2]});
        return m;
    }

    private static UserDto dto(AppUser u, Map<String, Object[]> act) {
        Object[] a = act.get(u.getEmail().toLowerCase(Locale.ROOT));
        return new UserDto(u.getId(), u.getEmail(), u.getFullName(), u.getRole().name(), u.isEnabled(),
                u.getLastLoginAt(), u.getCreatedAt(), a == null ? 0 : (Long) a[0], a == null ? null : (Instant) a[1],
                csv(u.getFeatures()), csv(u.getActions()), u.isOwnDataOnly());
    }

    private static Instant monthStart() {
        return LocalDate.now(InvoiceApiApplication.KSA).withDayOfMonth(1).atStartOfDay(InvoiceApiApplication.KSA).toInstant();
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }
}
