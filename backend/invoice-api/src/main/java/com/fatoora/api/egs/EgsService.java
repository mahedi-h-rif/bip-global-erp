package com.fatoora.api.egs;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.config.SecretCipher;
import com.fatoora.api.invoice.ZatcaSubmission;
import com.fatoora.api.invoice.ZatcaSubmissionRepository;
import com.fatoora.api.organization.Organization;
import com.fatoora.api.organization.OrganizationRepository;
import com.fatoora.zatca.api.CsidResponse;
import com.fatoora.zatca.api.FatooraClient;
import com.fatoora.zatca.api.FatooraResponse;
import com.fatoora.zatca.api.FatooraUnavailableException;
import com.fatoora.zatca.api.SubmissionResponse;
import com.fatoora.zatca.crypto.EcKeys;
import com.fatoora.zatca.crypto.Hashing;
import com.fatoora.zatca.crypto.ZatcaCertificate;
import com.fatoora.zatca.csr.CsrConfig;
import com.fatoora.zatca.csr.CsrGenerator;
import com.fatoora.zatca.model.Address;
import com.fatoora.zatca.model.DocumentType;
import com.fatoora.zatca.model.InvoiceDocument;
import com.fatoora.zatca.model.InvoiceKind;
import com.fatoora.zatca.model.InvoiceLine;
import com.fatoora.zatca.model.Party;
import com.fatoora.zatca.sign.InvoiceSigner;
import com.fatoora.zatca.sign.SignedInvoice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Registers EGS units with ZATCA:
 * CSR → compliance CSID (OTP) → compliance sample documents → production CSID, and later renewal.
 * <p>
 * Network calls are made outside database transactions; each write is its own short transaction.
 */
@Service
public class EgsService {

    public static final String SOLUTION_SERIAL_PREFIX = "1-FatooraPlatform|2-Portal-1.0|3-";

    /** One onboarding step. {@code warning}: it worked, but something needs the user's attention. */
    public record Step(String name, boolean ok, String detail, boolean warning) {
        public Step(String name, boolean ok, String detail) {
            this(name, ok, detail, false);
        }
    }

    public record OnboardingResult(Long egsUnitId, EgsUnit.Status status, List<Step> steps) {}

    private final EgsUnitRepository units;
    private final EgsCredentialRepository credentials;
    private final OrganizationRepository orgs;
    private final ZatcaSubmissionRepository submissions;
    private final FatooraGateway gateway;
    private final SecretCipher cipher;
    private final AuditService audit;

    public EgsService(EgsUnitRepository units, EgsCredentialRepository credentials, OrganizationRepository orgs,
                      ZatcaSubmissionRepository submissions, FatooraGateway gateway, SecretCipher cipher, AuditService audit) {
        this.units = units;
        this.credentials = credentials;
        this.orgs = orgs;
        this.submissions = submissions;
        this.gateway = gateway;
        this.cipher = cipher;
        this.audit = audit;
    }

    @Transactional
    public EgsUnit create(Long orgId, String name, String branch, String location, String industry, String invoiceTypes) {
        Organization org = orgs.findById(orgId).orElseThrow(() -> ApiException.notFound("Organization"));
        EgsUnit unit = new EgsUnit();
        unit.setOrganizationId(orgId);
        unit.setName(name);
        unit.setBranch(branch);
        unit.setLocation(location);
        unit.setIndustry(industry);
        unit.setInvoiceTypes(invoiceTypes == null ? "1100" : invoiceTypes);
        unit.setEnvironment(org.getZatcaEnvironment());
        unit.setSerialNumber(SOLUTION_SERIAL_PREFIX + UUID.randomUUID());
        unit.setLastInvoiceHash(Hashing.INITIAL_PIH);
        units.save(unit);
        audit.record(orgId, "EGS_CREATED", "egs_unit", unit.getId(), unit.getName() + " / " + unit.getEnvironment());
        return unit;
    }

