package com.fatoora.api.common;

import com.fatoora.api.audit.AuditLog;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.invoice.InvoiceDtos;
import com.fatoora.api.invoice.InvoiceService;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class MiscController {

    private final InvoiceService invoices;
    private final AuditService audit;

    public MiscController(InvoiceService invoices, AuditService audit) {
        this.invoices = invoices;
        this.audit = audit;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "UP");
    }

    /** Business figures are for the client's admin; normal staff only work with invoices and dues. */
    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('ADMIN','BRANCH')")
    InvoiceDtos.Dashboard dashboard() {
        return invoices.dashboard(CurrentUser.orgId());
    }

    @GetMapping("/audit")
    @PreAuthorize("hasRole('ADMIN')")
    Page<AuditLog> audit(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
                         @RequestParam(required = false) String actor, @RequestParam(required = false) String q,
                         @RequestParam(required = false) java.time.LocalDate from,
                         @RequestParam(required = false) java.time.LocalDate to) {
        var ksa = com.fatoora.api.InvoiceApiApplication.KSA;
        return audit.list(CurrentUser.orgId(), actor, q,
                from == null ? null : from.atStartOfDay(ksa).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(ksa).toInstant(), page, size);
    }
}
