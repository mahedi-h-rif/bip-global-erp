package com.fatoora.api.agreement;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** An agreement with a client, started from the standard text and printed on the company letterhead. */
@Entity
@Table(name = "agreement")
@Getter
@Setter
public class Agreement {

    public enum Status { DRAFT, SENT, SIGNED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;
    private String number;
    private Long customerId;
    private String clientName;
    /** Free text printed under the client's name: ID, address, contact. */
    private String clientDetails;
    private String title;
    private String body;
    private LocalDate agreementDate;

    @Enumerated(EnumType.STRING)
    private Status status = Status.DRAFT;

    private LocalDate signedOn;
    private String createdBy;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
