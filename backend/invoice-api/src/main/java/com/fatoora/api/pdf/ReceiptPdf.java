package com.fatoora.api.pdf;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.invoice.Invoice;
import com.fatoora.api.invoice.InvoiceLineEntity;
import com.fatoora.api.organization.Organization;
import com.fatoora.zatca.model.InvoiceTotals;
import com.openhtmltopdf.bidi.support.ICUBidiReorderer;
import com.openhtmltopdf.bidi.support.ICUBidiSplitter;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;

/** 80 mm thermal receipt for a point-of-sale simplified tax invoice (فاتورة ضريبية مبسطة), with the ZATCA QR. */
@Service
public class ReceiptPdf {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public byte[] render(Invoice inv, Organization org, BigDecimal paid, BigDecimal change, String cashier) {
        StringBuilder rows = new StringBuilder();
        for (InvoiceLineEntity l : inv.getLines()) {
            BigDecimal net = InvoiceTotals.round(l.getQuantity().multiply(l.getUnitPrice()));
            BigDecimal gross = net.add(InvoiceTotals.round(net.multiply(l.getVatRate()).divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)));
            rows.append("<tr><td colspan=\"3\" dir=\"auto\">").append(LetterheadPdf.esc(l.getName())).append("</td></tr>")
                    .append("<tr><td class=\"m\">").append(l.getQuantity().stripTrailingZeros().toPlainString()).append(" × ")
                    .append(LetterheadPdf.money(l.getUnitPrice()).replace(" SAR", "")).append("</td><td></td><td class=\"r\">")
                    .append(LetterheadPdf.money(gross).replace(" SAR", "")).append("</td></tr>");
        }
        BigDecimal due = inv.getTotalInclVat().subtract(paid).max(BigDecimal.ZERO);
        String logo = org.getLogo() == null ? "" : "<div class=\"c\">" + LetterheadPdf.img(org.getLogo(),
                org.getLogoContentType(), 46, 200) + "</div>";
        String html = """
                <html><head><style>
                @page { size: 80mm %dmm; margin: 4mm 4mm; }
                body { font-family: Plex; font-size: 8.5pt; color: #000; }
                table { width: 100%%; border-collapse: collapse; } td { padding: 1px 0; vertical-align: top; }
                .c { text-align: center; } .r { text-align: right; } .m { color: #333; } .b { font-weight: 700; }
                .hr { border-top: 1px dashed #000; margin: 4px 0; } h1 { font-size: 10pt; margin: 2px 0; }
                </style></head><body>
                %s
                <div class="c b">%s</div><div class="c" dir="rtl">%s</div>
                <div class="c m">VAT %s · CR %s</div>
                <div class="c m" dir="rtl">%s</div>
                <div class="hr"></div>
                <h1 class="c">Simplified Tax Invoice · فاتورة ضريبية مبسطة</h1>
                <table><tr><td>No. %s</td><td class="r">%s</td></tr><tr><td colspan="2" class="m">Cashier: %s%s</td></tr></table>
                <div class="hr"></div>
                <table>%s</table>
                <div class="hr"></div>
                <table>
                  <tr><td>Total excl. VAT · الإجمالي قبل الضريبة</td><td class="r">%s</td></tr>
                  <tr><td>VAT 15%% · الضريبة</td><td class="r">%s</td></tr>
                  <tr class="b"><td>Total · الإجمالي</td><td class="r">%s</td></tr>
                  <tr><td>Paid · المدفوع</td><td class="r">%s</td></tr>
                  %s%s
                </table>
                <div class="hr"></div>
                <div class="c"><img src="%s" style="width:34mm; height:34mm"/></div>
                <div class="c m">Thank you · شكراً لزيارتكم</div>
                </body></html>""".formatted(
                150 + inv.getLines().size() * 10 + (org.getLogo() == null ? 0 : 26),
                logo, LetterheadPdf.esc(org.getLegalNameEn() == null ? "" : org.getLegalNameEn()), LetterheadPdf.esc(org.getLegalNameAr()),
                LetterheadPdf.esc(org.getVatNumber()), LetterheadPdf.esc(org.getOtherId() == null ? "" : org.getOtherId()),
                LetterheadPdf.esc(String.join("، ", java.util.stream.Stream.of(org.getStreet(), org.getDistrict(), org.getCity())
                        .filter(s -> s != null && !s.isBlank()).toList())),
                LetterheadPdf.esc(inv.getNumber()), TS.format(inv.getIssuedAt().atZone(InvoiceApiApplication.KSA)),
                LetterheadPdf.esc(cashier == null ? "" : cashier),
                inv.getCustomerId() == null ? "" : "",
                rows,
                LetterheadPdf.money(inv.getTotalExclVat()), LetterheadPdf.money(inv.getVatTotal()), LetterheadPdf.money(inv.getTotalInclVat()),
                LetterheadPdf.money(paid),
                change.signum() > 0 ? "<tr><td>Change · الباقي</td><td class=\"r\">" + LetterheadPdf.money(change) + "</td></tr>" : "",
                due.signum() > 0 ? "<tr class=\"b\"><td>Due · المتبقي</td><td class=\"r\">" + LetterheadPdf.money(due) + "</td></tr>" : "",
                InvoicePdfService.qrDataUri(inv.getQrCode()));
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();
            b.useUnicodeBidiSplitter(new ICUBidiSplitter.ICUBidiSplitterFactory());
            b.useUnicodeBidiReorderer(new ICUBidiReorderer());
            b.defaultTextDirection(BaseRendererBuilder.TextDirection.LTR);
            b.useFont(() -> InvoicePdfService.font("IBMPlexSansArabic-Regular.ttf"), "Plex", 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            b.useFont(() -> InvoicePdfService.font("IBMPlexSansArabic-Bold.ttf"), "Plex", 700, BaseRendererBuilder.FontStyle.NORMAL, true);
            b.withHtmlContent(html, null);
            b.toStream(out);
            b.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
