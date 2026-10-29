package com.fatoora.api.invoice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ZatcaSubmissionRepository extends JpaRepository<ZatcaSubmission, Long> {
    List<ZatcaSubmission> findByInvoiceIdOrderByCreatedAtAsc(Long invoiceId);

    List<ZatcaSubmission> findByEgsUnitIdAndInvoiceIdIsNullOrderByCreatedAtAsc(Long egsUnitId);
}
