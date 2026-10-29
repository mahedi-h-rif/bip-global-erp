package com.fatoora.api.invoice;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.config.FatooraProperties;
import com.fatoora.api.customer.Customer;
import com.fatoora.api.customer.CustomerRepository;
import com.fatoora.api.egs.EgsService;
import com.fatoora.api.egs.EgsUnit;
import com.fatoora.api.egs.EgsUnitRepository;
import com.fatoora.api.egs.SigningMaterial;
import com.fatoora.api.organization.Organization;
import com.fatoora.api.organization.OrganizationRepository;
import com.fatoora.zatca.model.Address;
import com.fatoora.zatca.model.DocumentType;
import com.fatoora.zatca.model.InvoiceDocument;
import com.fatoora.zatca.model.InvoiceKind;
import com.fatoora.zatca.model.Party;
import com.fatoora.zatca.sign.InvoiceSigner;
import com.fatoora.zatca.sign.SignedInvoice;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns a draft into a legally numbered, signed, chained document — all in one transaction that
 * holds a row lock on the EGS unit. If anything fails, the number, ICV and PIH are all rolled back
 * together, so the chain never has gaps.
 */
@Service
public class InvoiceSigningService {

    private static final Duration REPORTING_LIMIT = Duration.ofHours(24);

    private final InvoiceRepository invoices;
    private final OrganizationRepository orgs;
    private final CustomerRepository customers;
    private final EgsUnitRepository units;
    private final EgsService egs;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final FatooraProperties props;

    public InvoiceSigningService(InvoiceRepository invoices, OrganizationRepository orgs, CustomerRepository customers,
                                 EgsUnitRepository units, EgsService egs, JdbcTemplate jdbc, JsonMapper json,
                                 FatooraProperties props) {
        this.invoices = invoices;
        this.orgs = orgs;
        this.customers = customers;
        this.units = units;
        this.egs = egs;
        this.jdbc = jdbc;
        this.json = json;
        this.props = props;
    }

    @Transactional
    public Invoice sign(Long orgId, Long invoiceId, SendMode sendMode) {
        Invoice inv = invoices.findByIdAndOrganizationId(invoiceId, orgId).orElseThrow(() -> ApiException.notFound("Invoice"));
        if (inv.getStatus() != InvoiceStatus.DRAFT)
            throw ApiException.conflict("not_draft", "Only drafts can be issued");
        SendMode mode = sendMode == null ? SendMode.NOW : sendMode;
        if (inv.getKind() == InvoiceKind.STANDARD && mode == SendMode.LATER)
            throw ApiException.badRequest("standard_later",
                    "A standard (B2B) invoice is only valid once ZATCA clears it. Keep it as a draft to send later.");

        Organization org = orgs.findById(orgId).orElseThrow();
        Party seller = org.toParty();
        Party buyer = buyer(inv);
        validate(inv, seller, buyer);

        EgsUnit defaultUnit = units.findFirstByOrganizationIdAndStatusAndIsDefaultTrue(orgId, EgsUnit.Status.ACTIVE)
                .orElseThrow(() -> setupProblem("no_egs",
                        "No active ZATCA device. Onboard one under Settings → ZATCA devices first."));
        if (defaultUnit.getEnvironment() != org.getZatcaEnvironment())
            throw setupProblem("egs_environment",
                    "The default ZATCA device is registered in " + defaultUnit.getEnvironment()
                            + " but the organization uses " + org.getZatcaEnvironment());
        EgsUnit unit = units.lockById(defaultUnit.getId()).orElseThrow();
        SigningMaterial material = egs.signingMaterial(unit.getId());
        checkCertificateVat(material, seller, unit);

        LocalDateTime issueTime = LocalDateTime.now(InvoiceApiApplication.KSA).truncatedTo(ChronoUnit.SECONDS);
        String number = nextNumber(org, inv.getType(), issueTime.getYear());
        long icv = unit.getIcv() + 1;

        InvoiceDocument doc = new InvoiceDocument(number, UUID.randomUUID(), issueTime,
                inv.getSupplyDate() != null ? inv.getSupplyDate() : issueTime.toLocalDate(),
                inv.getKind(), inv.getType(), inv.getCurrency(), inv.getBillingReference(), inv.getReason(),
                inv.getPaymentMeans(), seller, buyer,
                inv.getLines().stream().map(InvoiceLineEntity::toModel).toList(),
                icv, unit.getLastInvoiceHash());
        SignedInvoice signed = InvoiceSigner.sign(doc, material.certificate(), material.privateKey(), issueTime);

        unit.setIcv(icv);
        unit.setLastInvoiceHash(signed.invoiceHash());

        Instant issuedAt = issueTime.atZone(InvoiceApiApplication.KSA).toInstant();
        inv.setNumber(number);
        inv.setUuid(doc.uuid());
        inv.setIssuedAt(issuedAt);
        inv.setSupplyDate(doc.supplyDate());
        inv.setSellerSnapshot(json.writeValueAsString(seller));
        inv.setBuyerSnapshot(buyer == null ? null : json.writeValueAsString(buyer));
        inv.setEgsUnitId(unit.getId());
        inv.setIcv(icv);
        inv.setPih(doc.previousHash());
        inv.setInvoiceHash(signed.invoiceHash());
        inv.setSignedXml(signed.xml());
        inv.setQrCode(signed.qrBase64());
        inv.setSendMode(mode);
        inv.setStatus(InvoiceStatus.SIGNED);
        if (inv.getKind() == InvoiceKind.SIMPLIFIED) {
            inv.setReportDeadlineAt(issuedAt.plus(REPORTING_LIMIT));
            Duration after = props.autoReportAfter();
            boolean manual = after == null || after.isZero();
            // "Send later" with auto-reporting off: only staff clicking "Send to ZATCA" submits it.
            inv.setScheduledSubmitAt(mode == SendMode.NOW ? Instant.now() : manual ? null : issuedAt.plus(after));
        } else {
            inv.setScheduledSubmitAt(Instant.now());
        }
        return inv;
    }

