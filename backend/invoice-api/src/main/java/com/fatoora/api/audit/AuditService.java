package com.fatoora.api.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    /** Written in its own transaction so failed business operations are still recorded. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long organizationId, String action, String entityType, Long entityId, String details) {
        recordAs(currentActor(), organizationId, action, entityType, entityId, details);
    }

    /** For events before anyone is signed in (a sign-in itself): {@code actor} is the login it concerns. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAs(String actor, Long organizationId, String action, String entityType, Long entityId, String details) {
        AuditLog log = new AuditLog();
        log.setOrganizationId(organizationId);
        log.setActor(actor);
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setDetails(details == null ? null : details.length() > 4000 ? details.substring(0, 4000) : details);
        repo.save(log);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> list(Long organizationId, int page, int size) {
        return list(organizationId, null, null, null, null, page, size);
    }

    /**
     * The log, newest first, optionally narrowed to one user ({@code actor}, their login email), an action or
     * text ({@code q}, matched in the action, record type and details) and a time window [from, to).
     */
    @Transactional(readOnly = true)
    public Page<AuditLog> list(Long organizationId, String actor, String q, java.time.Instant from, java.time.Instant to,
                               int page, int size) {
        String pattern = blankToNull(q) == null ? "%" : "%" + q.strip().toLowerCase(java.util.Locale.ROOT) + "%";
        return repo.search(organizationId, actor == null ? "" : actor.strip(), pattern,
                from == null ? java.time.Instant.EPOCH : from,
                to == null ? java.time.Instant.parse("9999-01-01T00:00:00Z") : to,
                PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated()
                || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken ? "system" : auth.getName();
    }
}
