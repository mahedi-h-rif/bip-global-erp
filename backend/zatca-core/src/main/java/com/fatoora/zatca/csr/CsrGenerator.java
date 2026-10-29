package com.fatoora.zatca.csr;

import com.fatoora.zatca.ZatcaEnvironment;
import com.fatoora.zatca.crypto.EcKeys;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERPrintableString;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtensionsGenerator;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;

/**
 * Builds the CSR ZATCA expects for an EGS unit:
 * <ul>
 *   <li>subject: C=SA, OU=branch, O=taxpayer, CN=EGS name</li>
 *   <li>certificate template name extension (Microsoft OID 1.3.6.1.4.1.311.20.2) per environment</li>
 *   <li>subjectAltName dirName: SN=serial, UID=VAT, title=invoice types, registeredAddress, businessCategory</li>
 *   <li>secp256k1 key, SHA256withECDSA signature</li>
 * </ul>
 */
public final class CsrGenerator {

    private static final ASN1ObjectIdentifier CERT_TEMPLATE_NAME = new ASN1ObjectIdentifier("1.3.6.1.4.1.311.20.2");
    private static final ASN1ObjectIdentifier REGISTERED_ADDRESS = new ASN1ObjectIdentifier("2.5.4.26");

    private CsrGenerator() {}

    /** Result of CSR generation. Keep {@code privateKeyPem} secret: it signs every invoice of this EGS unit. */
    public record Result(String privateKeyPem, String csrPem) {
        /** base64 of the whole PEM text, the format of the {@code csr} field in the compliance API. */
        public String csrBase64() {
            return Base64.getEncoder().encodeToString(csrPem.getBytes(StandardCharsets.US_ASCII));
        }
    }

    public static Result generate(CsrConfig config, ZatcaEnvironment environment) {
        KeyPair keys = EcKeys.generate();

        X500Name subject = new X500NameBuilder(BCStyle.INSTANCE)
                .addRDN(BCStyle.C, "SA")
                .addRDN(BCStyle.OU, config.organizationUnit())
                .addRDN(BCStyle.O, config.organizationName())
                .addRDN(BCStyle.CN, config.commonName())
                .build();

        X500Name altName = new X500NameBuilder(BCStyle.INSTANCE)
                .addRDN(BCStyle.SURNAME, config.serialNumber())
                .addRDN(BCStyle.UID, config.vatNumber())
                .addRDN(BCStyle.T, config.invoiceTypes())
                .addRDN(REGISTERED_ADDRESS, config.location())
                .addRDN(BCStyle.BUSINESS_CATEGORY, config.industry())
                .build();

        try {
            ExtensionsGenerator ext = new ExtensionsGenerator();
            ext.addExtension(CERT_TEMPLATE_NAME, false, new DERPrintableString(environment.certificateTemplate()));
            ext.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(new GeneralName(GeneralName.directoryName, altName)));

            PKCS10CertificationRequest csr = new JcaPKCS10CertificationRequestBuilder(subject, keys.getPublic())
                    .addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, ext.generate())
                    .build(new JcaContentSignerBuilder("SHA256withECDSA").setProvider(EcKeys.BC).build(keys.getPrivate()));

            StringWriter sw = new StringWriter();
            try (JcaPEMWriter pem = new JcaPEMWriter(sw)) {
                pem.writeObject(csr);
            }
            return new Result(EcKeys.toPem(keys.getPrivate()), sw.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (OperatorCreationException e) {
            throw new IllegalStateException("Cannot sign CSR", e);
        }
    }
}
