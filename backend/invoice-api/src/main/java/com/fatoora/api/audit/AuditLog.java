package com.fatoora.api.audit;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;
    private String actor;
    private String action;
    private String entityType;
    private Long entityId;
    private String details;
    private Instant createdAt = Instant.now();
}
