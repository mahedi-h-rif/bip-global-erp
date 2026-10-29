package com.fatoora.api.payment;

import com.fatoora.api.invoice.InvoiceFigures;
import com.fatoora.zatca.model.DocumentType;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What each issued invoice is worth after its credit and debit notes, and how much of it is still owed.
 * Pure function of the documents and payment totals, so it is unit tested without a database.
 */
public final class BalanceCalculator {

    public enum PaymentStatus { UNPAID, PARTIAL, PAID }

    /**
     * @param total    the invoice's own total including VAT
     * @param notes    issued debit notes minus issued credit notes raised against it
     * @param adjusted total + notes: what the customer owes in all
     * @param paid     payments recorded against it
     * @param due      adjusted − paid, never below zero
     * @param overpaid paid − adjusted when a credit note came after payment, otherwise zero
     */
    public record Balance(Long invoiceId, BigDecimal total, BigDecimal notes, BigDecimal adjusted, BigDecimal paid,
                          BigDecimal due, BigDecimal overpaid, PaymentStatus status) {}

    private BalanceCalculator() {}

    /** Balances keyed by invoice id, for every issued document of type INVOICE among {@code documents}. */
    public static Map<Long, Balance> compute(Collection<InvoiceFigures> documents, Map<Long, BigDecimal> paidByInvoice) {
        Map<Long, BigDecimal> notes = new HashMap<>();
        for (InvoiceFigures d : documents) {
            if (!d.counts() || d.originalInvoiceId() == null || d.type() == DocumentType.INVOICE) continue;
            BigDecimal signed = d.type() == DocumentType.CREDIT_NOTE ? d.totalInclVat().negate() : d.totalInclVat();
            notes.merge(d.originalInvoiceId(), signed, BigDecimal::add);
        }
        Map<Long, Balance> result = new LinkedHashMap<>();
        for (InvoiceFigures d : documents) {
            if (!d.counts() || d.type() != DocumentType.INVOICE) continue;
            result.put(d.id(), balance(d.id(), d.totalInclVat(), notes.getOrDefault(d.id(), BigDecimal.ZERO),
                    paidByInvoice.getOrDefault(d.id(), BigDecimal.ZERO)));
        }
        return result;
    }

    static Balance balance(Long id, BigDecimal total, BigDecimal notes, BigDecimal paid) {
        BigDecimal adjusted = total.add(notes).max(BigDecimal.ZERO);
        BigDecimal remaining = adjusted.subtract(paid);
        BigDecimal due = remaining.max(BigDecimal.ZERO);
        BigDecimal overpaid = remaining.negate().max(BigDecimal.ZERO);
        PaymentStatus status = due.signum() == 0 ? PaymentStatus.PAID
                : paid.signum() == 0 ? PaymentStatus.UNPAID : PaymentStatus.PARTIAL;
        return new Balance(id, total, notes, adjusted, paid, due, overpaid, status);
    }
}
