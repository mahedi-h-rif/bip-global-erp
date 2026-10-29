package com.fatoora.api.invoice;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long>, JpaSpecificationExecutor<Invoice> {

    Optional<Invoice> findByIdAndOrganizationId(Long id, Long organizationId);

    /** Serialises payments on one invoice so two cashiers can't together exceed what is due. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id = :id and i.organizationId = :orgId")
    Optional<Invoice> lockByIdAndOrganizationId(Long id, Long orgId);

    Page<Invoice> findByOrganizationId(Long organizationId, Pageable pageable);

    /** Signed documents whose scheduled submission time has come. */
    @Query("select i.id from Invoice i where i.status = com.fatoora.api.invoice.InvoiceStatus.SIGNED "
            + "and i.scheduledSubmitAt <= :now order by i.scheduledSubmitAt")
    List<Long> findDueForSubmission(Instant now);

    String FIGURES = "select new com.fatoora.api.invoice.InvoiceFigures(i.id, i.number, i.kind, i.type, i.status, "
            + "i.customerId, i.originalInvoiceId, i.totalInclVat, i.vatTotal, i.issuedAt, i.dueDate) from Invoice i ";

    /** Every issued document of the organization (drafts and rejected ones excluded). */
    @Query(FIGURES + "where i.organizationId = :orgId and i.status <> com.fatoora.api.invoice.InvoiceStatus.DRAFT "
            + "and i.status <> com.fatoora.api.invoice.InvoiceStatus.REJECTED")
    List<InvoiceFigures> findIssuedFigures(Long orgId);

    /** The given documents plus every note raised against them. */
    @Query(FIGURES + "where i.id in :ids or i.originalInvoiceId in :ids")
    List<InvoiceFigures> findFiguresWithNotes(java.util.Collection<Long> ids);

    boolean existsByCustomerId(Long customerId);

    boolean existsByOriginalInvoiceId(Long originalInvoiceId);

    long countByOrganizationIdAndStatus(Long organizationId, InvoiceStatus status);

    List<Invoice> findTop20ByOrganizationIdAndStatusOrderByReportDeadlineAtAsc(Long organizationId, InvoiceStatus status);

    @Query("select coalesce(sum(i.totalInclVat), 0) from Invoice i where i.organizationId = :orgId "
            + "and i.issuedAt >= :from and i.status <> com.fatoora.api.invoice.InvoiceStatus.DRAFT "
            + "and i.type = com.fatoora.zatca.model.DocumentType.INVOICE")
    BigDecimal sumIssuedSince(Long orgId, Instant from);
}
