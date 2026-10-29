package com.fatoora.api.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PartnerRepository extends JpaRepository<Partner, Long> {

    Optional<Partner> findByIdAndOrganizationId(Long id, Long organizationId);

    List<Partner> findByOrganizationIdOrderByIdAsc(Long organizationId);
}
