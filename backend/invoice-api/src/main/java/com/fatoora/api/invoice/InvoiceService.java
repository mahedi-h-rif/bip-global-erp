package com.fatoora.api.invoice;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.customer.Customer;
import com.fatoora.api.customer.CustomerRepository;
import com.fatoora.api.egs.EgsCredential;
import com.fatoora.api.egs.EgsService;
import com.fatoora.api.egs.EgsUnit;
import com.fatoora.api.egs.EgsUnitRepository;
import com.fatoora.api.invoice.InvoiceDtos.*;
import com.fatoora.api.payment.BalanceCalculator.Balance;
import com.fatoora.api.payment.PaymentService;
import com.fatoora.api.product.ProductRepository;
import com.fatoora.zatca.model.DocumentType;
import com.fatoora.zatca.model.InvoiceKind;
import com.fatoora.zatca.model.InvoiceLine;
import com.fatoora.zatca.model.InvoiceTotals;
import com.fatoora.zatca.model.VatCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Drafts, notes, queries and the issue/submit orchestration. */
@Service
public class InvoiceService {

    private final InvoiceRepository invoices;
    private final CustomerRepository customers;
    private final ZatcaSubmissionRepository submissions;
    private final EgsUnitRepository units;
    private final EgsService egs;
    private final InvoiceSigningService signing;
    private final InvoiceSubmissionService submitter;
    private final ProductRepository products;
    private final PaymentService payments;
    private final AuditService audit;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.fatoora.api.product.UomService uom;

    public InvoiceService(InvoiceRepository invoices, CustomerRepository customers, ZatcaSubmissionRepository submissions,
                          EgsUnitRepository units, EgsService egs, InvoiceSigningService signing,
                          InvoiceSubmissionService submitter, ProductRepository products, PaymentService payments,
                          AuditService audit) {
        this.invoices = invoices;
        this.customers = customers;
        this.submissions = submissions;
        this.units = units;
        this.egs = egs;
        this.signing = signing;
        this.submitter = submitter;
        this.products = products;
        this.payments = payments;
        this.audit = audit;
    }

    // --- drafts ----------------------------------------------------------------------------------

    @Transactional
    public Invoice createDraft(Long orgId, String user, DraftRequest req) {
        Invoice inv = new Invoice();
        inv.setOrganizationId(orgId);
        inv.setCreatedBy(user);
        applyDraft(inv, req);
        invoices.save(inv);
        audit.record(orgId, "INVOICE_DRAFT_CREATED", "invoice", inv.getId(), inv.getKind() + " " + inv.getType());
        return inv;
    }

    @Transactional
    public Invoice updateDraft(Long orgId, Long id, DraftRequest req) {
        Invoice inv = load(orgId, id);
        requireDraft(inv);
        applyDraft(inv, req);
        return inv;
    }

    /**
     * Drafts can always be deleted. So can test documents (signed by a sandbox or simulation device): ZATCA
     * never received them as real invoices. Real issued invoices are cancelled with a credit note instead.
     */
    @Transactional
    public void deleteDraft(Long orgId, Long id) {
        Invoice inv = load(orgId, id);
        if (inv.getStatus() != InvoiceStatus.DRAFT) {
            if (!isTestDocument(inv))
                throw ApiException.conflict("not_draft", "Issued invoices can't be deleted; cancel it with a credit note instead");
            if (invoices.existsByOriginalInvoiceId(id))
                throw ApiException.conflict("has_notes", "Delete the credit or debit notes of this test invoice first");
            payments.deleteForInvoice(id);
            submissions.deleteAll(submissions.findByInvoiceIdOrderByCreatedAtAsc(id));
            invoices.delete(inv);
            audit.record(orgId, "TEST_INVOICE_DELETED", "invoice", id, inv.getNumber());
            return;
        }
        invoices.delete(inv);
        audit.record(orgId, "INVOICE_DRAFT_DELETED", "invoice", id, null);
    }

    /** The device that signs new documents today is a sandbox or simulation one. */
    private boolean isTestEnvironment(Long orgId) {
        return units.findFirstByOrganizationIdAndStatusAndIsDefaultTrue(orgId, EgsUnit.Status.ACTIVE)
                .map(u -> u.getEnvironment() != com.fatoora.zatca.ZatcaEnvironment.PRODUCTION).orElse(false);
    }

