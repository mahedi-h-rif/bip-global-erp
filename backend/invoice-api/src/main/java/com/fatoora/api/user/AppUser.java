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
     * BRANCH is a division's login (e.g. aluminium, laser): only invoices, quotations, agreements and its own
     * cash-book entries, and only the records it created itself. It sits outside the hierarchy on purpose.
     */
    public enum Role { OPERATOR, ADMIN, ACCOUNTANT, VIEWER, BRANCH }

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
    /** Per-user access: comma-separated feature keys (see FeatureCatalog); null when not on per-user access. */
    private String features;
    /** Per-user access: comma-separated actions (view, create, update, delete). */
    private String actions;
    /** Per-user access: sees and changes only the records this login created. */
    private boolean ownDataOnly;

    /** Admin-chosen access instead of a built-in role's: the features, actions and data scope above apply. */
    public boolean hasCustomAccess() {
        return features != null && role != Role.ADMIN && role != Role.OPERATOR;
    }
}
