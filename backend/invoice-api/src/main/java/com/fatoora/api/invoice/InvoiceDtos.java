package com.fatoora.api.invoice;

import com.fatoora.api.payment.BalanceCalculator.PaymentStatus;
import com.fatoora.api.payment.PaymentService;
import com.fatoora.zatca.model.DocumentType;
import com.fatoora.zatca.model.InvoiceKind;
import com.fatoora.zatca.model.VatCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class InvoiceDtos {

    private InvoiceDtos() {}

    public record LineDto(
            @NotBlank String name,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
            String unitCode,
            @NotNull @DecimalMin("0") BigDecimal unitPrice,
            VatCategory vatCategory,
            BigDecimal vatRate,
            String exemptionCode,
            String exemptionReason,
            Long productId,
            Long deliveryLineId,
            Long salesOrderLineId,
            /** Read-only: base units per unit, resolved by the server from the item's units. */
            BigDecimal uomFactor) {

        public LineDto(String name, BigDecimal quantity, String unitCode, BigDecimal unitPrice, VatCategory vatCategory,
                       BigDecimal vatRate, String exemptionCode, String exemptionReason, Long productId, Long deliveryLineId) {
            this(name, quantity, unitCode, unitPrice, vatCategory, vatRate, exemptionCode, exemptionReason, productId, deliveryLineId, null, null);
        }
    }

    /** Body for creating or editing a draft. */
    public record DraftRequest(
            @NotNull InvoiceKind kind,
            DocumentType type,
            Long customerId,
            LocalDate supplyDate,
            String paymentMeans,
            SendMode sendMode,
            Long originalInvoiceId,
            String reason,
            String internalNote,
            LocalDate dueDate,
            @NotEmpty List<@Valid LineDto> lines,
            Long warehouseId,
            String customerRef,
            String salesEmployee) {}

    public record IssueRequest(SendMode sendMode) {}

    public record NoteRequest(@NotNull DocumentType type, @NotBlank String reason) {}

    public record Submission(String endpoint, Integer httpStatus, String outcome, String response, Instant at) {}

    public record InvoiceSummary(
            Long id, String number, InvoiceKind kind, DocumentType type, InvoiceStatus status, SendMode sendMode,
            String customerName, BigDecimal totalInclVat, BigDecimal vatTotal, Instant issuedAt,
            Instant reportDeadlineAt, Instant createdAt,
            LocalDate dueDate, BigDecimal paidAmount, BigDecimal dueAmount, PaymentStatus paymentStatus,
            boolean downloadable,
            // Signed by a sandbox or simulation device: not a legal invoice, so it may be deleted.
            boolean test,
            /** CANCELLED, CLOSED or null. */
            String lifecycle) {}

    /** Cancel an issued invoice: a full credit note is issued; goods and payments are reversed only when ticked. */
    public record CancelRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 500) String reason,
                                boolean goodsReturned, boolean paymentRefunded) {}

    /** Close an invoice: the unpaid rest is written off. */
    public record CloseRequest(@jakarta.validation.constraints.Size(max = 500) String reason) {}

    public record InvoiceDetail(
            Long id, String number, UUID uuid, InvoiceKind kind, DocumentType type, InvoiceStatus status,
            SendMode sendMode, Long customerId, String customerName, LocalDate supplyDate, String paymentMeans,
            Long originalInvoiceId, String billingReference, String reason, String internalNote,
            BigDecimal totalExclVat, BigDecimal vatTotal, BigDecimal totalInclVat,
            Long icv, String invoiceHash, String qrCode, Instant issuedAt, Instant scheduledSubmitAt,
            Instant reportDeadlineAt, Instant submittedAt, int submitAttempts,
            String zatcaWarnings, String zatcaErrors, String lastError,
            boolean downloadable, List<LineDto> lines, List<Submission> submissions,
            Instant createdAt, String createdBy,
            // Payment tracking (internal, never sent to ZATCA). Null for drafts, rejected documents and notes.
            boolean editable, LocalDate dueDate, BigDecimal notesTotal, BigDecimal adjustedTotal,
            BigDecimal paidAmount, BigDecimal dueAmount, BigDecimal overpaidAmount, PaymentStatus paymentStatus,
            List<PaymentService.PaymentDto> payments,
            // Signed by a sandbox or simulation device: not a legal invoice, so it may be deleted.
            boolean test,
            Long warehouseId,
            String customerRef,
            String salesEmployee,
            String lifecycle, String lifecycleReason, java.time.Instant lifecycleAt, Long cancelNoteId, String cancelNoteNumber) {}

    public record Dashboard(
            long drafts, long awaitingSubmission, long rejected, BigDecimal issuedThisMonth,
            List<InvoiceSummary> queue, List<CertificateWarning> certificates) {}

    public record CertificateWarning(Long egsUnitId, String name, Instant expiresAt, long daysLeft) {}
}
