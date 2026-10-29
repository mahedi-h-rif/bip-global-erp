package com.fatoora.api.account;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** What a partner may draw in one year. */
@Entity
@Table(name = "partner_entitlement")
@Getter
@Setter
public class PartnerEntitlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long partnerId;
    private int year;
    private BigDecimal amount;
}
