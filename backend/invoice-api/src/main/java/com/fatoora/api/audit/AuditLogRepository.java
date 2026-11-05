package com.fatoora.api.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    Page<AuditLog> findByOrganizationId(Long organizationId, Pageable pageable);

    /** [action, count] of the log entries in [from, to). */
    @Query("select a.action, count(a) from AuditLog a where a.organizationId = :orgId "
            + "and a.createdAt >= :from and a.createdAt < :to group by a.action")
    List<Object[]> countActions(Long orgId, Instant from, Instant to);

    /** How many different users did something in [from, to). */
    @Query("select count(distinct a.actor) from AuditLog a where a.organizationId = :orgId "
            + "and a.createdAt >= :from and a.createdAt < :to and a.actor <> 'system'")
    long countActors(Long orgId, Instant from, Instant to);
}
