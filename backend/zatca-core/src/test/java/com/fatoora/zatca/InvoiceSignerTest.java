package com.fatoora.zatca;

import com.fatoora.zatca.crypto.EcKeys;
import com.fatoora.zatca.crypto.Hashing;
import com.fatoora.zatca.crypto.InvoiceHasher;
import com.fatoora.zatca.crypto.ZatcaCertificate;
import com.fatoora.zatca.csr.CsrConfig;
import com.fatoora.zatca.csr.CsrGenerator;
import com.fatoora.zatca.model.DocumentType;
import com.fatoora.zatca.model.InvoiceKind;
import com.fatoora.zatca.model.InvoiceTotals;
import com.fatoora.zatca.qr.QrCodeTlv;
import com.fatoora.zatca.sign.InvoiceSigner;
import com.fatoora.zatca.sign.SignedInvoice;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.openssl.PEMParser;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InvoiceSignerTest {

    @Test
    void initialPihIsBase64OfHexSha256OfZero() {
        assertEquals(Hashing.INITIAL_PIH, Hashing.base64OfHexSha256("0".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void totalsFollowZatcaRounding() {
        var doc = Samples.invoice(InvoiceKind.SIMPLIFIED, DocumentType.INVOICE, 1, Hashing.INITIAL_PIH);
        InvoiceTotals t = InvoiceTotals.of(doc.lines());
        // 40 × 2.18 = 87.20, 2 × 45.50 = 91.00, 100 zero-rated
        assertEquals(new BigDecimal("278.20"), t.lineExtension());
        assertEquals(new BigDecimal("26.73"), t.vatTotal()); // round(178.20 × 15%) = 26.73
        assertEquals(new BigDecimal("304.93"), t.taxInclusive());
        assertEquals(2, t.breakdown().size());
    }

    @Test
    void signedInvoiceKeepsHashAndVerifies() throws Exception {
        var ss = Samples.selfSigned();
        var cert = ZatcaCertificate.fromBase64(ss.certBase64());
        var doc = Samples.invoice(InvoiceKind.SIMPLIFIED, DocumentType.INVOICE, 1, Hashing.INITIAL_PIH);

        SignedInvoice signed = InvoiceSigner.sign(doc, cert, ss.keys().getPrivate(), LocalDateTime.now());

        assertEquals(signed.invoiceHash(), InvoiceHasher.hash(signed.xml()), "hash must ignore signature, QR and extensions");
        assertTrue(EcKeys.verify(cert.publicKey(), Base64.getDecoder().decode(signed.invoiceHash()),
                Base64.getDecoder().decode(signed.signatureValue())));
        assertTrue(signed.xml().contains("<cbc:InvoiceTypeCode name=\"0200000\">388</cbc:InvoiceTypeCode>"));

        Map<Integer, byte[]> qr = QrCodeTlv.decode(signed.qrBase64());
        assertEquals(Samples.SELLER.name(), new String(qr.get(1), StandardCharsets.UTF_8));
        assertEquals("304.93", new String(qr.get(4), StandardCharsets.UTF_8));
        assertEquals(signed.invoiceHash(), new String(qr.get(6), StandardCharsets.UTF_8));
        assertNotNull(qr.get(9), "simplified invoices carry the certificate signature in tag 9");
    }

    @Test
    void creditNoteCarriesBillingReferenceAndReason() throws Exception {
        var ss = Samples.selfSigned();
        var doc = Samples.invoice(InvoiceKind.STANDARD, DocumentType.CREDIT_NOTE, 2, Hashing.INITIAL_PIH);
        var signed = InvoiceSigner.sign(doc, ZatcaCertificate.fromBase64(ss.certBase64()), ss.keys().getPrivate(),
                LocalDateTime.now());
        assertTrue(signed.xml().contains(">381</cbc:InvoiceTypeCode>"));
        assertTrue(signed.xml().contains("<cac:BillingReference><cac:InvoiceDocumentReference><cbc:ID>INV-1</cbc:ID>"));
        assertTrue(signed.xml().contains("<cbc:InstructionNote>"));
    }

    @Test
    void csrContainsZatcaFields() throws Exception {
        var cfg = new CsrConfig("NG-PORTAL-1", "1-Fatoora|2-Portal|3-" + java.util.UUID.randomUUID(), Samples.SELLER_VAT,
                "Head Office", "Nazmul Global Company", "1100", "QFDA3287", "Fuel retail");
        var result = CsrGenerator.generate(cfg, ZatcaEnvironment.SANDBOX);
        try (PEMParser p = new PEMParser(new StringReader(result.csrPem()))) {
            var csr = (PKCS10CertificationRequest) p.readObject();
            String subject = csr.getSubject().toString();
            assertTrue(subject.contains("CN=NG-PORTAL-1"), subject);
            assertTrue(subject.contains("C=SA"), subject);
            assertEquals(1, csr.getAttributes().length);
        }
        assertNotNull(EcKeys.privateKeyFromPem(result.privateKeyPem()));
        assertTrue(new String(Base64.getDecoder().decode(result.csrBase64())).startsWith("-----BEGIN CERTIFICATE REQUEST-----"));
    }
}
