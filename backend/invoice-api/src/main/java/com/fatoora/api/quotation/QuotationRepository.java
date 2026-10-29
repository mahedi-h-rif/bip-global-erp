package com.fatoora.api.quotation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface QuotationRepository extends JpaRepository<Quotation, Long> {

    Optional<Quotation> findByIdAndOrganizationId(Long id, Long organizationId);

    List<Quotation> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    /** Highest number used with this prefix, e.g. "QT-2026-". */
    @Query("select max(q.number) from Quotation q where q.organizationId = :orgId and q.number like concat(:prefix, '%')")
    String maxNumber(Long orgId, String prefix);
}
