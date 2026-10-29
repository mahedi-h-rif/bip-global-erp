package com.fatoora.api.payment;

import com.fatoora.api.invoice.InvoiceFigures;
import com.fatoora.api.invoice.InvoiceStatus;
import com.fatoora.api.payment.BalanceCalculator.Balance;
import com.fatoora.api.payment.BalanceCalculator.PaymentStatus;
import com.fatoora.zatca.model.DocumentType;
import com.fatoora.zatca.model.InvoiceKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BalanceCalculatorTest {

    static InvoiceFigures doc(long id, DocumentType type, InvoiceStatus status, Long original, String total) {
        return new InvoiceFigures(id, "N" + id, InvoiceKind.SIMPLIFIED, type, status, 7L, original,
                new BigDecimal(total), BigDecimal.ZERO, Instant.parse("2026-10-01T09:00:00Z"), null);
    }

    static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    void unpaidPartialAndPaid() {
        var docs = List.of(
                doc(1, DocumentType.INVOICE, InvoiceStatus.REPORTED, null, "115.00"),
                doc(2, DocumentType.INVOICE, InvoiceStatus.CLEARED, null, "230.00"),
                doc(3, DocumentType.INVOICE, InvoiceStatus.SIGNED, null, "50.00"));
        Map<Long, Balance> b = BalanceCalculator.compute(docs, Map.of(2L, d("100.00"), 3L, d("50.00")));

        assertEquals(PaymentStatus.UNPAID, b.get(1L).status());
        assertEquals(0, d("115.00").compareTo(b.get(1L).due()));
        assertEquals(PaymentStatus.PARTIAL, b.get(2L).status());
        assertEquals(0, d("130.00").compareTo(b.get(2L).due()));
        assertEquals(PaymentStatus.PAID, b.get(3L).status(), "signed simplified invoices are already owed");
        assertEquals(0, b.get(3L).due().signum());
    }

    @Test
    void draftsAndRejectedCarryNoBalance() {
        var docs = List.of(
                doc(1, DocumentType.INVOICE, InvoiceStatus.DRAFT, null, "100"),
                doc(2, DocumentType.INVOICE, InvoiceStatus.REJECTED, null, "100"));
        assertTrue(BalanceCalculator.compute(docs, Map.of()).isEmpty());
    }

    @Test
    void creditAndDebitNotesAdjustTheOriginalInvoice() {
        var docs = List.of(
                doc(1, DocumentType.INVOICE, InvoiceStatus.CLEARED, null, "1000.00"),
                doc(2, DocumentType.CREDIT_NOTE, InvoiceStatus.CLEARED, 1L, "200.00"),
                doc(3, DocumentType.DEBIT_NOTE, InvoiceStatus.CLEARED, 1L, "50.00"),
                // Notes that are drafts or rejected don't count yet.
                doc(4, DocumentType.CREDIT_NOTE, InvoiceStatus.DRAFT, 1L, "999.00"),
                doc(5, DocumentType.CREDIT_NOTE, InvoiceStatus.REJECTED, 1L, "999.00"));
        Balance b = BalanceCalculator.compute(docs, Map.of(1L, d("300.00"))).get(1L);

        assertEquals(1, BalanceCalculator.compute(docs, Map.of()).size(), "notes themselves have no balance");
        assertEquals(0, d("-150.00").compareTo(b.notes()));
        assertEquals(0, d("850.00").compareTo(b.adjusted()));
        assertEquals(0, d("550.00").compareTo(b.due()));
        assertEquals(PaymentStatus.PARTIAL, b.status());
    }

    @Test
    void creditNoteAfterFullPaymentShowsOverpayment() {
        var docs = List.of(
                doc(1, DocumentType.INVOICE, InvoiceStatus.REPORTED, null, "115.00"),
                doc(2, DocumentType.CREDIT_NOTE, InvoiceStatus.REPORTED, 1L, "15.00"));
        Balance b = BalanceCalculator.compute(docs, Map.of(1L, d("115.00"))).get(1L);
        assertEquals(PaymentStatus.PAID, b.status());
        assertEquals(0, b.due().signum());
        assertEquals(0, d("15.00").compareTo(b.overpaid()));
    }

    @Test
    void fullyCreditedInvoiceIsSettled() {
        var docs = List.of(
                doc(1, DocumentType.INVOICE, InvoiceStatus.CLEARED, null, "100.00"),
                doc(2, DocumentType.CREDIT_NOTE, InvoiceStatus.CLEARED, 1L, "100.00"));
        Balance b = BalanceCalculator.compute(docs, Map.of()).get(1L);
        assertEquals(0, b.adjusted().signum());
        assertEquals(PaymentStatus.PAID, b.status());
    }
}
