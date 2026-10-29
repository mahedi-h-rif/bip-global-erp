package com.fatoora.zatca.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything ZATCA needs to know about one tax invoice, credit note or debit note.
 * Plain data: no persistence or framework types, so any product can build one.
 *
 * @param number           seller's invoice number (KSA-… "Invoice number"), unique per seller
 * @param uuid             unique document UUID (KSA-1)
 * @param issueDateTime    issue date and time in Saudi local time (UTC+3)
 * @param supplyDate       date of supply; required for standard invoices (KSA-5)
 * @param kind             standard (B2B, cleared) or simplified (B2C, reported)
 * @param type             invoice, credit note or debit note
 * @param currency         ISO 4217 document currency, normally SAR
 * @param billingReference original invoice number; required for credit/debit notes (BR-KSA-56)
 * @param reason           reason for issuing a credit/debit note (KSA-10)
 * @param paymentMeans     UNTDID 4461 payment means code, e.g. "10" cash, "30" credit transfer, "48" card
 * @param seller           the supplier (the taxpayer issuing the document)
 * @param buyer            the customer; mandatory details for standard invoices, optional for simplified
 * @param lines            at least one line
 * @param icv              invoice counter value: sequential per EGS unit (KSA-16)
 * @param previousHash     hash of the previous document from the same EGS unit (KSA-13)
 */
public record InvoiceDocument(
        String number,
        UUID uuid,
        LocalDateTime issueDateTime,
        LocalDate supplyDate,
        InvoiceKind kind,
        DocumentType type,
        String currency,
        String billingReference,
        String reason,
        String paymentMeans,
        Party seller,
        Party buyer,
        List<InvoiceLine> lines,
        long icv,
        String previousHash) {

    public InvoiceDocument {
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(issueDateTime, "issueDateTime");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(seller, "seller");
        Objects.requireNonNull(previousHash, "previousHash");
        if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("At least one invoice line is required");
        if (icv < 1) throw new IllegalArgumentException("ICV must be >= 1");
        if (type != DocumentType.INVOICE && (billingReference == null || billingReference.isBlank()))
            throw new IllegalArgumentException("Credit and debit notes must reference the original invoice");
        if (type != DocumentType.INVOICE && (reason == null || reason.isBlank()))
            throw new IllegalArgumentException("Credit and debit notes must state a reason");
        currency = currency == null ? "SAR" : currency;
        paymentMeans = paymentMeans == null ? "10" : paymentMeans;
        lines = List.copyOf(lines);
    }
}
