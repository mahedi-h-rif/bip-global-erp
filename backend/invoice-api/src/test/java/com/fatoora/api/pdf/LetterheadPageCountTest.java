package com.fatoora.api.pdf;

import com.fatoora.api.organization.Organization;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Documents on the letterhead never end with a page that has only the header, footer and watermark. */
class LetterheadPageCountTest {

    private static final String PIXEL = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

    private static Organization org() throws Exception {
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
    void noBlankTrailingPage() throws Exception {
        LetterheadPdf pdf = new LetterheadPdf();
        Organization org = org();
        for (int n = 0; n <= 60; n++) {
            String body = "<h1>Quotation</h1>" + "<p>Line of body text to fill the page.</p>".repeat(n)
                    + LetterheadPdf.signatures(org, "Client");
            try (var doc = Loader.loadPDF(pdf.render(org, "t", body))) {
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
}
