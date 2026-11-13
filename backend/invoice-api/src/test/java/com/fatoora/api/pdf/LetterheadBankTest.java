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
        try {
            var logo = Path.of("..", "..", "deploy", "assets", "bip-logo.png");
            if (Files.exists(logo)) {
                o.setLogo(java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(logo)));
                o.setLogoContentType("image/png");
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
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
                assertTrue(text.contains("312798399100003"), style + ": VAT in header");
                assertTrue(text.contains("7043504286"), style + ": CR in header");
                // The header pattern: English side and Arabic side each show the numbers once; never again in the footer.
                int expected = "SKYLINE".equals(style) ? 1 : 2;
                assertEquals(expected, text.split("312798399100003", -1).length - 1, style + ": VAT number only in the header");
                assertEquals(expected, text.split("7043504286", -1).length - 1, style + ": CR number only in the header");
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
    void ibanGroupedByFour() {
        assertEquals("SA56 8000 0450 6080 1014 1252", BankDetailsBlock.groupIban("SA5680000450608010141252"));
        assertEquals("SA56 8000 0450 6080 1014 1252", BankDetailsBlock.groupIban("sa56 8000 0450 6080 1014 1252"));
    }

    @Test
    void uploadedQrReplacesTheGeneratedOne() throws Exception {
        Organization o = org("CLASSIC");
        var img = new java.awt.image.BufferedImage(20, 20, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", png);
        String b64 = java.util.Base64.getEncoder().encodeToString(png.toByteArray());
        o.setBankQr(b64);
        o.setBankQrContentType("image/png");
        assertTrue(BankDetailsBlock.html(o).contains("data:image/png;base64," + b64), "the uploaded QR is printed");
    }

    @Test
    void noBlockWithoutIban() {
        Organization o = org("CLASSIC");
        o.setBankIban(null);
        assertEquals("", BankDetailsBlock.html(o));
    }
}
