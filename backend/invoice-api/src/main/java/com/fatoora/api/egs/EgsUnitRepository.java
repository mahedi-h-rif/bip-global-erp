package com.fatoora.api.egs;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface EgsUnitRepository extends JpaRepository<EgsUnit, Long> {

    List<EgsUnit> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    Optional<EgsUnit> findByIdAndOrganizationId(Long id, Long organizationId);

    Optional<EgsUnit> findFirstByOrganizationIdAndStatusAndIsDefaultTrue(Long organizationId, EgsUnit.Status status);

    /** SELECT … FOR UPDATE: serialises signing per device so ICV/PIH stay gap-free and ordered. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from EgsUnit e where e.id = :id")
    Optional<EgsUnit> lockById(Long id);
}
