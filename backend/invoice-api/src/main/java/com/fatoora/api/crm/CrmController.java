package com.fatoora.api.crm;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.flow.FlowService;
import com.fatoora.api.invoice.InvoiceDtos;
import com.fatoora.api.invoice.InvoiceService;
import com.fatoora.api.invoice.SendMode;
import com.fatoora.zatca.model.DocumentType;
import com.fatoora.zatca.model.InvoiceKind;
import com.fatoora.zatca.model.VatCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** CRM pipeline (Odoo CRM), customer credit control, and recurring contract invoices (Odoo Subscriptions). */
@RestController
@RequestMapping("/api/v1/crm")
public class CrmController {

    private static final String WRITE = "hasAnyRole('ADMIN','ACCOUNTANT')";

    public record Lead(Long id, @NotBlank @Size(max = 300) String title, @Size(max = 300) String company, @Size(max = 200) String contactName,
                       @Size(max = 40) String phone, @Email String email, @Size(max = 60) String source,
                       @Pattern(regexp = "NEW|QUALIFIED|PROPOSAL|WON|LOST") String stage, @DecimalMin("0") BigDecimal expectedValue,
                       LocalDate expectedClose, String owner, @Size(max = 4000) String notes, @Size(max = 500) String lostReason,
                       Long customerId, String customerName) {}

    public record Credit(Long customerId, String name, @DecimalMin("0") BigDecimal creditLimit, @Min(0) @Max(365) Integer paymentTermsDays,
                         BigDecimal outstanding, BigDecimal available) {}

    public record RecLine(@NotBlank String name, @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
                          @NotNull @DecimalMin("0") BigDecimal unitPrice, BigDecimal vatRate, Long productId) {}

    public record Recurring(Long id, @NotNull Long customerId, String customerName, @NotBlank @Size(max = 200) String name,
                            @Pattern(regexp = "STANDARD|SIMPLIFIED") String kind, @NotEmpty List<@Valid RecLine> lines,
                            @Min(1) @Max(12) Integer intervalMonths, @NotNull LocalDate nextDate, LocalDate endDate, Boolean active,
                            Long lastInvoiceId, BigDecimal amount) {}

    private final JdbcTemplate jdbc;
    private final FlowService flow;
    private final InvoiceService invoices;
    private final JsonMapper json;
    private final AuditService audit;

    public CrmController(JdbcTemplate jdbc, FlowService flow, InvoiceService invoices, JsonMapper json, AuditService audit) {
        this.jdbc = jdbc;
        this.flow = flow;
        this.invoices = invoices;
        this.json = json;
        this.audit = audit;
    }

    // --- leads -----------------------------------------------------------------------------------

    @GetMapping("/leads")
    List<Lead> leads(@RequestParam(required = false) String stage) {
        return jdbc.query("""
                SELECT l.*, COALESCE(c.name_en, c.name_ar) AS cname FROM lead l LEFT JOIN customer c ON c.id = l.customer_id
                WHERE l.organization_id = ? AND (?::text IS NULL OR l.stage = ?::text) ORDER BY l.updated_at DESC LIMIT 500""",
                (rs, i) -> new Lead(rs.getLong("id"), rs.getString("title"), rs.getString("company"), rs.getString("contact_name"), rs.getString("phone"),
                        rs.getString("email"), rs.getString("source"), rs.getString("stage"), rs.getBigDecimal("expected_value"),
                        rs.getObject("expected_close", LocalDate.class), rs.getString("owner"), rs.getString("notes"), rs.getString("lost_reason"),
                        (Long) rs.getObject("customer_id"), rs.getString("cname")), CurrentUser.orgId(), stage, stage);
    }

    @PostMapping("/leads")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Lead createLead(@Valid @RequestBody Lead l) {
        Long id = jdbc.queryForObject("INSERT INTO lead (organization_id, title, owner) VALUES (?, ?, ?) RETURNING id", Long.class,
                CurrentUser.orgId(), l.title().trim(), CurrentUser.get().email());
        writeLead(id, l);
        audit.record(CurrentUser.orgId(), "LEAD_CREATED", "lead", id, l.title());
        return lead(id);
    }

