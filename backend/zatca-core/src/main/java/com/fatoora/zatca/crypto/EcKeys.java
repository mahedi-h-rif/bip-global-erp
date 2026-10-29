package com.fatoora.zatca.crypto;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Security;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;

/**
 * ECDSA on secp256k1, the curve ZATCA mandates. The JDK dropped secp256k1, so BouncyCastle does the work.
 */
public final class EcKeys {

    public static final Provider BC = provider();

    private EcKeys() {}

    private static Provider provider() {
        Provider existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
        if (existing != null) return existing;
        BouncyCastleProvider bc = new BouncyCastleProvider();
        Security.addProvider(bc);
        return bc;
    }

    public static KeyPair generate() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC", BC);
            gen.initialize(new ECGenParameterSpec("secp256k1"));
            return gen.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot generate secp256k1 key pair", e);
        }
    }

    /** PKCS#8 PEM ("-----BEGIN PRIVATE KEY-----"). */
    public static String toPem(PrivateKey key) {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter pem = new JcaPEMWriter(sw)) {
            pem.writeObject(new org.bouncycastle.openssl.jcajce.JcaPKCS8Generator(key, null));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sw.toString();
    }

    /** Reads a PKCS#8 or SEC1 ("EC PRIVATE KEY") PEM. */
    public static PrivateKey privateKeyFromPem(String pem) {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider(BC);
            if (obj instanceof PEMKeyPair pair) return converter.getKeyPair(pair).getPrivate();
            if (obj instanceof PrivateKeyInfo info) return converter.getPrivateKey(info);
            throw new IllegalArgumentException("Unsupported private key PEM: " + (obj == null ? "empty" : obj.getClass()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** SHA256withECDSA over {@code data}; returns the DER-encoded signature. */
    public static byte[] sign(PrivateKey key, byte[] data) {
        try {
            Signature sig = Signature.getInstance("SHA256withECDSA", BC);
            sig.initSign(key);
            sig.update(data);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("ECDSA signing failed", e);
        }
    }

    public static boolean verify(java.security.PublicKey key, byte[] data, byte[] signature) {
        try {
            Signature sig = Signature.getInstance("SHA256withECDSA", BC);
            sig.initVerify(key);
            sig.update(data);
            return sig.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
