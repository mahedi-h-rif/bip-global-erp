package com.fatoora.api.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface IncomeEntryRepository extends JpaRepository<IncomeEntry, Long> {

    Optional<IncomeEntry> findByIdAndOrganizationId(Long id, Long organizationId);

    List<IncomeEntry> findByOrganizationIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(Long orgId, LocalDate from, LocalDate to);

    /** Distinct "received by" names already used, for the name dropdown. */
    @Query("select distinct trim(e.receivedBy) from IncomeEntry e where e.organizationId = :orgId "
            + "and e.receivedBy is not null and trim(e.receivedBy) <> '' order by trim(e.receivedBy)")
    List<String> distinctReceivedBy(Long orgId);

    @Modifying
    @Query("delete from IncomeEntry e where e.organizationId = :orgId and e.source = :source and e.entryDate between :from and :to")
    int deleteImported(Long orgId, AccountSource source, LocalDate from, LocalDate to);
}
