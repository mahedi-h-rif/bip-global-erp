package com.fatoora.api.pdf;

import com.fatoora.api.organization.Organization;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CR and VAT print in the header (not the footer) and the bank block with its QR prints on the body. */
class LetterheadBankTest {

    static Organization org(String style) {
        Organization o = new Organization();
        o.setLegalNameEn("BIP Gulf Logistics Services Company");
        o.setLegalNameAr("شركة بي آي بي الخليج للخدمات اللوجستية");
        o.setVatNumber("312798399100003");
        o.setOtherId("7043504286");
        o.setCity("الرياض");
        o.setStreet("ابن مفلح");
        o.setDistrict("حي الملز");
        o.setBuildingNumber("3965");
        o.setPostalCode("12836");
        o.setPhone("+966504628404");
        o.setLetterheadStyle(style);
        o.setBankAccountName("BIP GULF LOGISTICS SERVICES COMPANY");
        o.setBankAccountNo("45000-001-0006080141252");
        o.setBankIban("SA56 8000 0450 6080 1014 1252");
        o.setBankName("AL RAJHI BANK");
        return o;
    }

    @Test
    void headerHoldsRegistrationAndBodyHoldsBankBlock() throws Exception {
        Path out = Path.of("build", "pdf-preview");
        Files.createDirectories(out);
        for (String style : new String[]{"CLASSIC", "MODERN", "SKYLINE"}) {
            Organization o = org(style);
            byte[] pdf = new LetterheadPdf().render(o, "Quotation Q-1", "<h1>Quotation</h1><p>Body text.</p>" + BankDetailsBlock.html(o));
            Files.write(out.resolve(style + ".pdf"), pdf);
            try (var doc = Loader.loadPDF(pdf)) {
                String text = new PDFTextStripper().getText(doc);
                assertTrue(text.contains("VAT No. 312798399100003"), style + ": VAT in header");
                assertTrue(text.contains("CR No. 7043504286"), style + ": CR in header");
                assertEquals(1, text.split("312798399100003", -1).length - 1 - (text.contains("الرقم الضريبي") ? 1 : 0),
                        style + ": VAT printed once in English (not again in the footer)");
                assertTrue(text.contains("SA56 8000 0450 6080 1014 1252"), style + ": IBAN in bank block");
                assertTrue(text.contains("AL RAJHI BANK"), style + ": bank name");
                var r = new org.apache.pdfbox.rendering.PDFRenderer(doc).renderImageWithDPI(0, 80);
                javax.imageio.ImageIO.write(r, "png", out.resolve(style + ".png").toFile());
            }
        }
    }

    @Test
    void qrHoldsAllDetails() {
        String p = BankDetailsBlock.qrPayload(org("CLASSIC"));
        assertTrue(p.contains("Iban: SA56 8000 0450 6080 1014 1252"));
        assertTrue(p.contains("Account number: 45000-001-0006080141252"));
        assertTrue(p.contains("Bank name: AL RAJHI BANK"));
    }

    @Test
    void noBlockWithoutIban() {
        Organization o = org("CLASSIC");
        o.setBankIban(null);
        assertEquals("", BankDetailsBlock.html(o));
    }
}
