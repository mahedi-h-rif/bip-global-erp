package com.fatoora.api.document;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** An expiry reminder that went out for a document; unread ones show in the notification bell. */
@Entity
@Table(name = "document_alert")
@Getter
@Setter
public class DocumentAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;
    private Long documentId;
    private int daysLeft;
    private LocalDate expiryDate;
    private String message;
    private String emailStatus;
    private String emailRecipients;
    private Instant readAt;
    private String readBy;
    private Instant createdAt = Instant.now();
}