    @PutMapping("/leads/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    Lead updateLead(@PathVariable Long id, @Valid @RequestBody Lead l) {
        Lead old = lead(id);
        if ("LOST".equals(l.stage()) && (l.lostReason() == null || l.lostReason().isBlank()))
            throw new ApiException.Validation(Map.of("lostReason", "Say why the opportunity was lost"));
        writeLead(id, l);
        if (!Objects.equals(old.stage(), l.stage()))
            audit.record(CurrentUser.orgId(), "LEAD_STAGE", "lead", id, old.stage() + " → " + l.stage());
        return lead(id);
    }

    /** Won: makes the company a customer (if it isn't one yet) so a quotation can be made for it. */
    @PostMapping("/leads/{id}/convert")
    @PreAuthorize(WRITE)
    @Transactional
    Lead convert(@PathVariable Long id) {
        Long orgId = CurrentUser.orgId();
        Lead l = lead(id);
        Long customerId = l.customerId();
        if (customerId == null) {
            String name = l.company() != null ? l.company() : l.title();
            customerId = jdbc.queryForList("SELECT id FROM customer WHERE organization_id = ? AND (lower(name_en) = lower(?) OR name_ar = ?)",
                    Long.class, orgId, name, name).stream().findFirst().orElse(null);
            if (customerId == null)
                customerId = jdbc.queryForObject("INSERT INTO customer (organization_id, name_ar, name_en, phone, email) VALUES (?, ?, ?, ?, ?) RETURNING id",
                        Long.class, orgId, name, name, l.phone(), l.email());
        }
        jdbc.update("UPDATE lead SET customer_id = ?, stage = CASE WHEN stage IN ('NEW','QUALIFIED') THEN 'PROPOSAL' ELSE stage END, updated_at = now() WHERE id = ?",
                customerId, id);
        audit.record(orgId, "LEAD_CONVERTED", "lead", id, "customer " + customerId);
        return lead(id);
    }

    private void writeLead(Long id, Lead l) {
        jdbc.update("""
                UPDATE lead SET title = ?, company = ?, contact_name = ?, phone = ?, email = ?, source = ?, stage = ?, expected_value = ?,
                  expected_close = ?, notes = ?, lost_reason = ?, updated_at = now() WHERE id = ?""",
                l.title().trim(), b(l.company()), b(l.contactName()), b(l.phone()), b(l.email()), b(l.source()), l.stage() == null ? "NEW" : l.stage(),
                l.expectedValue(), l.expectedClose(), b(l.notes()), b(l.lostReason()), id);
    }

    private Lead lead(Long id) {
        return leads(null).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Lead"));
    }

    // --- credit control --------------------------------------------------------------------------

    @GetMapping("/credit")
    List<Credit> credit() {
        return jdbc.query("SELECT id, COALESCE(name_en, name_ar) AS n, credit_limit, payment_terms_days FROM customer WHERE organization_id = ? ORDER BY lower(COALESCE(name_en, name_ar))",
                (rs, i) -> {
                    BigDecimal owed = flow.outstanding(rs.getLong("id"));
                    BigDecimal lim = rs.getBigDecimal("credit_limit");
                    return new Credit(rs.getLong("id"), rs.getString("n"), lim, (Integer) rs.getObject("payment_terms_days"), owed,
                            lim == null || lim.signum() == 0 ? null : lim.subtract(owed));
                }, CurrentUser.orgId());
    }

    @PutMapping("/credit/{customerId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    Credit setCredit(@PathVariable Long customerId, @Valid @RequestBody Credit c) {
        int n = jdbc.update("UPDATE customer SET credit_limit = ?, payment_terms_days = ? WHERE id = ? AND organization_id = ?",
                c.creditLimit(), c.paymentTermsDays(), customerId, CurrentUser.orgId());
        if (n == 0) throw ApiException.notFound("Customer");
        audit.record(CurrentUser.orgId(), "CREDIT_LIMIT_SET", "customer", customerId, c.creditLimit() + " SAR, " + c.paymentTermsDays() + " days");
        return credit().stream().filter(x -> x.customerId().equals(customerId)).findFirst().orElseThrow();
    }

    // --- recurring invoices ----------------------------------------------------------------------

