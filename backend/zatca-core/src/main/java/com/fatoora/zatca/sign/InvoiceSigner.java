package com.fatoora.zatca.sign;

import com.fatoora.zatca.crypto.EcKeys;
import com.fatoora.zatca.crypto.Hashing;
import com.fatoora.zatca.crypto.InvoiceHasher;
import com.fatoora.zatca.crypto.ZatcaCertificate;
import com.fatoora.zatca.model.InvoiceDocument;
import com.fatoora.zatca.model.InvoiceKind;
import com.fatoora.zatca.model.InvoiceTotals;
import com.fatoora.zatca.qr.QrCodeTlv;
import com.fatoora.zatca.ubl.UblInvoiceWriter;
import com.fatoora.zatca.xml.Ns;
import com.fatoora.zatca.xml.XmlWriter;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Produces a signed ZATCA invoice (XAdES-BES enveloped signature inside UBL extensions, plus QR code).
 * <p>
 * ZATCA's scheme differs from plain XMLDSig: {@code SignatureValue} is the ECDSA signature over the
 * <em>invoice hash bytes</em>, and the certificate / signed-properties digests are base64 of hex digests.
 */
public final class InvoiceSigner {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final String SHA256 = "http://www.w3.org/2001/04/xmlenc#sha256";
    private static final String XPATH = "http://www.w3.org/TR/1999/REC-xpath-19991116";
    private static final String C14N11 = "http://www.w3.org/2006/12/xml-c14n11";

    private InvoiceSigner() {}

    public static SignedInvoice sign(InvoiceDocument doc, ZatcaCertificate cert, PrivateKey key, LocalDateTime signingTime) {
        String unsigned = UblInvoiceWriter.unsigned(doc);
        String invoiceHash = InvoiceHasher.hash(unsigned);

        byte[] signature = EcKeys.sign(key, Base64.getDecoder().decode(invoiceHash));
        String signatureValue = Base64.getEncoder().encodeToString(signature);

        String signingTimeText = TIMESTAMP.format(signingTime);
        String signedPropertiesDigest = Hashing.base64OfHexSha256(
                signedProperties(cert, signingTimeText, true).getBytes(StandardCharsets.UTF_8));

        InvoiceTotals totals = InvoiceTotals.of(doc.lines());
        QrCodeTlv qr = new QrCodeTlv()
                .add(1, doc.seller().name())
                .add(2, doc.seller().vatNumber())
                .add(3, TIMESTAMP.format(doc.issueDateTime()))
                .add(4, totals.taxInclusive().toPlainString())
                .add(5, totals.vatTotal().toPlainString())
                .add(6, invoiceHash)
                .add(7, signatureValue)
                .add(8, cert.publicKeyDer());
        if (doc.kind() == InvoiceKind.SIMPLIFIED) qr.add(9, cert.certificateSignature());
        String qrBase64 = qr.toBase64();

        String extensions = ublExtensions(invoiceHash, signedPropertiesDigest, signatureValue, cert, signingTimeText);
        String signedXml = UblInvoiceWriter.render(doc, extensions, qrBase64);

        String check = InvoiceHasher.hash(signedXml);
        if (!check.equals(invoiceHash)) {
            throw new IllegalStateException("Invoice hash changed after signing (" + invoiceHash + " vs " + check + ")");
        }
        return new SignedInvoice(signedXml, invoiceHash, signatureValue, qrBase64, doc.uuid());
    }

    /**
     * {@code xades:SignedProperties}. With {@code forDigest}, namespace declarations are placed on the
     * elements that use them, which is how ZATCA's validator serialises the element before hashing it.
     */
    static String signedProperties(ZatcaCertificate cert, String signingTime, boolean forDigest) {
        String xadesNs = forDigest ? " xmlns:xades=\"" + Ns.XADES + "\"" : "";
        String dsNs = forDigest ? " xmlns:ds=\"" + Ns.DS + "\"" : "";
        return "<xades:SignedProperties" + xadesNs + " Id=\"xadesSignedProperties\">"
                + "<xades:SignedSignatureProperties>"
                + "<xades:SigningTime>" + signingTime + "</xades:SigningTime>"
                + "<xades:SigningCertificate><xades:Cert><xades:CertDigest>"
                + "<ds:DigestMethod" + dsNs + " Algorithm=\"" + SHA256 + "\"/>"
                + "<ds:DigestValue" + dsNs + ">" + cert.digest() + "</ds:DigestValue>"
                + "</xades:CertDigest><xades:IssuerSerial>"
                + "<ds:X509IssuerName" + dsNs + ">" + XmlWriter.escape(cert.issuerName(), false) + "</ds:X509IssuerName>"
                + "<ds:X509SerialNumber" + dsNs + ">" + cert.serialNumber() + "</ds:X509SerialNumber>"
                + "</xades:IssuerSerial></xades:Cert></xades:SigningCertificate>"
                + "</xades:SignedSignatureProperties>"
                + "</xades:SignedProperties>";
    }

