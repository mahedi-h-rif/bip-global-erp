package com.fatoora.zatca.api;

/** Network failure or timeout talking to Fatoora. Safe to retry: nothing was accepted. */
public class FatooraUnavailableException extends RuntimeException {
    public FatooraUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
