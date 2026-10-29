package com.fatoora.api.agreement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AgreementRepository extends JpaRepository<Agreement, Long> {

    Optional<Agreement> findByIdAndOrganizationId(Long id, Long organizationId);

    List<Agreement> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    /** Highest number used with this prefix, e.g. "AGR-2026-". */
    @Query("select max(a.number) from Agreement a where a.organizationId = :orgId and a.number like concat(:prefix, '%')")
    String maxNumber(Long orgId, String prefix);
}
