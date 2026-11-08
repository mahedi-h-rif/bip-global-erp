package com.fatoora.api.flow;

import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.invoice.*;
import com.fatoora.zatca.model.DocumentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Sales returns (quotation: "full and partial returns with automatic stock and accounting updates"): choose invoice lines
 * and quantities; a credit note is made for exactly those, which on issue puts the stock back and reduces the balance.
 */
@RestController
@RequestMapping("/api/v1/invoices/{invoiceId}/returns")
public class ReturnController {

    public record ReturnLine(@NotNull @Min(0) Integer lineIndex, @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity) {}

    public record ReturnRequest(@NotBlank @Size(max = 500) String reason, @NotEmpty List<@Valid ReturnLine> lines, Boolean issue) {}

    /** Per line of the invoice: sold, already returned on credit notes, still returnable. */
    public record Returnable(int lineIndex, String name, BigDecimal unitPrice, BigDecimal sold, BigDecimal returned, BigDecimal returnable) {}

    private final InvoiceService invoices;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ReturnController(InvoiceService invoices, JdbcTemplate jdbc, AuditService audit) {
        this.invoices = invoices;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping
    List<Returnable> returnable(@PathVariable Long invoiceId) {
        Invoice inv = issuedInvoice(invoiceId);
        List<Returnable> out = new ArrayList<>();
        List<InvoiceLineEntity> lines = inv.getLines();
        for (int i = 0; i < lines.size(); i++) {
            InvoiceLineEntity l = lines.get(i);
            BigDecimal back = jdbc.queryForObject("""
                    SELECT COALESCE(sum(cl.quantity), 0) FROM invoice_line cl JOIN invoice c ON c.id = cl.invoice_id
                    WHERE c.original_invoice_id = ? AND c.doc_type = 'CREDIT_NOTE' AND c.status <> 'REJECTED'
                      AND cl.name = ? AND cl.unit_price = ? AND COALESCE(cl.product_id, -1) = COALESCE(?::bigint, -1)""",
                    BigDecimal.class, invoiceId, l.getName(), l.getUnitPrice(), l.getProductId());
            out.add(new Returnable(i, l.getName(), l.getUnitPrice(), l.getQuantity(), back, l.getQuantity().subtract(back).max(BigDecimal.ZERO)));
        }
        return out;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Map<String, Object> create(@PathVariable Long invoiceId, @Valid @RequestBody ReturnRequest req) {
        Long orgId = CurrentUser.orgId();
        Invoice inv = issuedInvoice(invoiceId);
        List<Returnable> can = returnable(invoiceId);
        List<InvoiceDtos.LineDto> lines = new ArrayList<>();
        for (ReturnLine r : req.lines()) {
            if (r.lineIndex() >= can.size()) throw ApiException.badRequest("unknown_line", "Unknown invoice line");
            Returnable x = can.get(r.lineIndex());
            if (r.quantity().compareTo(x.returnable()) > 0)
                throw ApiException.badRequest("over_return", "\"" + x.name() + "\": only " + x.returnable().stripTrailingZeros().toPlainString() + " can still be returned");
            InvoiceLineEntity l = inv.getLines().get(r.lineIndex());
            lines.add(new InvoiceDtos.LineDto(l.getName(), r.quantity(), l.getUnitCode(), l.getUnitPrice(), l.getVatCategory(), l.getVatRate(),
                    l.getExemptionCode(), l.getExemptionReason(), l.getProductId(), null));
        }
        Invoice note = invoices.createDraft(orgId, CurrentUser.get().email(), new InvoiceDtos.DraftRequest(inv.getKind(), DocumentType.CREDIT_NOTE,
                inv.getCustomerId(), null, inv.getPaymentMeans(), SendMode.NOW, inv.getId(), req.reason().trim(), "Return on " + inv.getNumber(),
                null, lines, inv.getWarehouseId(), inv.getCustomerRef(), inv.getSalesEmployee()));
        audit.record(orgId, "SALES_RETURN", "invoice", note.getId(), inv.getNumber() + ": " + req.lines().size() + " line(s) – " + req.reason());
        if (Boolean.TRUE.equals(req.issue())) note = invoices.issue(orgId, note.getId(), SendMode.NOW);
        return Map.of("creditNoteId", note.getId(), "status", note.getStatus().name(), "total", note.getTotalInclVat());
    }

    private Invoice issuedInvoice(Long id) {
        Invoice inv = invoices.load(CurrentUser.orgId(), id);
        if (inv.getType() != DocumentType.INVOICE || inv.getStatus() == InvoiceStatus.DRAFT || inv.getStatus() == InvoiceStatus.REJECTED)
            throw ApiException.badRequest("not_returnable", "Returns can only be made on an issued invoice");
        return inv;
    }
}
