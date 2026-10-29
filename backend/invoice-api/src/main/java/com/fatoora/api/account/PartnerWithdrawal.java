package com.fatoora.api.account;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Money a partner took out of the company. */
@Entity
@Table(name = "partner_withdrawal")
@Getter
@Setter
public class PartnerWithdrawal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;
    private Long partnerId;
    private LocalDate entryDate;
    private BigDecimal amount;
    private String reference;
    private String approvedBy;
    private String notes;

    @Enumerated(EnumType.STRING)
    private AccountSource source = AccountSource.MANUAL;

    private String createdBy;
    private Instant createdAt = Instant.now();
}
