package com.fatoora.api.worker;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

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
    private boolean active = true;
    /** Order on the ledger, as in the company's register. */
    private int position;
    private Instant createdAt = Instant.now();
}
