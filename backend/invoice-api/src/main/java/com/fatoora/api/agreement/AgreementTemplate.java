package com.fatoora.api.agreement;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** The organization's standard agreement text, edited by the admin. */
@Entity
@Table(name = "agreement_template")
@Getter
@Setter
public class AgreementTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;
    private String title;
    private String body;
    private String titleAr;
    private String bodyAr;
    private String updatedBy;
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
