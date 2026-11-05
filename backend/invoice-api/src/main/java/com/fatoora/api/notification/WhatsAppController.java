package com.fatoora.api.notification;

import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.organization.Organization;
import com.fatoora.api.organization.OrganizationRepository;
import com.fatoora.api.user.AppUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The company's WhatsApp number, linked by scanning a QR: its status for everyone (so staff know whether
 * messages go out directly), and linking, unlinking and settings for the admin.
 */
@RestController
@RequestMapping("/api/v1/whatsapp")
public class WhatsAppController {

    /** @param qr PNG data URL to scan; only sent to admins */
    public record StatusDto(boolean configured, String status, String qr, String phone, String name, String lastError,
                            boolean reminders, boolean documentReminders) {}

    public record SettingsRequest(boolean reminders, boolean documentReminders) {}

    public record TestRequest(String phone) {}

    private final WhatsAppGateway gateway;
    private final OrganizationRepository orgs;
    private final AuditService audit;

    public WhatsAppController(WhatsAppGateway gateway, OrganizationRepository orgs, AuditService audit) {
        this.gateway = gateway;
        this.orgs = orgs;
        this.audit = audit;
    }

    @GetMapping("/status")
    StatusDto status() {
        return dto(gateway.status());
    }

    /** Reconnects the saved session, or shows a new QR to scan. */
    @PostMapping("/connect")
    @PreAuthorize("hasRole('ADMIN')")
    StatusDto connect() {
        requireConfigured();
        return dto(gateway.connect());
    }

    @PostMapping("/disconnect")
    @PreAuthorize("hasRole('ADMIN')")
    StatusDto disconnect() {
        requireConfigured();
        audit.record(CurrentUser.orgId(), "WHATSAPP_DISCONNECTED", "whatsapp", null, null);
        return dto(gateway.disconnect());
    }

    /** Unlinks the number; linking again needs a new QR scan. */
    @PostMapping("/logout")
    @PreAuthorize("hasRole('ADMIN')")
    StatusDto logout() {
        requireConfigured();
        WhatsAppGateway.Status before = gateway.status();
        audit.record(CurrentUser.orgId(), "WHATSAPP_UNLINKED", "whatsapp", null, before.phone());
        return dto(gateway.logout());
    }

    @PutMapping("/settings")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    StatusDto settings(@RequestBody SettingsRequest req) {
        Organization org = org();
        org.setWhatsappReminders(req.reminders());
        org.setWhatsappDocumentReminders(req.documentReminders());
        orgs.save(org);
        audit.record(org.getId(), "WHATSAPP_SETTINGS_UPDATED", "whatsapp", null,
                "reminders " + req.reminders() + ", document reminders " + req.documentReminders());
        return dto(gateway.status());
    }

    /** A short test message, to check the link works. */
    @PostMapping("/test")
    @PreAuthorize("hasRole('ADMIN')")
    SendResult test(@RequestBody TestRequest req) {
        requireConfigured();
        String to = PhoneNumbers.normalize(req.phone());
        if (to == null) throw ApiException.badRequest("no_mobile", "Enter a mobile number");
        Organization org = org();
        return gateway.sendText(to, "✅ Test message from " + (org.displayName() == null ? "the invoice portal" : org.displayName())
                + ": WhatsApp is connected.\nرسالة تجريبية: واتساب متصل.");
    }

    private StatusDto dto(WhatsAppGateway.Status s) {
        Organization org = org();
        boolean admin = CurrentUser.get().role() == AppUser.Role.ADMIN || CurrentUser.isOperator();
        return new StatusDto(gateway.configured(), s.status(), admin ? s.qr() : null, s.phone(), s.name(), s.lastError(),
                org.isWhatsappReminders(), org.isWhatsappDocumentReminders());
    }

    private Organization org() {
        return orgs.findById(CurrentUser.orgId()).orElseThrow();
    }

    private void requireConfigured() {
        if (!gateway.configured())
            throw ApiException.conflict("whatsapp_not_set_up", "WhatsApp is not set up on this server. Ask your provider to enable it.");
    }
}
