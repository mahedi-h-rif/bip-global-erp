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
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Invoices on the SKYLINE letterhead keep every ZATCA element and repeat the letterhead on each page. */
class SkylineInvoiceTest {

    @Test
    void invoiceOnTheSkylineLetterhead() throws Exception {
        Organization org = SkylineLetterheadTest.org();
        org.setId(1L);
        OrganizationRepository repo = (OrganizationRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{OrganizationRepository.class},
                (p, m, a) -> m.getName().equals("findById") ? Optional.of(org) : null);
        JsonMapper json = new JsonMapper();
        InvoicePdfService service = new InvoicePdfService(repo, json);

        for (int lines : new int[]{3, 28}) {
            Invoice inv = invoice(json, lines);
            byte[] pdf = service.render(inv);
            SkylineLetterheadTest.save("skyline-invoice-" + lines + ".pdf", pdf);
            try (var doc = Loader.loadPDF(pdf)) {
                for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                    PDFTextStripper s = new PDFTextStripper();
                    s.setStartPage(p);
                    s.setEndPage(p);
                    String text = s.getText(doc);
                    assertTrue(text.contains("asthacompany.com") && text.contains("7054876219"), "page " + p);
                }
                String all = new PDFTextStripper().getText(doc);
                assertTrue(all.contains("Tax Invoice") && all.contains("INV-0042") && !all.contains("Invoice hash"));
            }
        }
    }

    private static Invoice invoice(JsonMapper json, int count) {
        Invoice inv = new Invoice();
        inv.setOrganizationId(1L);
        inv.setKind(InvoiceKind.STANDARD);
        inv.setType(DocumentType.INVOICE);
        inv.setStatus(InvoiceStatus.CLEARED);
        inv.setNumber("INV-0042");
        inv.setUuid(UUID.randomUUID());
        inv.setIssuedAt(Instant.parse("2026-11-08T09:30:00Z"));
        inv.setIcv(42L);
        inv.setInvoiceHash("q1w2e3r4t5y6u7i8o9p0==");
        inv.setQrCode("AQ1Bc3RoYSBHdWxmIFRyYWRpbmcCDzMxNDkyNzA0OTUwMDAwMwMUMjAyNi0xMS0wOFQwOTozMDowMFoEBjExNS4wMAUFMTUuMDA=");
        inv.setSignedXml("<Invoice/>");
        Address a = new Address("فيصل بن مشعل", "7845", null, "حي المنتزه الغربي", "بريدة", "52381", "SA");
        inv.setSellerSnapshot(json.writeValueAsString(new Party("شركة أستا جلف تريدينج", "314927049500003", "7054876219", "CRN", a)));
        inv.setBuyerSnapshot(json.writeValueAsString(new Party("Client Trading Est.", "300000000000003", null, null,
                new Address("King Fahd Road", "1234", null, "Al Olaya", "Riyadh", "12211", "SA"))));
        for (int i = 0; i < count; i++) {
            InvoiceLineEntity l = new InvoiceLineEntity();
            l.setInvoice(inv);
            l.setPosition(i + 1);
            l.setName(i % 2 == 0 ? "Supply and install ducting work, galvanized sheet" : "توريد وتركيب مكيف سبليت");
            l.setQuantity(new BigDecimal("2"));
            l.setUnitPrice(new BigDecimal("500.00"));
            inv.getLines().add(l);
        }
        return inv;
    }
}
