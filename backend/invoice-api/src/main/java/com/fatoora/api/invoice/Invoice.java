package com.fatoora.api.invoice;

import com.fatoora.zatca.model.DocumentType;
import com.fatoora.zatca.model.InvoiceKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "invoice")
@Getter
@Setter
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;

    @Enumerated(EnumType.STRING)
    private InvoiceKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type")
    private DocumentType type;

    @Enumerated(EnumType.STRING)
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    private SendMode sendMode = SendMode.NOW;

    private String number;
    private UUID uuid;
    private Instant issuedAt;
    private LocalDate supplyDate;
    private String currency = "SAR";
    private String paymentMeans = "10";
    private Long customerId;
    private String buyerSnapshot;
    private String sellerSnapshot;
    private Long originalInvoiceId;
    private String billingReference;
    private String reason;
    private String internalNote;
    private LocalDate dueDate;
    private BigDecimal totalExclVat = BigDecimal.ZERO;
    private BigDecimal vatTotal = BigDecimal.ZERO;
    private BigDecimal totalInclVat = BigDecimal.ZERO;

    private Long egsUnitId;
    private Long icv;
    private String pih;
    private String invoiceHash;
    private String signedXml;
    private String clearedXml;
    private String qrCode;

    private Instant scheduledSubmitAt;
    private Instant reportDeadlineAt;
    private Instant submittedAt;
    private int submitAttempts;
    private String zatcaWarnings;
    private String zatcaErrors;
    private String lastError;

    private String createdBy;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("position")
    private List<InvoiceLineEntity> lines = new ArrayList<>();

    public void replaceLines(List<InvoiceLineEntity> newLines) {
        lines.clear();
        int pos = 1;
        for (InvoiceLineEntity l : newLines) {
            l.setInvoice(this);
            l.setPosition(pos++);
            lines.add(l);
        }
    }

    /** Legal copy to hand to the buyer: ZATCA's cleared XML for standard documents, our signed XML otherwise. */
    public String legalXml() {
        return clearedXml != null ? clearedXml : signedXml;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