    /** Runs the full onboarding with an OTP from the Fatoora portal. Safe to re-run after a failure. */
    public OnboardingResult onboard(Long orgId, Long unitId, String otp) {
        EgsUnit unit = units.findByIdAndOrganizationId(unitId, orgId).orElseThrow(() -> ApiException.notFound("EGS unit"));
        if (unit.getStatus() == EgsUnit.Status.ACTIVE)
            throw ApiException.conflict("already_active", "This EGS unit is already onboarded. Use renew instead.");
        Organization org = orgs.findById(orgId).orElseThrow();
        FatooraClient client = gateway.client(unit.getEnvironment());
        List<Step> steps = new ArrayList<>();

        // 1. Key pair + CSR
        CsrConfig cfg = csrConfig(unit, org);
        CsrGenerator.Result csr = CsrGenerator.generate(cfg, unit.getEnvironment());
        steps.add(new Step("Generate key pair and CSR", true, unit.getEnvironment().certificateTemplate()));

        // 2. Compliance CSID
        FatooraResponse<CsidResponse> ccsid = call(() -> client.requestComplianceCsid(csr.csrBase64(), otp));
        record(unit, "/compliance", ccsid);
        if (!ccsid.accepted() || ccsid.body() == null || ccsid.body().binarySecurityToken() == null) {
            return fail(unit, steps, "Compliance CSID", describe(ccsid));
        }
        EgsCredential compliance = saveCredential(unit, EgsCredential.Kind.COMPLIANCE, ccsid.body(), csr.privateKeyPem());
        setStatus(unit, EgsUnit.Status.COMPLIANCE);
        steps.add(new Step("Compliance CSID", true, "Request " + ccsid.body().requestID()));

        // 3. Compliance samples: every document type this unit is allowed to issue
        var creds = new FatooraClient.Credentials(ccsid.body().binarySecurityToken(), ccsid.body().secret());
        var cert = ZatcaCertificate.fromBinarySecurityToken(ccsid.body().binarySecurityToken());
        var key = EcKeys.privateKeyFromPem(csr.privateKeyPem());
        String pih = Hashing.INITIAL_PIH;
        long icv = 1;
        for (InvoiceKind kind : kinds(cfg)) {
            for (DocumentType type : DocumentType.values()) {
                SignedInvoice signed = InvoiceSigner.sign(sample(org, kind, type, icv++, pih), cert, key, now());
                FatooraResponse<SubmissionResponse> res = call(() -> client.checkCompliance(signed, creds));
                record(unit, "/compliance/invoices " + kind + " " + type, res);
                String label = "Compliance check: " + kind.name().toLowerCase() + " " + type.name().toLowerCase().replace('_', ' ');
                if (!res.accepted()) return fail(unit, steps, label, describe(res));
                steps.add(new Step(label, true, res.body() == null ? "" : res.body().warnings().isEmpty() ? "PASS"
                        : res.body().warnings().size() + " warning(s)"));
                pih = signed.invoiceHash();
            }
        }

        // 4. Production CSID
        FatooraResponse<CsidResponse> pcsid = call(() -> client.requestProductionCsid(compliance.getRequestId(), creds));
        record(unit, "/production/csids", pcsid);
        if (!pcsid.accepted() || pcsid.body() == null || pcsid.body().binarySecurityToken() == null) {
            return fail(unit, steps, "Production CSID", describe(pcsid));
        }
        saveCredential(unit, EgsCredential.Kind.PRODUCTION, pcsid.body(), csr.privateKeyPem());
        activate(unit.getId());
        String certVat = ZatcaCertificate.fromBinarySecurityToken(pcsid.body().binarySecurityToken()).vatNumber().orElse(null);
        if (certVat != null && !certVat.equals(org.getVatNumber())) {
            steps.add(new Step("Production CSID", true,
                    "Issued for VAT " + certVat + ", not your VAT " + org.getVatNumber() + ". The developer sandbox gives everyone "
                            + "the same test certificate, so only invoices for VAT " + certVat + " can be cleared here. "
                            + "Use Simulation to test with your real VAT.", true));
        } else {
            steps.add(new Step("Production CSID", true, "Device is ready to issue invoices"));
        }
        audit.record(orgId, "EGS_ONBOARDED", "egs_unit", unit.getId(), unit.getEnvironment().name());
        return new OnboardingResult(unit.getId(), EgsUnit.Status.ACTIVE, steps);
    }

