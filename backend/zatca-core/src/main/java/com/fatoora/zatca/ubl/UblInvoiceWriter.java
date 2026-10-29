package com.fatoora.zatca.ubl;

import com.fatoora.zatca.model.Address;
import com.fatoora.zatca.model.DocumentType;
import com.fatoora.zatca.model.InvoiceDocument;
import com.fatoora.zatca.model.InvoiceKind;
import com.fatoora.zatca.model.InvoiceTotals;
import com.fatoora.zatca.model.Party;
import com.fatoora.zatca.xml.Ns;
import com.fatoora.zatca.xml.XmlWriter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;

/**
 * Renders an {@link InvoiceDocument} as a ZATCA-profile UBL 2.1 {@code Invoice}.
 * <p>
 * The three parts that ZATCA excludes from the invoice hash (UBL extensions holding the signature,
 * the QR attachment and {@code cac:Signature}) are optional so the same method produces both the
 * unsigned form that gets hashed and the final signed document.
 */
public final class UblInvoiceWriter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String SIGNATURE_ID = "urn:oasis:names:specification:ubl:signature:Invoice";
    private static final String SIGNATURE_METHOD = "urn:oasis:names:specification:ubl:dsig:enveloped:xades";

    private UblInvoiceWriter() {}

    /** Unsigned document: the exact form whose hash ZATCA computes. */
    public static String unsigned(InvoiceDocument doc) {
        return render(doc, null, null);
    }

    /**
     * @param ublExtensions complete {@code <ext:UBLExtensions>} element, or null
     * @param qrBase64      base64 TLV QR payload, or null; when present {@code cac:Signature} is written too
     */
    public static String render(InvoiceDocument doc, String ublExtensions, String qrBase64) {
        InvoiceTotals totals = InvoiceTotals.of(doc.lines());
        String cur = doc.currency();
        XmlWriter w = new XmlWriter().declaration();

        w.start("Invoice")
                .attr("xmlns", Ns.INVOICE)
                .attr("xmlns:cac", Ns.CAC)
                .attr("xmlns:cbc", Ns.CBC)
                .attr("xmlns:ext", Ns.EXT);

        if (ublExtensions != null) w.raw(ublExtensions);

        w.element("cbc:ProfileID", "reporting:1.0");
        w.element("cbc:ID", doc.number());
        w.element("cbc:UUID", doc.uuid().toString());
        w.element("cbc:IssueDate", DATE.format(doc.issueDateTime()));
        w.element("cbc:IssueTime", TIME.format(doc.issueDateTime()));
        w.start("cbc:InvoiceTypeCode").attr("name", doc.kind().subtypeCode()).text(doc.type().code()).end();
        w.element("cbc:DocumentCurrencyCode", cur);
        w.element("cbc:TaxCurrencyCode", "SAR");

        if (doc.type() != DocumentType.INVOICE) {
            w.start("cac:BillingReference").start("cac:InvoiceDocumentReference")
                    .element("cbc:ID", doc.billingReference())
                    .end().end();
        }

        w.start("cac:AdditionalDocumentReference")
                .element("cbc:ID", "ICV")
                .element("cbc:UUID", Long.toString(doc.icv()))
                .end();
        attachment(w, "PIH", doc.previousHash());
        if (qrBase64 != null) {
            attachment(w, "QR", qrBase64);
            w.start("cac:Signature")
                    .element("cbc:ID", SIGNATURE_ID)
                    .element("cbc:SignatureMethod", SIGNATURE_METHOD)
                    .end();
        }

        w.start("cac:AccountingSupplierParty");
        party(w, doc.seller());
        w.end();

        w.start("cac:AccountingCustomerParty");
        if (doc.buyer() != null) party(w, doc.buyer());
        w.end();

        if (doc.supplyDate() != null || doc.kind() == InvoiceKind.STANDARD) {
            var supply = doc.supplyDate() != null ? doc.supplyDate() : doc.issueDateTime().toLocalDate();
            w.start("cac:Delivery").element("cbc:ActualDeliveryDate", DATE.format(supply)).end();
        }

        w.start("cac:PaymentMeans").element("cbc:PaymentMeansCode", doc.paymentMeans());
        if (doc.type() != DocumentType.INVOICE) w.element("cbc:InstructionNote", doc.reason());
        w.end();

        // First TaxTotal: VAT in the tax currency (SAR). Second: breakdown per VAT category.
        w.start("cac:TaxTotal");
        amount(w, "cbc:TaxAmount", totals.vatTotal(), "SAR");
        w.end();

        w.start("cac:TaxTotal");
        amount(w, "cbc:TaxAmount", totals.vatTotal(), cur);
        for (InvoiceTotals.VatBreakdown b : totals.breakdown()) {
            w.start("cac:TaxSubtotal");
            amount(w, "cbc:TaxableAmount", b.taxable(), cur);
            amount(w, "cbc:TaxAmount", b.vat(), cur);
            w.start("cac:TaxCategory")
                    .start("cbc:ID").attr("schemeID", "UN/ECE 5305").attr("schemeAgencyID", "6")
                    .text(b.category().name()).end()
                    .element("cbc:Percent", percent(b.rate()))
                    .element("cbc:TaxExemptionReasonCode", b.exemptionCode())
                    .element("cbc:TaxExemptionReason", b.exemptionReason())
                    .start("cac:TaxScheme")
                    .start("cbc:ID").attr("schemeID", "UN/ECE 5153").attr("schemeAgencyID", "6").text("VAT").end()
                    .end()
                    .end();
            w.end();
        }
        w.end();

        w.start("cac:LegalMonetaryTotal");
        amount(w, "cbc:LineExtensionAmount", totals.lineExtension(), cur);
        amount(w, "cbc:TaxExclusiveAmount", totals.taxExclusive(), cur);
        amount(w, "cbc:TaxInclusiveAmount", totals.taxInclusive(), cur);
        amount(w, "cbc:AllowanceTotalAmount", BigDecimal.ZERO, cur);
        amount(w, "cbc:PrepaidAmount", BigDecimal.ZERO, cur);
        amount(w, "cbc:PayableAmount", totals.payable(), cur);
        w.end();

        int lineNo = 1;
        for (InvoiceTotals.LineAmounts l : totals.lines()) {
            w.start("cac:InvoiceLine").element("cbc:ID", Integer.toString(lineNo++));
            w.start("cbc:InvoicedQuantity").attr("unitCode", l.line().unitCode())
                    .text(plain(l.line().quantity(), 0)).end();
            amount(w, "cbc:LineExtensionAmount", l.net(), cur);
            w.start("cac:TaxTotal");
            amount(w, "cbc:TaxAmount", l.vat(), cur);
            amount(w, "cbc:RoundingAmount", l.gross(), cur);
            w.end();
            w.start("cac:Item")
                    .element("cbc:Name", l.line().name())
                    .start("cac:ClassifiedTaxCategory")
                    .element("cbc:ID", l.line().vatCategory().name())
                    .element("cbc:Percent", percent(l.line().vatRate()))
                    .start("cac:TaxScheme").element("cbc:ID", "VAT").end()
                    .end()
                    .end();
            w.start("cac:Price");
            w.start("cbc:PriceAmount").attr("currencyID", cur).text(plain(l.line().unitPrice(), 2)).end();
            w.end();
            w.end();
        }

        w.end();
        return w.toString();
    }

    private static void attachment(XmlWriter w, String id, String base64) {
        w.start("cac:AdditionalDocumentReference")
                .element("cbc:ID", id)
                .start("cac:Attachment")
                .start("cbc:EmbeddedDocumentBinaryObject").attr("mimeCode", "text/plain").text(base64).end()
                .end()
                .end();
    }

    private static void party(XmlWriter w, Party p) {
        w.start("cac:Party");
        if (p.otherId() != null && !p.otherId().isBlank()) {
            w.start("cac:PartyIdentification")
                    .element("cbc:ID", p.otherId(), "schemeID", p.otherIdScheme() == null ? "OTH" : p.otherIdScheme())
                    .end();
        }
        Address a = p.address();
        if (a != null) {
            w.start("cac:PostalAddress")
                    .element("cbc:StreetName", a.street())
                    .element("cbc:BuildingNumber", a.buildingNumber())
                    .element("cbc:PlotIdentification", a.additionalNumber())
                    .element("cbc:CitySubdivisionName", a.district())
                    .element("cbc:CityName", a.city())
                    .element("cbc:PostalZone", a.postalCode())
                    .start("cac:Country").element("cbc:IdentificationCode", a.countryCode()).end()
                    .end();
        }
        w.start("cac:PartyTaxScheme");
        if (p.hasVat()) w.element("cbc:CompanyID", p.vatNumber());
        w.start("cac:TaxScheme").element("cbc:ID", "VAT").end();
        w.end();
        w.start("cac:PartyLegalEntity").element("cbc:RegistrationName", p.name()).end();
        w.end();
    }

    private static void amount(XmlWriter w, String name, BigDecimal value, String currency) {
        w.start(name).attr("currencyID", currency).text(InvoiceTotals.round(value).toPlainString()).end();
    }

    private static String percent(BigDecimal rate) {
        return rate.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Plain decimal with at least {@code minScale} fraction digits and no trailing noise. */
    private static String plain(BigDecimal value, int minScale) {
        BigDecimal v = value.stripTrailingZeros();
        if (v.scale() < minScale) v = v.setScale(minScale, RoundingMode.UNNECESSARY);
        return v.toPlainString();
    }
}
