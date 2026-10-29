package com.fatoora.zatca;

import com.fatoora.zatca.api.FatooraClient;
import com.fatoora.zatca.api.FatooraResponse;
import com.fatoora.zatca.api.SubmissionResponse;
import com.fatoora.zatca.crypto.EcKeys;
import com.fatoora.zatca.crypto.Hashing;
import com.fatoora.zatca.crypto.ZatcaCertificate;
import com.fatoora.zatca.csr.CsrConfig;
import com.fatoora.zatca.csr.CsrGenerator;
import com.fatoora.zatca.model.DocumentType;
import com.fatoora.zatca.model.InvoiceKind;
import com.fatoora.zatca.sign.InvoiceSigner;
import com.fatoora.zatca.sign.SignedInvoice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full onboarding against ZATCA's public developer sandbox (OTP 123345).
 * Run with: ./gradlew :zatca-core:test -Dzatca.live=true --tests '*SandboxOnboardingIT'
 */
@EnabledIfSystemProperty(named = "zatca.live", matches = "true")
class SandboxOnboardingIT {

    @Test
    void onboardSubmitSixComplianceDocumentsAndGetProductionCsid() {
        var client = new FatooraClient(ZatcaEnvironment.SANDBOX);
        var cfg = new CsrConfig("NG-PORTAL-SANDBOX", "1-Fatoora|2-Portal|3-" + UUID.randomUUID(), Samples.SELLER_VAT,
                "Head Office", "Nazmul Global Company", "1100", "QFDA3287", "Fuel retail");
        var csr = CsrGenerator.generate(cfg, ZatcaEnvironment.SANDBOX);

        var ccsid = client.requestComplianceCsid(csr.csrBase64(), "123345");
        System.out.println("COMPLIANCE CSID " + ccsid.status() + " " + ccsid.raw());
        assertTrue(ccsid.accepted(), ccsid.raw());
        var creds = new FatooraClient.Credentials(ccsid.body().binarySecurityToken(), ccsid.body().secret());
        var cert = ZatcaCertificate.fromBinarySecurityToken(ccsid.body().binarySecurityToken());
        var key = EcKeys.privateKeyFromPem(csr.privateKeyPem());
        System.out.println("ISSUER " + cert.issuerName() + " SERIAL " + cert.serialNumber());

        String pih = Hashing.INITIAL_PIH;
        long icv = 1;
        int failures = 0;
        for (InvoiceKind kind : InvoiceKind.values()) {
            for (DocumentType type : DocumentType.values()) {
                var doc = Samples.invoice(kind, type, icv++, pih);
                SignedInvoice signed = InvoiceSigner.sign(doc, cert, key, LocalDateTime.now());
                FatooraResponse<SubmissionResponse> res = client.checkCompliance(signed, creds);
                System.out.println("\n=== " + kind + " " + type + " → HTTP " + res.status() + "\n" + res.raw());
                if (!res.accepted()) {
                    failures++;
                    System.out.println("XML:\n" + signed.xml());
                }
                pih = signed.invoiceHash();
            }
        }
        assertEquals(0, failures, "all six compliance documents must pass");

        var pcsid = client.requestProductionCsid(ccsid.body().requestID(), creds);
        System.out.println("PRODUCTION CSID " + pcsid.status() + " " + pcsid.raw());
        assertTrue(pcsid.accepted(), pcsid.raw());

        // Live use of the production CSID: clear a standard invoice, report a simplified one.
        var prod = new FatooraClient.Credentials(pcsid.body().binarySecurityToken(), pcsid.body().secret());
        var prodCert = ZatcaCertificate.fromBinarySecurityToken(pcsid.body().binarySecurityToken());
        assertEquals(java.util.Optional.of(Samples.SELLER_VAT), cert.vatNumber(), "compliance CSID carries the CSR's VAT");
        assertEquals(java.util.Optional.of("399999999900003"), prodCert.vatNumber(),
                "the developer sandbox always returns its shared test certificate");

        var standard = InvoiceSigner.sign(Samples.invoice(InvoiceKind.STANDARD, DocumentType.INVOICE, icv++, pih),
                prodCert, key, LocalDateTime.now());
        var cleared = client.clear(standard, prod);
        System.out.println("\n=== CLEARANCE → HTTP " + cleared.status() + "\n" + cleared.raw());
        assertTrue(cleared.accepted(), cleared.raw());
        assertEquals("CLEARED", cleared.body().clearanceStatus());
        assertNotNull(cleared.body().clearedInvoiceXml());

        var simplified = InvoiceSigner.sign(Samples.invoice(InvoiceKind.SIMPLIFIED, DocumentType.INVOICE, icv, standard.invoiceHash()),
                prodCert, key, LocalDateTime.now());
        var reported = client.report(simplified, prod);
        System.out.println("\n=== REPORTING → HTTP " + reported.status() + "\n" + reported.raw());
        assertTrue(reported.accepted(), reported.raw());
        assertEquals("REPORTED", reported.body().reportingStatus());
    }
}
