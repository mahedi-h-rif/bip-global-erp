package com.fatoora.zatca.crypto;

import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * A CSID certificate issued by ZATCA.
 * <p>
 * ZATCA returns {@code binarySecurityToken} = base64( base64(DER certificate) ). The inner base64
 * text (a PEM body without headers) is what goes into {@code ds:X509Certificate} and what the
 * certificate digest is computed over.
 */
public final class ZatcaCertificate {

    private final String base64Der;
    private final X509Certificate x509;

    private ZatcaCertificate(String base64Der) {
        this.base64Der = base64Der.replaceAll("\\s", "");
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509", EcKeys.BC);
            byte[] der = Base64.getDecoder().decode(this.base64Der);
            this.x509 = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Invalid certificate", e);
        }
    }

    /** From the {@code binarySecurityToken} field of a ZATCA CSID response. */
    public static ZatcaCertificate fromBinarySecurityToken(String token) {
        return new ZatcaCertificate(new String(Base64.getDecoder().decode(token), StandardCharsets.US_ASCII));
    }

    /** From a base64 DER string or a PEM with headers. */
    public static ZatcaCertificate fromBase64(String base64OrPem) {
        return new ZatcaCertificate(base64OrPem
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", ""));
    }

    /** Body of {@code ds:X509Certificate}. */
    public String base64Der() {
        return base64Der;
    }

    /** base64( hex( SHA-256( base64 text ) ) ), the digest format ZATCA's validator expects. */
    public String digest() {
        return Hashing.base64OfHexSha256(base64Der.getBytes(StandardCharsets.US_ASCII));
    }

    /** Issuer DN, most specific RDN first, joined with ", " (e.g. "CN=TSZEINVOICE-SubCA-1, DC=extgazt, …"). */
    public String issuerName() {
        X500Name issuer = X500Name.getInstance(x509.getIssuerX500Principal().getEncoded());
        List<String> parts = new ArrayList<>();
        for (RDN rdn : issuer.getRDNs()) {
            var tv = rdn.getFirst();
            parts.add(BCStyle.INSTANCE.oidToDisplayName(tv.getType()).toUpperCase() + "=" + IETFUtils.valueToString(tv.getValue()));
        }
        Collections.reverse(parts);
        return String.join(", ", parts);
    }

    /**
     * The VAT number this certificate is issued for: the UID in the subjectAltName directory name
     * (as requested in the CSR), falling back to a VAT-shaped number in the subject.
     * ZATCA only accepts documents whose seller VAT matches it.
     */
    public java.util.Optional<String> vatNumber() {
        try {
            byte[] ext = x509.getExtensionValue(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName.getId());
            if (ext != null) {
                var octets = org.bouncycastle.asn1.ASN1OctetString.getInstance(ext).getOctets();
                for (var name : org.bouncycastle.asn1.x509.GeneralNames.getInstance(octets).getNames()) {
                    if (name.getTagNo() != org.bouncycastle.asn1.x509.GeneralName.directoryName) continue;
                    for (RDN rdn : X500Name.getInstance(name.getName()).getRDNs(BCStyle.UID)) {
                        return java.util.Optional.of(IETFUtils.valueToString(rdn.getFirst().getValue()));
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the subject
        }
        var m = java.util.regex.Pattern.compile("3\\d{13}3").matcher(x509.getSubjectX500Principal().getName());
        return m.find() ? java.util.Optional.of(m.group()) : java.util.Optional.empty();
    }

    public String serialNumber() {
        return x509.getSerialNumber().toString();
    }

    public PublicKey publicKey() {
        return x509.getPublicKey();
    }

    /** DER SubjectPublicKeyInfo, QR tag 8. */
    public byte[] publicKeyDer() {
        return x509.getPublicKey().getEncoded();
    }

    /** ZATCA's signature over this certificate, QR tag 9. */
    public byte[] certificateSignature() {
        return x509.getSignature();
    }

    public Instant notAfter() {
        return x509.getNotAfter().toInstant();
    }

    public X509Certificate x509() {
        return x509;
    }
}
