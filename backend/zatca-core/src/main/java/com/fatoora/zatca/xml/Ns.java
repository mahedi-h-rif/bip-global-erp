package com.fatoora.zatca.xml;

/** XML namespaces used in ZATCA UBL 2.1 invoices. */
public final class Ns {
    public static final String INVOICE = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2";
    public static final String CAC = "urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2";
    public static final String CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";
    public static final String EXT = "urn:oasis:names:specification:ubl:schema:xsd:CommonExtensionComponents-2";
    public static final String SIG = "urn:oasis:names:specification:ubl:schema:xsd:CommonSignatureComponents-2";
    public static final String SAC = "urn:oasis:names:specification:ubl:schema:xsd:SignatureAggregateComponents-2";
    public static final String SBC = "urn:oasis:names:specification:ubl:schema:xsd:SignatureBasicComponents-2";
    public static final String DS = "http://www.w3.org/2000/09/xmldsig#";
    public static final String XADES = "http://uri.etsi.org/01903/v1.3.2#";

    private Ns() {}
}
