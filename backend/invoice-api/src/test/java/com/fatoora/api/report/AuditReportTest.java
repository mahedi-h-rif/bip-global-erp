package com.fatoora.api.report;

import com.fatoora.api.account.AccountReport;
import com.fatoora.api.document.CompanyDocument;
import com.fatoora.api.invoice.InvoiceAuditRow;
import com.fatoora.api.invoice.InvoiceFigures;
import com.fatoora.api.invoice.InvoiceStatus;
import com.fatoora.api.organization.Organization;
import com.fatoora.api.payment.BalanceCalculator;
import com.fatoora.api.pdf.LetterheadPdf;
import com.fatoora.zatca.model.DocumentType;
import com.fatoora.zatca.model.InvoiceKind;
import com.fatoora.zatca.model.VatCategory;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuditReportTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 29);
    static final Instant NOW = Instant.parse("2026-10-29T09:00:00Z");

    static InvoiceAuditRow row(long id, InvoiceKind kind, DocumentType type, InvoiceStatus status, Long customer, String net, String vat,
                               String issued, String submitted) {
        return new InvoiceAuditRow(id, "INV-" + id, kind, type, status, customer, new BigDecimal(net), new BigDecimal(vat),
                issued == null ? null : Instant.parse(issued), submitted == null ? null : Instant.parse(submitted), Instant.parse("2026-07-01T00:00:00Z"));
    }

    static InvoiceFigures fig(InvoiceAuditRow r, Long original, LocalDate due) {
        return new InvoiceFigures(r.id(), r.number(), r.kind(), r.type(), r.status(), r.customerId(), original,
                r.totalExclVat().add(r.vatTotal()), r.vatTotal(), r.issuedAt(), due);
    }

    // 1: simplified, reported on time. 2: B2B to a buyer with no VAT number, mixed standard and zero-rated lines.
    // 3: export (zero-rated, buyer in the UAE). 4: credit note on 1. 5: simplified reported 30 h late.
    // 6: signed, never accepted. 7: rejected. 8: old draft.
    final InvoiceAuditRow r1 = row(1, InvoiceKind.SIMPLIFIED, DocumentType.INVOICE, InvoiceStatus.REPORTED, 1L, "1000.00", "150.00", "2026-07-05T08:00:00Z", "2026-07-05T09:00:00Z");
    final InvoiceAuditRow r2 = row(2, InvoiceKind.STANDARD, DocumentType.INVOICE, InvoiceStatus.CLEARED, 2L, "2000.00", "150.00", "2026-08-01T08:00:00Z", "2026-08-01T08:01:00Z");
    final InvoiceAuditRow r3 = row(3, InvoiceKind.STANDARD, DocumentType.INVOICE, InvoiceStatus.CLEARED, 3L, "500.00", "0.00", "2026-08-10T08:00:00Z", "2026-08-10T08:01:00Z");
    final InvoiceAuditRow r4 = row(4, InvoiceKind.SIMPLIFIED, DocumentType.CREDIT_NOTE, InvoiceStatus.REPORTED, 1L, "100.00", "15.00", "2026-09-01T08:00:00Z", "2026-09-01T08:05:00Z");
    final InvoiceAuditRow r5 = row(5, InvoiceKind.SIMPLIFIED, DocumentType.INVOICE, InvoiceStatus.REPORTED, null, "200.00", "30.00", "2026-09-02T08:00:00Z", "2026-09-03T14:00:00Z");
    final InvoiceAuditRow r6 = row(6, InvoiceKind.SIMPLIFIED, DocumentType.INVOICE, InvoiceStatus.SIGNED, null, "100.00", "15.00", "2026-09-20T08:00:00Z", null);
    final InvoiceAuditRow r7 = row(7, InvoiceKind.STANDARD, DocumentType.INVOICE, InvoiceStatus.REJECTED, 1L, "50.00", "7.50", "2026-09-21T08:00:00Z", null);
    final InvoiceAuditRow r8 = row(8, InvoiceKind.SIMPLIFIED, DocumentType.INVOICE, InvoiceStatus.DRAFT, null, "10.00", "1.50", null, null);

    AuditReport.Inputs inputs(List<AuditReport.ChainLink> chain, Map<String, Long> actions, AccountReport.Category... categories) {
        var issued = List.of(fig(r1, null, null), fig(r2, null, LocalDate.of(2026, 6, 1)), fig(r3, null, null), fig(r4, 1L, null),
                fig(r5, null, null), fig(r6, null, null));
        var balances = BalanceCalculator.compute(issued, Map.of(1L, new BigDecimal("1035.00"), 3L, new BigDecimal("500.00")));
        return new AuditReport.Inputs(
                new AuditReport.Company("Astha Gulf Trading", "شركة أستا جلف", "314927049500003", null, "7845, Buraydah", true, List.of("CR number")),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), TODAY, NOW, "Test Admin",
                List.of(r1, r2, r3, r4, r5, r6, r7, r8),
                List.of(new AuditReport.LineNet(2L, VatCategory.S, new BigDecimal("1000")), new AuditReport.LineNet(2L, VatCategory.Z, new BigDecimal("1000")),
                        new AuditReport.LineNet(3L, VatCategory.Z, new BigDecimal("500"))),
                chain, issued, balances,
                Map.of(1L, new AuditReport.Party(1L, "Walk-in Ali", false, "SA"), 2L, new AuditReport.Party(2L, "Noor Contracting", false, "SA"),
                        3L, new AuditReport.Party(3L, "Dubai Buyer", true, "AE")),
                new AuditReport.Cash(new BigDecimal("3000"), new BigDecimal("2500"), new BigDecimal("1000"), List.of(categories)),
                List.of(new AuditReport.FileOnRecord(CompanyDocument.Type.CR, null, "1131234567", LocalDate.of(2026, 10, 1)),
                        new AuditReport.FileOnRecord(CompanyDocument.Type.IQAMA, 10L, "2400000000", LocalDate.of(2026, 11, 20))),
                List.of(new AuditReport.Staff(10L, "Karim", true), new AuditReport.Staff(11L, "Rafiq", true), new AuditReport.Staff(12L, "Old", false)),
                new AuditReport.Activity(actions, 2));
    }

    AuditReport.Result run() {
        return AuditReport.build(inputs(
                List.of(new AuditReport.ChainLink(1L, 1L), new AuditReport.ChainLink(1L, 2L), new AuditReport.ChainLink(1L, 4L)),
                Map.of("PAYMENT_DELETED", 2L, "INVOICE_ISSUED", 7L)));
    }

    static void eq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    static boolean has(AuditReport.Result r, String code) {
        return r.findings().stream().anyMatch(f -> f.code().equals(code));
    }

    @Test
    void vatReturnSplitsByCategoryExportsAndCreditNotes() {
        var v = run().vat();
        var box = v.boxes();
        eq("2300.00", box.get(0).amount());   // 1000 (inv 1) + 1000 standard part of inv 2 + 200 (inv 5) + 100 (inv 6)
        eq("-100.00", box.get(0).adjustment()); // credit note 4
        eq("1000.00", box.get(1).amount());   // zero-rated part of inv 2, domestic buyer
        eq("500.00", box.get(2).amount());    // inv 3 to the UAE: export
        eq("0", box.get(3).amount());
        eq("330.00", v.total().vat());        // 150 + 150 + 0 + 30 + 15 − 15; the rejected invoice does not count
        assertEquals(LocalDate.of(2026, 10, 31), v.filingDeadline());
    }

    @Test
    void eInvoicingChecks() {
        var r = run();
        var e = r.einvoicing();
        assertEquals(1, e.lateReports());
        assertEquals(1, e.overdueUnreported());
        assertEquals(1, e.rejected());
        assertEquals(1, e.standardWithoutBuyerId(), "invoice 2's buyer has no VAT number; the rejected one is not counted");
        assertEquals(1, e.chainProblems(), "ICV 3 is missing");
        assertEquals(1, e.oldDrafts());
        for (String code : List.of("LATE_REPORTING", "UNREPORTED_OVERDUE", "REJECTED", "CHAIN_GAP", "STANDARD_NO_BUYER_ID", "OLD_DRAFTS"))
            assertTrue(has(r, code), code);
    }

    @Test
    void receivablesAgeFromDueDate() {
        var rec = run().receivables();
        // inv 2 (due 1 June, 150 days) 2150 · inv 5 (58 days) 230 · inv 6 (39 days) 115 · inv 1 is paid after its credit note
        eq("2495.00", rec.totalDue());
        eq("2150.00", rec.aging().get(4).amount());
        eq("345.00", rec.aging().get(2).amount());
        assertEquals("Noor Contracting", rec.topDebtors().getFirst().name());
    }

    @Test
    void documentsWorkforceCashAndControls() {
        var r = run();
        // Document tracking is not part of the system: no document list and no document or Iqama findings.
        assertTrue(r.documents().isEmpty());
        assertFalse(has(r, "DOC_EXPIRED") || has(r, "DOC_MISSING") || has(r, "IQAMA_EXPIRING") || has(r, "IQAMA_MISSING"));
        assertEquals(2, r.workforce().active());
        eq("-500", r.cash().retained());
        assertTrue(has(r, "NEGATIVE_CASH"));
        assertTrue(has(r, "DELETIONS"));
        assertTrue(has(r, "COMPANY_DETAILS"));
        assertEquals(AuditReport.Severity.HIGH, r.findings().getFirst().severity(), "worst first");
        assertTrue(r.score().score() < 60);
        assertEquals("Weak", r.score().rating());
    }

    @Test
    void cleanBooksScoreWell() {
        var s = AuditReport.score(List.of());
        assertEquals(100, s.score());
        assertEquals("Good", s.rating());
    }

    @Test
    void findingTextsHaveNoUnfilledPlaceholders() {
        for (var f : run().findings())
            for (String t : List.of(f.text(), f.textAr(), f.action(), f.actionAr()))
                assertFalse(AuditReport.fill(t, f.vars()).matches(".*\\{\\w+}.*"), f.code() + ": " + t);
    }

    @Test
    void rendersBilingualPdf() throws Exception {
        Organization o = new Organization();
        o.setLegalNameEn("Astha Gulf Trading Company");
        o.setLegalNameAr("شركة أستا جلف تريدينج");
        o.setVatNumber("314927049500003");
        o.setCity("بريدة");
        byte[] pdf = new LetterheadPdf().render(o, "Business audit", AuditReportPdf.html(run()));
        // AUDIT_PDF_OUT=<file> keeps the PDF to look at.
        String keep = System.getenv("AUDIT_PDF_OUT");
        if (keep != null) java.nio.file.Files.write(java.nio.file.Path.of(keep), pdf);
        try (var doc = Loader.loadPDF(pdf)) {
            assertTrue(doc.getNumberOfPages() >= 2);
        }
    }
}
