package com.fatoora.api.account;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A job billed to a customer in the cash book: the bill and what was received; due is the rest. */
@Entity
@Table(name = "account_income")
@Getter
@Setter
public class IncomeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;
    private LocalDate entryDate;
    private String customerName;
    private String mobile;
    private String details;
    /** Who received the money (free text; the staff member or branch who took it). */
    private String receivedBy;
    private BigDecimal amount;
    private BigDecimal paid = BigDecimal.ZERO;

    /** How the money moved: cash, card, bank transfer, cheque or other. */
    @Enumerated(EnumType.STRING)
    private com.fatoora.api.payment.InvoicePayment.Method method = com.fatoora.api.payment.InvoicePayment.Method.CASH;

    @Enumerated(EnumType.STRING)
    private AccountSource source = AccountSource.MANUAL;

    private String createdBy;
    private Instant createdAt = Instant.now();
    private String updatedBy;
    private Instant updatedAt;
}
