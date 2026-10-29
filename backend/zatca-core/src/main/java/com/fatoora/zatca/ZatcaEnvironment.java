package com.fatoora.zatca;

import java.net.URI;

/**
 * The three Fatoora environments. Onboarding in one does not carry over to another:
 * each needs its own CSR (the certificate template name differs) and its own CSIDs.
 */
public enum ZatcaEnvironment {
    /** Developer portal sandbox: open to everyone, fixed OTP 123345, no real taxpayer needed. */
    SANDBOX("https://gw-fatoora.zatca.gov.sa/e-invoicing/developer-portal", "TSTZATCA-Code-Signing"),
    /** Simulation: real taxpayer onboarding via fatoora simulation portal, invoices are not legally binding. */
    SIMULATION("https://gw-fatoora.zatca.gov.sa/e-invoicing/simulation", "PREZATCA-Code-Signing"),
    /** Production: legally binding clearance and reporting. */
    PRODUCTION("https://gw-fatoora.zatca.gov.sa/e-invoicing/core", "ZATCA-Code-Signing");

    private final URI baseUrl;
    private final String certificateTemplate;

    ZatcaEnvironment(String baseUrl, String certificateTemplate) {
        this.baseUrl = URI.create(baseUrl);
        this.certificateTemplate = certificateTemplate;
    }

    public URI baseUrl() {
        return baseUrl;
    }

    /** Value of the certificate template name extension (OID 1.3.6.1.4.1.311.20.2) in the CSR. */
    public String certificateTemplate() {
        return certificateTemplate;
    }
}
