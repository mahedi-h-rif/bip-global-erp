package com.fatoora.api.payment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Money received against an issued invoice. Internal bookkeeping only; nothing here goes to ZATCA. */
@Entity
@Table(name = "invoice_payment")
@Getter
@Setter
public class InvoicePayment {

    public enum Method { CASH, BANK_TRANSFER, CARD, CHEQUE, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;
    private Long invoiceId;
    private BigDecimal amount;
    private LocalDate paidOn;

    @Enumerated(EnumType.STRING)
    private Method method = Method.CASH;

    private String reference;
    private String note;
    private String createdBy;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
