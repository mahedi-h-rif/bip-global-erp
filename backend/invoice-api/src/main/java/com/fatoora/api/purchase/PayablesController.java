package com.fatoora.api.purchase;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.inventory.StockService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Accounts payable: supplier invoices (SINV-) made from goods receipts (Copy To) or directly, and supplier payments
 * (SP-) settling one or more of them. Payments are cancelled with a reason, never deleted.
 */
@RestController
@RequestMapping("/api/v1")
public class PayablesController {

    private static final String WRITE = SupplierController.WRITE;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public record InvLine(Long goodsReceiptLineId, Long productId, @NotBlank String name,
                          @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
                          @NotNull @DecimalMin("0") BigDecimal unitCost, @DecimalMin("0") BigDecimal vatRate) {}

    public record InvRequest(@NotNull Long supplierId, @Size(max = 100) String supplierInvoiceNo, LocalDate invoiceDate, LocalDate dueDate,
                             @Pattern(regexp = "[A-Z]{3}") String currency, @DecimalMin(value = "0", inclusive = false) BigDecimal exchangeRate,
                             @Size(max = 4000) String remarks, @NotEmpty List<@Valid InvLine> lines) {}

    public record InvDto(Long id, String number, Long supplierId, String supplierName, String supplierInvoiceNo, LocalDate invoiceDate,
                         LocalDate dueDate, String currency, BigDecimal exchangeRate, String status, String remarks, BigDecimal totalExclVat,
                         BigDecimal vatTotal, BigDecimal totalInclVat, BigDecimal totalSar, BigDecimal vatSar, BigDecimal paid, BigDecimal due,
                         long overdueDays, String createdBy, Instant createdAt, List<InvLine> lines, List<String> goodsReceipts) {}

    public record Alloc(@NotNull Long supplierInvoiceId, @NotNull @DecimalMin("0.01") BigDecimal amount, String invoiceNumber) {}

    public record PayRequest(@NotNull Long supplierId, LocalDate paymentDate, @NotBlank String method, @Size(max = 255) String reference,
                             @Size(max = 1000) String note, @NotEmpty List<@Valid Alloc> allocations) {}

    public record PayDto(Long id, String number, Long supplierId, String supplierName, LocalDate paymentDate, String method,
                         String reference, BigDecimal amount, String note, String status, String cancelReason, String createdBy,
                         Instant createdAt, List<Alloc> allocations) {}

    public record CancelRequest(@NotBlank @Size(max = 500) String reason) {}

    private final JdbcTemplate jdbc;
    private final StockService numbers;
    private final PurchaseController purchases;
    private final AuditService audit;

    public PayablesController(JdbcTemplate jdbc, StockService numbers, PurchaseController purchases, AuditService audit) {
        this.jdbc = jdbc;
        this.numbers = numbers;
        this.purchases = purchases;
        this.audit = audit;
    }

    // --- supplier invoices -----------------------------------------------------------------------

    @GetMapping("/supplier-invoices")
    List<InvDto> listInv(@RequestParam(required = false) Long supplierId, @RequestParam(defaultValue = "false") boolean openOnly) {
        Long orgId = CurrentUser.orgId();
        List<InvDto> out = new ArrayList<>();
        for (Long id : jdbc.queryForList("SELECT id FROM supplier_invoice WHERE organization_id = ? AND (?::bigint IS NULL OR supplier_id = ?::bigint) ORDER BY invoice_date, id",
                Long.class, orgId, supplierId, supplierId)) {
            InvDto d = inv(orgId, id);
            if (!openOnly || ("OPEN".equals(d.status()) && d.due().signum() > 0)) out.add(d);
        }
        Collections.reverse(out);
        return out;
    }

    @GetMapping("/supplier-invoices/{id}")
    InvDto getInv(@PathVariable Long id) {
        return inv(CurrentUser.orgId(), id);
    }

