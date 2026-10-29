package com.fatoora.api.egs;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** A compliance or production CSID with its (encrypted) private key and secret. */
@Entity
@Table(name = "egs_credential")
@Getter
@Setter
public class EgsCredential {

    public enum Kind { COMPLIANCE, PRODUCTION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long egsUnitId;

    @Enumerated(EnumType.STRING)
    private Kind kind;

    private String binarySecurityToken;
    private String secretEnc;
    private String privateKeyEnc;
    private String requestId;
    private Instant certificateNotAfter;
    private boolean active = true;
    private Instant createdAt = Instant.now();
}