    /** Ids of the organization's sandbox and simulation devices. */
    private java.util.Set<Long> testUnits(Long orgId) {
        return units.findByOrganizationIdOrderByCreatedAtDesc(orgId).stream()
                .filter(u -> u.getEnvironment() != com.fatoora.zatca.ZatcaEnvironment.PRODUCTION)
                .map(EgsUnit::getId).collect(Collectors.toSet());
    }

    /** Issued by a sandbox or simulation device, so not a legal invoice. */
    boolean isTestDocument(Invoice inv) {
        return inv.getEgsUnitId() != null && units.findById(inv.getEgsUnitId())
                .map(u -> u.getEnvironment() != com.fatoora.zatca.ZatcaEnvironment.PRODUCTION).orElse(false);
    }

    /** Credit or debit note against an accepted invoice, pre-filled with its lines. */
    @Transactional
    public Invoice createNote(Long orgId, String user, Long originalId, NoteRequest req) {
        Invoice original = load(orgId, originalId);
        if (original.getType() != DocumentType.INVOICE || !original.getStatus().accepted())
            throw ApiException.conflict("not_noteable", "Notes can only be raised against an invoice ZATCA has accepted");
        if (req.type() == DocumentType.INVOICE) throw ApiException.badRequest("bad_type", "Choose credit or debit note");
        // The note would be signed by today's device; a test invoice must never get a real note.
        if (isTestDocument(original) != isTestEnvironment(orgId))
            throw ApiException.conflict("test_document", "This is a test invoice from the ZATCA sandbox. Delete it instead of raising a note.");

        Invoice note = new Invoice();
        note.setOrganizationId(orgId);
        note.setCreatedBy(user);
        note.setKind(original.getKind());
        note.setType(req.type());
        note.setCustomerId(original.getCustomerId());
        note.setOriginalInvoiceId(original.getId());
        note.setBillingReference(original.getNumber());
        note.setReason(req.reason());
        note.setPaymentMeans(original.getPaymentMeans());
        note.setSupplyDate(LocalDate.now(InvoiceApiApplication.KSA));
        // A credit note returns goods to stock, even when the original took them out on a delivery.
        note.replaceLines(original.getLines().stream().map(InvoiceService::copyLine)
                .peek(l -> { l.setDeliveryLineId(null); l.setSalesOrderLineId(null); }).toList());
        note.setWarehouseId(original.getWarehouseId());
        recalc(note);
        invoices.save(note);
        audit.record(orgId, "NOTE_DRAFT_CREATED", "invoice", note.getId(), req.type() + " for " + original.getNumber());
        return note;
    }

    /** Copies any document into a new draft, e.g. to correct a rejected invoice. */
    @Transactional
    public Invoice duplicate(Long orgId, String user, Long id) {
        Invoice src = load(orgId, id);
        Invoice copy = new Invoice();
        copy.setOrganizationId(orgId);
        copy.setCreatedBy(user);
        copy.setKind(src.getKind());
        copy.setType(src.getType());
        copy.setCustomerId(src.getCustomerId());
        copy.setOriginalInvoiceId(src.getOriginalInvoiceId());
        copy.setBillingReference(src.getBillingReference());
        copy.setReason(src.getReason());
        copy.setPaymentMeans(src.getPaymentMeans());
        copy.setSupplyDate(src.getSupplyDate());
        copy.setInternalNote(src.getStatus() == InvoiceStatus.REJECTED ? "Correction of " + src.getNumber() : src.getInternalNote());
        copy.replaceLines(src.getLines().stream().map(InvoiceService::copyLine).toList());
        recalc(copy);
        invoices.save(copy);
        audit.record(orgId, "INVOICE_DUPLICATED", "invoice", copy.getId(), "from " + id);
        return copy;
    }

    // --- issue & submit ----------------------------------------------------------------------------

    /** Signs the draft; with send mode NOW it is submitted to ZATCA immediately. */
    public Invoice issue(Long orgId, Long id, SendMode mode) {
        Invoice signed = signing.sign(orgId, id, mode);
        audit.record(orgId, "INVOICE_ISSUED", "invoice", id, signed.getNumber() + " ICV " + signed.getIcv());
        if (signed.getSendMode() == SendMode.NOW) submitter.submit(id);
        return load(orgId, id);
    }

