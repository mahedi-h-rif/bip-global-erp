package com.fatoora.zatca.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One invoice line. Prices are VAT-exclusive.
 *
 * @param name            item name
 * @param quantity        invoiced quantity
 * @param unitCode        UN/ECE rec 20 unit code, e.g. "PCE" (piece), "LTR" (litre), "KGM"
 * @param unitPrice       net price per unit, before VAT
 * @param vatCategory     S, Z, E or O
 * @param vatRate         VAT percentage, e.g. 15; must be 0 for Z, E and O
 * @param exemptionCode   VATEX code, required for Z, E and O (e.g. "VATEX-SA-32")
 * @param exemptionReason human-readable exemption reason, required with {@code exemptionCode}
 */
public record InvoiceLine(
        String name,
        BigDecimal quantity,
        String unitCode,
        BigDecimal unitPrice,
        VatCategory vatCategory,
        BigDecimal vatRate,
        String exemptionCode,
        String exemptionReason) {

    public InvoiceLine {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(vatCategory, "vatCategory");
        if (quantity.signum() <= 0) throw new IllegalArgumentException("Quantity must be positive");
        if (unitPrice.signum() < 0) throw new IllegalArgumentException("Unit price cannot be negative");
        unitCode = unitCode == null ? "PCE" : unitCode;
        vatRate = vatRate == null ? vatCategory.defaultRate() : vatRate;
        if (vatCategory != VatCategory.S && vatRate.signum() != 0)
            throw new IllegalArgumentException("VAT rate must be 0 for category " + vatCategory);
        if (vatCategory != VatCategory.S && (exemptionCode == null || exemptionReason == null))
            throw new IllegalArgumentException("Category " + vatCategory + " needs an exemption code and reason");
    }

    /** Convenience for the common case: a standard-rated (15%) line. */
    public static InvoiceLine standardRated(String name, BigDecimal quantity, String unitCode, BigDecimal unitPrice) {
        return new InvoiceLine(name, quantity, unitCode, unitPrice, VatCategory.S, new BigDecimal("15"), null, null);
    }
}