    @GetMapping("/recurring")
    List<Recurring> recurring() {
        return jdbc.query("""
                SELECT r.*, COALESCE(c.name_en, c.name_ar) AS cname FROM recurring_invoice r JOIN customer c ON c.id = r.customer_id
                WHERE r.organization_id = ? ORDER BY r.active DESC, r.next_date""", (rs, i) -> {
            List<RecLine> lines = parseLines(rs.getString("lines"));
            BigDecimal amt = lines.stream().map(l -> l.quantity().multiply(l.unitPrice()).multiply(BigDecimal.ONE.add(
                    (l.vatRate() == null ? new BigDecimal("15") : l.vatRate()).movePointLeft(2)))).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            return new Recurring(rs.getLong("id"), rs.getLong("customer_id"), rs.getString("cname"), rs.getString("name"), rs.getString("kind"), lines,
                    rs.getInt("interval_months"), rs.getObject("next_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                    rs.getBoolean("active"), (Long) rs.getObject("last_invoice_id"), amt);
        }, CurrentUser.orgId());
    }

    @PostMapping("/recurring")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Recurring createRecurring(@Valid @RequestBody Recurring r) {
        Long orgId = CurrentUser.orgId();
        if (jdbc.queryForObject("SELECT count(*) FROM customer WHERE id = ? AND organization_id = ?", Integer.class, r.customerId(), orgId) == 0)
            throw ApiException.notFound("Customer");
        Long id = jdbc.queryForObject("""
                INSERT INTO recurring_invoice (organization_id, customer_id, name, kind, lines, interval_months, next_date, end_date, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, orgId, r.customerId(), r.name().trim(),
                r.kind() == null ? "STANDARD" : r.kind(), json.writeValueAsString(r.lines()), r.intervalMonths() == null ? 1 : r.intervalMonths(),
                r.nextDate(), r.endDate(), CurrentUser.get().email());
        audit.record(orgId, "RECURRING_CREATED", "recurring_invoice", id, r.name());
        return recurring().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    @PutMapping("/recurring/{id}/active")
    @PreAuthorize(WRITE)
    void setActive(@PathVariable Long id, @RequestParam boolean active) {
        jdbc.update("UPDATE recurring_invoice SET active = ? WHERE id = ? AND organization_id = ?", active, id, CurrentUser.orgId());
    }

    /** Makes the due draft invoices now (the scheduler does the same every morning). */
    @PostMapping("/recurring/run")
    @PreAuthorize(WRITE)
    @Transactional
    Map<String, Object> runNow() {
        return Map.of("created", generate(CurrentUser.orgId(), CurrentUser.get().email()));
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Riyadh")
    @Transactional
    public void daily() {
        for (Long org : jdbc.queryForList("SELECT DISTINCT organization_id FROM recurring_invoice WHERE active", Long.class)) generate(org, "scheduler");
    }

    /** One draft invoice per due template; the template moves to its next date. Drafts are reviewed and issued by staff. */
    List<Long> generate(Long orgId, String user) {
        LocalDate today = LocalDate.now(InvoiceApiApplication.KSA);
        List<Long> created = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT * FROM recurring_invoice WHERE organization_id = ? AND active AND next_date <= ? AND (end_date IS NULL OR next_date <= end_date)
                FOR UPDATE SKIP LOCKED""", orgId, today)) {
            LocalDate next = ((java.sql.Date) r.get("next_date")).toLocalDate();
            String period = next.getYear() + "-" + String.format("%02d", next.getMonthValue());
            List<InvoiceDtos.LineDto> lines = parseLines((String) r.get("lines")).stream()
                    .map(l -> new InvoiceDtos.LineDto(l.name() + " (" + period + ")", l.quantity(), "PCE", l.unitPrice(), VatCategory.S,
                            l.vatRate() == null ? new BigDecimal("15") : l.vatRate(), null, null, l.productId(), null)).toList();
            var inv = invoices.createDraft(orgId, user, new InvoiceDtos.DraftRequest(InvoiceKind.valueOf((String) r.get("kind")), DocumentType.INVOICE,
                    ((Number) r.get("customer_id")).longValue(), next, null, SendMode.NOW, null, null,
                    "Recurring: " + r.get("name") + " · " + period, null, lines, null, null, null));
            jdbc.update("UPDATE recurring_invoice SET next_date = ?, last_invoice_id = ? WHERE id = ?",
                    next.plusMonths(((Number) r.get("interval_months")).intValue()), inv.getId(), r.get("id"));
            created.add(inv.getId());
            audit.recordAs(user, orgId, "RECURRING_INVOICE_DRAFT", "invoice", inv.getId(), r.get("name") + " " + period);
        }
        return created;
    }

    private List<RecLine> parseLines(String s) {
        return Arrays.asList(json.readValue(s, RecLine[].class));
    }

    private static String b(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
