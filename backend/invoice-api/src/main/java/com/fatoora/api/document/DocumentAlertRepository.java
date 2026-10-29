package com.fatoora.api.document;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface DocumentAlertRepository extends JpaRepository<DocumentAlert, Long> {

    Optional<DocumentAlert> findByIdAndOrganizationId(Long id, Long organizationId);

    List<DocumentAlert> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId, Pageable page);

    long countByOrganizationIdAndReadAtIsNull(Long organizationId);

    @Modifying
    @Query("update DocumentAlert a set a.readAt = :at, a.readBy = :by where a.organizationId = :orgId and a.readAt is null")
    int markAllRead(Long orgId, Instant at, String by);
}