    /** "Send now" for a document that is signed but not yet accepted (queued, or waiting for a retry). */
    public Invoice submitNow(Long orgId, Long id) {
        Invoice inv = load(orgId, id);
        if (inv.getStatus() != InvoiceStatus.SIGNED)
            throw ApiException.conflict("not_pending", "Only signed documents waiting for ZATCA can be sent");
        submitter.submit(id);
        return load(orgId, id);
    }

    // --- queries ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<InvoiceSummary> list(Long orgId, InvoiceStatus status, InvoiceKind kind, String q, int page, int size) {
        Specification<Invoice> spec = (root, query, cb) -> cb.equal(root.get("organizationId"), orgId);
        String owner = com.fatoora.api.auth.CurrentUser.owner();
        if (owner != null) spec = spec.and((root, query, cb) -> cb.equal(cb.lower(root.get("createdBy")), owner.toLowerCase()));
        if (status != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        if (kind != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("kind"), kind));
        if (q != null && !q.isBlank()) {
            String like = "%" + q.toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(cb.coalesce(root.get("number"), "")), like));
        }
        Page<Invoice> result = invoices.findAll(spec,
                PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<Long, String> names = customerNames(orgId);
        Map<Long, Balance> balances = payments.balancesFor(result.map(Invoice::getId).getContent());
        java.util.Set<Long> test = testUnits(orgId);
        return result.map(i -> summary(i, names, balances.get(i.getId()), test));
    }

    @Transactional(readOnly = true)
    public InvoiceDetail detail(Long orgId, Long id) {
        Invoice i = load(orgId, id);
        String customerName = i.getCustomerId() == null ? null : customerNames(orgId).get(i.getCustomerId());
        List<Submission> subs = submissions.findByInvoiceIdOrderByCreatedAtAsc(id).stream()
                .map(s -> new Submission(s.getEndpoint(), s.getHttpStatus(), s.getOutcome(), s.getResponseBody(), s.getCreatedAt()))
                .toList();
        List<LineDto> lines = i.getLines().stream().map(l -> new LineDto(l.getName(), l.getQuantity(), l.getUnitCode(),
                l.getUnitPrice(), l.getVatCategory(), l.getVatRate(), l.getExemptionCode(), l.getExemptionReason(),
                l.getProductId(), l.getDeliveryLineId(), l.getSalesOrderLineId(), l.getUomFactor())).toList();
        Balance b = payments.balance(id);
        List<PaymentService.PaymentDto> paid = b == null ? List.of() : payments.list(orgId, id);
        return new InvoiceDetail(i.getId(), i.getNumber(), i.getUuid(), i.getKind(), i.getType(), i.getStatus(),
                i.getSendMode(), i.getCustomerId(), customerName, i.getSupplyDate(), i.getPaymentMeans(),
                i.getOriginalInvoiceId(), i.getBillingReference(), i.getReason(), i.getInternalNote(),
                i.getTotalExclVat(), i.getVatTotal(), i.getTotalInclVat(), i.getIcv(), i.getInvoiceHash(), i.getQrCode(),
                i.getIssuedAt(), i.getScheduledSubmitAt(), i.getReportDeadlineAt(), i.getSubmittedAt(),
                i.getSubmitAttempts(), i.getZatcaWarnings(), i.getZatcaErrors(), i.getLastError(),
                downloadable(i), lines, subs, i.getCreatedAt(), i.getCreatedBy(),
                i.getStatus() == InvoiceStatus.DRAFT, i.getDueDate(),
                b == null ? null : b.notes(), b == null ? null : b.adjusted(), b == null ? null : b.paid(),
                b == null ? null : b.due(), b == null ? null : b.overpaid(), b == null ? null : b.status(), paid,
                isTestDocument(i), i.getWarehouseId(), i.getCustomerRef(), i.getSalesEmployee(),
                lifecycle(i), i.getCancelledAt() != null ? i.getCancelReason() : i.getCloseReason(),
                i.getCancelledAt() != null ? i.getCancelledAt() : i.getClosedAt(), i.getCancelNoteId(),
                i.getCancelNoteId() == null ? null : invoices.findById(i.getCancelNoteId()).map(Invoice::getNumber).orElse(null));
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(Long orgId) {
        Instant monthStart = LocalDate.now(InvoiceApiApplication.KSA).withDayOfMonth(1)
                .atStartOfDay(InvoiceApiApplication.KSA).toInstant();
        Map<Long, String> names = customerNames(orgId);
        // A branch login sees only the documents it created.
        String owner = com.fatoora.api.auth.CurrentUser.owner();
        List<InvoiceSummary> queue = (owner == null
                ? invoices.findTop20ByOrganizationIdAndStatusOrderByReportDeadlineAtAsc(orgId, InvoiceStatus.SIGNED)
                : invoices.findTop20ByOrganizationIdAndStatusAndCreatedByIgnoreCaseOrderByReportDeadlineAtAsc(orgId, InvoiceStatus.SIGNED, owner))
                .stream().map(i -> summary(i, names, null, testUnits(orgId))).toList();

        // Certificate renewal is the provider's job, so only the operator sees expiry warnings.
        List<CertificateWarning> certs = new ArrayList<>();
        List<EgsUnit> devices = com.fatoora.api.auth.CurrentUser.isOperator()
                ? units.findByOrganizationIdOrderByCreatedAtDesc(orgId) : List.of();
        for (EgsUnit u : devices) {
            if (u.getStatus() != EgsUnit.Status.ACTIVE) continue;
            egs.activeProductionCredentials(u.getId()).stream()
                    .map(EgsCredential::getCertificateNotAfter).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .ifPresent(exp -> certs.add(new CertificateWarning(u.getId(), u.getName(), exp,
                            Duration.between(Instant.now(), exp).toDays())));
        }
        if (owner != null)
            return new Dashboard(
                    invoices.countByOrganizationIdAndStatusAndCreatedByIgnoreCase(orgId, InvoiceStatus.DRAFT, owner),
                    invoices.countByOrganizationIdAndStatusAndCreatedByIgnoreCase(orgId, InvoiceStatus.SIGNED, owner),
                    invoices.countByOrganizationIdAndStatusAndCreatedByIgnoreCase(orgId, InvoiceStatus.REJECTED, owner),
                    invoices.sumIssuedSinceBy(orgId, monthStart, owner),
                    queue, certs);
        return new Dashboard(
                invoices.countByOrganizationIdAndStatus(orgId, InvoiceStatus.DRAFT),
                invoices.countByOrganizationIdAndStatus(orgId, InvoiceStatus.SIGNED),
                invoices.countByOrganizationIdAndStatus(orgId, InvoiceStatus.REJECTED),
                invoices.sumIssuedSince(orgId, monthStart),
                queue, certs);
    }

    @Transactional(readOnly = true)
    public Invoice load(Long orgId, Long id) {
        return invoices.findByIdAndOrganizationId(id, orgId)
                .filter(i -> com.fatoora.api.auth.CurrentUser.owns(i.getCreatedBy()))
                .orElseThrow(() -> ApiException.notFound("Invoice"));
    }

    /**
     * Standard documents may only be handed to the buyer once ZATCA has cleared them.
     * Simplified documents are valid from the moment they are signed.
     */
    public static boolean downloadable(Invoice i) {
        if (i.getKind() == InvoiceKind.STANDARD) {
            return i.getStatus() == InvoiceStatus.CLEARED || i.getStatus() == InvoiceStatus.CLEARED_WITH_WARNINGS;
        }
        return i.getStatus() != InvoiceStatus.DRAFT && i.getSignedXml() != null;
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void applyDraft(Invoice inv, DraftRequest req) {
        DocumentType type = req.type() == null ? DocumentType.INVOICE : req.type();
        if (req.customerId() != null)
            customers.findByIdAndOrganizationId(req.customerId(), inv.getOrganizationId())
                    .orElseThrow(() -> ApiException.notFound("Customer"));
        if (req.kind() == InvoiceKind.STANDARD && req.sendMode() == SendMode.LATER)
            throw ApiException.badRequest("standard_later", "Standard invoices can't be queued; keep them as draft instead");

        inv.setKind(req.kind());
        inv.setType(type);
        inv.setCustomerId(req.customerId());
        inv.setSupplyDate(req.supplyDate());
        inv.setPaymentMeans(req.paymentMeans() == null || req.paymentMeans().isBlank() ? "10" : req.paymentMeans());
        inv.setSendMode(req.sendMode() == null ? SendMode.NOW : req.sendMode());
        inv.setReason(emptyToNull(req.reason()));
        inv.setInternalNote(emptyToNull(req.internalNote()));
        inv.setDueDate(type == DocumentType.INVOICE ? req.dueDate() : null);
        inv.setWarehouseId(req.warehouseId());
        inv.setCustomerRef(emptyToNull(req.customerRef()));
        inv.setSalesEmployee(emptyToNull(req.salesEmployee()));
        if (type != DocumentType.INVOICE && req.originalInvoiceId() != null) {
            Invoice original = load(inv.getOrganizationId(), req.originalInvoiceId());
            inv.setOriginalInvoiceId(original.getId());
            inv.setBillingReference(original.getNumber());
        }
        for (LineDto l : req.lines())
            if (l.productId() != null && products.findByIdAndOrganizationId(l.productId(), inv.getOrganizationId()).isEmpty())
                throw ApiException.notFound("Product");
        inv.replaceLines(req.lines().stream().map(this::toEntity).toList());
        // The item code is internal: a line picked as "code · name" keeps only the name on the invoice.
        for (InvoiceLineEntity e : inv.getLines()) {
            if (e.getProductId() == null || e.getName() == null) continue;
            products.findByIdAndOrganizationId(e.getProductId(), inv.getOrganizationId()).ifPresent(p -> {
                String code = p.getNumber();
                if (code != null && !code.isBlank() && e.getName().startsWith(code + " · ") && e.getName().length() > code.length() + 3)
                    e.setName(e.getName().substring(code.length() + 3).trim());
            });
        }
        recalc(inv);
    }

    private InvoiceLineEntity toEntity(LineDto d) {
        VatCategory cat = d.vatCategory() == null ? VatCategory.S : d.vatCategory();
        // Validates the line with the same rules the signer will apply.
        InvoiceLine model = new InvoiceLine(d.name(), d.quantity(), d.unitCode(), d.unitPrice(), cat, d.vatRate(),
                emptyToNull(d.exemptionCode()), emptyToNull(d.exemptionReason()));
        InvoiceLineEntity e = new InvoiceLineEntity();
        e.setName(model.name());
        e.setQuantity(model.quantity());
        e.setUnitCode(model.unitCode());
        e.setUnitPrice(model.unitPrice());
        e.setVatCategory(model.vatCategory());
        e.setVatRate(model.vatRate());
        e.setExemptionCode(model.exemptionCode());
        e.setExemptionReason(model.exemptionReason());
        e.setProductId(d.productId());
        e.setDeliveryLineId(d.deliveryLineId());
        e.setSalesOrderLineId(d.salesOrderLineId());
        e.setUomFactor(uom == null ? java.math.BigDecimal.ONE : uom.factor(d.productId(), model.unitCode()));
        return e;
    }

    private static InvoiceLineEntity copyLine(InvoiceLineEntity l) {
        InvoiceLineEntity e = new InvoiceLineEntity();
        e.setName(l.getName());
        e.setQuantity(l.getQuantity());
        e.setUnitCode(l.getUnitCode());
        e.setUnitPrice(l.getUnitPrice());
        e.setVatCategory(l.getVatCategory());
        e.setVatRate(l.getVatRate());
        e.setExemptionCode(l.getExemptionCode());
        e.setExemptionReason(l.getExemptionReason());
        e.setProductId(l.getProductId());
        e.setDeliveryLineId(l.getDeliveryLineId());
        e.setSalesOrderLineId(l.getSalesOrderLineId());
        e.setUomFactor(l.getUomFactor());
        return e;
    }

    private static void recalc(Invoice inv) {
        InvoiceTotals t = InvoiceTotals.of(inv.getLines().stream().map(InvoiceLineEntity::toModel).toList());
        inv.setTotalExclVat(t.taxExclusive());
        inv.setVatTotal(t.vatTotal());
        inv.setTotalInclVat(t.taxInclusive());
    }

    private static void requireDraft(Invoice inv) {
        if (inv.getStatus() != InvoiceStatus.DRAFT)
            throw ApiException.conflict("not_draft", "Issued documents can't be changed. Raise a credit or debit note instead.");
    }

    private Map<Long, String> customerNames(Long orgId) {
        return customers.findByOrganizationId(orgId, Sort.unsorted()).stream()
                .collect(Collectors.toMap(Customer::getId, c -> c.getNameEn() != null && !c.getNameEn().isBlank()
                        ? c.getNameEn() : c.getNameAr(), (a, b) -> a));
    }

    private static InvoiceSummary summary(Invoice i, Map<Long, String> names, Balance b, java.util.Set<Long> testUnits) {
        return new InvoiceSummary(i.getId(), i.getNumber(), i.getKind(), i.getType(), i.getStatus(), i.getSendMode(),
                i.getCustomerId() == null ? null : names.get(i.getCustomerId()), i.getTotalInclVat(), i.getVatTotal(),
                i.getIssuedAt(), i.getReportDeadlineAt(), i.getCreatedAt(), i.getDueDate(),
                b == null ? null : b.paid(), b == null ? null : b.due(), b == null ? null : b.status(), downloadable(i),
                i.getEgsUnitId() != null && testUnits.contains(i.getEgsUnitId()), lifecycle(i));
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // --- cancel and close ---------------------------------------------------------------------------

    static String lifecycle(Invoice i) {
        return i.getCancelledAt() != null ? "CANCELLED" : i.getClosedAt() != null ? "CLOSED" : null;
    }

    /** Checks an invoice can be cancelled, before anything is created. */
    @Transactional(readOnly = true)
    public void requireCancellable(Long orgId, Long id) {
        Invoice inv = load(orgId, id);
        if (inv.getType() != DocumentType.INVOICE || !inv.getStatus().accepted())
            throw ApiException.conflict("not_cancellable", "Only an invoice ZATCA has accepted can be cancelled");
        if (inv.getCancelledAt() != null) throw ApiException.conflict("already_cancelled", "This invoice is already cancelled");
        boolean hasCredit = invoices.existsByOriginalInvoiceIdAndTypeAndStatusNot(id, DocumentType.CREDIT_NOTE, InvoiceStatus.REJECTED);
        if (hasCredit)
            throw ApiException.conflict("has_credit_note",
                    "This invoice already has a credit note. Delete the draft note, or credit the rest from the credit note screen");
    }

    /** Marks the credit note made for a cancellation: whether issuing it puts the goods back in stock. */
    @Transactional
    public void setStockReturn(Long orgId, Long noteId, boolean goodsReturned) {
        Invoice note = load(orgId, noteId);
        note.setNoStockReturn(!goodsReturned);
    }

    /**
     * After the full credit note is accepted: the invoice shows as cancelled, and when the customer was refunded,
     * a refund of everything paid is recorded (a negative payment) so nothing shows as paid or overpaid.
     */
    @Transactional
    public void completeCancel(Long orgId, String user, Long id, Long noteId, String reason, boolean refunded) {
        Invoice inv = load(orgId, id);
        inv.setCancelledAt(java.time.Instant.now());
        inv.setCancelReason(reason);
        inv.setCancelNoteId(noteId);
        inv.setClosedAt(null);
        inv.setCloseReason(null);
        if (refunded) {
            var paid = payments.balance(id);
            java.math.BigDecimal amount = paid == null ? java.math.BigDecimal.ZERO : paid.paid();
            if (amount.signum() > 0) payments.recordRefund(orgId, user, id, amount, "Refund on cancellation: " + reason);
        }
        audit.record(orgId, "INVOICE_CANCELLED", "invoice", id, inv.getNumber() + " · " + reason
                + (refunded ? " · payment refunded" : "") );
    }

    /** Close: the unpaid rest is written off; no more dues or reminders. Reopen undoes it. */
    @Transactional
    public void close(Long orgId, Long id, String reason) {
        Invoice inv = load(orgId, id);
        if (inv.getType() != DocumentType.INVOICE || !inv.getStatus().billable())
            throw ApiException.conflict("not_closable", "Only an issued invoice can be closed");
        if (inv.getCancelledAt() != null) throw ApiException.conflict("cancelled", "This invoice is cancelled");
        inv.setClosedAt(java.time.Instant.now());
        inv.setCloseReason(emptyToNull(reason));
        audit.record(orgId, "INVOICE_CLOSED", "invoice", id, inv.getNumber() + (reason == null ? "" : " · " + reason));
    }

    @Transactional
    public void reopen(Long orgId, Long id) {
        Invoice inv = load(orgId, id);
        if (inv.getClosedAt() == null) throw ApiException.conflict("not_closed", "This invoice is not closed");
        inv.setClosedAt(null);
        inv.setCloseReason(null);
        audit.record(orgId, "INVOICE_REOPENED", "invoice", id, inv.getNumber());
    }
}
