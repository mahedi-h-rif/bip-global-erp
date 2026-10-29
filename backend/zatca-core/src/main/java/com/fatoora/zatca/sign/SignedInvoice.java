package com.fatoora.zatca.sign;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Result of signing.
 *
 * @param xml            final signed XML
 * @param invoiceHash    base64 SHA-256 invoice hash; becomes the next document's PIH
 * @param signatureValue base64 ECDSA signature
 * @param qrBase64       QR payload (TLV, base64)
 * @param uuid           document UUID
 */
public record SignedInvoice(String xml, String invoiceHash, String signatureValue, String qrBase64, UUID uuid) {

    /** Base64 of the XML, as the ZATCA API expects in the {@code invoice} field. */
    public String xmlBase64() {
        return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
    }
}
