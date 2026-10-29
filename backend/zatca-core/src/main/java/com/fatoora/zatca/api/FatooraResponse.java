package com.fatoora.zatca.api;

/**
 * @param status HTTP status code returned by Fatoora
 * @param body   parsed JSON body, or null when the body was empty or not JSON
 * @param raw    raw response body, kept for the audit trail
 */
public record FatooraResponse<T>(int status, T body, String raw) {

    /** 200 or 202: the document or request was accepted (202 = with warnings). */
    public boolean accepted() {
        return status == 200 || status == 202;
    }
}
