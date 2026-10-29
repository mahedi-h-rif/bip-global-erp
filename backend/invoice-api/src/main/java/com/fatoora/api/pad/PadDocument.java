package com.fatoora.api.pad;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** A free document (letter, notice, certificate) written on the company letterhead pad. */
@Entity
@Table(name = "pad_document")
@Getter
@Setter
public class PadDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;
    private String title;
    /** Formatted text from the editor, cleaned by {@link PadHtml}. */
    private String body;
    private String createdBy;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
