package com.fatoora.api.worker;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface WorkerLeaveRepository extends JpaRepository<WorkerLeave, Long> {

    /** Leaves of the given workers that overlap the [from, to] range, most recent first. */
    @Query("select l from WorkerLeave l where l.workerId in :ids and l.startDate <= :to and l.endDate >= :from "
            + "order by l.startDate desc, l.id desc")
    List<WorkerLeave> inRange(Collection<Long> ids, LocalDate from, LocalDate to);
}
