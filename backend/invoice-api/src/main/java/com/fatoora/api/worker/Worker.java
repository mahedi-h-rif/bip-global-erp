package com.fatoora.api.worker;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** An outside worker the company pays for work done, month by month. */
@Entity
@Table(name = "worker")
@Getter
@Setter
public class Worker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;
    private String name;
    private String phone;
    private String iqamaNumber;
    private String nationality;
    private String notes;
    /** A non-permanent, daily-wage worker: cost is days worked × the daily rate, set on the worker. */
    private boolean daily = false;
    /** Daily rate for a daily-wage worker. */
    private BigDecimal dailyRate;
    /** Days worked for a daily-wage worker; work total = days × dailyRate, posted to the current month. */
    private BigDecimal days;
    private boolean active = true;
    /** Order on the ledger, as in the company's register. */
    private int position;
    private Instant createdAt = Instant.now();
}
