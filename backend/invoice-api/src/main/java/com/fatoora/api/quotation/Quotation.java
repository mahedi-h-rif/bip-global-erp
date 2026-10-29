package com.fatoora.api.quotation;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** A price offer to a customer. Internal only: it is never signed or sent to ZATCA, and can always be changed. */
@Entity
@Table(name = "quotation")
@Getter
@Setter
public class Quotation {

    public enum Status { DRAFT, SENT, ACCEPTED, DECLINED, INVOICED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;
    private String number;
    private Long customerId;
    /** For a prospect who isn't a saved customer yet. */
    private String clientName;
    private LocalDate issueDate;
    private LocalDate validUntil;

    @Enumerated(EnumType.STRING)
    private Status status = Status.DRAFT;

    private String notes;
    private String terms;
    private BigDecimal totalExclVat = BigDecimal.ZERO;
    private BigDecimal vatTotal = BigDecimal.ZERO;
    private BigDecimal totalInclVat = BigDecimal.ZERO;
    /** The invoice draft this quotation was converted into. */
    private Long invoiceId;
    private String createdBy;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "quotation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<QuotationLine> lines = new ArrayList<>();

    public void replaceLines(List<QuotationLine> next) {
        lines.clear();
        int i = 0;
        for (QuotationLine l : next) {
            l.setQuotation(this);
            l.setPosition(i++);
            lines.add(l);
        }
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