    /** Copy To Supplier Invoice: the not-yet-invoiced lines of one or more goods receipts of the same supplier. */
    @GetMapping("/supplier-invoices/from-goods-receipts")
    InvRequest prefill(@RequestParam List<Long> ids) {
        Long orgId = CurrentUser.orgId();
        Long supplierId = null;
        String currency = null;
        BigDecimal rate = null;
        List<InvLine> lines = new ArrayList<>();
        List<String> numbersSeen = new ArrayList<>();
        for (Long gid : ids) {
            var g = purchases.grn(orgId, gid);
            if (!"POSTED".equals(g.status())) throw ApiException.badRequest("grn_cancelled", g.number() + " is cancelled");
            if (supplierId == null) { supplierId = g.supplierId(); currency = g.currency(); rate = g.exchangeRate(); }
            else if (!supplierId.equals(g.supplierId())) throw ApiException.badRequest("mixed_suppliers", "All receipts must be from the same supplier");
            for (var l : g.lines())
                if (l.remaining().signum() > 0)
                    lines.add(new InvLine(l.id(), l.productId(), l.name(), l.remaining(), l.unitCost(), l.vatRate()));
            numbersSeen.add(g.number());
        }
        if (lines.isEmpty()) throw ApiException.badRequest("nothing_to_invoice", "These receipts are already fully invoiced");
        return new InvRequest(supplierId, null, LocalDate.now(InvoiceApiApplication.KSA), null, currency, rate,
                "Based on Goods Receipts " + String.join(", ", numbersSeen) + ".", lines);
    }

