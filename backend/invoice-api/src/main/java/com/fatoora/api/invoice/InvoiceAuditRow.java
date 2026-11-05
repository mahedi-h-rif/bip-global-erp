package com.fatoora.api.invoice;

import com.fatoora.zatca.model.DocumentType;
import com.fatoora.zatca.model.InvoiceKind;

import java.math.BigDecimal;
import java.time.Instant;

/** The columns of a document the business audit checks: amounts, ZATCA status and timing. */
public record InvoiceAuditRow(
        Long id,
        String number,
        InvoiceKind kind,
        DocumentType type,
        InvoiceStatus status,
        Long customerId,
        BigDecimal totalExclVat,
        BigDecimal vatTotal,
        Instant issuedAt,
        Instant submittedAt,
        Instant createdAt) {}
