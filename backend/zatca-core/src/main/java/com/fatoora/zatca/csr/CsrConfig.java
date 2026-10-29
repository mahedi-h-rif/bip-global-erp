package com.fatoora.zatca.csr;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Inputs for an EGS unit's certificate signing request, per ZATCA's onboarding guideline.
 *
 * @param commonName       unique name of the EGS unit, e.g. "NG-HQ-PORTAL-1"
 * @param serialNumber     "1-&lt;solution name&gt;|2-&lt;model/version&gt;|3-&lt;device serial or UUID&gt;"
 * @param vatNumber        15-digit VAT number (organization identifier)
 * @param organizationUnit branch name; for VAT groups the 10-digit TIN of the member
 * @param organizationName taxpayer name
 * @param invoiceTypes     4 flags "TSCZ": T = standard, S = simplified, C and Z reserved. "1100" = both
 * @param location         branch / device location, e.g. short national address
 * @param industry         business category, e.g. "Fuel retail"
 */
public record CsrConfig(
        String commonName,
        String serialNumber,
        String vatNumber,
        String organizationUnit,
        String organizationName,
        String invoiceTypes,
        String location,
        String industry) {

    private static final Pattern VAT = Pattern.compile("3\\d{13}3");
    private static final Pattern SERIAL = Pattern.compile("1-[^|]+\\|2-[^|]+\\|3-[^|]+");
    private static final Pattern TYPES = Pattern.compile("[01]{4}");

    public CsrConfig {
        Objects.requireNonNull(commonName, "commonName");
        Objects.requireNonNull(organizationUnit, "organizationUnit");
        Objects.requireNonNull(organizationName, "organizationName");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(industry, "industry");
        if (vatNumber == null || !VAT.matcher(vatNumber).matches())
            throw new IllegalArgumentException("VAT number must be 15 digits starting and ending with 3");
        if (serialNumber == null || !SERIAL.matcher(serialNumber).matches())
            throw new IllegalArgumentException("Serial number must look like 1-Solution|2-Model|3-Serial");
        if (invoiceTypes == null || !TYPES.matcher(invoiceTypes).matches() || invoiceTypes.startsWith("00"))
            throw new IllegalArgumentException("Invoice types must be 4 binary flags with standard and/or simplified set");
    }

    public boolean issuesStandard() {
        return invoiceTypes.charAt(0) == '1';
    }

    public boolean issuesSimplified() {
        return invoiceTypes.charAt(1) == '1';
    }
}
