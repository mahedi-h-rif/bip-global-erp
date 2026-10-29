package com.fatoora.api.worker;

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
}