    /** Renews the production CSID (new key pair + CSR, fresh OTP). The ICV/PIH chain continues. */
    public OnboardingResult renew(Long orgId, Long unitId, String otp) {
        EgsUnit unit = units.findByIdAndOrganizationId(unitId, orgId).orElseThrow(() -> ApiException.notFound("EGS unit"));
        if (unit.getStatus() != EgsUnit.Status.ACTIVE)
            throw ApiException.conflict("not_active", "Only an active EGS unit can be renewed");
        Organization org = orgs.findById(orgId).orElseThrow();
        SigningMaterial current = signingMaterial(unit.getId());

        CsrGenerator.Result csr = CsrGenerator.generate(csrConfig(unit, org), unit.getEnvironment());
        FatooraResponse<CsidResponse> res = call(() ->
                gateway.client(unit.getEnvironment()).renewProductionCsid(csr.csrBase64(), otp, current.credentials()));
        record(unit, "PATCH /production/csids", res);
        if (!res.accepted() || res.body() == null || res.body().binarySecurityToken() == null) {
            throw ApiException.badRequest("renewal_failed", describe(res));
        }
        replaceProductionCredential(unit, res.body(), csr.privateKeyPem());
        audit.record(orgId, "EGS_RENEWED", "egs_unit", unit.getId(), null);
        return new OnboardingResult(unit.getId(), EgsUnit.Status.ACTIVE,
                List.of(new Step("Production CSID renewed", true, "New certificate is active")));
    }

    /** Decrypts the active production credentials of a unit. */
    @Transactional(readOnly = true)
    public SigningMaterial signingMaterial(Long unitId) {
        EgsCredential c = credentials.findFirstByEgsUnitIdAndKindAndActiveTrueOrderByCreatedAtDesc(unitId, EgsCredential.Kind.PRODUCTION)
                .orElseThrow(() -> ApiException.conflict("no_production_csid", "EGS unit has no production CSID"));
        return new SigningMaterial(
                ZatcaCertificate.fromBinarySecurityToken(c.getBinarySecurityToken()),
                EcKeys.privateKeyFromPem(cipher.decrypt(c.getPrivateKeyEnc())),
                new FatooraClient.Credentials(c.getBinarySecurityToken(), cipher.decrypt(c.getSecretEnc())));
    }

    @Transactional(readOnly = true)
    public List<EgsCredential> activeProductionCredentials(Long unitId) {
        return credentials.findByEgsUnitIdAndKindAndActiveTrue(unitId, EgsCredential.Kind.PRODUCTION);
    }

