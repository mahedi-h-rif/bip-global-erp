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

    /** Optional so tests can build the service without it; used for the buyer's name in the other language. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.fatoora.api.customer.CustomerRepository customers;

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

    static java.io.InputStream font(String name) {
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

    private String html(Invoice inv, Organization org, Party seller, Party buyer) {
        InvoiceTotals totals = InvoiceTotals.of(inv.getLines().stream().map(InvoiceLineEntity::toModel).toList());
        var issued = inv.getIssuedAt().atZone(InvoiceApiApplication.KSA);
        String[] title = title(inv.getKind(), inv.getType());
        boolean skyline = LetterheadPdf.isSkyline(org);
        String color = skyline ? SkylineLetterhead.NAVY : org.getBrandColor() == null ? "#0B2A6F" : org.getBrandColor();
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
        if (inv.getDueDate() != null) meta.append(row("Payment due date", DATE.format(inv.getDueDate()), "تاريخ الاستحقاق"));
        if (inv.getCustomerRef() != null) meta.append(row("Customer PO no.", esc(inv.getCustomerRef()), "رقم أمر الشراء"));
        if (inv.getSalesEmployee() != null) meta.append(row("Sales employee", esc(inv.getSalesEmployee()), "مندوب المبيعات"));
        if (inv.getType() != DocumentType.INVOICE) {
            meta.append(row("Original invoice", esc(inv.getBillingReference()), "الفاتورة الأصلية"))
                    .append(row("Reason", esc(inv.getReason()), "سبب الإصدار"));
        }

        String brandEn = org.getLegalNameEn() == null || org.getLegalNameEn().isBlank() ? nz(seller.name()) : org.getLegalNameEn();

        return """
                <!DOCTYPE html>
                <html><head><meta charset="UTF-8"/>
                <title>%s</title>
                <style>
                  %s
                  * { font-family: 'Plex'; }
                  body { font-size: 9pt; color: #1b2233; }
                  table { width: 100%%; border-collapse: collapse; }
                  .rtl { direction: rtl; text-align: right; }
                  .head td { vertical-align: top; }
                  .brand-en { font-size: 11.5pt; font-weight: 700; color: %s; white-space: nowrap; }
                  .brand-ar { font-size: 11.5pt; font-weight: 700; color: %s; }
                  .muted { color: #5a6275; }
                  .title { margin: 12px 0 10px; border-top: 3px solid %s; border-bottom: 1px solid #e3e7ef; }
                  .title td { padding: 8px 0; }
                  .title td.t { font-size: 15pt; font-weight: 700; color: %s; width: 1%%; white-space: nowrap; padding-right: 10px; }
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
                  .pk td { padding: 2px 6px; font-size: 8pt; }
                  .pk td.en, .pk td.ar { width: 27%%; }
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
                </style></head>
                <body>
                  %s

                  <table class="title"><tr>
                    <td class="t">%s</td>
                    <td class="t">/</td>
                    <td class="t rtl">%s</td>
                    <td></td>
                  </tr></table>

                  %s

                  %s

                  <table class="lines">
                    <tr>
                      <th>#</th><th>Item name<br/>اسم الصنف</th><th>Qty<br/>الكمية</th>
                      <th>Unit price<br/>سعر الوحدة</th><th>Taxable<br/>المبلغ الخاضع</th>
                      <th>VAT %%<br/>النسبة</th><th>VAT<br/>الضريبة</th><th>Total<br/>الإجمالي</th>
                    </tr>
                    %s
                  </table>

                  <table class="bottom"><tr>
                    <td class="col" style="width:40%%"></td>
                    <td class="col" style="width:60%%">
                      <table class="sum">
                        <tr><td>Total excl. VAT</td><td class="rtl">الإجمالي غير شامل الضريبة</td><td class="v">%s %s</td></tr>
                        <tr><td>VAT total</td><td class="rtl">مجموع ضريبة القيمة المضافة</td><td class="v">%s %s</td></tr>
                        <tr class="total"><td>Total incl. VAT</td><td class="rtl">الإجمالي شامل الضريبة</td><td class="v">%s %s</td></tr>
                      </table>
                    </td>
                  </tr></table>

                  %s
                </body></html>
                """.formatted(
                esc(inv.getNumber()),
                // On the SKYLINE letterhead the page chrome (header, footer, backdrop) carries the company identity.
                skyline ? SkylineLetterhead.css() + " body { margin: 0; }" : "@page { size: A4; margin: 14mm 12mm; }",
                color, color, color, color, color, color,
                skyline ? SkylineLetterhead.chrome(org) : classicHead(org, seller, brandEn),
                title[1], title[0],
                metaAndQr(meta.toString(), qrDataUri(inv.getQrCode())),
                parties(seller, org.getLegalNameEn(), buyer, buyerOtherName(inv, buyer)),
                rows,
                money(totals.taxExclusive()), cur,
                money(totals.vatTotal()), cur,
                money(totals.taxInclusive()), cur,
                extras(inv, org, totals.taxInclusive()));
    }

    /**
     * The buyer's name in the other language (the invoice carries the Arabic name when there is one): from the
     * customer record, so the PDF shows the buyer in both English and Arabic.
     */
    private String buyerOtherName(Invoice inv, Party buyer) {
        if (buyer == null || customers == null || inv.getCustomerId() == null) return null;
        return customers.findByIdAndOrganizationId(inv.getCustomerId(), inv.getOrganizationId()).map(c -> {
            String en = c.getNameEn(), ar = c.getNameAr();
            if (en == null || en.isBlank() || ar == null || ar.isBlank()) return null;
            return buyer.name().equals(ar) ? en : buyer.name().equals(en) ? ar : null;
        }).orElse(null);
    }

    /** The invoice details (number, dates, references) on the left, the ZATCA QR code on the right. */
    private static String metaAndQr(String meta, String qr) {
        return "<table style=\"page-break-inside:avoid\"><tr>"
                + "<td style=\"width:75%; vertical-align:top; padding:0\"><table class=\"kv\">" + meta + "</table></td>"
                + "<td style=\"width:2%; padding:0\"></td>"
                + "<td style=\"width:23%; vertical-align:top; text-align:center; padding:0\">"
                + "<img src=\"" + qr + "\" style=\"width:118px;height:118px\"/>"
                + "</td></tr></table>";
    }

    /**
     * Seller and buyer boxes side by side across the full width. The boxes are table cells of one row, so they
     * always share the same height; each shows only the fields that are filled in.
     */
    private static String parties(Party seller, String sellerNameEn, Party buyer, String buyerOther) {
        String box = "<td style=\"width:49.25%%; vertical-align:top; padding:0; border:1px solid #e3e7ef; background:#f6f8fb\">"
                + "<table><tr><td class=\"hd\" style=\"background:#e9edf4; padding:5px 8px; font-weight:700\">%s</td>"
                + "<td class=\"hd rtl\" style=\"background:#e9edf4; padding:5px 8px; font-weight:700\">%s</td></tr></table>"
                + "<table class=\"kv pk\">%s</table></td>";
        String gap = "<td style=\"width:1.5%; padding:0\"></td>";
        String buyerCell = buyer == null ? "<td style=\"width:49.25%; padding:0\"></td>" : box.formatted("Buyer", "المشتري", partyRows(buyer, buyerOther));
        return "<table style=\"margin-top:10px; page-break-inside:avoid\"><tr>"
                + box.formatted("Seller", "البائع", partyRows(seller, sellerNameEn)) + gap + buyerCell
                + "</tr></table>";
    }

    /** "English label | value | Arabic label" rows for one party, leaving out whatever is not filled in. */
    private static String partyRows(Party p, String nameEn) {
        StringBuilder kv = new StringBuilder().append(row("Name", esc(p.name()), "الاسم"));
        if (nameEn != null && !nameEn.isBlank() && !nameEn.equals(p.name())) {
            // The second name is labelled by its own language: English under an Arabic name, or the other way round.
            boolean arabic = nameEn.codePoints().anyMatch(ch -> Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.ARABIC);
            kv.append(arabic ? row("Name (Arabic)", esc(nameEn), "الاسم بالعربية") : row("Name (English)", esc(nameEn), "الاسم بالإنجليزية"));
        }
        if (p.hasVat()) kv.append(row("VAT number", esc(p.vatNumber()), "الرقم الضريبي"));
        if (!blank(p.otherId())) {
            String[] label = schemeLabel(p.otherIdScheme());
            kv.append(row(label[0], esc(p.otherId()), label[1]));
        }
        Address a = p.address();
        if (a != null) {
            kv.append(row("Address", joined("، ", a.street(), a.district(), a.city()), "العنوان"))
                    .append(row("Building / postal", joined(" · ",
                            blank(a.buildingNumber()) ? null : "Bldg " + a.buildingNumber(),
                            blank(a.postalCode()) ? null : "Postal " + a.postalCode(),
                            blank(a.additionalNumber()) ? null : "Add. " + a.additionalNumber(),
                            a.countryCode()), "المبنى / الرمز البريدي"));
        }
        return kv.toString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** Amount in words, references ("Based on …"), bank details and the company's footer line. */
    private static String extras(Invoice inv, Organization org, java.math.BigDecimal total) {
        StringBuilder b = new StringBuilder("<div style=\"margin-top:8px; font-size:8pt\">");
        b.append("<div><b>Amount in words:</b> ").append(esc(com.fatoora.api.common.AmountInWords.english(total, inv.getCurrency())))
                .append("</div><div style=\"direction:rtl; text-align:right\"><b>المبلغ كتابة:</b> ")
                .append(esc(com.fatoora.api.common.AmountInWords.arabic(total))).append("</div>");
        if (inv.getInternalNote() != null && inv.getInternalNote().startsWith("Based on"))
            b.append("<div style=\"margin-top:4px\"><b>Remarks:</b> ").append(esc(inv.getInternalNote())).append("</div>");
        b.append(BankDetailsBlock.html(org));
        b.append("<div style=\"margin-top:6px; color:#555\">This is a system-generated e-invoice and does not need a signature · "
                + "هذه فاتورة إلكترونية صادرة من النظام ولا تحتاج إلى توقيع</div>");
        if (org.getInvoiceFooter() != null && !org.getInvoiceFooter().isBlank())
            b.append("<div style=\"margin-top:4px; color:#555\">").append(esc(org.getInvoiceFooter())).append("</div>");
        return b.append("</div>").toString();
    }

    /** The classic invoice header: logo, English name and contacts, then the registration name and city. */
    private static String classicHead(Organization org, Party seller, String brandEn) {
        // Same header as every other document: English details, logo in the middle, Arabic details.
        // The title below draws the brand rule under it.
        return LetterheadPdf.patternHeader(org, org.getBrandColor() == null ? "#0B2A6F" : org.getBrandColor());
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

    static String qrDataUri(String payload) {
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
