package com.fatoora.api.pdf;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.invoice.Invoice;
import com.fatoora.api.invoice.InvoiceLineEntity;
import com.fatoora.api.organization.Organization;
import com.fatoora.api.organization.OrganizationRepository;
import com.fatoora.zatca.model.Address;
import com.fatoora.zatca.model.DocumentType;
import com.fatoora.zatca.model.InvoiceKind;
import com.fatoora.zatca.model.InvoiceTotals;
import com.fatoora.zatca.model.Party;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.openhtmltopdf.bidi.support.ICUBidiReorderer;
import com.openhtmltopdf.bidi.support.ICUBidiSplitter;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.GregorianCalendar;
import java.util.Map;

/**
 * Bilingual (Arabic / English) invoice PDF in PDF/A-3b with the legal XML embedded, the format
 * ZATCA accepts for sharing e-invoices with buyers.
 */
@Service
public class InvoicePdfService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final OrganizationRepository orgs;
    private final JsonMapper json;

    public InvoicePdfService(OrganizationRepository orgs, JsonMapper json) {
        this.orgs = orgs;
        this.json = json;
    }

    public byte[] render(Invoice inv) {
        Organization org = orgs.findById(inv.getOrganizationId()).orElseThrow();
        Party seller = json.readValue(inv.getSellerSnapshot(), Party.class);
        Party buyer = inv.getBuyerSnapshot() == null ? null : json.readValue(inv.getBuyerSnapshot(), Party.class);
        String html = html(inv, org, seller, buyer);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();
            b.usePdfAConformance(PdfRendererBuilder.PdfAConformance.PDFA_3_B);
            b.useColorProfile(ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData());
            b.useUnicodeBidiSplitter(new ICUBidiSplitter.ICUBidiSplitterFactory());
            b.useUnicodeBidiReorderer(new ICUBidiReorderer());
            b.defaultTextDirection(BaseRendererBuilder.TextDirection.LTR);
            b.useFont(() -> font("IBMPlexSansArabic-Regular.ttf"), "Plex", 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            b.useFont(() -> font("IBMPlexSansArabic-Bold.ttf"), "Plex", 700, BaseRendererBuilder.FontStyle.NORMAL, true);
            b.withHtmlContent(html, null);
            b.toStream(out);
            b.run();
            return embedXml(out.toByteArray(), inv.getNumber() + ".xml", inv.legalXml());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static java.io.InputStream font(String name) {
        return InvoicePdfService.class.getResourceAsStream("/fonts/" + name);
    }

    /** Attaches the XML as an associated file (AFRelationship = Source), as PDF/A-3 requires. */
    private static byte[] embedXml(byte[] pdf, String fileName, String xml) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] data = xml.getBytes(StandardCharsets.UTF_8);
            PDEmbeddedFile ef = new PDEmbeddedFile(doc, new ByteArrayInputStream(data));
            ef.setSubtype("application/xml");
            ef.setSize(data.length);
            ef.setModDate(new GregorianCalendar());

            PDComplexFileSpecification spec = new PDComplexFileSpecification();
            spec.setFile(fileName);
            spec.setFileUnicode(fileName);
            spec.setEmbeddedFile(ef);
            spec.setEmbeddedFileUnicode(ef);
            spec.setFileDescription("ZATCA e-invoice XML");
            spec.getCOSObject().setName(COSName.getPDFName("AFRelationship"), "Source");

            PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
            tree.setNames(Map.of(fileName, spec));
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
            names.setEmbeddedFiles(tree);
            doc.getDocumentCatalog().setNames(names);

            COSArray af = new COSArray();
            af.add(spec);
            doc.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("AF"), af);
            doc.save(out);
            return out.toByteArray();
        }
    }

    // --- HTML ------------------------------------------------------------------------------------
    // Layout follows the usual Saudi bilingual invoice: every row is "English label | value | Arabic label",
    // so no single line mixes left-to-right and right-to-left text.

    /** Escaped non-blank parts joined with {@code sep}; null when there are none, so {@link #row} skips it. */
    private static String joined(String sep, String... parts) {
        String s = java.util.Arrays.stream(parts).filter(p -> p != null && !p.isBlank()).map(InvoicePdfService::esc)
                .collect(java.util.stream.Collectors.joining(sep));
        return s.isEmpty() ? null : s;
    }

    /** "Email · website", then "Mobile ...", under the company name, leaving out whatever is not set. */
    private static String contact(Organization org) {
        String web = joined(" · ", org.getEmail(), org.getWebsite());
        String mobile = joined("", org.getPhone());
        return (web == null ? "" : "<div class=\"muted\">" + web + "</div>")
                + (mobile == null ? "" : "<div class=\"muted\">Mobile " + mobile + "</div>");
    }

    private static final int LOGO_HEIGHT = 66, LOGO_MAX_WIDTH = 160;

    /** The company logo as the first header cell, at its own aspect ratio; nothing when none is uploaded. */
    private static String logo(Organization org) {
        if (org.getLogo() == null) return "";
        int w = LOGO_HEIGHT, h = LOGO_HEIGHT;
        try {
            var img = javax.imageio.ImageIO.read(new ByteArrayInputStream(java.util.Base64.getDecoder().decode(org.getLogo())));
            if (img != null) {
                w = Math.round(LOGO_HEIGHT * (float) img.getWidth() / img.getHeight());
                if (w > LOGO_MAX_WIDTH) {
                    h = Math.round(h * (float) LOGO_MAX_WIDTH / w);
                    w = LOGO_MAX_WIDTH;
                }
            }
        } catch (IOException ignored) {
            // Unreadable dimensions: fall back to a square.
        }
        return "<td style=\"width:" + (w + 10) + "px\"><img src=\"data:" + org.getLogoContentType() + ";base64,"
                + org.getLogo() + "\" style=\"width:" + w + "px;height:" + h + "px\"/></td>";
    }

    private String html(Invoice inv, Organization org, Party seller, Party buyer) {
        InvoiceTotals totals = InvoiceTotals.of(inv.getLines().stream().map(InvoiceLineEntity::toModel).toList());
        var issued = inv.getIssuedAt().atZone(InvoiceApiApplication.KSA);
        String[] title = title(inv.getKind(), inv.getType());
        String color = org.getBrandColor() == null ? "#0B2A6F" : org.getBrandColor();
        String cur = esc(inv.getCurrency());

        StringBuilder rows = new StringBuilder();
        int n = 1;
        for (InvoiceTotals.LineAmounts l : totals.lines()) {
            rows.append("<tr>")
                    .append(td(Integer.toString(n++), "c"))
                    .append(td(esc(l.line().name()), startsRtl(l.line().name()) ? "desc rtl" : "desc"))
                    .append(td(plain(l.line().quantity()) + " " + esc(l.line().unitCode()), "n"))
                    .append(td(money(l.line().unitPrice()), "n"))
                    .append(td(money(l.net()), "n"))
                    .append(td(plain(l.line().vatRate()) + "%", "n"))
                    .append(td(money(l.vat()), "n"))
                    .append(td(money(l.gross()), "n"))
                    .append("</tr>");
        }

        StringBuilder meta = new StringBuilder()
                .append(row("Invoice number", esc(inv.getNumber()), "رقم الفاتورة"))
                .append(row("Issue date", DATE.format(issued) + " " + TIME.format(issued), "تاريخ الإصدار"))
                .append(row("Supply date", DATE.format(inv.getSupplyDate() == null ? issued.toLocalDate() : inv.getSupplyDate()),
                        "تاريخ التوريد"));
        if (inv.getType() != DocumentType.INVOICE) {
            meta.append(row("Original invoice", esc(inv.getBillingReference()), "الفاتورة الأصلية"))
                    .append(row("Reason", esc(inv.getReason()), "سبب الإصدار"));
        }

        String buyerBlock = buyer == null ? "" : party("Buyer", "المشتري", buyer);
        String brandEn = org.getLegalNameEn() == null || org.getLegalNameEn().isBlank() ? nz(seller.name()) : org.getLegalNameEn();

        String[] stamp = switch (inv.getStatus()) {
            case CLEARED, CLEARED_WITH_WARNINGS -> new String[]{"Cleared by ZATCA", "معتمدة من هيئة الزكاة والضريبة والجمارك"};
            case REPORTED, REPORTED_WITH_WARNINGS -> new String[]{"Reported to ZATCA", "تم الإبلاغ لهيئة الزكاة والضريبة والجمارك"};
            case SIGNED -> new String[]{"", ""}; // valid for the customer already; no "pending" note on their copy
            default -> new String[]{esc(inv.getStatus().name()), ""};
        };

        return """
                <!DOCTYPE html>
                <html><head><meta charset="UTF-8"/>
                <title>%s</title>
                <style>
                  @page { size: A4; margin: 14mm 12mm; }
                  * { font-family: 'Plex'; }
                  body { font-size: 9pt; color: #1b2233; }
                  table { width: 100%%; border-collapse: collapse; }
                  .rtl { direction: rtl; text-align: right; }
                  .head td { vertical-align: top; }
                  .brand-en { font-size: 14pt; font-weight: 700; color: %s; }
                  .brand-ar { font-size: 15pt; font-weight: 700; color: %s; }
                  .muted { color: #5a6275; }
                  .title { margin: 12px 0 10px; border-top: 3px solid %s; border-bottom: 1px solid #e3e7ef; }
                  .title td { font-size: 15pt; font-weight: 700; color: %s; padding: 8px 0; }
                  .kv td { padding: 3px 6px; }
                  .kv td.en { color: #5a6275; width: 30%%; }
                  .kv td.v { font-weight: 700; }
                  .kv td.ar { color: #5a6275; width: 30%%; direction: rtl; text-align: right; }
                  .parties { margin-top: 10px; }
                  .parties td.col { width: 50%%; vertical-align: top; padding: 0 3px; }
                  .box { border: 1px solid #e3e7ef; background: #f6f8fb; }
                  .box td.hd { background: #e9edf4; padding: 5px 8px; font-weight: 700; }
                  .box .kv td { padding: 2px 8px; font-size: 8.5pt; }
                  .box .kv td.en, .box .kv td.ar { width: 28%%; }
                  .lines { margin-top: 12px; }
                  .lines th { background: %s; color: #fff; font-weight: 700; font-size: 8pt; padding: 5px 4px; text-align: center; }
                  .lines td { border-bottom: 1px solid #e3e7ef; padding: 6px 4px; }
                  td.n { text-align: right; white-space: nowrap; }
                  td.c { text-align: center; }
                  .bottom { margin-top: 14px; }
                  .bottom td.col { vertical-align: top; }
                  .sum td { padding: 5px 8px; }
                  .sum td.v { text-align: right; font-weight: 700; white-space: nowrap; }
                  .sum tr.total td { background: %s; color: #fff; font-size: 10.5pt; }
                  .stamp { margin-top: 6px; font-size: 8.5pt; color: #1b6b05; font-weight: 700; }
                  .foot { margin-top: 18px; font-size: 7pt; color: #8a91a3; }
                </style></head>
                <body>
                  <table class="head"><tr>
                    %s
                    <td>
                      <div class="brand-en">%s</div>
                      %s
                    </td>
                    <td class="rtl">
                      <div class="brand-ar">%s</div>
                      <div class="muted">%s</div>
                    </td>
                  </tr></table>

                  <table class="title"><tr>
                    <td>%s</td>
                    <td class="rtl">%s</td>
                  </tr></table>

                  <table class="kv">%s</table>

                  <table class="parties"><tr>
                    <td class="col">%s</td>
                    <td class="col">%s</td>
                  </tr></table>

                  <table class="lines">
                    <tr>
                      <th>#</th><th>Item name<br/>اسم الصنف</th><th>Qty<br/>الكمية</th>
                      <th>Unit price<br/>سعر الوحدة</th><th>Taxable<br/>المبلغ الخاضع</th>
                      <th>VAT %%<br/>النسبة</th><th>VAT<br/>الضريبة</th><th>Total<br/>الإجمالي</th>
                    </tr>
                    %s
                  </table>

                  <table class="bottom"><tr>
                    <td class="col" style="width:40%%">
                      <img src="%s" style="width:160px;height:160px"/>
                      <div class="stamp">%s</div>
                      <div class="stamp" style="direction:rtl;text-align:left">%s</div>
                    </td>
                    <td class="col" style="width:60%%">
                      <table class="sum">
                        <tr><td>Total excl. VAT</td><td class="rtl">الإجمالي غير شامل الضريبة</td><td class="v">%s %s</td></tr>
                        <tr><td>VAT total</td><td class="rtl">مجموع ضريبة القيمة المضافة</td><td class="v">%s %s</td></tr>
                        <tr class="total"><td>Total incl. VAT</td><td class="rtl">الإجمالي شامل الضريبة</td><td class="v">%s %s</td></tr>
                      </table>
                    </td>
                  </tr></table>

                  <div class="foot">UUID %s · ICV %d · Invoice hash %s</div>
                </body></html>
                """.formatted(
                esc(inv.getNumber()),
                color, color, color, color, color, color,
                logo(org),
                esc(brandEn),
                contact(org),
                // The registration name (Arabic when there is one); not repeated when it is the English name.
                esc(brandEn.equals(seller.name()) ? null : seller.name()),
                esc(nz(seller.address() == null ? null : seller.address().city())),
                title[1], title[0],
                meta,
                party("Seller", "البائع", seller, org.getLegalNameEn()), buyerBlock,
                rows,
                qrDataUri(inv.getQrCode()), stamp[0], stamp[1],
                money(totals.taxExclusive()), cur,
                money(totals.vatTotal()), cur,
                money(totals.taxInclusive()), cur,
                inv.getUuid(), inv.getIcv(), esc(inv.getInvoiceHash()));
    }

    static String[] title(InvoiceKind kind, DocumentType type) {
        boolean simplified = kind == InvoiceKind.SIMPLIFIED;
        return switch (type) {
            case INVOICE -> simplified
                    ? new String[]{"فاتورة ضريبية مبسطة", "Simplified Tax Invoice"}
                    : new String[]{"فاتورة ضريبية", "Tax Invoice"};
            case CREDIT_NOTE -> simplified
                    ? new String[]{"إشعار دائن مبسط", "Simplified Credit Note"}
                    : new String[]{"إشعار دائن", "Credit Note"};
            case DEBIT_NOTE -> simplified
                    ? new String[]{"إشعار مدين مبسط", "Simplified Debit Note"}
                    : new String[]{"إشعار مدين", "Debit Note"};
        };
    }

    /** Party box: one row per field, English label left, Arabic label right. */
    private static String party(String en, String ar, Party p) {
        return party(en, ar, p, null);
    }

    /** {@code nameEn}: the English trading name, printed under the Arabic legal name when it differs. */
    private static String party(String en, String ar, Party p, String nameEn) {
        StringBuilder kv = new StringBuilder().append(row("Name", esc(p.name()), "الاسم"));
        if (nameEn != null && !nameEn.isBlank() && !nameEn.equals(p.name()))
            kv.append(row("Name (English)", esc(nameEn), "الاسم بالإنجليزية"));
        if (p.hasVat()) kv.append(row("VAT number", esc(p.vatNumber()), "الرقم الضريبي"));
        if (p.otherId() != null) {
            String[] label = schemeLabel(p.otherIdScheme());
            kv.append(row(label[0], esc(p.otherId()), label[1]));
        }
        Address a = p.address();
        if (a != null) {
            // Two compact rows: the (usually Arabic) street, district and city, then the numbers.
            String place = joined("، ", a.street(), a.district(), a.city());
            String numbers = joined(" · ",
                    a.buildingNumber() == null || a.buildingNumber().isBlank() ? null : "Bldg " + a.buildingNumber(),
                    a.postalCode() == null || a.postalCode().isBlank() ? null : "Postal " + a.postalCode(),
                    a.additionalNumber() == null || a.additionalNumber().isBlank() ? null : "Add. " + a.additionalNumber(),
                    a.countryCode());
            kv.append(row("Address", place, "العنوان"))
                    .append(row("Building / postal", numbers, "المبنى / الرمز البريدي"));
        }
        return """
                <div class="box">
                  <table><tr><td class="hd">%s</td><td class="hd rtl">%s</td></tr></table>
                  <table class="kv">%s</table>
                </div>""".formatted(en, ar, kv);
    }

    /** "English label | value | Arabic label"; skipped when there is no value. */
    private static String row(String en, String value, String ar) {
        if (value == null || value.isBlank()) return "";
        boolean arabic = value.codePoints().anyMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.ARABIC);
        return "<tr><td class=\"en\">" + en + "</td><td class=\"v" + (arabic ? " rtl" : "") + "\">" + value
                + "</td><td class=\"ar\">" + ar + "</td></tr>";
    }

    private static String[] schemeLabel(String scheme) {
        return switch (scheme == null ? "OTH" : scheme) {
            case "CRN" -> new String[]{"CR number", "السجل التجاري"};
            case "NAT" -> new String[]{"National ID", "رقم الهوية"};
            case "IQA" -> new String[]{"Iqama", "رقم الإقامة"};
            case "PAS" -> new String[]{"Passport", "جواز السفر"};
            case "700" -> new String[]{"700 number", "الرقم الموحد"};
            default -> new String[]{"ID", "رقم التعريف"};
        };
    }

    private static String qrDataUri(String payload) {
        try {
            // ISO 18004 quiet zone is 4 modules. Render at a whole number of pixels per module so the
            // viewer's downscaling doesn't blur the module edges (a 9-tag ZATCA payload is very dense).
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 4);
            int modules = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints).getWidth();
            int side = modules * 8;
            BitMatrix m = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, side, side, hints);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(m, "PNG", png);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("QR rendering failed", e);
        }
    }

    private static String td(String content, String cls) {
        return "<td class=\"" + cls + "\">" + content + "</td>";
    }

    private static String money(BigDecimal v) {
        return String.format("%,.2f", v);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /**
     * True when the text's first strong character is right-to-left (Arabic, Hebrew), i.e. what HTML's
     * {@code dir="auto"} would pick. The PDF renderer has no {@code dir="auto"}, and free text such as an
     * Arabic item name laid out in an LTR cell gets its wrapped lines and embedded Latin words/numbers
     * reordered wrongly.
     */
    public static boolean startsRtl(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            byte d = Character.getDirectionality(cp);
            if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false;
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
