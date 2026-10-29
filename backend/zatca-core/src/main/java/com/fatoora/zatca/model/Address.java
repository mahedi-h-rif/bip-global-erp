package com.fatoora.zatca.model;

/**
 * Saudi national address (or a foreign address for non-KSA buyers).
 *
 * @param street           street name
 * @param buildingNumber   4-digit building number for KSA addresses (BR-KSA-37)
 * @param additionalNumber 4-digit additional number (secondary number), optional
 * @param district         district / neighbourhood (city subdivision)
 * @param city             city name
 * @param postalCode       5-digit postal code for KSA addresses (BR-KSA-66)
 * @param countryCode      ISO 3166-1 alpha-2, normally "SA"
 */
public record Address(
        String street,
        String buildingNumber,
        String additionalNumber,
        String district,
        String city,
        String postalCode,
        String countryCode) {

    public Address {
        countryCode = countryCode == null || countryCode.isBlank() ? "SA" : countryCode;
    }
}
