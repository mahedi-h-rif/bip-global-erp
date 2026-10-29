package com.fatoora.zatca.qr;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ZATCA QR payload: a sequence of Tag-Length-Value records, base64 encoded.
 * <pre>
 *  1 seller name        2 seller VAT number   3 timestamp (issue date + time)
 *  4 total incl. VAT    5 VAT total           6 invoice hash (base64 text)
 *  7 ECDSA signature (base64 text)            8 public key (DER bytes)
 *  9 ZATCA's signature over the CSID certificate (DER bytes; simplified documents)
 * </pre>
 * Tag and length are one byte each, so every value must be at most 255 bytes.
 */
public final class QrCodeTlv {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    public QrCodeTlv add(int tag, String value) {
        return add(tag, value.getBytes(StandardCharsets.UTF_8));
    }

    public QrCodeTlv add(int tag, byte[] value) {
        if (value.length > 255) throw new IllegalArgumentException("QR tag " + tag + " exceeds 255 bytes");
        out.write(tag);
        out.write(value.length);
        out.writeBytes(value);
        return this;
    }

    public String toBase64() {
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /** Decodes a QR payload into tag → raw bytes; used by tests and the PDF preview. */
    public static Map<Integer, byte[]> decode(String base64) {
        byte[] data = Base64.getDecoder().decode(base64);
        Map<Integer, byte[]> tags = new LinkedHashMap<>();
        int i = 0;
        while (i < data.length) {
            int tag = data[i++] & 0xFF;
            int len = data[i++] & 0xFF;
            byte[] value = new byte[len];
            System.arraycopy(data, i, value, 0, len);
            tags.put(tag, value);
            i += len;
        }
        return tags;
    }
}
