package com.fatoora.api.flow;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.customer.CustomerRepository;
import com.fatoora.api.inventory.StockService;
import com.fatoora.api.payment.BalanceCalculator;
import com.fatoora.api.payment.InvoicePayment;
import com.fatoora.api.payment.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Incoming payments (RC-YYYY-NNNNNN): one amount from a customer settling one or more invoices. Each allocation is
 * an ordinary invoice payment tagged with the receipt, so dues, reminders and reports keep working unchanged.
 */
@RestController
@RequestMapping("/api/v1/receipts")
public class ReceiptController {

    private static final String WRITE = "hasAnyRole('ADMIN','ACCOUNTANT')";

    public record Allocation(@NotNull Long invoiceId, @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal amount,
                             String invoiceNumber) {}

    public record Request(@NotNull Long customerId, LocalDate receiptDate, @NotNull InvoicePayment.Method method,
                          @Size(max = 255) String reference, @Size(max = 1000) String note,
                          @NotEmpty List<@Valid Allocation> allocations) {}

    public record Dto(Long id, String number, Long customerId, String customerName, LocalDate receiptDate, String method,
                      String reference, BigDecimal amount, String note, String createdBy, Instant createdAt,
                      List<Allocation> allocations, String status, Instant cancelledAt, String cancelledBy, String cancelReason) {}

    public record CancelRequest(@NotBlank @Size(max = 500) String reason) {}

    /** An issued invoice of the customer that still has something to pay. */
    public record OpenInvoice(Long invoiceId, String number, LocalDate issueDate, LocalDate dueDate, String customerRef,
                              BigDecimal total, BigDecimal due, long overdueDays) {}

    private final JdbcTemplate jdbc;
    private final PaymentService payments;
    private final StockService numbers;
    private final CustomerRepository customers;
    private final AuditService audit;

