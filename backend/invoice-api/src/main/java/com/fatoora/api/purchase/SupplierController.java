package com.fatoora.api.purchase;

import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Suppliers (vendors), local or international, with what is owed to each. */
@RestController
@RequestMapping("/api/v1/suppliers")
public class SupplierController {

    static final String WRITE = "hasAnyRole('ADMIN','ACCOUNTANT')";

    public record Dto(Long id, @Size(max = 30) String code, @NotBlank @Size(max = 300) String name, @Size(max = 300) String nameAr,
                      @Size(max = 30) String vatNumber, @Size(max = 30) String crNumber, @Size(max = 1000) String address,
                      @Size(max = 100) String city, @Pattern(regexp = "[A-Z]{2}") String country, @Size(max = 40) String phone,
                      @Email String email, @Size(max = 200) String contactPerson, @Pattern(regexp = "[A-Z]{3}") String currency,
                      @Min(0) @Max(365) Integer paymentTermsDays, Boolean active,
                      /** Read-only: open supplier-invoice balance in SAR. */
                      BigDecimal payable) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public SupplierController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping
    List<Dto> list(@RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean all) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return jdbc.query("""
                SELECT s.*, COALESCE((SELECT sum(v.total_sar) FROM supplier_invoice v WHERE v.supplier_id = s.id AND v.status = 'OPEN'), 0)
                       - COALESCE((SELECT sum(a.amount) FROM supplier_payment_allocation a JOIN supplier_payment p ON p.id = a.supplier_payment_id
                                   JOIN supplier_invoice v ON v.id = a.supplier_invoice_id
                                   WHERE v.supplier_id = s.id AND p.status = 'POSTED' AND v.status = 'OPEN'), 0) AS payable
                FROM supplier s WHERE s.organization_id = ? AND (? OR s.active)
                  AND (?::text IS NULL OR lower(s.name) LIKE ?::text OR lower(s.code) LIKE ?::text OR lower(COALESCE(s.name_ar, '')) LIKE ?::text)
                ORDER BY lower(s.name)""", (rs, i) -> dto(rs), CurrentUser.orgId(), all, like, like, like, like);
    }

    @GetMapping("/{id}")
    Dto get(@PathVariable Long id) {
        return list(null, true).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Supplier"));
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Dto create(@Valid @RequestBody Dto d) {
        Long orgId = CurrentUser.orgId();
        String code = d.code() == null || d.code().isBlank()
                ? "V%05d".formatted(jdbc.queryForObject("SELECT count(*) + 1 FROM supplier WHERE organization_id = ?", Integer.class, orgId))
                : d.code().trim();
        requireUniqueCode(orgId, code, null);
        Long id = jdbc.queryForObject("""
                INSERT INTO supplier (organization_id, code, name, name_ar, vat_number, cr_number, address, city, country, phone, email,
                                      contact_person, currency, payment_terms_days) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                Long.class, orgId, code, d.name().trim(), b(d.nameAr()), b(d.vatNumber()), b(d.crNumber()), b(d.address()), b(d.city()),
                d.country() == null ? "SA" : d.country(), b(d.phone()), b(d.email()), b(d.contactPerson()),
                d.currency() == null ? "SAR" : d.currency(), d.paymentTermsDays() == null ? 30 : d.paymentTermsDays());
        audit.record(orgId, "SUPPLIER_CREATED", "supplier", id, code + " " + d.name());
        return get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    Dto update(@PathVariable Long id, @Valid @RequestBody Dto d) {
        Dto old = get(id);
        Long orgId = CurrentUser.orgId();
        String code = d.code() == null || d.code().isBlank() ? old.code() : d.code().trim();
        requireUniqueCode(orgId, code, id);
        jdbc.update("""
                UPDATE supplier SET code = ?, name = ?, name_ar = ?, vat_number = ?, cr_number = ?, address = ?, city = ?, country = ?,
                  phone = ?, email = ?, contact_person = ?, currency = ?, payment_terms_days = ?, active = ? WHERE id = ?""",
                code, d.name().trim(), b(d.nameAr()), b(d.vatNumber()), b(d.crNumber()), b(d.address()), b(d.city()),
                d.country() == null ? old.country() : d.country(), b(d.phone()), b(d.email()), b(d.contactPerson()),
                d.currency() == null ? old.currency() : d.currency(), d.paymentTermsDays() == null ? old.paymentTermsDays() : d.paymentTermsDays(),
                d.active() == null || d.active(), id);
        audit.record(orgId, "SUPPLIER_UPDATED", "supplier", id, code + " " + d.name());
        return get(id);
    }

    private void requireUniqueCode(Long orgId, String code, Long exceptId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM supplier WHERE organization_id = ? AND lower(code) = lower(?) AND (?::bigint IS NULL OR id <> ?::bigint)",
                Integer.class, orgId, code, exceptId, exceptId);
        if (n != null && n > 0) throw new ApiException.Validation(Map.of("code", "A supplier with this code already exists"));
    }

    private static Dto dto(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Dto(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("name_ar"), rs.getString("vat_number"),
                rs.getString("cr_number"), rs.getString("address"), rs.getString("city"), rs.getString("country"), rs.getString("phone"),
                rs.getString("email"), rs.getString("contact_person"), rs.getString("currency"), rs.getInt("payment_terms_days"),
                rs.getBoolean("active"), rs.getBigDecimal("payable"));
    }

    static String b(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
