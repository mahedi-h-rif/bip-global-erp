package com.fatoora.api.account;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Money spent: what was bought or paid for. */
@Entity
@Table(name = "account_expense")
@Getter
@Setter
public class ExpenseEntry {

    public enum Category { MATERIALS, VEHICLE, FOOD, GOVERNMENT, SALARY, RENT_UTILITIES, OFFICE, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;
    private LocalDate entryDate;
    private String description;

    @Enumerated(EnumType.STRING)
    private Category category = Category.OTHER;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private AccountSource source = AccountSource.MANUAL;

    private String createdBy;
    private Instant createdAt = Instant.now();
}