    @Transactional
    public void makeDefault(Long orgId, Long unitId) {
        List<EgsUnit> all = units.findByOrganizationIdOrderByCreatedAtDesc(orgId);
        EgsUnit target = all.stream().filter(u -> u.getId().equals(unitId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("EGS unit"));
        if (target.getStatus() != EgsUnit.Status.ACTIVE)
            throw ApiException.conflict("not_active", "Only an active EGS unit can be the default");
        all.forEach(u -> u.setDefault(u.getId().equals(unitId)));
    }

    // --- helpers -----------------------------------------------------------------------------

    private static CsrConfig csrConfig(EgsUnit unit, Organization org) {
        String orgName = org.getLegalNameEn() != null && !org.getLegalNameEn().isBlank() ? org.getLegalNameEn() : org.getLegalNameAr();
        return new CsrConfig(unit.getName(), unit.getSerialNumber(), org.getVatNumber(), unit.getBranch(), orgName,
                unit.getInvoiceTypes(), unit.getLocation(), unit.getIndustry());
    }

    private static List<InvoiceKind> kinds(CsrConfig cfg) {
        List<InvoiceKind> kinds = new ArrayList<>();
        if (cfg.issuesStandard()) kinds.add(InvoiceKind.STANDARD);
        if (cfg.issuesSimplified()) kinds.add(InvoiceKind.SIMPLIFIED);
        return kinds;
    }

    /** A realistic sample document with the organization as seller, used only for compliance checks. */
    static InvoiceDocument sample(Organization org, InvoiceKind kind, DocumentType type, long icv, String pih) {
        Party buyer = new Party("Compliance Sample Buyer", "399999999800003", null, null,
                new Address("King Abdulaziz Rd", "1234", "5678", "Al Rayyan", "Buraydah", "52365", "SA"));
        boolean note = type != DocumentType.INVOICE;
        return new InvoiceDocument("SAMPLE-" + icv, UUID.randomUUID(), now().minusMinutes(1), LocalDate.now(InvoiceApiApplication.KSA),
                kind, type, "SAR", note ? "SAMPLE-1" : null, note ? "Compliance sample adjustment" : null, "10",
                org.toParty(), kind == InvoiceKind.STANDARD ? buyer : null,
                List.of(InvoiceLine.standardRated("Compliance sample item", BigDecimal.ONE, "PCE", new BigDecimal("100"))),
                icv, pih);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(InvoiceApiApplication.KSA).truncatedTo(ChronoUnit.SECONDS);
    }

    private <T> FatooraResponse<T> call(java.util.function.Supplier<FatooraResponse<T>> fn) {
        try {
            return fn.get();
        } catch (FatooraUnavailableException e) {
            return new FatooraResponse<>(0, null, e.getMessage());
        }
    }

    private OnboardingResult fail(EgsUnit unit, List<Step> steps, String step, String detail) {
        steps.add(new Step(step, false, detail));
        setStatus(unit, EgsUnit.Status.FAILED);
        audit.record(unit.getOrganizationId(), "EGS_ONBOARDING_FAILED", "egs_unit", unit.getId(), step + ": " + detail);
        return new OnboardingResult(unit.getId(), EgsUnit.Status.FAILED, steps);
    }

    static String describe(FatooraResponse<?> res) {
        if (res.status() == 0) return "Fatoora is unreachable: " + res.raw();
        if (res.body() instanceof SubmissionResponse s && !s.errors().isEmpty()) {
            return s.errors().stream().map(m -> m.code() + ": " + m.message()).reduce((a, b) -> a + "; " + b).orElse("");
        }
        String raw = res.raw() == null ? "" : res.raw();
        return "HTTP " + res.status() + (raw.isBlank() ? "" : " " + (raw.length() > 500 ? raw.substring(0, 500) : raw));
    }

    private void record(EgsUnit unit, String endpoint, FatooraResponse<?> res) {
        submissions.save(new ZatcaSubmission(null, unit.getId(), endpoint, res.status(),
                res.accepted() ? "ACCEPTED" : "FAILED", res.raw()));
    }

    private EgsCredential saveCredential(EgsUnit unit, EgsCredential.Kind kind, CsidResponse res, String privateKeyPem) {
        EgsCredential c = new EgsCredential();
        c.setEgsUnitId(unit.getId());
        c.setKind(kind);
        c.setBinarySecurityToken(res.binarySecurityToken());
        c.setSecretEnc(cipher.encrypt(res.secret()));
        c.setPrivateKeyEnc(cipher.encrypt(privateKeyPem));
        c.setRequestId(res.requestID());
        c.setCertificateNotAfter(notAfter(res.binarySecurityToken()));
        return credentials.save(c);
    }

    private static Instant notAfter(String token) {
        try {
            return ZatcaCertificate.fromBinarySecurityToken(token).notAfter();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void replaceProductionCredential(EgsUnit unit, CsidResponse res, String privateKeyPem) {
        List<EgsCredential> old = credentials.findByEgsUnitIdAndKindAndActiveTrue(unit.getId(), EgsCredential.Kind.PRODUCTION);
        saveCredential(unit, EgsCredential.Kind.PRODUCTION, res, privateKeyPem);
        old.forEach(c -> c.setActive(false));
        credentials.saveAll(old);
    }

    private void setStatus(EgsUnit unit, EgsUnit.Status status) {
        EgsUnit fresh = units.findById(unit.getId()).orElseThrow();
        fresh.setStatus(status);
        units.save(fresh);
        unit.setStatus(status);
    }

    private void activate(Long unitId) {
        EgsUnit fresh = units.findById(unitId).orElseThrow();
        fresh.setStatus(EgsUnit.Status.ACTIVE);
        fresh.setActivatedAt(Instant.now());
        // Compliance samples used their own chain; production documents start a fresh one.
        fresh.setIcv(0);
        fresh.setLastInvoiceHash(Hashing.INITIAL_PIH);
        units.save(fresh);
        if (units.findFirstByOrganizationIdAndStatusAndIsDefaultTrue(fresh.getOrganizationId(), EgsUnit.Status.ACTIVE).isEmpty()) {
            List<EgsUnit> all = units.findByOrganizationIdOrderByCreatedAtDesc(fresh.getOrganizationId());
            all.forEach(u -> u.setDefault(u.getId().equals(unitId)));
            units.saveAll(all);
        }
    }
}
