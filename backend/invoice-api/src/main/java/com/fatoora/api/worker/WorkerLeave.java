package com.fatoora.api.worker;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A worker's leave over a date range, with an optional salary deduction (salary cut) for the leave. */
@Entity
@Table(name = "worker_leave")
@Getter
@Setter
public class WorkerLeave {

    public enum Type { ANNUAL, SICK, UNPAID, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long workerId;
    private LocalDate startDate;
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    private Type type = Type.ANNUAL;

    /** Optional salary cut for this leave; null means no deduction. */
    private BigDecimal deduction;

    private String notes;
    private String createdBy;
    private Instant createdAt = Instant.now();
    private String updatedBy;
    private Instant updatedAt;
}
