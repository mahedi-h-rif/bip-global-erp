package com.fatoora.api.pdf;

import com.fatoora.api.organization.Organization;
import com.openhtmltopdf.bidi.support.ICUBidiReorderer;
import com.openhtmltopdf.bidi.support.ICUBidiSplitter;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.QuadCurve2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Company letterhead for quotations and agreements (not ZATCA documents): the logo and names in a header,
 * a faint logo watermark and a footer with address, registration and contact details on every page.
 */
@Component
public class LetterheadPdf {

    /** Renders {@code bodyHtml} (well-formed XHTML, already escaped) on the letterhead. */
    public byte[] render(Organization org, String title, String bodyHtml) {
        String color = org.getBrandColor() == null ? "#0B2A6F" : org.getBrandColor();
        if ("MODERN".equalsIgnoreCase(org.getLetterheadStyle()))
            return toPdf(modernHtml(org, title, bodyHtml, color));
        String html = """
                <!DOCTYPE html>
                <html><head><meta charset="UTF-8"/>
                <title>%s</title>
                <style>
                  @page {
                    size: A4; margin: 40mm 16mm 28mm 16mm;
                    @top-center { content: element(header); }
                    @bottom-center { content: element(footer); }
                  }
                  * { font-family: 'Plex'; }
                  body { font-size: 9.5pt; color: #1b2233; line-height: 1.45; }
                  table { width: 100%%; border-collapse: collapse; }
                  .rtl { direction: rtl; text-align: right; }
                  #header { position: running(header); }
                  #header td { vertical-align: middle; }
                  #header .en { font-size: 14pt; font-weight: 700; color: %s; }
                  #header .ar { font-size: 14pt; font-weight: 700; color: %s; }
                  #header .muted { font-size: 8pt; color: #5a6275; }
                  #header .rule { border-bottom: 3px solid %s; margin-top: 6px; }
                  #footer { position: running(footer); font-size: 7.5pt; color: #5a6275; border-top: 2px solid %s; padding-top: 5px; }
                  #footer td { vertical-align: top; }
                  .pageno::after { content: "Page " counter(page) " / " counter(pages); }
                  #watermark { position: fixed; top: 45mm; left: 0; width: 178mm; text-align: center; }
                  h1 { font-size: 15pt; color: %s; margin: 0 0 2px 0; }
                  h2 { font-size: 10.5pt; color: %s; margin: 12px 0 3px 0; }
                  p { margin: 0 0 6px 0; }
                  .muted { color: #5a6275; }
                  .brand { color: %s; }
                </style></head>
                <body>
                  <div id="header">
                    <table><tr>
                      %s
                      <td>
                        <div class="en">%s</div>
                        %s
                      </td>
                      <td class="rtl" style="white-space:nowrap; width:32%%"><div class="ar">%s</div><div class="muted">%s</div></td>
                    </tr></table>
                    <div class="rule"></div>
                  </div>
                  <div id="footer">
                    <table><tr>
                      <td>%s<br/>%s</td>
                      <td style="text-align:right; width:22%%"><span class="pageno"></span></td>
                    </tr></table>
                  </div>
                  %s
                  %s
                </body></html>
                """.formatted(
                InvoicePdfService.esc(title),
                color, color, color, color, color, color, color,
                logoCell(org),
                InvoicePdfService.esc(nz(org.getLegalNameEn())),
                contact(org),
                InvoicePdfService.esc(nz(org.getLegalNameAr())),
                InvoicePdfService.esc(nz(org.getCity())),
                // Arabic address and the numbers on separate lines, so right-to-left text and digits never mix.
                "<span dir=\"rtl\">" + InvoicePdfService.esc(joined("، ", org.getStreet(), org.getDistrict(), org.getCity())) + "</span>",
                InvoicePdfService.esc(joined(" · ",
                        org.getBuildingNumber() == null ? null : "Bldg " + org.getBuildingNumber(),
                        org.getPostalCode() == null ? null : "Postal " + org.getPostalCode(),
                        "VAT " + nz(org.getVatNumber()), "CR " + nz(org.getOtherId()))),
                watermark(org),
                bodyHtml);

        return toPdf(html);
    }

