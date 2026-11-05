package com.fatoora.api.worker;

import com.fatoora.api.payment.InvoicePayment;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** One worker's account for one month: value of the work done and the amount paid. Either may be empty. */
@Entity
@Table(name = "worker_month")
@Getter
@Setter
public class WorkerMonth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long workerId;
    private int year;
    private int month;
    private BigDecimal work;
    private BigDecimal paid;

    /** For a daily-wage worker: days worked and the daily rate; work = days × rate. Null for permanent workers. */
    private BigDecimal days;
    private BigDecimal rate;

    /** How the salary was paid: cash, card, etc. Null when not recorded. */
    @Enumerated(EnumType.STRING)
    private InvoicePayment.Method paidMethod;
}
