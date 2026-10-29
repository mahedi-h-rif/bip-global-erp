package com.fatoora.api.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ExpenseEntryRepository extends JpaRepository<ExpenseEntry, Long> {

    Optional<ExpenseEntry> findByIdAndOrganizationId(Long id, Long organizationId);

    List<ExpenseEntry> findByOrganizationIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(Long orgId, LocalDate from, LocalDate to);

    @Modifying
    @Query("delete from ExpenseEntry e where e.organizationId = :orgId and e.source = :source and e.entryDate between :from and :to")
    int deleteImported(Long orgId, AccountSource source, LocalDate from, LocalDate to);
}
