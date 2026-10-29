package com.fatoora.api.customer;

/**
 * Format of Saudi party identifiers (ZATCA "Other Seller/Buyer ID", BT-29 / BT-46). ZATCA accepts other
 * formats but flags them with BR-KSA-F-08 / F-13 warnings, so the portal refuses them up front.
 */
public final class OtherIdRules {

    private OtherIdRules() {}

    /** @return a problem description, or null when the value is acceptable (blank values are allowed). */
    public static String check(String scheme, String id) {
        if (id == null || id.isBlank()) return null;
        String s = scheme == null ? "OTH" : scheme;
        return switch (s) {
            case "CRN" -> id.matches("\\d{10}") ? null : "A commercial registration (CR) number has exactly 10 digits";
            case "700" -> id.matches("7\\d{9}") ? null : "A 700 (unified) number has 10 digits and starts with 7";
            case "NAT" -> id.matches("1\\d{9}") ? null : "A Saudi national ID has 10 digits and starts with 1";
            case "IQA" -> id.matches("2\\d{9}") ? null : "An Iqama number has 10 digits and starts with 2";
            default -> id.length() > 50 ? "ID is too long" : null;
        };
    }
}