    private byte[] toPdf(String html) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();
            b.useUnicodeBidiSplitter(new ICUBidiSplitter.ICUBidiSplitterFactory());
            b.useUnicodeBidiReorderer(new ICUBidiReorderer());
            b.defaultTextDirection(BaseRendererBuilder.TextDirection.LTR);
            b.useFont(() -> font("IBMPlexSansArabic-Regular.ttf"), "Plex", 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            b.useFont(() -> font("IBMPlexSansArabic-Bold.ttf"), "Plex", 700, BaseRendererBuilder.FontStyle.NORMAL, true);
            b.withHtmlContent(html, null);
            b.toStream(out);
            b.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The MODERN letterhead: the same identity and registration details as the classic one, restyled with a
     * logo roundel, a brand-and-gold header rule, a brand footer bar, a faint logo watermark and a themed
     * landmark line backdrop (Qassim date palms, a mosque and towers) drawn in the brand colour.
     */
    private String modernHtml(Organization org, String title, String bodyHtml, String color) {
        String gold = "#a9833f";
        String addr = "<span dir=\"rtl\">" + InvoicePdfService.esc(joined("، ", org.getStreet(), org.getDistrict(), org.getCity())) + "</span>";
        String reg = InvoicePdfService.esc(joined(" · ",
                org.getBuildingNumber() == null ? null : "Bldg " + org.getBuildingNumber(),
                org.getPostalCode() == null ? null : "Postal " + org.getPostalCode(),
                "VAT " + nz(org.getVatNumber()), "CR " + nz(org.getOtherId())));
        String contactLine = InvoicePdfService.esc(joined(" · ", org.getEmail(), org.getWebsite(),
                org.getPhone() == null || org.getPhone().isBlank() ? null : "Mobile " + org.getPhone()));
        return """
                <!DOCTYPE html>
                <html><head><meta charset="UTF-8"/>
                <title>%s</title>
                <style>
                  @page {
                    size: A4; margin: 42mm 16mm 32mm 16mm;
                    @top-center { content: element(header); }
                    @bottom-center { content: element(footer); }
                  }
                  * { font-family: 'Plex'; }
                  body { font-size: 9.5pt; color: #1b2233; line-height: 1.45; }
                  table { width: 100%%; border-collapse: collapse; }
                  .rtl { direction: rtl; text-align: right; }
                  #header { position: running(header); }
                  #header td { vertical-align: middle; }
                  #header .en { font-size: 13.5pt; font-weight: 700; color: %s; }
                  #header .tag { font-size: 7.5pt; color: %s; letter-spacing: 1px; margin-top: 1px; }
                  #header .ar { font-size: 13.5pt; font-weight: 700; color: #1b2233; }
                  #header .muted { font-size: 8pt; color: #5a6275; }
                  #header .rule { border-bottom: 3px solid %s; margin-top: 6px; }
                  #header .rule2 { width: 96px; border-bottom: 3px solid %s; margin-top: -3px; }
                  #footer { position: running(footer); }
                  #footer .gold { height: 3px; background: %s; }
                  #footer .bar { background: %s; }
                  #footer .bar td { vertical-align: middle; color: #ffffff; font-size: 7.5pt; padding: 7px 10px; }
                  #footer .bar .r { text-align: right; }
                  .pageno::after { content: "Page " counter(page) " / " counter(pages); }
                  #watermark { position: fixed; top: 46mm; left: 0; width: 178mm; text-align: center; }
                  #landmark { position: fixed; bottom: 33mm; left: 0; width: 178mm; text-align: center; }
                  h1 { font-size: 15pt; color: %s; margin: 0 0 2px 0; }
                  h2 { font-size: 10.5pt; color: %s; margin: 12px 0 3px 0; }
                  p { margin: 0 0 6px 0; }
                  .muted { color: #5a6275; }
                  .brand { color: %s; }
                </style></head>
                <body>
                  <div id="header">
                    <table><tr>
                      %s
                      <td>
                        <div class="en">%s</div>
                        <div class="tag">%s</div>
                        %s
                      </td>
                      <td class="rtl" style="white-space:nowrap; width:34%%"><div class="ar">%s</div><div class="muted">%s</div></td>
                    </tr></table>
                    <div class="rule"></div><div class="rule2"></div>
                  </div>
                  <div id="footer">
                    <div class="gold"></div>
                    <table class="bar"><tr>
                      <td>%s<br/>%s</td>
                      <td class="r" style="width:42%%">%s<br/><span class="pageno"></span></td>
                    </tr></table>
                  </div>
                  %s
                  %s
                  %s
                </body></html>
                """.formatted(
                InvoicePdfService.esc(title),
                color, gold, color, gold, gold, color, color, color, color,
                logoCell(org),
                InvoicePdfService.esc(nz(org.getLegalNameEn())),
                InvoicePdfService.esc(nz(org.getCity()) + (org.getCountryCode() == null ? "" : " · KSA")),
                contact(org),
                InvoicePdfService.esc(nz(org.getLegalNameAr())),
                InvoicePdfService.esc(nz(org.getCity())),
                addr, reg, contactLine,
                watermark(org),
                landmark(color));
    }

    /** A faint landmark line illustration (date palms, a mosque and towers) in the brand colour, as a PNG. */
    private static String landmark(String brandHex) {
        try {
            Color brand;
            try { brand = Color.decode(brandHex); } catch (RuntimeException e) { brand = new Color(0x0B2A6F); }
            int W = 1400, H = 230;
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(brand);
            g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.11f));
            double base = 198;
            g.draw(new Line2D.Double(10, base, W - 10, base));   // ground
            palm(g, 150, base);
            mosque(g, 360, base);
            kingdomCentre(g, 690, base);
            faisaliah(g, 910, base);
            tower(g, 1110, base);
            palm(g, 1290, base);
            g.dispose();
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(img, "png", png);
            int shownW = 560;
            return "<div id=\"landmark\"><img src=\"data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(png.toByteArray())
                    + "\" style=\"width:" + shownW + "px;height:" + Math.round(shownW * (double) H / W) + "px\"/></div>";
        } catch (IOException e) {
            return "";
        }
    }

    private static void palm(Graphics2D g, double cx, double base) {
        g.draw(new Line2D.Double(cx, base, cx, base - 84));
        double t = base - 84;
        g.draw(new QuadCurve2D.Double(cx, t, cx - 22, t - 14, cx - 40, t - 8));
        g.draw(new QuadCurve2D.Double(cx, t, cx - 18, t - 30, cx - 30, t - 44));
        g.draw(new QuadCurve2D.Double(cx, t, cx + 22, t - 14, cx + 40, t - 8));
        g.draw(new QuadCurve2D.Double(cx, t, cx + 18, t - 30, cx + 30, t - 44));
        g.draw(new QuadCurve2D.Double(cx, t, cx, t - 34, cx, t - 50));
    }

    private static void mosque(Graphics2D g, double x, double base) {
        g.draw(new Line2D.Double(x, base, x, base - 56));
        g.draw(new Line2D.Double(x + 120, base, x + 120, base - 56));
        g.draw(new Line2D.Double(x, base - 56, x + 120, base - 56));
        g.draw(new Arc2D.Double(x + 24, base - 56 - 36, 72, 72, 0, 180, Arc2D.OPEN)); // dome
        g.draw(new Line2D.Double(x + 60, base - 92, x + 60, base - 104));             // finial
        g.draw(new Line2D.Double(x + 112, base, x + 112, base - 96));                 // minaret
        g.draw(new Arc2D.Double(x + 104, base - 96 - 8, 16, 16, 0, 180, Arc2D.OPEN));
        g.draw(new Line2D.Double(x + 112, base - 104, x + 112, base - 114));
    }

    private static void kingdomCentre(Graphics2D g, double x, double base) {
        g.draw(new Line2D.Double(x, base, x, base - 120));
        g.draw(new Line2D.Double(x + 92, base, x + 92, base - 120));
        g.draw(new QuadCurve2D.Double(x, base - 120, x + 46, base - 150, x + 92, base - 120)); // tapered top
        g.draw(new QuadCurve2D.Double(x + 24, base - 122, x + 46, base - 92, x + 68, base - 122)); // arch opening
    }

    private static void faisaliah(Graphics2D g, double x, double base) {
        g.draw(new Line2D.Double(x, base, x + 30, base - 118));
        g.draw(new Line2D.Double(x + 60, base, x + 30, base - 118));
        g.draw(new Ellipse2D.Double(x + 30 - 11, base - 118 - 22, 22, 22)); // sphere
        g.draw(new Line2D.Double(x + 30, base - 140, x + 30, base - 158));  // spire
    }

    private static void tower(Graphics2D g, double x, double base) {
        Rectangle2D.Double body = new Rectangle2D.Double(x, base - 104, 56, 104);
        g.draw(body);
        for (int gy = 20; gy < 104; gy += 20) g.draw(new Line2D.Double(x, base - gy, x + 56, base - gy));
        g.draw(new Line2D.Double(x + 28, base - 104, x + 28, base));
    }

    /** An image file (base64) as a data URI with its pixel size scaled to {@code height} px, or null. */
    public static String img(String base64, String contentType, int height, int maxWidth) {
        if (base64 == null) return null;
        int w = height, h = height;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
            if (img != null) {
                w = Math.round(height * (float) img.getWidth() / img.getHeight());
                if (w > maxWidth) {
                    h = Math.round(h * (float) maxWidth / w);
                    w = maxWidth;
                }
            }
        } catch (IOException ignored) {
            // Unknown size: square.
        }
        return "<img src=\"data:" + contentType + ";base64," + base64 + "\" style=\"width:" + w + "px;height:" + h + "px\"/>";
    }

    /** "Email · website" and "Mobile …" lines under the English name. */
    private static String contact(Organization org) {
        String web = joined(" · ", org.getEmail(), org.getWebsite());
        String mobile = org.getPhone() == null || org.getPhone().isBlank() ? "" : "Mobile " + org.getPhone();
        return (web.isEmpty() ? "" : "<div class=\"muted\">" + InvoicePdfService.esc(web) + "</div>")
                + (mobile.isEmpty() ? "" : "<div class=\"muted\">" + InvoicePdfService.esc(mobile) + "</div>");
    }

    private static String logoCell(Organization org) {
        String img = img(org.getLogo(), org.getLogoContentType(), 70, 170);
        return img == null ? "" : "<td style=\"width:1%; padding-right:10px\">" + img + "</td>";
    }

    /** The logo, faded to a light watermark behind the text. */
    private static String watermark(Organization org) {
        if (org.getLogo() == null) return "";
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(org.getLogo())));
            if (src == null) return "";
            int w = 900, h = Math.round(w * (float) src.getHeight() / src.getWidth());
            BufferedImage faded = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = faded.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.07f));
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(faded, "png", png);
            int shownW = 460, shownH = Math.round(shownW * (float) h / w);
            return "<div id=\"watermark\"><img src=\"data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(png.toByteArray())
                    + "\" style=\"width:" + shownW + "px;height:" + shownH + "px\"/></div>";
        } catch (IOException e) {
            return "";
        }
    }

    // --- building blocks for documents on the letterhead -----------------------------------------

    public static String esc(String s) {
        return InvoicePdfService.esc(s);
    }

    /** Escaped text with its line breaks kept. */
    public static String text(String s) {
        return s == null ? "" : esc(s.strip()).replace("\r\n", "\n").replace("\n", "<br/>");
    }

    public static String money(java.math.BigDecimal v) {
        return String.format(java.util.Locale.US, "%,.2f", v == null ? java.math.BigDecimal.ZERO : v);
    }

    /** A grey box with a bilingual heading and "label: value" rows; blank values are left out. */
    public static String box(String en, String ar, String[][] rows) {
        StringBuilder kv = new StringBuilder();
        for (String[] r : rows) {
            if (r[1] == null || r[1].isBlank()) continue;
            kv.append("<tr><td style=\"color:#5a6275; width:30%; padding:2px 8px\">").append(esc(r[0]))
                    .append("</td><td style=\"font-weight:700; padding:2px 8px\">").append(text(r[1])).append("</td></tr>");
        }
        return """
                <div style="border:1px solid #e3e7ef; background:#f6f8fb;">
                  <table><tr><td style="background:#e9edf4; padding:5px 8px; font-weight:700">%s</td>
                  <td class="rtl" style="background:#e9edf4; padding:5px 8px; font-weight:700">%s</td></tr></table>
                  <table>%s</table>
                </div>""".formatted(esc(en), esc(ar), kv);
    }

    /**
     * Signature section, signed and stamped by hand on the printed paper: a signature line for each party
     * and a stamp box for the company. Kept on one page.
     */
    public static String signatures(Organization org, String clientName) {
        return "<table style=\"margin-top:16px; page-break-inside:avoid\"><tr>"
                + party("padding-right:14px", "For the company · عن الشركة", org.displayName(), "Company stamp · ختم الشركة")
                + party("padding-left:14px", "For the client · عن العميل", clientName, null)
                + "</tr></table>";
    }

    /** One party's column; {@code stamp} null leaves out the stamp box. */
    private static String party(String padding, String heading, String name, String stamp) {
        String box = stamp == null ? ""
                : "<td style=\"width:120px; height:104px; border:1px dashed #9aa3b5; text-align:center; vertical-align:middle; font-size:7pt; color:#9aa3b5\">"
                + esc(stamp) + "</td>";
        return """
                <td style="width:50%%; vertical-align:top; %s">
                  <div style="font-weight:700">%s</div>
                  <div>%s</div>
                  <table style="margin-top:6px"><tr>
                    <td style="vertical-align:bottom; padding-right:10px">
                      <div style="height:80px"></div>
                      <div style="border-top:1px solid #1b2233; padding-top:3px">Signature · التوقيع</div>
                    </td>
                    %s
                  </tr></table>
                </td>""".formatted(padding, esc(heading), esc(nz(name)), box);
    }

    static String joined(String sep, String... parts) {
        return Stream.of(parts).filter(p -> p != null && !p.isBlank()).collect(Collectors.joining(sep));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static java.io.InputStream font(String name) {
        return LetterheadPdf.class.getResourceAsStream("/fonts/" + name);
    }
}
