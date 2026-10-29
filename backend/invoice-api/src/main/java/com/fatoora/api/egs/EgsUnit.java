package com.fatoora.api.egs;

import com.fatoora.zatca.ZatcaEnvironment;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * A registered invoicing device. Owns a gap-free chain: every document it signs gets ICV = previous + 1
 * and PIH = hash of the previous document. {@code icv} and {@code lastInvoiceHash} are only changed
 * while the row is locked (see {@link EgsUnitRepository#lockById}).
 */
@Entity
@Table(name = "egs_unit")
@Getter
@Setter
public class EgsUnit {

    public enum Status { NEW, COMPLIANCE, ACTIVE, FAILED, RETIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;
    private String name;
    private String serialNumber;
    private String branch;
    private String location;
    private String industry;
    private String invoiceTypes = "1100";

    @Enumerated(EnumType.STRING)
    private ZatcaEnvironment environment;

    @Enumerated(EnumType.STRING)
    private Status status = Status.NEW;

    private long icv;
    private String lastInvoiceHash;
    private boolean isDefault;
    private Instant activatedAt;
    private Instant createdAt = Instant.now();
}
