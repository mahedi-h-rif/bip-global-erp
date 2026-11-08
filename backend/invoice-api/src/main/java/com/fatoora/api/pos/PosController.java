package com.fatoora.api.pos;

import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.inventory.StockService;
import com.fatoora.api.invoice.Invoice;
import com.fatoora.api.invoice.InvoiceDtos;
import com.fatoora.api.invoice.InvoiceService;
import com.fatoora.api.invoice.SendMode;
import com.fatoora.api.organization.OrganizationRepository;
import com.fatoora.api.payment.InvoicePayment;
import com.fatoora.api.payment.PaymentService;
import com.fatoora.api.pdf.ReceiptPdf;
import com.fatoora.zatca.model.DocumentType;
import com.fatoora.zatca.model.InvoiceKind;
import com.fatoora.zatca.model.VatCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Point of sale: a product grid per warehouse, a stock-aware cart, and one call that turns the cart into an issued
 * simplified tax invoice (reported to ZATCA) with its payments, then an 80 mm receipt.
 */
@RestController
@RequestMapping("/api/v1/pos")
public class PosController {

    public record GridItem(Long id, String number, String name, String nameAr, String barcode, String category, BigDecimal unitPrice,
                           String unitCode, boolean trackStock, BigDecimal onHand) {}

    public record CartLine(@NotNull Long productId, @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
                           @DecimalMin("0") BigDecimal unitPrice, @DecimalMin("0") @DecimalMax("100") BigDecimal discountPct) {}

    public record Tender(@NotNull InvoicePayment.Method method, @NotNull @DecimalMin("0") BigDecimal amount) {}

    public record SaleRequest(@NotNull Long warehouseId, Long customerId, @NotEmpty List<@Valid CartLine> lines,
                              List<@Valid Tender> payments, @Size(max = 500) String note) {}

    public record SaleResult(Long invoiceId, String number, String status, BigDecimal total, BigDecimal paid, BigDecimal change,
                             BigDecimal due) {}

    private final JdbcTemplate jdbc;
    private final InvoiceService invoices;
    private final PaymentService payments;
    private final StockService stock;
    private final ReceiptPdf receipts;
    private final OrganizationRepository orgs;
    private final AuditService audit;

    public PosController(JdbcTemplate jdbc, InvoiceService invoices, PaymentService payments, StockService stock, ReceiptPdf receipts,
                         OrganizationRepository orgs, AuditService audit) {
        this.jdbc = jdbc;
        this.invoices = invoices;
        this.payments = payments;
        this.stock = stock;
        this.receipts = receipts;
        this.orgs = orgs;
        this.audit = audit;
    }

    @GetMapping("/products")
    List<GridItem> grid(@RequestParam Long warehouseId, @RequestParam(required = false) String q) {
        Long orgId = CurrentUser.orgId();
        stock.requireWarehouse(orgId, warehouseId);
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return jdbc.query("""
                SELECT p.*, COALESCE(s.quantity, 0) AS on_hand FROM product p
                LEFT JOIN stock_level s ON s.product_id = p.id AND s.warehouse_id = ?
                WHERE p.organization_id = ? AND p.active AND p.is_sales_item
                  AND (?::text IS NULL OR lower(p.name) LIKE ?::text OR lower(p.number) LIKE ?::text OR p.barcode = ?::text
                       OR lower(COALESCE(p.name_ar, '')) LIKE ?::text)
                ORDER BY lower(COALESCE(p.category, '')), lower(p.name) LIMIT 300""",
                (rs, i) -> new GridItem(rs.getLong("id"), rs.getString("number"), rs.getString("name"), rs.getString("name_ar"),
                        rs.getString("barcode"), rs.getString("category"), rs.getBigDecimal("unit_price"), rs.getString("unit_code"),
                        rs.getBoolean("track_stock"), rs.getBigDecimal("on_hand")),
                warehouseId, orgId, like, like, like, q == null ? null : q.trim(), like);
    }

