package com.fatoora.api.report;

import com.fatoora.api.invoice.InvoiceFigures;
import com.fatoora.api.invoice.InvoiceStatus;
import com.fatoora.api.payment.BalanceCalculator;
import com.fatoora.zatca.model.DocumentType;
import com.fatoora.zatca.model.InvoiceKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SalesReportTest {

    static InvoiceFigures inv(long id, Long customer, String issuedUtc, String total, String vat) {
        return new InvoiceFigures(id, "INV-" + id, InvoiceKind.SIMPLIFIED, DocumentType.INVOICE, InvoiceStatus.REPORTED,
                customer, null, new BigDecimal(total), new BigDecimal(vat), Instant.parse(issuedUtc), null);
    }

    static InvoiceFigures credit(long id, long original, String total) {
        return new InvoiceFigures(id, "CN-" + id, InvoiceKind.SIMPLIFIED, DocumentType.CREDIT_NOTE, InvoiceStatus.REPORTED,
                1L, original, new BigDecimal(total), BigDecimal.ZERO, Instant.parse("2026-10-20T08:00:00Z"), null);
    }

    final List<InvoiceFigures> docs = List.of(
            inv(1, 1L, "2026-09-10T06:00:00Z", "115.00", "15.00"),   // 09:00 KSA, September
            inv(2, 1L, "2026-10-05T15:00:00Z", "230.00", "30.00"),   // 18:00 KSA, October
            inv(3, null, "2026-10-05T21:30:00Z", "57.50", "7.50"),   // 00:30 KSA on 6 October, walk-in
            credit(4, 2, "23.00"));

    SalesReport.Result run(SalesReport.Filter f) {
        var balances = BalanceCalculator.compute(docs, Map.of(1L, new BigDecimal("115.00"), 2L, new BigDecimal("100.00")));
        return SalesReport.build(docs, balances, f, id -> "Customer " + id);
    }

    static SalesReport.Filter all() {
        return new SalesReport.Filter(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null, null, null, null, null);
    }

    static void eq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void totalsAccountForCreditNotesAndNetEqualsCollectedPlusDue() {
        var t = run(all()).totals();
        assertEquals(3, t.invoices(), "credit notes are not counted as invoices");
        eq("402.50", t.gross());
        eq("-23.00", t.notes());
        eq("379.50", t.net());
        eq("215.00", t.collected());
        eq("164.50", t.due());
        eq(t.net().toPlainString(), t.collected().add(t.due()));
    }

    @Test
    void monthWiseAndCustomerWise() {
        var r = run(all());
        assertEquals(List.of("2026-09", "2026-10"), r.months().stream().map(SalesReport.Row::key).toList());
        assertEquals("Oct 2026", r.months().get(1).label());
        eq("264.50", r.months().get(1).net());
        assertEquals(List.of("2026-09-10", "2026-10-05", "2026-10-06"), r.days().stream().map(SalesReport.Row::key).toList());
        assertEquals("Customer 1", r.customers().getFirst().label());
        assertTrue(r.customers().stream().anyMatch(c -> c.label().equals("Walk-in customers")));
    }

    @Test
    void dateRangeIsInSaudiTime() {
        // 2026-10-05T21:30Z is already 6 October in Riyadh.
        var r = run(new SalesReport.Filter(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 6), null, null, null, null, null));
        assertEquals(1, r.totals().invoices());
        eq("57.50", r.totals().net());
    }

    @Test
    void timeOfDayWindowsIncludingOneThatWrapsPastMidnight() {
        var morning = run(new SalesReport.Filter(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null, null, 5, 12, null));
        assertEquals(1, morning.totals().invoices());
        var night = run(new SalesReport.Filter(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null, null, 21, 5, null));
        assertEquals(1, night.totals().invoices());
        eq("57.50", night.totals().net());
    }

    @Test
    void customerFilter() {
        var r = run(new SalesReport.Filter(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), 1L, null, null, null, null));
        assertEquals(2, r.totals().invoices());
        var walkIn = run(new SalesReport.Filter(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null, null, null, null,
                id -> id == null));
        assertEquals(1, walkIn.totals().invoices());
    }
}
