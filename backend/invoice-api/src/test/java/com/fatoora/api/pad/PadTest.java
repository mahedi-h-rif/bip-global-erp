package com.fatoora.api.pad;

import com.fatoora.api.organization.Organization;
import com.fatoora.api.pdf.LetterheadPdf;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class PadTest {

    private static final String PIXEL = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

    static final String LETTER = """
            <p style="text-align: right">Date: 28/10/2026</p>
            <p>To,<br>Eng. Eyasin<br>Buraidah</p>
            <h2>Subject: <u>Ducting work completion</u></h2>
            <p>Dear Sir, we are pleased to confirm that the <b>ducting work</b> is <i>complete</i> and
            <font color="#c0392b" size="5">tested</font>.</p>
            <ul><li>Supply duct 40 m</li><li>Return duct 25 m</li></ul>
            <ol><li>First</li><li>Second</li></ol>
            <table><tbody><tr><th>Item</th><th>Qty</th></tr><tr><td>Grille</td><td>12</td></tr></tbody></table>
            <p dir="rtl" style="text-align:right">شكراً لتعاملكم معنا</p>
            <hr>
            <p>Regards,</p>
            """;

    static Organization org() throws Exception {
        Organization o = new Organization();
        o.setLegalNameEn("Astha Gulf Trading Company");
        o.setLegalNameAr("شركة أستا جلف تريدينج");
        o.setVatNumber("314927049500003");
        o.setOtherId("7054876219");
        o.setStreet("فيصل بن مشعل بن سعود بن عبدالعزيز");
        o.setBuildingNumber("7845");
        o.setDistrict("حي المنتزه الغربي");
        o.setCity("بريدة");
        o.setPostalCode("52381");
        o.setEmail("info@asthacompany.com");
        o.setWebsite("asthacompany.com");
        o.setPhone("0550382797 - 0553080744");
        Path real = Path.of(System.getProperty("user.home"), "Downloads", "astha logo.png");
        o.setLogo(Files.exists(real) ? Base64.getEncoder().encodeToString(Files.readAllBytes(real)) : PIXEL);
        o.setLogoContentType("image/png");
        return o;
    }

    @Test
    void cleanRemovesScriptsLinksAndOutsideImages() {
        String html = PadHtml.clean("<p onclick=\"x()\" style=\"color:red;position:fixed\">Hi<script>alert(1)</script>"
                + "<a href=\"http://evil\">link</a><img src=\"http://evil/x.png\"><img src=\"data:image/png;base64,AAAA\"></p>");
        assertFalse(html.contains("script"));
        assertFalse(html.contains("onclick"));
        assertFalse(html.contains("href"));
        assertFalse(html.contains("http://evil/x.png"));
        assertFalse(html.contains("position"));
        assertTrue(html.contains("color:red"));
        assertTrue(html.contains("data:image/png"));
        assertTrue(html.contains("link"));
    }

    @Test
    void xhtmlIsWellFormed() {
        String x = PadHtml.xhtml("<p>A&nbsp;B<br>C</p><hr><font size=\"6\" color=\"#ff0000\">big</font>");
        assertTrue(x.contains("<br />") || x.contains("<br/>"));
        assertTrue(x.contains("font-size:18pt"));
        assertFalse(x.contains("&nbsp;"));
    }

    @Test
    void emptyDetection() {
        assertTrue(PadHtml.isEmpty("<p><br></p>"));
        assertFalse(PadHtml.isEmpty("<p>x</p>"));
    }

    @Test
    void rendersPdfAndWord() throws Exception {
        Organization org = org();
        byte[] pdf = new LetterheadPdf().render(org, "Letter",
                "<h1 style=\"text-align:center\">Completion letter</h1><div>" + PadHtml.xhtml(LETTER) + "</div>");
        try (var doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Ducting work completion"));
            assertTrue(text.contains("Grille"));
        }
        byte[] blank = new LetterheadPdf().render(org, "Letterhead", "");
        try (var doc = Loader.loadPDF(blank)) {
            assertEquals(1, doc.getNumberOfPages());
        }

        byte[] word = PadWord.build(org, "Completion letter", LETTER);
        try (XWPFDocument d = new XWPFDocument(new ByteArrayInputStream(word));
             XWPFWordExtractor ex = new XWPFWordExtractor(d)) {
            String text = ex.getText();
            assertTrue(text.contains("Astha Gulf Trading Company"), "header");
            assertTrue(text.contains("VAT 314927049500003"), "footer");
            assertTrue(text.contains("Supply duct 40 m"));
            assertTrue(text.contains("Grille"));
        }
        byte[] blankWord = PadWord.build(org, null, null);
        try (XWPFDocument d = new XWPFDocument(new ByteArrayInputStream(blankWord))) {
            assertFalse(d.getHeaderList().isEmpty());
        }

        // PAD_OUT=<dir> keeps the files to look at.
        String out = System.getenv("PAD_OUT");
        if (out != null) {
            Files.createDirectories(Path.of(out));
            Files.write(Path.of(out, "letter.pdf"), pdf);
            Files.write(Path.of(out, "letterhead-pad.pdf"), blank);
            Files.write(Path.of(out, "letter.docx"), word);
            Files.write(Path.of(out, "letterhead-pad.docx"), blankWord);
        }
    }
}
