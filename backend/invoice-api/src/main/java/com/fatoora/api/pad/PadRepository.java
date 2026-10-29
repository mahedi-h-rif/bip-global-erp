package com.fatoora.api.pad;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PadRepository extends JpaRepository<PadDocument, Long> {

    Optional<PadDocument> findByIdAndOrganizationId(Long id, Long organizationId);

    List<PadDocument> findByOrganizationIdOrderByUpdatedAtDesc(Long organizationId);
}