    public ReceiptController(JdbcTemplate jdbc, PaymentService payments, StockService numbers, CustomerRepository customers,
                             AuditService audit) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.numbers = numbers;
        this.customers = customers;
        this.audit = audit;
    }

    @GetMapping
    List<Dto> list(@RequestParam(required = false) Long customerId) {
        Long orgId = CurrentUser.orgId();
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM receipt WHERE organization_id = ? AND (?::bigint IS NULL OR customer_id = ?::bigint)
                ORDER BY id DESC LIMIT 500""", Long.class, orgId, customerId, customerId);
        List<Dto> out = new ArrayList<>();
        for (Long id : ids) out.add(dto(orgId, id));
        return out;
    }

    @GetMapping("/{id}")
    Dto get(@PathVariable Long id) {
        return dto(CurrentUser.orgId(), id);
    }

    @GetMapping("/open-invoices")
    List<OpenInvoice> openInvoices(@RequestParam Long customerId) {
        Long orgId = CurrentUser.orgId();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, number, (issued_at AT TIME ZONE 'Asia/Riyadh')::date AS issued, due_date, customer_ref, total_incl_vat
                FROM invoice WHERE organization_id = ? AND customer_id = ? AND doc_type = 'INVOICE'
                  AND status NOT IN ('DRAFT','REJECTED') ORDER BY issued_at""", orgId, customerId);
        Map<Long, BalanceCalculator.Balance> balances = payments.balancesFor(
                rows.stream().map(r -> ((Number) r.get("id")).longValue()).toList());
        LocalDate today = LocalDate.now(InvoiceApiApplication.KSA);
        List<OpenInvoice> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Long id = ((Number) r.get("id")).longValue();
            BalanceCalculator.Balance b = balances.get(id);
            if (b == null || b.due().signum() <= 0) continue;
            LocalDate due = r.get("due_date") == null ? null : ((java.sql.Date) r.get("due_date")).toLocalDate();
            LocalDate issued = r.get("issued") == null ? null : ((java.sql.Date) r.get("issued")).toLocalDate();
            LocalDate from = due != null ? due : issued;
            out.add(new OpenInvoice(id, (String) r.get("number"), issued, due, (String) r.get("customer_ref"),
                    (BigDecimal) r.get("total_incl_vat"), b.due(), from == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(from, today))));
        }
        return out;
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Dto create(@Valid @RequestBody Request req) {
        Long orgId = CurrentUser.orgId();
        customers.findByIdAndOrganizationId(req.customerId(), orgId).orElseThrow(() -> ApiException.notFound("Customer"));
        Set<Long> seen = new HashSet<>();
        for (Allocation a : req.allocations()) {
            if (!seen.add(a.invoiceId())) throw ApiException.badRequest("duplicate_invoice", "Each invoice may appear only once");
            Long owner = jdbc.queryForList("SELECT customer_id FROM invoice WHERE id = ? AND organization_id = ?", Long.class,
                    a.invoiceId(), orgId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Invoice"));
            if (!req.customerId().equals(owner))
                throw ApiException.badRequest("other_customer", "Every invoice must belong to the chosen customer");
        }
        BigDecimal total = req.allocations().stream().map(Allocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDate date = req.receiptDate() == null ? LocalDate.now(InvoiceApiApplication.KSA) : req.receiptDate();
        String number = numbers.nextNumber(orgId, "RECEIPT", "RC");
        String user = CurrentUser.get().email();
        Long id = jdbc.queryForObject("""
                INSERT INTO receipt (organization_id, number, customer_id, receipt_date, method, reference, amount, note, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, orgId, number, req.customerId(), date,
                req.method().name(), blank(req.reference()), total, blank(req.note()), user);
        for (Allocation a : req.allocations()) {
            // The payment service enforces "not more than is due" per invoice, inside this transaction.
            var p = payments.record(orgId, user, a.invoiceId(), new PaymentService.PaymentRequest(a.amount(), date, req.method(),
                    req.reference() == null || req.reference().isBlank() ? number : number + " · " + req.reference().trim(), blank(req.note())));
            jdbc.update("UPDATE invoice_payment SET receipt_id = ? WHERE id = ?", id, p.id());
        }
        audit.record(orgId, "RECEIPT_CREATED", "receipt", id, number + " " + total + " SAR, " + req.allocations().size() + " invoice(s)");
        return dto(orgId, id);
    }

    /**
     * Cancels a posted receipt: its payments come off the invoices (so they are due again and the payment can be
     * reposted correctly), while the receipt itself stays on record, marked cancelled, with what it had allocated.
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize(WRITE)
    @Transactional
    Dto cancel(@PathVariable Long id, @Valid @RequestBody CancelRequest req) {
        Long orgId = CurrentUser.orgId();
        Dto d = dto(orgId, id);
        if ("CANCELLED".equals(d.status())) return d;
        String snapshot = String.join(";", d.allocations().stream()
                .map(a -> a.invoiceId() + ":" + a.amount().toPlainString() + ":" + a.invoiceNumber()).toList());
        jdbc.update("DELETE FROM invoice_payment WHERE receipt_id = ?", id);
        jdbc.update("""
                UPDATE receipt SET status = 'CANCELLED', cancelled_at = now(), cancelled_by = ?, cancel_reason = ?,
                  cancelled_allocations = ? WHERE id = ?""", CurrentUser.get().email(), req.reason().trim(), snapshot, id);
        audit.record(orgId, "RECEIPT_CANCELLED", "receipt", id, d.number() + " " + d.amount() + " SAR: " + req.reason().trim());
        return dto(orgId, id);
    }

    private Dto dto(Long orgId, Long id) {
        List<Allocation> live = jdbc.query("""
                SELECT p.invoice_id, p.amount, i.number FROM invoice_payment p JOIN invoice i ON i.id = p.invoice_id
                WHERE p.receipt_id = ? ORDER BY p.id""", (rs, i) -> new Allocation(rs.getLong(1), rs.getBigDecimal(2), rs.getString(3)), id);
        return jdbc.query("""
                SELECT r.*, COALESCE(c.name_en, c.name_ar) AS cname FROM receipt r JOIN customer c ON c.id = r.customer_id
                WHERE r.id = ? AND r.organization_id = ?""",
                (rs, i) -> new Dto(rs.getLong("id"), rs.getString("number"), rs.getLong("customer_id"), rs.getString("cname"),
                        rs.getObject("receipt_date", LocalDate.class), rs.getString("method"), rs.getString("reference"),
                        rs.getBigDecimal("amount"), rs.getString("note"), rs.getString("created_by"),
                        rs.getTimestamp("created_at").toInstant(),
                        "CANCELLED".equals(rs.getString("status")) ? snapshot(rs.getString("cancelled_allocations")) : live,
                        rs.getString("status"),
                        rs.getTimestamp("cancelled_at") == null ? null : rs.getTimestamp("cancelled_at").toInstant(),
                        rs.getString("cancelled_by"), rs.getString("cancel_reason")), id, orgId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Receipt"));
    }

    private static List<Allocation> snapshot(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<Allocation> out = new ArrayList<>();
        for (String part : s.split(";")) {
            String[] f = part.split(":", 3);
            out.add(new Allocation(Long.parseLong(f[0]), new BigDecimal(f[1]), f.length > 2 ? f[2] : null));
        }
        return out;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
