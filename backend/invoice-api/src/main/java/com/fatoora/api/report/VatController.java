package com.fatoora.api.report;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * VAT owed to ZATCA: output VAT on issued sales documents (invoices and debit notes, less credit notes) minus input
 * VAT on supplier invoices (less supplier credit notes), for any period or by calendar quarter, plus a record of
 * each quarter's VAT return being filed and paid.
 */
@RestController
@RequestMapping("/api/v1/vat")
public class VatController {

    private static final String WRITE = "hasAnyRole('ADMIN','ACCOUNTANT')";

    /** VAT of a period. {@code payable} positive = owed to ZATCA, negative = refundable. */
    public record Summary(LocalDate from, LocalDate to,
                          BigDecimal salesNet, BigDecimal salesVat, BigDecimal creditNotesVat, BigDecimal debitNotesVat,
                          BigDecimal outputVat, BigDecimal purchasesNet, BigDecimal purchasesVat, BigDecimal supplierCreditsVat,
                          BigDecimal inputVat, BigDecimal payable, long salesDocuments, long purchaseDocuments) {}

    /** A quarter's VAT return as recorded by the company: filed with ZATCA and/or paid. */
    public record Filing(LocalDate filedOn, String reference, BigDecimal paidAmount, LocalDate paidOn, String notes,
                         String updatedBy, java.time.Instant updatedAt) {}

    public record Quarter(int year, int quarter, LocalDate from, LocalDate to, LocalDate deadline, Summary vat, Filing filing,
                          /** NOT_DUE, DUE (period ended, not filed), OVERDUE (deadline passed), FILED, PAID */
                          String state) {}

    public record FilingRequest(LocalDate filedOn, @Size(max = 100) String reference,
                                @DecimalMin("0") BigDecimal paidAmount, LocalDate paidOn, @Size(max = 1000) String notes) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public VatController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Any period; defaults to the current quarter. */
    @GetMapping("/summary")
    Summary summary(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        LocalDate today = LocalDate.now(InvoiceApiApplication.KSA);
        LocalDate start = from != null ? from : quarterStart(today);
        LocalDate end = to != null ? to : start.plusMonths(3).minusDays(1);
        if (end.isBefore(start)) throw ApiException.badRequest("bad_period", "The end date is before the start date");
        return compute(CurrentUser.orgId(), start, end);
    }

    /** The four quarters of a year with their VAT and filing record. */
    @GetMapping("/quarters")
    List<Quarter> quarters(@RequestParam(required = false) Integer year) {
        Long orgId = CurrentUser.orgId();
        LocalDate today = LocalDate.now(InvoiceApiApplication.KSA);
        int y = year == null ? today.getYear() : year;
        List<Quarter> out = new ArrayList<>();
        for (int q = 1; q <= 4; q++) {
            LocalDate from = LocalDate.of(y, 3 * q - 2, 1);
            LocalDate to = from.plusMonths(3).minusDays(1);
            LocalDate deadline = to.plusMonths(1).withDayOfMonth(to.plusMonths(1).lengthOfMonth());
            Summary vat = compute(orgId, from, to);
            Filing filing = filing(orgId, y, q);
            String state;
            if (filing != null && filing.paidOn() != null) state = "PAID";
            else if (filing != null && filing.filedOn() != null) state = "FILED";
            else if (!today.isAfter(to)) state = "NOT_DUE";
            else state = today.isAfter(deadline) ? "OVERDUE" : "DUE";
            out.add(new Quarter(y, q, from, to, deadline, vat, filing, state));
        }
        return out;
    }

    /** Records (or updates) that the quarter's VAT return was filed with ZATCA and/or paid. */
    @PutMapping("/returns/{year}/{quarter}")
    @PreAuthorize(WRITE)
    @Transactional
    Quarter saveFiling(@PathVariable int year, @PathVariable int quarter, @Valid @RequestBody FilingRequest req) {
        if (quarter < 1 || quarter > 4 || year < 2018 || year > 2100) throw ApiException.badRequest("bad_quarter", "Choose a quarter 1 to 4");
        Long orgId = CurrentUser.orgId();
        jdbc.update("""
                INSERT INTO vat_return (organization_id, year, quarter, filed_on, reference, paid_amount, paid_on, notes, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (organization_id, year, quarter) DO UPDATE SET filed_on = EXCLUDED.filed_on, reference = EXCLUDED.reference,
                  paid_amount = EXCLUDED.paid_amount, paid_on = EXCLUDED.paid_on, notes = EXCLUDED.notes,
                  updated_by = EXCLUDED.updated_by, updated_at = now()""",
                orgId, year, quarter, req.filedOn(), blank(req.reference()), req.paidAmount(), req.paidOn(), blank(req.notes()),
                CurrentUser.get().email());
        audit.record(orgId, "VAT_RETURN_RECORDED", "vat_return", null, year + " Q" + quarter
                + (req.filedOn() != null ? " filed " + req.filedOn() : "") + (req.paidOn() != null ? " paid " + req.paidAmount() : ""));
        return quarters(year).get(quarter - 1);
    }

