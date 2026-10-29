package com.fatoora.zatca.api;

import java.util.List;

/**
 * Response of the compliance / production CSID endpoints.
 *
 * @param requestID           compliance request id; needed to request the production CSID
 * @param binarySecurityToken base64 of the certificate's base64 DER; used as the Basic-auth username
 * @param secret              Basic-auth password
 */
public record CsidResponse(
        String requestID,
        String dispositionMessage,
        String binarySecurityToken,
        String secret,
        List<Object> errors) {}
