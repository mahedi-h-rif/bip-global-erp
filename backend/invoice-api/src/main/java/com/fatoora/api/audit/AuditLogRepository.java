package com.fatoora.api.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    Page<AuditLog> findByOrganizationId(Long organizationId, Pageable pageable);

    /**
     * Filtered log. {@code actor} "" matches everyone; {@code q} is a lower-case LIKE pattern ("%" for all);
     * the window is [from, to).
     */
    @Query("select a from AuditLog a where a.organizationId = :orgId "
            + "and (:actor = '' or lower(a.actor) = lower(:actor)) "
            + "and (lower(a.action) like :q or lower(coalesce(a.entityType, '')) like :q "
            + "     or lower(coalesce(a.details, '')) like :q) "
            + "and a.createdAt >= :from and a.createdAt < :to")
    Page<AuditLog> search(Long orgId, String actor, String q, Instant from, Instant to, Pageable pageable);

    /** [actor, count, last time] of each user's log entries since {@code from}. */
    @Query("select lower(a.actor), count(a), max(a.createdAt) from AuditLog a where a.organizationId = :orgId "
            + "and a.createdAt >= :from and a.actor is not null group by lower(a.actor)")
    List<Object[]> activityByActor(Long orgId, Instant from);

    /** [action, count] of the log entries in [from, to). */
    @Query("select a.action, count(a) from AuditLog a where a.organizationId = :orgId "
            + "and a.createdAt >= :from and a.createdAt < :to group by a.action")
    List<Object[]> countActions(Long orgId, Instant from, Instant to);

    /** How many different users did something in [from, to). */
    @Query("select count(distinct a.actor) from AuditLog a where a.organizationId = :orgId "
            + "and a.createdAt >= :from and a.createdAt < :to and a.actor <> 'system'")
    long countActors(Long orgId, Instant from, Instant to);
}
