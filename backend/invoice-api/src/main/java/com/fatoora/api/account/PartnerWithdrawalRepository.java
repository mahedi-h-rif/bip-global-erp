package com.fatoora.api.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PartnerWithdrawalRepository extends JpaRepository<PartnerWithdrawal, Long> {

    Optional<PartnerWithdrawal> findByIdAndOrganizationId(Long id, Long organizationId);

    List<PartnerWithdrawal> findByOrganizationIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(Long orgId, LocalDate from, LocalDate to);

    @Modifying
    @Query("delete from PartnerWithdrawal w where w.organizationId = :orgId and w.source = :source and w.entryDate between :from and :to")
    int deleteImported(Long orgId, AccountSource source, LocalDate from, LocalDate to);
}
