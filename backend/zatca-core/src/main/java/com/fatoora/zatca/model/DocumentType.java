package com.fatoora.zatca.model;

/** UNTDID 1001 document type codes used by ZATCA. */
public enum DocumentType {
    INVOICE("388"),
    DEBIT_NOTE("383"),
    CREDIT_NOTE("381");

    private final String code;

    DocumentType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
