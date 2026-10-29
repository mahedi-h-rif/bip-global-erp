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
    private BigDecimal amount;
    private BigDecimal paid = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    private AccountSource source = AccountSource.MANUAL;

    private String createdBy;
    private Instant createdAt = Instant.now();
}
