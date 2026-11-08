package com.fatoora.api.pdf;

import com.fatoora.api.organization.Organization;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Base64;

/**
 * The SKYLINE letterhead: a tricolour stripe, the Arabic name and slogan, the logo in the middle, contacts with
 * icons, an ornamented rule, a faint logo watermark, a faded line-art skyline and a light footer band with the
 * address and registration numbers. Used for pads, quotations, agreements and reports, and for invoices.
 *
 * <p>Everything that never changes (stripes, rule, ornament, skyline, footer band) is one A4 backdrop image painted
 * as the page background; the company's own details are live text laid over it, in a running header and footer
 * that sit in the page margins, so body content can never run into them.
 */
public final class SkylineLetterhead {

    public static final String NAVY = "#163A5C", BLUE = "#0071B5", RED = "#ED1B24";

    /** Page margins (mm). The body stays between the header rule and the skyline's dense lower half. */
    static final int TOP = 41, BOTTOM = 46, SIDE = 15;

    private static final String BACKDROP = dataUri("page-skyline.png");
    private static final String PHONE = dataUri("icon-phone.png");
    private static final String MAIL = dataUri("icon-mail.png");
    private static final String WEB = dataUri("icon-web.png");
    private static final String PIN = dataUri("icon-pin.png");

    private SkylineLetterhead() {
    }

    /** The @page rule and chrome styles; append the document's own styles after this. */
    public static String css() {
        return """
                @page {
                  size: A4; margin: %dmm %dmm %dmm %dmm;
                  background-image: url('%s'); background-size: 210mm 297mm; background-repeat: no-repeat;
                  background-position: 0 0;
                  @top-center { content: element(header); vertical-align: top; }
                  @bottom-center { content: element(footer); vertical-align: top; }
                }
                #sky-header { position: running(header); height: %dmm; }
                #sky-header table, #sky-footer table { width: 100%%; border-collapse: collapse; }
                #sky-header td { vertical-align: top; padding: 0; }
                #sky-header .name { font-size: 15pt; font-weight: 700; color: %s; line-height: 1.25; }
                #sky-header .tag { font-size: 9.5pt; font-weight: 700; color: %s; line-height: 1.3; margin-top: 3px; }
                #sky-header .contact td { font-size: 8.6pt; font-weight: 700; color: %s; padding: 0 0 7px 0; text-align: right; white-space: nowrap; }
                #sky-header .contact td.ic { width: 16px; padding-left: 6px; }
                #sky-footer { position: running(footer); height: %dmm; }
                #sky-footer td { vertical-align: top; font-size: 8.4pt; color: #3c4a5e; line-height: 1.55; padding: 0; }
                #sky-footer .ids { border-left: 1px solid #9fb6cc; padding-left: 9px; text-align: right; white-space: nowrap; }
                #sky-footer .ids b { color: %s; }
                #sky-footer .pageno { font-size: 7pt; color: #7a8799; }
                #sky-footer .pageno::after { content: "Page " counter(page) " / " counter(pages); }
                #sky-watermark { position: fixed; left: 0; width: %dmm; text-align: center; }
                .sky-ref { width: 100%%; border-collapse: collapse; margin: 0 0 14px 0; }
                .sky-ref td { font-size: 8.8pt; color: #4b5668; padding: 0 4px; vertical-align: bottom; white-space: nowrap; }
                .sky-ref td.blank { border-bottom: 1px dotted #9aa3b5; width: 30%%; }
                """.formatted(TOP, SIDE, BOTTOM, SIDE, BACKDROP, TOP, NAVY, BLUE, NAVY, BOTTOM, NAVY, 210 - 2 * SIDE);
    }

    /** The running header and footer and the watermark: put right after {@code <body>}. */
    public static String chrome(Organization org) {
        String ar = nz(org.getLegalNameAr()).isBlank() ? null : org.getLegalNameAr();
        String name = ar != null
                ? "<div class=\"name\" dir=\"rtl\" style=\"text-align:left\">" + esc(ar) + "</div>"
                : "<div class=\"name\">" + esc(nz(org.getLegalNameEn())) + "</div>";
        String tag = nz(org.getTagline()).isBlank() ? "" : "<div class=\"tag\">" + LetterheadPdf.text(org.getTagline()) + "</div>";
        // When the Arabic name is the headline, the English one goes under it in place of a missing slogan.
        if (tag.isEmpty() && ar != null && !nz(org.getLegalNameEn()).isBlank())
            tag = "<div class=\"tag\">" + esc(org.getLegalNameEn()) + "</div>";

        StringBuilder contacts = new StringBuilder();
        contact(contacts, org.getPhone(), PHONE, 13.5, 11);
        contact(contacts, org.getEmail(), MAIL, 13.5, 9.75);
        contact(contacts, org.getWebsite(), WEB, 13.5, 11);

        String logo = LetterheadPdf.img(org.getLogo(), org.getLogoContentType(), 92, 190);
        String city = nz(org.getCity());
        boolean arabicCity = city.codePoints().anyMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.ARABIC);
        String numbers = LetterheadPdf.joined(" · ",
                org.getBuildingNumber() == null ? null : "Bldg " + org.getBuildingNumber(),
                org.getPostalCode() == null ? null : "Postal Code " + org.getPostalCode(),
                city.isBlank() || arabicCity ? "KSA" : city + ", KSA");
        String address = LetterheadPdf.joined("، ", org.getStreet(), org.getDistrict(), org.getCity());

