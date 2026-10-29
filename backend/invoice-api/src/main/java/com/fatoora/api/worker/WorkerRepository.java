package com.fatoora.api.worker;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface WorkerRepository extends JpaRepository<Worker, Long> {

    Optional<Worker> findByIdAndOrganizationId(Long id, Long organizationId);

    List<Worker> findByOrganizationIdOrderByPositionAscIdAsc(Long organizationId);

    @Query("select coalesce(max(w.position), 0) from Worker w where w.organizationId = :orgId")
    int maxPosition(Long orgId);
}