    @PostMapping("/supplier-invoices")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    InvDto createInv(@Valid @RequestBody InvRequest req) {
        Long orgId = CurrentUser.orgId();
        Map<String, Object> s = jdbc.queryForList("SELECT * FROM supplier WHERE id = ? AND organization_id = ?", req.supplierId(), orgId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Supplier"));
        // Receipt lines may be billed at most once in total, and only to their own supplier.
        Map<Long, BigDecimal> asked = new HashMap<>();
        for (InvLine l : req.lines()) if (l.goodsReceiptLineId() != null) asked.merge(l.goodsReceiptLineId(), l.quantity(), BigDecimal::add);
        for (var e : asked.entrySet()) {
            Map<String, Object> gl = jdbc.queryForList("""
                    SELECT gl.quantity, g.supplier_id, g.organization_id, g.status, g.number FROM goods_receipt_line gl
                    JOIN goods_receipt g ON g.id = gl.goods_receipt_id WHERE gl.id = ?""", e.getKey()).stream().findFirst()
                    .orElseThrow(() -> ApiException.notFound("Goods receipt line"));
            if (!orgId.equals(((Number) gl.get("organization_id")).longValue()) || !req.supplierId().equals(((Number) gl.get("supplier_id")).longValue()))
                throw ApiException.badRequest("other_supplier", "A receipt line belongs to another supplier");
            if (!"POSTED".equals(gl.get("status"))) throw ApiException.badRequest("grn_cancelled", gl.get("number") + " is cancelled");
            BigDecimal billed = jdbc.queryForObject("""
                    SELECT COALESCE(sum(sl.quantity), 0) FROM supplier_invoice_line sl JOIN supplier_invoice v ON v.id = sl.supplier_invoice_id
                    WHERE sl.goods_receipt_line_id = ? AND v.status = 'OPEN'""", BigDecimal.class, e.getKey());
            BigDecimal left = ((BigDecimal) gl.get("quantity")).subtract(billed);
            if (e.getValue().compareTo(left) > 0)
                throw ApiException.badRequest("over_billing", "A line bills more than is left on " + gl.get("number") + " (" + StockService.plainOf(left) + ")");
        }
        String currency = req.currency() == null ? (String) s.get("currency") : req.currency();
        BigDecimal rate = "SAR".equals(currency) ? BigDecimal.ONE : req.exchangeRate() == null ? BigDecimal.ONE : req.exchangeRate();
        LocalDate date = req.invoiceDate() == null ? LocalDate.now(InvoiceApiApplication.KSA) : req.invoiceDate();
        LocalDate due = req.dueDate() != null ? req.dueDate() : date.plusDays(((Number) s.get("payment_terms_days")).longValue());
        String number = numbers.nextNumber(orgId, "SUPPLIER_INVOICE", "SINV");
        Long id = jdbc.queryForObject("""
                INSERT INTO supplier_invoice (organization_id, number, supplier_id, supplier_invoice_no, invoice_date, due_date, currency,
                                              exchange_rate, remarks, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                Long.class, orgId, number, req.supplierId(), SupplierController.b(req.supplierInvoiceNo()), date, due, currency, rate,
                SupplierController.b(req.remarks()), CurrentUser.get().email());
        BigDecimal net = BigDecimal.ZERO, vat = BigDecimal.ZERO;
        int pos = 1;
        for (InvLine l : req.lines()) {
            BigDecimal r = l.vatRate() == null ? new BigDecimal("15") : l.vatRate();
            BigDecimal ln = l.quantity().multiply(l.unitCost()).setScale(2, RoundingMode.HALF_UP);
            net = net.add(ln);
            vat = vat.add(ln.multiply(r).divide(HUNDRED, 2, RoundingMode.HALF_UP));
            jdbc.update("""
                    INSERT INTO supplier_invoice_line (supplier_invoice_id, position, goods_receipt_line_id, product_id, name, quantity, unit_cost, vat_rate)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", id, pos++, l.goodsReceiptLineId(), l.productId(), l.name().strip(), l.quantity(), l.unitCost(), r);
        }
        BigDecimal total = net.add(vat);
        jdbc.update("UPDATE supplier_invoice SET total_excl_vat = ?, vat_total = ?, total_incl_vat = ?, total_sar = ?, vat_sar = ? WHERE id = ?",
                net, vat, total, total.multiply(rate).setScale(2, RoundingMode.HALF_UP), vat.multiply(rate).setScale(2, RoundingMode.HALF_UP), id);
        audit.record(orgId, "SUPPLIER_INVOICE_CREATED", "supplier_invoice", id, number + " " + total + " " + currency);
        return inv(orgId, id);
    }

    @PostMapping("/supplier-invoices/{id}/cancel")
    @PreAuthorize(WRITE)
    @Transactional
    InvDto cancelInv(@PathVariable Long id) {
        Long orgId = CurrentUser.orgId();
        InvDto d = inv(orgId, id);
        if (d.paid().signum() > 0) throw ApiException.conflict("supplier_invoice_paid", "Cancel the payments on this invoice first");
        jdbc.update("UPDATE supplier_invoice SET status = 'CANCELLED' WHERE id = ?", id);
        audit.record(orgId, "SUPPLIER_INVOICE_CANCELLED", "supplier_invoice", id, d.number());
        return inv(orgId, id);
    }

    // --- supplier payments -----------------------------------------------------------------------

    @GetMapping("/supplier-payments")
    List<PayDto> listPay(@RequestParam(required = false) Long supplierId) {
        Long orgId = CurrentUser.orgId();
        List<PayDto> out = new ArrayList<>();
        for (Long id : jdbc.queryForList("SELECT id FROM supplier_payment WHERE organization_id = ? AND (?::bigint IS NULL OR supplier_id = ?::bigint) ORDER BY id DESC LIMIT 500",
                Long.class, orgId, supplierId, supplierId)) out.add(pay(orgId, id));
        return out;
    }

    @GetMapping("/supplier-payments/{id}")
    PayDto getPay(@PathVariable Long id) {
        return pay(CurrentUser.orgId(), id);
    }

    @PostMapping("/supplier-payments")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    PayDto createPay(@Valid @RequestBody PayRequest req) {
        Long orgId = CurrentUser.orgId();
        Set<Long> seen = new HashSet<>();
        for (Alloc a : req.allocations()) {
            if (!seen.add(a.supplierInvoiceId())) throw ApiException.badRequest("duplicate_invoice", "Each invoice may appear only once");
            jdbc.queryForList("SELECT id FROM supplier_invoice WHERE id = ? FOR UPDATE", Long.class, a.supplierInvoiceId());
            InvDto d = inv(orgId, a.supplierInvoiceId());
            if (!d.supplierId().equals(req.supplierId())) throw ApiException.badRequest("other_supplier", d.number() + " belongs to another supplier");
            if (!"OPEN".equals(d.status())) throw ApiException.badRequest("supplier_invoice_cancelled", d.number() + " is cancelled");
            if (a.amount().compareTo(d.due()) > 0)
                throw new ApiException.Validation(Map.of("amount", "The amount for " + d.number() + " can't exceed what is still due (" + d.due() + " SAR)"));
        }
        BigDecimal total = req.allocations().stream().map(Alloc::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String number = numbers.nextNumber(orgId, "SUPPLIER_PAYMENT", "SP");
        Long id = jdbc.queryForObject("""
                INSERT INTO supplier_payment (organization_id, number, supplier_id, payment_date, method, reference, amount, note, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, orgId, number, req.supplierId(),
                req.paymentDate() == null ? LocalDate.now(InvoiceApiApplication.KSA) : req.paymentDate(), req.method(),
                SupplierController.b(req.reference()), total, SupplierController.b(req.note()), CurrentUser.get().email());
        for (Alloc a : req.allocations())
            jdbc.update("INSERT INTO supplier_payment_allocation (supplier_payment_id, supplier_invoice_id, amount) VALUES (?, ?, ?)",
                    id, a.supplierInvoiceId(), a.amount());
        audit.record(orgId, "SUPPLIER_PAYMENT_CREATED", "supplier_payment", id, number + " " + total + " SAR");
        return pay(orgId, id);
    }

    @PostMapping("/supplier-payments/{id}/cancel")
    @PreAuthorize(WRITE)
    @Transactional
    PayDto cancelPay(@PathVariable Long id, @Valid @RequestBody CancelRequest req) {
        Long orgId = CurrentUser.orgId();
        PayDto d = pay(orgId, id);
        jdbc.update("UPDATE supplier_payment SET status = 'CANCELLED', cancel_reason = ? WHERE id = ?", req.reason().trim(), id);
        audit.record(orgId, "SUPPLIER_PAYMENT_CANCELLED", "supplier_payment", id, d.number() + ": " + req.reason());
        return pay(orgId, id);
    }

    // --- helpers ---------------------------------------------------------------------------------

    InvDto inv(Long orgId, Long id) {
        List<InvLine> lines = jdbc.query("SELECT * FROM supplier_invoice_line WHERE supplier_invoice_id = ? ORDER BY position",
                (rs, i) -> new InvLine((Long) rs.getObject("goods_receipt_line_id"), (Long) rs.getObject("product_id"), rs.getString("name"),
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_cost"), rs.getBigDecimal("vat_rate")), id);
        List<String> grns = jdbc.queryForList("""
                SELECT DISTINCT g.number FROM supplier_invoice_line sl JOIN goods_receipt_line gl ON gl.id = sl.goods_receipt_line_id
                JOIN goods_receipt g ON g.id = gl.goods_receipt_id WHERE sl.supplier_invoice_id = ? ORDER BY g.number""", String.class, id);
        BigDecimal paid = jdbc.queryForObject("""
                SELECT COALESCE(sum(a.amount), 0) FROM supplier_payment_allocation a JOIN supplier_payment p ON p.id = a.supplier_payment_id
                WHERE a.supplier_invoice_id = ? AND p.status = 'POSTED'""", BigDecimal.class, id);
        LocalDate today = LocalDate.now(InvoiceApiApplication.KSA);
        return jdbc.query("""
                SELECT v.*, s.name AS sname FROM supplier_invoice v JOIN supplier s ON s.id = v.supplier_id WHERE v.id = ? AND v.organization_id = ?""",
                (rs, i) -> {
                    BigDecimal due = "OPEN".equals(rs.getString("status")) ? rs.getBigDecimal("total_sar").subtract(paid).max(BigDecimal.ZERO) : BigDecimal.ZERO;
                    LocalDate dd = rs.getObject("due_date", LocalDate.class);
                    long overdue = due.signum() > 0 && dd != null ? Math.max(0, ChronoUnit.DAYS.between(dd, today)) : 0;
                    return new InvDto(rs.getLong("id"), rs.getString("number"), rs.getLong("supplier_id"), rs.getString("sname"),
                            rs.getString("supplier_invoice_no"), rs.getObject("invoice_date", LocalDate.class), dd, rs.getString("currency"),
                            rs.getBigDecimal("exchange_rate"), rs.getString("status"), rs.getString("remarks"), rs.getBigDecimal("total_excl_vat"),
                            rs.getBigDecimal("vat_total"), rs.getBigDecimal("total_incl_vat"), rs.getBigDecimal("total_sar"), rs.getBigDecimal("vat_sar"),
                            paid, due, overdue, rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(), lines, grns);
                }, id, orgId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Supplier invoice"));
    }

    private PayDto pay(Long orgId, Long id) {
        List<Alloc> alloc = jdbc.query("""
                SELECT a.supplier_invoice_id, a.amount, v.number FROM supplier_payment_allocation a JOIN supplier_invoice v ON v.id = a.supplier_invoice_id
                WHERE a.supplier_payment_id = ? ORDER BY a.id""", (rs, i) -> new Alloc(rs.getLong(1), rs.getBigDecimal(2), rs.getString(3)), id);
        return jdbc.query("""
                SELECT p.*, s.name AS sname FROM supplier_payment p JOIN supplier s ON s.id = p.supplier_id WHERE p.id = ? AND p.organization_id = ?""",
                (rs, i) -> new PayDto(rs.getLong("id"), rs.getString("number"), rs.getLong("supplier_id"), rs.getString("sname"),
                        rs.getObject("payment_date", LocalDate.class), rs.getString("method"), rs.getString("reference"), rs.getBigDecimal("amount"),
                        rs.getString("note"), rs.getString("status"), rs.getString("cancel_reason"), rs.getString("created_by"),
                        rs.getTimestamp("created_at").toInstant(), alloc), id, orgId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Supplier payment"));
    }
}
