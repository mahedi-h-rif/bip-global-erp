package com.fatoora.api.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CompanyDocumentRepository extends JpaRepository<CompanyDocument, Long> {

    Optional<CompanyDocument> findByIdAndOrganizationId(Long id, Long organizationId);

    List<CompanyDocument> findByOrganizationId(Long organizationId);

    /** Documents still being reminded about, across all organizations (the scheduler). */
    @Query("select d from CompanyDocument d where d.remindersEnabled = true and d.expiryDate is not null")
    List<CompanyDocument> findWatched();
}
