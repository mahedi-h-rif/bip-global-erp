package com.fatoora.zatca;

import com.fatoora.zatca.crypto.EcKeys;
import com.fatoora.zatca.model.Address;
import com.fatoora.zatca.model.DocumentType;
import com.fatoora.zatca.model.InvoiceDocument;
import com.fatoora.zatca.model.InvoiceKind;
import com.fatoora.zatca.model.InvoiceLine;
import com.fatoora.zatca.model.Party;
import com.fatoora.zatca.model.VatCategory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.KeyPair;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Test fixtures: a sandbox seller (ZATCA's test VAT number) and buyer. */
public final class Samples {

    public static final String SELLER_VAT = "399999999900003";

    public static final Party SELLER = new Party(
            "شركة نازمول جلوبال", SELLER_VAT, "7054854679", "CRN",
            new Address("الملك فيصل بن عبد العزيز", "3287", "9519", "حي النهضة", "البكيرية", "52727", "SA"));

    public static final Party BUYER = new Party(
            "شركة العميل التجارية", "399999999800003", null, null,
            new Address("طريق الملك عبدالعزيز", "1234", "5678", "حي الريان", "بريدة", "52365", "SA"));

    private Samples() {}

    public static InvoiceDocument invoice(InvoiceKind kind, DocumentType type, long icv, String pih) {
        List<InvoiceLine> lines = List.of(
                InvoiceLine.standardRated("بنزين 91 - Petrol 91", new BigDecimal("40"), "LTR", new BigDecimal("2.18")),
                InvoiceLine.standardRated("زيت محرك - Engine oil", new BigDecimal("2"), "PCE", new BigDecimal("45.50")),
                new InvoiceLine("خدمة تصدير - Export service", BigDecimal.ONE, "PCE", new BigDecimal("100"),
                        VatCategory.Z, BigDecimal.ZERO, "VATEX-SA-32", "Export of goods"));
        boolean note = type != DocumentType.INVOICE;
        return new InvoiceDocument(
                (note ? "CN-" : "INV-") + icv, UUID.randomUUID(),
                LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).minusMinutes(1),
                LocalDate.now(),
                kind, type, "SAR",
                note ? "INV-1" : null,
                note ? "Returned goods / مرتجع" : null,
                "10",
                SELLER,
                kind == InvoiceKind.STANDARD ? BUYER : null,
                lines, icv, pih);
    }

    /** Self-signed secp256k1 certificate standing in for a ZATCA CSID in offline tests. */
    public static SelfSigned selfSigned() throws Exception {
        KeyPair keys = EcKeys.generate();
        X500Name name = new X500Name("CN=Test EGS, O=Test, C=SA");
        var builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(123456789L),
                Date.from(Instant.now().minusSeconds(60)), Date.from(Instant.now().plus(365, ChronoUnit.DAYS)),
                name, keys.getPublic());
        var holder = builder.build(new JcaContentSignerBuilder("SHA256withECDSA").setProvider(EcKeys.BC).build(keys.getPrivate()));
        var cert = new JcaX509CertificateConverter().setProvider(EcKeys.BC).getCertificate(holder);
        return new SelfSigned(keys, Base64.getEncoder().encodeToString(cert.getEncoded()));
    }

    public record SelfSigned(KeyPair keys, String certBase64) {}
}
