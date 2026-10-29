package com.fatoora.api.notification;

import com.fatoora.api.config.FatooraProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Unguessable public links to an invoice PDF, for reminders sent to customers who have no portal login.
 * The token is an HMAC of the invoice id with a server secret, so it can't be guessed from the id or number.
 */
@Component
public class InvoiceLinks {

    private final byte[] key;
    private final String portalUrl;

    public InvoiceLinks(FatooraProperties props) {
        this.key = ("invoice-link|" + props.jwt().secret()).getBytes(StandardCharsets.UTF_8);
        String url = props.portalUrl() == null ? "" : props.portalUrl().strip();
        this.portalUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public String pdfUrl(Long invoiceId) {
        return portalUrl + "/api/v1/public/invoices/" + invoiceId + "/" + token(invoiceId);
    }

    /** Staff-only link to the invoice inside the portal. */
    public String portalUrl(Long invoiceId) {
        return portalUrl + "/invoices/" + invoiceId;
    }

    public boolean valid(Long invoiceId, String token) {
        return token != null && MessageDigest.isEqual(
                token(invoiceId).getBytes(StandardCharsets.US_ASCII), token.getBytes(StandardCharsets.US_ASCII));
    }

    String token(Long invoiceId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] sig = mac.doFinal(("invoice-pdf:" + invoiceId).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig).substring(0, 32);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
