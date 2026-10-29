package com.fatoora.zatca.model;

import java.math.BigDecimal;

/** UNCL 5305 VAT category codes accepted by ZATCA. */
public enum VatCategory {
    /** Standard rate (15%). */
    S(new BigDecimal("15")),
    /** Zero rated (exports, some medicines, etc.). */
    Z(BigDecimal.ZERO),
    /** Exempt (financial services, residential rent…). */
    E(BigDecimal.ZERO),
    /** Out of scope of VAT. */
    O(BigDecimal.ZERO);

    private final BigDecimal defaultRate;

    VatCategory(BigDecimal defaultRate) {
        this.defaultRate = defaultRate;
    }

    public BigDecimal defaultRate() {
        return defaultRate;
    }
}
