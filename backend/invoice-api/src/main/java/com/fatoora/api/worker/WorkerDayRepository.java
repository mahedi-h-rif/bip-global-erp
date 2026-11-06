package com.fatoora.api.worker;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WorkerDayRepository extends JpaRepository<WorkerDay, Long> {

    Optional<WorkerDay> findByWorkerIdAndEntryDate(Long workerId, LocalDate date);

    List<WorkerDay> findByWorkerIdInAndEntryDateBetweenOrderByEntryDateAscIdAsc(Collection<Long> workerIds, LocalDate from, LocalDate to);

    List<WorkerDay> findByWorkerIdAndEntryDateBetween(Long workerId, LocalDate from, LocalDate to);

    boolean existsByWorkerIdAndEntryDateBetween(Long workerId, LocalDate from, LocalDate to);
}
