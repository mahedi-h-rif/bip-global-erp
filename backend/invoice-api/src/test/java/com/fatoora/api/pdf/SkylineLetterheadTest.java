package com.fatoora.api.pdf;

import com.fatoora.api.organization.Organization;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The SKYLINE letterhead: company details on every page, and the body never ends on a page of its own. */
class SkylineLetterheadTest {

    private static final String PIXEL = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

    static Organization org() throws Exception {
        Organization o = new Organization();
        o.setLegalNameEn("Astha Gulf Trading Company");
        o.setLegalNameAr("شركة أستا جلف تريدينج");
        o.setTagline("Engineering Tomorrow,\nBuilding Trust Today");
        o.setVatNumber("314927049500003");
        o.setOtherId("7054876219");
        o.setStreet("فيصل بن مشعل بن سعود بن عبدالعزيز");
        o.setBuildingNumber("7845");
        o.setDistrict("حي المنتزه الغربي");
        o.setCity("بريدة");
        o.setPostalCode("52381");
        o.setEmail("info@asthacompany.com");
        o.setWebsite("asthacompany.com");
        o.setPhone("0550382797 · 0553080744");
        o.setLetterheadStyle("SKYLINE");
        Path real = Path.of(System.getProperty("user.home"), "Downloads", "astha logo.png");
        o.setLogo(Files.exists(real) ? Base64.getEncoder().encodeToString(Files.readAllBytes(real)) : PIXEL);
        o.setLogoContentType("image/png");
        return o;
    }

    @Test
    void blankPadIsOnePageWithTheDetails() throws Exception {
        Organization org = org();
        byte[] pdf = new LetterheadPdf().render(org, "Letterhead", "", true);
        save("skyline-pad.pdf", pdf);
        try (var doc = Loader.loadPDF(pdf)) {
            assertEquals(1, doc.getNumberOfPages());
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("info@asthacompany.com"));
            assertTrue(text.contains("314927049500003"));
            assertTrue(text.contains("Ref. No."));
        }
    }

    @Test
    void longDocumentKeepsHeaderAndFooterOnEveryPage() throws Exception {
        Organization org = org();
        String body = "<h1 style=\"text-align:center\">Service agreement</h1>"
                + "<h2>1. Scope · النطاق</h2><p>The contractor supplies and installs the ducting, grilles and controls described in the quotation, tests the system and hands it over in working order. يقوم المقاول بتوريد وتركيب مجاري الهواء.</p>".repeat(14)
                + LetterheadPdf.signatures(org, "Client Company");
        byte[] pdf = new LetterheadPdf().render(org, "Agreement", body);
        save("skyline-agreement.pdf", pdf);
        try (var doc = Loader.loadPDF(pdf)) {
            assertTrue(doc.getNumberOfPages() > 1);
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                PDFTextStripper s = new PDFTextStripper();
                s.setStartPage(p);
                s.setEndPage(p);
                String text = s.getText(doc);
                assertTrue(text.contains("asthacompany.com") && text.contains("7054876219"), "page " + p);
            }
        }
    }

    @Test
    void noBlankTrailingPage() throws Exception {
        LetterheadPdf pdf = new LetterheadPdf();
        Organization org = org();
        for (int n = 0; n <= 70; n++) {
            String body = "<h1>Quotation</h1>" + "<p>Line of body text to fill the page.</p>".repeat(n)
                    + LetterheadPdf.signatures(org, "Client");
            byte[] bytes = pdf.render(org, "t", body);
            try (var doc = Loader.loadPDF(bytes)) {
                PDFTextStripper s = new PDFTextStripper();
                int last = doc.getNumberOfPages();
                s.setStartPage(last);
                s.setEndPage(last);
                String text = s.getText(doc);
                assertTrue(text.contains("Signature") || text.contains("Line of body"),
                        "paragraphs=" + n + ": last of " + last + " pages has no content");
            }
        }
    }

    @Test
    void wordPadHasTheLetterhead() throws Exception {
        Organization org = org();
        byte[] blank = com.fatoora.api.pad.PadWord.build(org, null, null);
        byte[] letter = com.fatoora.api.pad.PadWord.build(org, "Completion letter",
                "<p>Dear Sir,</p><p>We confirm the ducting work is complete. نؤكد اكتمال الأعمال.</p>");
        save("skyline-pad.docx", blank);
        save("skyline-letter.docx", letter);
        try (var d = new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(letter));
             var ex = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(d)) {
            String text = ex.getText();
            assertTrue(text.contains("info@asthacompany.com"), "header");
            assertTrue(text.contains("314927049500003"), "footer");
            assertTrue(text.contains("Ref. No."));
            assertTrue(text.contains("We confirm the ducting work"));
            assertTrue(d.getHeaderList().get(0)._getHdrFtr().xmlText().contains("behindDoc=\"1\""), "backdrop");
        }
    }

    /** SKY_OUT=<dir> keeps the files to look at. */
    static void save(String name, byte[] bytes) throws Exception {
        String out = System.getenv("SKY_OUT");
        if (out == null) return;
        Files.createDirectories(Path.of(out));
        Files.write(Path.of(out, name), bytes);
    }
}
