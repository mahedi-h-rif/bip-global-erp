package com.fatoora.api.pdf;

import com.fatoora.api.invoice.Invoice;
import com.fatoora.api.invoice.InvoiceLineEntity;
import com.fatoora.api.invoice.InvoiceStatus;
import com.fatoora.api.organization.Organization;
import com.fatoora.api.organization.OrganizationRepository;
import com.fatoora.zatca.model.Address;
import com.fatoora.zatca.model.DocumentType;
import com.fatoora.zatca.model.InvoiceKind;
import com.fatoora.zatca.model.Party;
import com.fatoora.zatca.model.VatCategory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An invoice issued without ZATCA: titled "Invoice", no VAT columns or totals, no QR and no XML attached. */
class WithoutZatcaInvoiceTest {

    @Test
    void plainInvoice() throws Exception {
        Organization org = LetterheadBankTest.org("CLASSIC");
        org.setId(1L);
        OrganizationRepository repo = (OrganizationRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{OrganizationRepository.class},
                (p, m, a) -> m.getName().equals("findById") ? Optional.of(org) : null);
        JsonMapper json = new JsonMapper();
        Invoice inv = new Invoice();
        inv.setOrganizationId(1L);
        inv.setKind(InvoiceKind.SIMPLIFIED);
        inv.setType(DocumentType.INVOICE);
        inv.setWithoutZatca(true);
        inv.setStatus(InvoiceStatus.ISSUED);
        inv.setNumber("INV-2026-000013");
        inv.setUuid(UUID.randomUUID());
        inv.setIssuedAt(Instant.parse("2026-11-15T10:00:00Z"));
        inv.setSellerSnapshot(json.writeValueAsString(new Party("شركة بي آي بي الخليج للخدمات اللوجستية", "312798399100003",
                "7043504286", "CRN", new Address("ابن مفلح", "3965", "6745", "حي الملز", "الرياض", "12836", "SA"))));
        InvoiceLineEntity l = new InvoiceLineEntity();
        l.setInvoice(inv);
        l.setPosition(1);
        l.setName("Carton tape");
        l.setQuantity(new BigDecimal("2"));
        l.setUnitCode("RO");
        l.setUnitPrice(new BigDecimal("100.00"));
        l.setVatCategory(VatCategory.O);
        l.setVatRate(BigDecimal.ZERO);
        l.setExemptionCode("VATEX-SA-OOS");
        l.setExemptionReason("Not subject to VAT");
        inv.getLines().add(l);

        byte[] pdf = new InvoicePdfService(repo, json).render(inv);
        Path out = Path.of("build", "pdf-preview");
        Files.createDirectories(out);
        Files.write(out.resolve("without-zatca-invoice.pdf"), pdf);
        try (var doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Invoice") && !text.contains("Tax Invoice"), "plain title");
            assertFalse(text.contains("VAT total") || text.contains("Total incl. VAT"), "no VAT totals");
            assertTrue(text.contains("200.00") && text.contains("2 Roll"), "total and full unit name");
            assertNull(doc.getDocumentCatalog().getNames(), "no XML attached");
            javax.imageio.ImageIO.write(new org.apache.pdfbox.rendering.PDFRenderer(doc).renderImageWithDPI(0, 90), "png",
                    out.resolve("without-zatca-invoice.png").toFile());
        }
    }
}
