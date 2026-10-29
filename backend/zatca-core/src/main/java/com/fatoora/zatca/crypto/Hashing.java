package com.fatoora.zatca.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

public final class Hashing {

    /**
     * PIH of the first document an EGS unit ever issues: base64 of the hex SHA-256 of "0",
     * as defined in ZATCA's security features standard.
     */
    public static final String INITIAL_PIH =
            "NWZlY2ViNjZmZmM4NmYzOGQ5NTI3ODZjNmQ2OTZjNzljMmRiYzIzOWRkNGU5MWI0NjcyOWQ3M2EyN2ZiNTdlOQ==";

    private Hashing() {}

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String base64Sha256(byte[] data) {
        return Base64.getEncoder().encodeToString(sha256(data));
    }

    /** ZATCA's certificate and signed-properties digests are base64 of the lowercase hex digest. */
    public static String base64OfHexSha256(byte[] data) {
        String hex = HexFormat.of().formatHex(sha256(data));
        return Base64.getEncoder().encodeToString(hex.getBytes(StandardCharsets.US_ASCII));
    }
}