    @DeleteMapping("/returns/{year}/{quarter}")
    @PreAuthorize(WRITE)
    @Transactional
    Quarter clearFiling(@PathVariable int year, @PathVariable int quarter) {
        Long orgId = CurrentUser.orgId();
        jdbc.update("DELETE FROM vat_return WHERE organization_id = ? AND year = ? AND quarter = ?", orgId, year, quarter);
        audit.record(orgId, "VAT_RETURN_CLEARED", "vat_return", null, year + " Q" + quarter);
        return quarters(year).get(Math.max(1, Math.min(4, quarter)) - 1);
    }

    // --- figures ------------------------------------------------------------------------------------

    Summary compute(Long orgId, LocalDate from, LocalDate to) {
        // Sales: issued documents ZATCA counts (not drafts or rejected), by their issue date in Saudi time.
        Map<String, Object> s = jdbc.queryForMap("""
                SELECT COALESCE(sum(CASE WHEN doc_type = 'INVOICE' THEN total_excl_vat END), 0) AS inv_net,
                       COALESCE(sum(CASE WHEN doc_type = 'INVOICE' THEN vat_total END), 0) AS inv_vat,
                       COALESCE(sum(CASE WHEN doc_type = 'CREDIT_NOTE' THEN total_excl_vat END), 0) AS cn_net,
                       COALESCE(sum(CASE WHEN doc_type = 'CREDIT_NOTE' THEN vat_total END), 0) AS cn_vat,
                       COALESCE(sum(CASE WHEN doc_type = 'DEBIT_NOTE' THEN total_excl_vat END), 0) AS dn_net,
                       COALESCE(sum(CASE WHEN doc_type = 'DEBIT_NOTE' THEN vat_total END), 0) AS dn_vat,
                       count(*) AS docs
                FROM invoice WHERE organization_id = ? AND status NOT IN ('DRAFT','REJECTED') AND issued_at IS NOT NULL
                  AND (issued_at AT TIME ZONE 'Asia/Riyadh')::date BETWEEN ? AND ?""", orgId, from, to);
        // Purchases: supplier invoices less supplier credit notes, in SAR, by their own dates.
        Map<String, Object> p = jdbc.queryForMap("""
                SELECT COALESCE((SELECT sum(total_sar - vat_sar) FROM supplier_invoice WHERE organization_id = ? AND status <> 'CANCELLED'
                                 AND invoice_date BETWEEN ? AND ?), 0) AS si_net,
                       COALESCE((SELECT sum(vat_sar) FROM supplier_invoice WHERE organization_id = ? AND status <> 'CANCELLED'
                                 AND invoice_date BETWEEN ? AND ?), 0) AS si_vat,
                       COALESCE((SELECT sum(total_sar - vat_sar) FROM supplier_credit_note WHERE organization_id = ? AND status <> 'CANCELLED'
                                 AND credit_date BETWEEN ? AND ?), 0) AS sc_net,
                       COALESCE((SELECT sum(vat_sar) FROM supplier_credit_note WHERE organization_id = ? AND status <> 'CANCELLED'
                                 AND credit_date BETWEEN ? AND ?), 0) AS sc_vat,
                       (SELECT count(*) FROM supplier_invoice WHERE organization_id = ? AND status <> 'CANCELLED' AND invoice_date BETWEEN ? AND ?) AS docs""",
                orgId, from, to, orgId, from, to, orgId, from, to, orgId, from, to, orgId, from, to);
        BigDecimal salesNet = n(s, "inv_net").subtract(n(s, "cn_net")).add(n(s, "dn_net"));
        BigDecimal output = n(s, "inv_vat").subtract(n(s, "cn_vat")).add(n(s, "dn_vat"));
        BigDecimal purchasesNet = n(p, "si_net").subtract(n(p, "sc_net"));
        BigDecimal input = n(p, "si_vat").subtract(n(p, "sc_vat"));
        return new Summary(from, to, salesNet, n(s, "inv_vat"), n(s, "cn_vat"), n(s, "dn_vat"), output,
                purchasesNet, n(p, "si_vat"), n(p, "sc_vat"), input, output.subtract(input),
                ((Number) s.get("docs")).longValue(), ((Number) p.get("docs")).longValue());
    }

    private Filing filing(Long orgId, int year, int quarter) {
        return jdbc.query("SELECT * FROM vat_return WHERE organization_id = ? AND year = ? AND quarter = ?",
                (rs, i) -> new Filing(rs.getObject("filed_on", LocalDate.class), rs.getString("reference"), rs.getBigDecimal("paid_amount"),
                        rs.getObject("paid_on", LocalDate.class), rs.getString("notes"), rs.getString("updated_by"),
                        rs.getTimestamp("updated_at").toInstant()), orgId, year, quarter).stream().findFirst().orElse(null);
    }

    static LocalDate quarterStart(LocalDate d) {
        return LocalDate.of(d.getYear(), ((d.getMonthValue() - 1) / 3) * 3 + 1, 1);
    }

    private static BigDecimal n(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString()).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