    @PostMapping("/sale")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','BRANCH')")
    @Transactional
    SaleResult sale(@Valid @RequestBody SaleRequest req) {
        Long orgId = CurrentUser.orgId();
        String user = CurrentUser.get().email();
        stock.requireWarehouse(orgId, req.warehouseId());
        List<InvoiceDtos.LineDto> lines = new ArrayList<>();
        for (CartLine c : req.lines()) {
            Map<String, Object> p = jdbc.queryForList("SELECT name, unit_price, unit_code FROM product WHERE id = ? AND organization_id = ? AND active",
                    c.productId(), orgId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Product"));
            BigDecimal price = c.unitPrice() != null ? c.unitPrice() : (BigDecimal) p.get("unit_price");
            if (c.discountPct() != null && c.discountPct().signum() > 0)
                price = price.subtract(price.multiply(c.discountPct()).divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
            lines.add(new InvoiceDtos.LineDto((String) p.get("name"), c.quantity(), (String) p.get("unit_code"),
                    price.setScale(2, RoundingMode.HALF_UP), VatCategory.S, new BigDecimal("15"), null, null, c.productId(), null));
        }
        List<Tender> tenders = req.payments() == null ? List.of() : req.payments().stream().filter(t -> t.amount().signum() > 0).toList();
        String means = tenders.isEmpty() ? "1" : tenders.stream().map(Tender::method).distinct().count() > 1 ? "1"
                : switch (tenders.getFirst().method()) { case CASH -> "10"; case CARD -> "48"; case BANK_TRANSFER -> "42"; default -> "1"; };
        Invoice draft = invoices.createDraft(orgId, user, new InvoiceDtos.DraftRequest(InvoiceKind.SIMPLIFIED, DocumentType.INVOICE,
                req.customerId(), LocalDate.now(com.fatoora.api.InvoiceApiApplication.KSA), means, SendMode.NOW, null, null,
                req.note() == null || req.note().isBlank() ? "POS sale" : "POS sale · " + req.note().trim(), null, lines,
                req.warehouseId(), null, null));
        BigDecimal total = draft.getTotalInclVat();
        BigDecimal tendered = tenders.stream().map(Tender::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (tendered.compareTo(total) < 0 && req.customerId() == null)
            throw ApiException.badRequest("credit_needs_customer", "A credit or part-paid sale needs a customer, so the balance can be followed up");
        // Issuing signs the invoice, takes the stock (refused if short) and reports it to ZATCA.
        Invoice inv = invoices.issue(orgId, draft.getId(), SendMode.NOW);
        BigDecimal left = total, paid = BigDecimal.ZERO;
        for (Tender t : tenders) {
            BigDecimal amount = t.amount().min(left);
            if (amount.signum() <= 0) break;
            payments.record(orgId, user, inv.getId(), new PaymentService.PaymentRequest(amount, null, t.method(), "POS", null));
            left = left.subtract(amount);
            paid = paid.add(amount);
        }
        BigDecimal change = tendered.subtract(total).max(BigDecimal.ZERO);
        audit.record(orgId, "POS_SALE", "invoice", inv.getId(), inv.getNumber() + " " + total + " SAR, paid " + paid);
        return new SaleResult(inv.getId(), inv.getNumber(), inv.getStatus().name(), total, paid, change, total.subtract(paid));
    }

    /** 80 mm receipt; {@code tendered} lets the reprint show the change given. */
    @GetMapping("/receipt/{invoiceId}")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> receipt(@PathVariable Long invoiceId, @RequestParam(required = false) BigDecimal tendered) {
        Long orgId = CurrentUser.orgId();
        Invoice inv = invoices.load(orgId, invoiceId);
        if (inv.getNumber() == null || inv.getQrCode() == null) throw ApiException.badRequest("not_issued", "The invoice is not issued yet");
        BigDecimal paid = jdbc.queryForObject("SELECT COALESCE(sum(amount), 0) FROM invoice_payment WHERE invoice_id = ?", BigDecimal.class, invoiceId);
        BigDecimal change = tendered == null ? BigDecimal.ZERO : tendered.subtract(inv.getTotalInclVat()).max(BigDecimal.ZERO);
        byte[] pdf = receipts.render(inv, orgs.findById(orgId).orElseThrow(), paid, change, inv.getCreatedBy());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(inv.getNumber() + "-receipt.pdf").build().toString())
                .body(pdf);
    }
}
