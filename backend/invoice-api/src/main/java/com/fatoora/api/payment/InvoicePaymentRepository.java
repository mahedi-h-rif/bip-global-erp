package com.fatoora.api.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InvoicePaymentRepository extends JpaRepository<InvoicePayment, Long> {

    Optional<InvoicePayment> findByIdAndOrganizationId(Long id, Long organizationId);

    List<InvoicePayment> findByInvoiceIdOrderByPaidOnAscIdAsc(Long invoiceId);

    @Query("select new com.fatoora.api.payment.PaidTotal(p.invoiceId, sum(p.amount)) "
            + "from InvoicePayment p where p.organizationId = :orgId group by p.invoiceId")
    List<PaidTotal> totalsByInvoice(Long orgId);

    @Query("select new com.fatoora.api.payment.PaidTotal(p.invoiceId, sum(p.amount)) "
            + "from InvoicePayment p where p.invoiceId in :invoiceIds group by p.invoiceId")
    List<PaidTotal> totalsFor(Collection<Long> invoiceIds);
}