    private static String ublExtensions(String invoiceHash, String signedPropertiesDigest, String signatureValue,
                                        ZatcaCertificate cert, String signingTime) {
        return "<ext:UBLExtensions><ext:UBLExtension>"
                + "<ext:ExtensionURI>urn:oasis:names:specification:ubl:dsig:enveloped:xades</ext:ExtensionURI>"
                + "<ext:ExtensionContent>"
                + "<sig:UBLDocumentSignatures xmlns:sig=\"" + Ns.SIG + "\" xmlns:sac=\"" + Ns.SAC + "\" xmlns:sbc=\"" + Ns.SBC + "\">"
                + "<sac:SignatureInformation>"
                + "<cbc:ID>urn:oasis:names:specification:ubl:signature:1</cbc:ID>"
                + "<sbc:ReferencedSignatureID>urn:oasis:names:specification:ubl:signature:Invoice</sbc:ReferencedSignatureID>"
                + "<ds:Signature xmlns:ds=\"" + Ns.DS + "\" Id=\"signature\">"
                + "<ds:SignedInfo>"
                + "<ds:CanonicalizationMethod Algorithm=\"" + C14N11 + "\"/>"
                + "<ds:SignatureMethod Algorithm=\"http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256\"/>"
                + "<ds:Reference Id=\"invoiceSignedData\" URI=\"\">"
                + "<ds:Transforms>"
                + transform("not(//ancestor-or-self::ext:UBLExtensions)")
                + transform("not(//ancestor-or-self::cac:Signature)")
                + transform("not(//ancestor-or-self::cac:AdditionalDocumentReference[cbc:ID='QR'])")
                + "<ds:Transform Algorithm=\"" + C14N11 + "\"/>"
                + "</ds:Transforms>"
                + "<ds:DigestMethod Algorithm=\"" + SHA256 + "\"/>"
                + "<ds:DigestValue>" + invoiceHash + "</ds:DigestValue>"
                + "</ds:Reference>"
                + "<ds:Reference Type=\"http://www.w3.org/2000/09/xmldsig#SignatureProperties\" URI=\"#xadesSignedProperties\">"
                + "<ds:DigestMethod Algorithm=\"" + SHA256 + "\"/>"
                + "<ds:DigestValue>" + signedPropertiesDigest + "</ds:DigestValue>"
                + "</ds:Reference>"
                + "</ds:SignedInfo>"
                + "<ds:SignatureValue>" + signatureValue + "</ds:SignatureValue>"
                + "<ds:KeyInfo><ds:X509Data><ds:X509Certificate>" + cert.base64Der() + "</ds:X509Certificate></ds:X509Data></ds:KeyInfo>"
                + "<ds:Object>"
                + "<xades:QualifyingProperties xmlns:xades=\"" + Ns.XADES + "\" Target=\"signature\">"
                + signedProperties(cert, signingTime, false)
                + "</xades:QualifyingProperties>"
                + "</ds:Object>"
                + "</ds:Signature>"
                + "</sac:SignatureInformation>"
                + "</sig:UBLDocumentSignatures>"
                + "</ext:ExtensionContent>"
                + "</ext:UBLExtension></ext:UBLExtensions>";
    }

    private static String transform(String xpath) {
        return "<ds:Transform Algorithm=\"" + XPATH + "\"><ds:XPath>" + XmlWriter.escape(xpath, false) + "</ds:XPath></ds:Transform>";
    }
}
