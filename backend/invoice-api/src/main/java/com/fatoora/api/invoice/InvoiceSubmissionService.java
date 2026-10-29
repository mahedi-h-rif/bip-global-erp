package com.fatoora.api.invoice;

import com.fatoora.api.audit.AuditService;
import com.fatoora.api.egs.EgsService;
import com.fatoora.api.egs.EgsUnit;
import com.fatoora.api.egs.EgsUnitRepository;
import com.fatoora.api.egs.FatooraGateway;
import com.fatoora.api.egs.SigningMaterial;
import com.fatoora.zatca.api.FatooraClient;
import com.fatoora.zatca.api.FatooraResponse;
import com.fatoora.zatca.api.FatooraUnavailableException;
import com.fatoora.zatca.api.SubmissionResponse;
import com.fatoora.zatca.model.InvoiceKind;
import com.fatoora.zatca.sign.SignedInvoice;
import com.fatoora.zatca.xml.UblReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends signed documents to ZATCA and records the outcome.
 * <ul>
 *   <li>200 → CLEARED / REPORTED; 202 → accepted with warnings</li>
 *   <li>400 → REJECTED (stays in the chain; issue a corrected document)</li>
 *   <li>303 on clearance → ZATCA has switched clearance off: report instead</li>
 *   <li>network errors, 401, 429, 5xx → stays SIGNED and is retried with back-off</li>
 * </ul>
 * The HTTP call happens outside any database transaction.
 */
@Service
public class InvoiceSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceSubmissionService.class);

    private final InvoiceRepository invoices;
    private final ZatcaSubmissionRepository submissions;
    private final EgsUnitRepository units;
    private final EgsService egs;
    private final FatooraGateway gateway;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final AuditService audit;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public InvoiceSubmissionService(InvoiceRepository invoices, ZatcaSubmissionRepository submissions,
                                    EgsUnitRepository units, EgsService egs, FatooraGateway gateway,
                                    TransactionTemplate tx, JsonMapper json, AuditService audit) {
        this.invoices = invoices;
        this.submissions = submissions;
        this.units = units;
        this.egs = egs;
        this.gateway = gateway;
        this.tx = tx;
        this.json = json;
        this.audit = audit;
    }

    /** Submits one signed document. Returns its status afterwards. */
    public InvoiceStatus submit(Long invoiceId) {
        if (!inFlight.add(invoiceId)) {
            return invoices.findById(invoiceId).map(Invoice::getStatus).orElse(null);
        }
        try {
            Invoice inv = invoices.findById(invoiceId).orElseThrow();
            if (inv.getStatus() != InvoiceStatus.SIGNED) return inv.getStatus();

            EgsUnit unit = units.findById(inv.getEgsUnitId()).orElseThrow();
            SigningMaterial material = egs.signingMaterial(unit.getId());
            FatooraClient client = gateway.client(unit.getEnvironment());
            SignedInvoice signed = new SignedInvoice(inv.getSignedXml(), inv.getInvoiceHash(), null, inv.getQrCode(), inv.getUuid());

            String endpoint;
            FatooraResponse<SubmissionResponse> res;
            try {
                if (inv.getKind() == InvoiceKind.STANDARD) {
                    endpoint = "clearance";
                    res = client.clear(signed, material.credentials());
                    if (res.status() == 303) {
                        submissions.save(new ZatcaSubmission(inv.getId(), unit.getId(), endpoint, 303, "CLEARANCE_OFF", res.raw()));
                        endpoint = "reporting";
                        res = client.report(signed, material.credentials());
                    }
                } else {
                    endpoint = "reporting";
                    res = client.report(signed, material.credentials());
                }
            } catch (FatooraUnavailableException e) {
                endpoint = inv.getKind() == InvoiceKind.STANDARD ? "clearance" : "reporting";
                res = new FatooraResponse<>(0, null, e.getMessage());
            }

            final String ep = endpoint;
            final FatooraResponse<SubmissionResponse> result = res;
            return tx.execute(status -> apply(invoiceId, unit.getId(), ep, result));
        } finally {
            inFlight.remove(invoiceId);
        }
    }

    private InvoiceStatus apply(Long invoiceId, Long unitId, String endpoint, FatooraResponse<SubmissionResponse> res) {
        Invoice inv = invoices.findById(invoiceId).orElseThrow();
        inv.setSubmitAttempts(inv.getSubmitAttempts() + 1);
        SubmissionResponse body = res.body();
        String outcome;

        if (res.accepted() && body != null) {
            boolean cleared = "clearance".equals(endpoint);
            boolean warnings = res.status() == 202 || !body.warnings().isEmpty();
            inv.setStatus(cleared
                    ? (warnings ? InvoiceStatus.CLEARED_WITH_WARNINGS : InvoiceStatus.CLEARED)
                    : (warnings ? InvoiceStatus.REPORTED_WITH_WARNINGS : InvoiceStatus.REPORTED));
            inv.setZatcaWarnings(body.warnings().isEmpty() ? null : json.writeValueAsString(body.warnings()));
            inv.setSubmittedAt(Instant.now());
            inv.setScheduledSubmitAt(null);
            inv.setLastError(null);
            if (cleared && body.clearedInvoiceXml() != null) {
                String clearedXml = body.clearedInvoiceXml();
                inv.setClearedXml(clearedXml);
                UblReader.qrCode(clearedXml).ifPresent(inv::setQrCode);
            }
            outcome = inv.getStatus().name();
        } else if (res.status() == 400) {
            inv.setStatus(InvoiceStatus.REJECTED);
            inv.setZatcaErrors(body == null ? res.raw() : json.writeValueAsString(body.errors()));
            inv.setZatcaWarnings(body == null || body.warnings().isEmpty() ? null : json.writeValueAsString(body.warnings()));
            inv.setScheduledSubmitAt(null);
            outcome = "REJECTED";
        } else {
            // Transient: keep SIGNED and retry with exponential back-off (1, 2, 4 … max 30 minutes).
            long minutes = Math.min(30, 1L << Math.min(inv.getSubmitAttempts() - 1, 5));
            inv.setScheduledSubmitAt(res.status() == 409 ? null : Instant.now().plus(Duration.ofMinutes(minutes)));
            inv.setLastError(res.status() == 0 ? res.raw() : "HTTP " + res.status() + ": " + truncate(res.raw()));
            outcome = "RETRY";
        }

        submissions.save(new ZatcaSubmission(inv.getId(), unitId, endpoint, res.status(), outcome, res.raw()));
        audit.record(inv.getOrganizationId(), "ZATCA_" + endpoint.toUpperCase(), "invoice", inv.getId(),
                inv.getNumber() + " → " + outcome);
        log.info("Invoice {} {} → HTTP {} {}", inv.getNumber(), endpoint, res.status(), outcome);
        return inv.getStatus();
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 1500 ? s.substring(0, 1500) : s;
    }
}
