package com.fatoora.api.payment;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.invoice.Invoice;
import com.fatoora.api.invoice.InvoiceFigures;
import com.fatoora.api.invoice.InvoiceRepository;
import com.fatoora.api.payment.BalanceCalculator.Balance;
import com.fatoora.zatca.model.DocumentType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Payments received against issued invoices, and the resulting balances. */
@Service
public class PaymentService {

    public record PaymentRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal amount,
            LocalDate paidOn,
            InvoicePayment.Method method,
            @Size(max = 255) String reference,
            @Size(max = 1000) String note) {}

    public record PaymentDto(Long id, Long invoiceId, BigDecimal amount, LocalDate paidOn, InvoicePayment.Method method,
                             String reference, String note, String createdBy, Instant createdAt) {

        static PaymentDto of(InvoicePayment p) {
            return new PaymentDto(p.getId(), p.getInvoiceId(), p.getAmount(), p.getPaidOn(), p.getMethod(),
                    p.getReference(), p.getNote(), p.getCreatedBy(), p.getCreatedAt());
        }
    }

    private final InvoicePaymentRepository payments;
    private final InvoiceRepository invoices;
    private final AuditService audit;

    public PaymentService(InvoicePaymentRepository payments, InvoiceRepository invoices, AuditService audit) {
        this.payments = payments;
        this.invoices = invoices;
        this.audit = audit;
    }

    // --- balances --------------------------------------------------------------------------------

    /** Balances of every issued invoice of the organization. */
    @Transactional(readOnly = true)
    public Map<Long, Balance> balances(Long orgId) {
        return BalanceCalculator.compute(invoices.findIssuedFigures(orgId), paidTotals(payments.totalsByInvoice(orgId)));
    }

    /** Balances of the given invoices only (drafts and notes among them are skipped). */
    @Transactional(readOnly = true)
    public Map<Long, Balance> balancesFor(Collection<Long> invoiceIds) {
        if (invoiceIds.isEmpty()) return Map.of();
        List<InvoiceFigures> docs = invoices.findFiguresWithNotes(invoiceIds);
        return BalanceCalculator.compute(docs, paidTotals(payments.totalsFor(invoiceIds)));
    }

    /** Null for documents that carry no balance (drafts, rejected documents, credit and debit notes). */
    @Transactional(readOnly = true)
    public Balance balance(Long invoiceId) {
        return balancesFor(List.of(invoiceId)).get(invoiceId);
    }

    // --- payments --------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<PaymentDto> list(Long orgId, Long invoiceId) {
        load(orgId, invoiceId);
        return payments.findByInvoiceIdOrderByPaidOnAscIdAsc(invoiceId).stream().map(PaymentDto::of).toList();
    }

    @Transactional
    public PaymentDto record(Long orgId, String user, Long invoiceId, PaymentRequest req) {
        Invoice inv = lockPayable(orgId, invoiceId);
        requireWithinDue(inv, req.amount(), BigDecimal.ZERO);
        InvoicePayment p = new InvoicePayment();
        p.setOrganizationId(orgId);
        p.setInvoiceId(inv.getId());
        p.setCreatedBy(user);
        apply(p, req);
        payments.save(p);
        audit.record(orgId, "PAYMENT_RECORDED", "invoice", inv.getId(), inv.getNumber() + " " + p.getAmount() + " " + p.getMethod());
        return PaymentDto.of(p);
    }

    @Transactional
    public PaymentDto update(Long orgId, Long paymentId, PaymentRequest req) {
        InvoicePayment p = payments.findByIdAndOrganizationId(paymentId, orgId).orElseThrow(() -> ApiException.notFound("Payment"));
        Invoice inv = lockPayable(orgId, p.getInvoiceId());
        requireWithinDue(inv, req.amount(), p.getAmount());
        apply(p, req);
        audit.record(orgId, "PAYMENT_UPDATED", "invoice", inv.getId(), inv.getNumber() + " " + p.getAmount() + " " + p.getMethod());
        return PaymentDto.of(p);
    }

    /** Removes every payment of an invoice that is itself being deleted (a test document). */
    @Transactional
    public void deleteForInvoice(Long invoiceId) {
        payments.deleteAll(payments.findByInvoiceIdOrderByPaidOnAscIdAsc(invoiceId));
    }

    @Transactional
    public void delete(Long orgId, Long paymentId) {
        InvoicePayment p = payments.findByIdAndOrganizationId(paymentId, orgId).orElseThrow(() -> ApiException.notFound("Payment"));
        payments.delete(p);
        audit.record(orgId, "PAYMENT_DELETED", "invoice", p.getInvoiceId(), p.getAmount() + " " + p.getMethod());
    }

    /** The due date is internal payment terms, not part of the ZATCA XML, so it may change after issue. */
    @Transactional
    public void setDueDate(Long orgId, Long invoiceId, LocalDate dueDate) {
        Invoice inv = load(orgId, invoiceId);
        if (inv.getType() != DocumentType.INVOICE) throw ApiException.badRequest("not_invoice", "Only invoices have a due date");
        inv.setDueDate(dueDate);
        audit.record(orgId, "DUE_DATE_SET", "invoice", inv.getId(), String.valueOf(dueDate));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private Invoice lockPayable(Long orgId, Long invoiceId) {
        Invoice inv = invoices.lockByIdAndOrganizationId(invoiceId, orgId).orElseThrow(() -> ApiException.notFound("Invoice"));
        if (inv.getType() != DocumentType.INVOICE)
            throw ApiException.badRequest("not_invoice", "Record payments against the invoice, not against a credit or debit note");
        if (!inv.getStatus().billable())
            throw ApiException.conflict("not_issued", "Payments can only be recorded against an issued invoice");
        return inv;
    }

    /** @param replacing the amount of the payment being edited, which is freed up again */
    private void requireWithinDue(Invoice inv, BigDecimal amount, BigDecimal replacing) {
        Balance b = balance(inv.getId());
        BigDecimal available = (b == null ? BigDecimal.ZERO : b.due()).add(replacing);
        if (amount.compareTo(available) > 0)
            throw new ApiException.Validation(Map.of("amount", "The amount can't exceed what is still due (" + available + " SAR)"));
    }

    private static void apply(InvoicePayment p, PaymentRequest req) {
        p.setAmount(req.amount());
        p.setPaidOn(req.paidOn() == null ? LocalDate.now(InvoiceApiApplication.KSA) : req.paidOn());
        p.setMethod(req.method() == null ? InvoicePayment.Method.CASH : req.method());
        p.setReference(blankToNull(req.reference()));
        p.setNote(blankToNull(req.note()));
    }

    private Invoice load(Long orgId, Long invoiceId) {
        return invoices.findByIdAndOrganizationId(invoiceId, orgId).orElseThrow(() -> ApiException.notFound("Invoice"));
    }

    private static Map<Long, BigDecimal> paidTotals(List<PaidTotal> totals) {
        return totals.stream().collect(Collectors.toMap(PaidTotal::invoiceId, PaidTotal::amount));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
