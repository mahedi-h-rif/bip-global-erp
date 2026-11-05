package com.fatoora.api.worker;

import com.fatoora.api.payment.InvoicePayment;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One worker's figures for one day; the month total in {@link WorkerMonth} includes them. */
@Entity
@Table(name = "worker_day")
@Getter
@Setter
public class WorkerDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long workerId;
    private LocalDate entryDate;
    private BigDecimal work;
    private BigDecimal paid;

    /** How the day's payment was made: cash, card, etc. */
    @Enumerated(EnumType.STRING)
    private InvoicePayment.Method method;

    private String notes;
    private String createdBy;
    private Instant createdAt = Instant.now();
    private String updatedBy;
    private Instant updatedAt;
}
