package com.fatoora.api.invoice;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One call to Fatoora, kept verbatim for the audit trail. */
@Entity
@Table(name = "zatca_submission")
@Getter
@Setter
@NoArgsConstructor
public class ZatcaSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long invoiceId;
    private Long egsUnitId;
    private String endpoint;
    private Integer httpStatus;
    private String outcome;
    private String responseBody;
    private Instant createdAt = Instant.now();

    public ZatcaSubmission(Long invoiceId, Long egsUnitId, String endpoint, Integer httpStatus, String outcome,
                           String responseBody) {
        this.invoiceId = invoiceId;
        this.egsUnitId = egsUnitId;
        this.endpoint = endpoint;
        this.httpStatus = httpStatus;
        this.outcome = outcome;
        this.responseBody = responseBody;
    }
}
