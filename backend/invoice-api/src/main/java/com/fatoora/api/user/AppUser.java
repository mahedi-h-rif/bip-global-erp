package com.fatoora.api.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "app_user")
@Getter
@Setter
public class AppUser {

    /**
     * OPERATOR is the e-invoicing provider (ZATCA onboarding, environment, legal identity) and inherits
     * everything below it. ADMIN / ACCOUNTANT / VIEWER are the client's own staff and never see ZATCA setup.
     */
    public enum Role { OPERATOR, ADMIN, ACCOUNTANT, VIEWER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;
    private String email;
    private String fullName;
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    private Role role;

    private boolean enabled = true;
    private String totpSecretEnc;
    private Instant lastLoginAt;
    private Instant createdAt = Instant.now();
}
