package com.fatoora.api.agreement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AgreementTemplateRepository extends JpaRepository<AgreementTemplate, Long> {

    Optional<AgreementTemplate> findByOrganizationId(Long organizationId);
}
