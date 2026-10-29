package com.fatoora.api.account;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** A partner of the company who draws money from it. */
@Entity
@Table(name = "partner")
@Getter
@Setter
public class Partner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;
    private String name;
    private boolean active = true;
    private Instant createdAt = Instant.now();
}