        return """
                <div id="sky-header">
                  <table style="margin-top:7.5mm"><tr>
                    <td style="width:38%%; padding-top:2mm">%s%s</td>
                    <td style="width:24%%; text-align:center">%s</td>
                    <td style="width:38%%; padding-top:2.5mm"><table class="contact">%s</table></td>
                  </tr></table>
                </div>
                <div id="sky-footer">
                  <table style="margin-top:%smm"><tr>
                    <td style="width:7mm; padding-top:1px"><img src="%s" style="width:3.6mm;height:4.6mm"/></td>
                    <td><span dir="rtl">%s</span><br/>%s</td>
                    <td class="ids" style="width:58mm"><b>VAT</b> %s<br/><b>CR</b> %s<br/><span class="pageno"></span></td>
                  </tr></table>
                </div>
                %s
                """.formatted(
                name, tag,
                logo == null ? "" : logo,
                contacts,
                // The footer band starts 276.5 mm down the page; the footer margin box starts at 297 - BOTTOM.
                String.valueOf(276.5 - (297 - BOTTOM) + 4.5),
                PIN, esc(address), esc(numbers),
                esc(nz(org.getVatNumber())), esc(nz(org.getOtherId())),
                watermark(org));
    }

    /** "Ref. No. ____ الرقم    Date ____ التاريخ" to fill in by hand, for letters on the pad. */
    public static String refLine() {
        return """
                <table class="sky-ref"><tr>
                  <td>Ref. No.</td><td class="blank"></td><td dir="rtl">الرقم</td>
                  <td style="width:8%"></td>
                  <td>Date</td><td class="blank"></td><td dir="rtl">التاريخ</td>
                </tr></table>""";
    }

    private static void contact(StringBuilder out, String value, String icon, double w, double h) {
        if (value == null || value.isBlank()) return;
        out.append("<tr><td>").append(esc(value.strip())).append("</td><td class=\"ic\"><img src=\"").append(icon)
                .append("\" style=\"width:").append(w).append("px;height:").append(h).append("px\"/></td></tr>");
    }

    /** The logo, very faint, centred on the page (about 90 mm wide, centre 148 mm from the top). */
    private static String watermark(Organization org) {
        Watermark wm = watermarkImage(org);
        if (wm == null) return "";
        // Fixed boxes are placed in the page's content area, which starts TOP mm down.
        double top = Math.max(0, 148 - TOP - wm.heightMm() / 2);
        return "<div id=\"sky-watermark\" style=\"top:" + fmt(top) + "mm\"><img src=\"data:image/png;base64,"
                + Base64.getEncoder().encodeToString(wm.png())
                + "\" style=\"width:" + fmt(wm.widthMm()) + "mm;height:" + fmt(wm.heightMm()) + "mm\"/></div>";
    }

    /** The faded logo PNG and the size it is shown at, centred 148 mm down the page. */
    public record Watermark(byte[] png, double widthMm, double heightMm) {
    }

    /** The logo faded to a faint watermark, or null when there is no readable logo. */
    public static Watermark watermarkImage(Organization org) {
        if (org.getLogo() == null) return null;
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(org.getLogo())));
            if (src == null) return null;
            int w = 900, h = Math.round(w * (float) src.getHeight() / src.getWidth());
            BufferedImage faded = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = faded.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.06f));
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(faded, "png", png);
            double shownW = 90, shownH = shownW * h / w;
            if (shownH > 110) {
                shownW = shownW * 110 / shownH;
                shownH = 110;
            }
            return new Watermark(png.toByteArray(), shownW, shownH);
        } catch (IOException e) {
            return null;
        }
    }

    private static String fmt(double mm) {
        return String.format(java.util.Locale.US, "%.1f", mm);
    }

    private static String dataUri(String name) {
        try (InputStream in = SkylineLetterhead.class.getResourceAsStream("/letterhead/" + name)) {
            if (in == null) throw new IllegalStateException("Missing letterhead asset " + name);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String esc(String s) {
        return InvoicePdfService.esc(s);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
