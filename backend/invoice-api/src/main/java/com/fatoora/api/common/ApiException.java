package com.fatoora.api.common;

import org.springframework.http.HttpStatus;

/** Business error with an HTTP status and a stable code the portal can translate. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", what + " not found");
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /** Business validation failure tied to specific fields (shown next to the inputs in the portal). */
    public static class Validation extends ApiException {
        private final java.util.Map<String, String> fields;

        public Validation(java.util.Map<String, String> fields) {
            super(HttpStatus.BAD_REQUEST, "validation", String.join(". ", fields.values()));
            this.fields = java.util.Map.copyOf(fields);
        }

        public java.util.Map<String, String> fields() {
            return fields;
        }
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
