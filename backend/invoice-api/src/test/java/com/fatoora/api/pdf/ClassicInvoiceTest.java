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

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The classic invoice: pattern header (English · logo · Arabic) and seller/buyer on aligned rows beside the QR. */
class ClassicInvoiceTest {

    @Test
    void classicInvoice() throws Exception {
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
        inv.setStatus(InvoiceStatus.REPORTED);
        inv.setNumber("INV-2026-000012");
        inv.setUuid(UUID.randomUUID());
        inv.setIssuedAt(Instant.parse("2026-11-12T10:59:17Z"));
        inv.setIcv(12L);
        inv.setInvoiceHash("q1w2e3r4t5y6u7i8o9p0==");
        inv.setQrCode("AQ1Bc3RoYSBHdWxmIFRyYWRpbmcCDzMxNDkyNzA0OTUwMDAwMwMUMjAyNi0xMS0wOFQwOTozMDowMFoEBjExNS4wMAUFMTUuMDA=");
        inv.setSignedXml("<Invoice/>");
        inv.setSellerSnapshot(json.writeValueAsString(new Party("شركة بي آي بي الخليج للخدمات اللوجستية", "312798399100003",
                "7043504286", "CRN", new Address("ابن مفلح", "3965", "6745", "حي الملز", "الرياض", "12836", "SA"))));
        inv.setBuyerSnapshot(json.writeValueAsString(new Party("Md Mahedi Hassen", null, null, null,
                new Address("Joar Sahara, Vatara", null, null, null, "Dhaka Feni", null, "BD"))));
        for (int i = 0; i < 2; i++) {
            InvoiceLineEntity l = new InvoiceLineEntity();
            l.setInvoice(inv);
            l.setPosition(i + 1);
            l.setName(i == 0 ? "Carton tape" : "Pallet wrap");
            l.setQuantity(BigDecimal.ONE);
            l.setUnitPrice(new BigDecimal("100.00"));
            inv.getLines().add(l);
        }
        byte[] pdf = new InvoicePdfService(repo, json).render(inv);
        Path out = Path.of("build", "pdf-preview");
        Files.createDirectories(out);
        Files.write(out.resolve("classic-invoice.pdf"), pdf);
        try (var doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("CR No: 7043504286") && text.contains("VAT No: 312798399100003"), "pattern header");
            assertTrue(text.contains("Seller") && text.contains("Buyer") && text.contains("Md Mahedi Hassen"), "parties");
            javax.imageio.ImageIO.write(new org.apache.pdfbox.rendering.PDFRenderer(doc).renderImageWithDPI(0, 90), "png",
                    out.resolve("classic-invoice.png").toFile());
        }
    }
}
