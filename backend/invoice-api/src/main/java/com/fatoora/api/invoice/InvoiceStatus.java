package com.fatoora.api.invoice;

import java.util.Set;

public enum InvoiceStatus {
    /** Editable, no number, not in any chain. A standard invoice "sent later" stays here. */
    DRAFT,
    /** Numbered, signed and chained. Waiting for (or retrying) clearance / reporting. */
    SIGNED,
    CLEARED,
    CLEARED_WITH_WARNINGS,
    REPORTED,
    REPORTED_WITH_WARNINGS,
    /** ZATCA refused it. It stays in the chain; issue a corrected document instead. */
    REJECTED;

    public static final Set<InvoiceStatus> ACCEPTED =
            Set.of(CLEARED, CLEARED_WITH_WARNINGS, REPORTED, REPORTED_WITH_WARNINGS);

    public boolean accepted() {
        return ACCEPTED.contains(this);
    }

    /** Issued and not rejected: the customer owes it (simplified invoices are valid from signing). */
    public boolean billable() {
        return this != DRAFT && this != REJECTED;
    }
}