    /**
     * ZATCA rejects any document whose seller VAT differs from the VAT in the signing certificate
     * ("certificate-permissions"). Refuse before a number is consumed. The usual cause is the developer
     * sandbox, which hands everyone the same test certificate.
     */
    static void checkCertificateVat(SigningMaterial material, Party seller, EgsUnit unit) {
        String certVat = material.certificate().vatNumber().orElse(null);
        if (certVat == null || certVat.equals(seller.vatNumber())) return;
        String message = unit.getEnvironment() == com.fatoora.zatca.ZatcaEnvironment.SANDBOX
                ? "ZATCA's developer sandbox issues one shared test certificate for VAT " + certVat
                  + ", so it can only clear test invoices for that VAT, not your company's VAT " + seller.vatNumber()
                  + ". To test with your real VAT, switch to Simulation and register a device with an OTP from the Fatoora simulation portal."
                : "The ZATCA device '" + unit.getName() + "' is certified for VAT " + certVat + " but the company VAT is "
                  + seller.vatNumber() + ". Register a new device for the current VAT under Settings → ZATCA devices.";
        throw setupProblem("certificate_vat_mismatch", message);
    }

    /**
     * A problem only the e-invoicing provider can fix. The provider sees the technical reason; the client's
     * staff get a plain message and nothing about ZATCA devices or certificates.
     */
    static ApiException setupProblem(String code, String technicalMessage) {
        String message = com.fatoora.api.auth.CurrentUser.isOperator() ? technicalMessage
                : "E-invoicing is not fully set up for your company yet, so this invoice can't be issued. "
                  + "Your draft is saved. Please contact your e-invoicing provider.";
        return ApiException.conflict(code, message);
    }

    private Party buyer(Invoice inv) {
        if (inv.getCustomerId() == null) return null;
        Customer c = customers.findByIdAndOrganizationId(inv.getCustomerId(), inv.getOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Customer"));
        return c.toParty();
    }

    /** Checks the business rules ZATCA would reject, before a number is consumed. */
    static void validate(Invoice inv, Party seller, Party buyer) {
        List<String> problems = new ArrayList<>();
        if (inv.getLines().isEmpty()) problems.add("Add at least one line");
        checkAddress("Seller", seller.address(), problems);
        if (seller.vatNumber() == null || !seller.vatNumber().matches("3\\d{13}3"))
            problems.add("Seller VAT number is invalid");
        if (inv.getKind() == InvoiceKind.STANDARD) {
            if (buyer == null) {
                problems.add("A standard (B2B) invoice needs a customer");
            } else {
                if (!buyer.hasVat() && (buyer.otherId() == null || buyer.otherIdScheme() == null))
                    problems.add("Customer needs a VAT number or another ID (CR, national ID…)");
                checkAddress("Customer", buyer.address(), problems);
            }
        }
        if (inv.getType() != DocumentType.INVOICE) {
            if (inv.getBillingReference() == null || inv.getBillingReference().isBlank())
                problems.add("Credit/debit notes must reference the original invoice");
            if (inv.getReason() == null || inv.getReason().isBlank())
                problems.add("Credit/debit notes need a reason");
        }
        if (!problems.isEmpty())
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "not_issuable", String.join(". ", problems));
    }

    private static void checkAddress(String who, Address a, List<String> problems) {
        if (a == null) {
            problems.add(who + " address is missing");
            return;
        }
        boolean ksa = "SA".equals(a.countryCode());
        if (blank(a.street()) || blank(a.city()) || blank(a.district()))
            problems.add(who + " address needs street, district and city");
        if (ksa && (a.buildingNumber() == null || !a.buildingNumber().matches("\\d{4}")))
            problems.add(who + " building number must be 4 digits");
        if (ksa && (a.postalCode() == null || !a.postalCode().matches("\\d{5}")))
            problems.add(who + " postal code must be 5 digits");
        if (who.equals("Customer") && problems.stream().anyMatch(p -> p.startsWith("Customer")))
            problems.add("Fill in the customer's \"National address for B2B tax invoices\" section");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** Atomic, gap-free per organization / document type / year: e.g. INV-2026-000001. */
    private String nextNumber(Organization org, DocumentType type, int year) {
        Long value = jdbc.queryForObject("""
                INSERT INTO document_sequence (organization_id, doc_type, year, next_value) VALUES (?, ?, ?, 2)
                ON CONFLICT (organization_id, doc_type, year)
                DO UPDATE SET next_value = document_sequence.next_value + 1
                RETURNING next_value - 1
                """, Long.class, org.getId(), type.name(), year);
        String prefix = switch (type) {
            case INVOICE -> org.getInvoicePrefix();
            case CREDIT_NOTE -> org.getCreditNotePrefix();
            case DEBIT_NOTE -> org.getDebitNotePrefix();
        };
        return "%s-%d-%06d".formatted(prefix, year, value);
    }
}
