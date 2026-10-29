package com.fatoora.api.worker;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WorkerMonthRepository extends JpaRepository<WorkerMonth, Long> {

    List<WorkerMonth> findByWorkerIdInAndYear(Collection<Long> workerIds, int year);

    Optional<WorkerMonth> findByWorkerIdAndYearAndMonth(Long workerId, int year, int month);

    @Query("select distinct m.year from WorkerMonth m where m.workerId in :workerIds order by m.year")
    List<Integer> years(Collection<Long> workerIds);

    /** [month, amount paid to all workers] for each month of a year that has payments. */
    @Query("select m.month, sum(m.paid) from WorkerMonth m, Worker w where w.id = m.workerId and w.organizationId = :orgId"
            + " and m.year = :year and m.paid is not null group by m.month")
    List<Object[]> paidByMonth(Long orgId, int year);
}
