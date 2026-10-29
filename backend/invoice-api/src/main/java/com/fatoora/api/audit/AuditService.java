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
        AuditLog log = new AuditLog();
        log.setOrganizationId(organizationId);
        log.setActor(currentActor());
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setDetails(details == null ? null : details.length() > 4000 ? details.substring(0, 4000) : details);
        repo.save(log);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> list(Long organizationId, int page, int size) {
        return repo.findByOrganizationId(organizationId,
                PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    private static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated() ? "system" : auth.getName();
    }
}
