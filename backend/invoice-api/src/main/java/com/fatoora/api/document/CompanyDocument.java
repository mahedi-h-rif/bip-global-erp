package com.fatoora.api.document;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** A scanned document of the company or of one of its workers, watched for its expiry date. */
@Entity
@Table(name = "document")
@Getter
@Setter
public class CompanyDocument {

    public enum Type { IQAMA, PASSPORT, WORK_PERMIT, DRIVING_LICENSE, CR, MUNICIPAL_LICENSE, VAT_CERTIFICATE, CHAMBER, INSURANCE, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;
    /** Empty for a company document. */
    private Long workerId;

    @Enumerated(EnumType.STRING)
    private Type type;

    private String title;
    private String number;
    private String holderName;
    private String nationality;
    private LocalDate issueDate;
    private LocalDate expiryDate;
    private String notes;
    private boolean remindersEnabled = true;
    private boolean remindAfterExpiry = true;
    /** Day the last expiry reminder went out; cleared when the expiry date changes (renewal). */
    private LocalDate lastReminderOn;

    private String fileType;
    private String fileName;
    private Integer fileSize;
    private String ocrText;
    private String createdBy;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
