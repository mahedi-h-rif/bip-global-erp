package com.fatoora.api.egs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EgsCredentialRepository extends JpaRepository<EgsCredential, Long> {

    Optional<EgsCredential> findFirstByEgsUnitIdAndKindAndActiveTrueOrderByCreatedAtDesc(Long egsUnitId, EgsCredential.Kind kind);

    List<EgsCredential> findByEgsUnitIdAndKindAndActiveTrue(Long egsUnitId, EgsCredential.Kind kind);
}
