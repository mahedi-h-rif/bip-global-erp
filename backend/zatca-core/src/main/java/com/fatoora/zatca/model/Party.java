package com.fatoora.zatca.model;

/**
 * Seller or buyer.
 *
 * @param name          registration name (Arabic legal name for the seller is recommended)
 * @param vatNumber     15-digit VAT registration number, starting and ending with 3; null if the party has none
 * @param otherId       other identifier, e.g. the CR number (required for the seller, BR-KSA-08)
 * @param otherIdScheme scheme of {@code otherId}: CRN, MOM, MLS, 700, SAG, NAT, GCC, IQA, PAS, TIN, OTH
 * @param address       national address; mandatory for the seller and for buyers on standard invoices
 */
public record Party(String name, String vatNumber, String otherId, String otherIdScheme, Address address) {

    public boolean hasVat() {
        return vatNumber != null && !vatNumber.isBlank();
    }
}
