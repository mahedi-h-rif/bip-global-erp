package com.fatoora.api.invoice;

import com.fatoora.zatca.model.DocumentType;
import com.fatoora.zatca.model.InvoiceKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** The money-related columns of a document, without its lines or XML. Used for dues and reports. */
public record InvoiceFigures(
        Long id,
        String number,
        InvoiceKind kind,
        DocumentType type,
        InvoiceStatus status,
        Long customerId,
        Long originalInvoiceId,
        BigDecimal totalInclVat,
        BigDecimal vatTotal,
        Instant issuedAt,
        LocalDate dueDate) {

    /** Issued documents that are legally valid (or on their way to ZATCA). Drafts and rejected ones carry no money. */
    public boolean counts() {
        return status.billable();
    }
}
