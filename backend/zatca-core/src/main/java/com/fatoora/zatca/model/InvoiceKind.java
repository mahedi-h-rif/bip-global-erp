package com.fatoora.zatca.model;

/** Standard documents are cleared by ZATCA before delivery; simplified ones are reported within 24 hours. */
public enum InvoiceKind {
    /** B2B / B2G tax invoice (فاتورة ضريبية). InvoiceTypeCode name "0100000". */
    STANDARD("0100000"),
    /** B2C simplified tax invoice (فاتورة ضريبية مبسطة). InvoiceTypeCode name "0200000". */
    SIMPLIFIED("0200000");

    private final String subtypeCode;

    InvoiceKind(String subtypeCode) {
        this.subtypeCode = subtypeCode;
    }

    /** Value of the {@code name} attribute on {@code cbc:InvoiceTypeCode}. */
    public String subtypeCode() {
        return subtypeCode;
    }
}
