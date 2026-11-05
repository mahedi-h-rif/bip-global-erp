package com.fatoora.api.worker;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.organization.Organization;
import com.fatoora.api.organization.OrganizationRepository;
import com.fatoora.api.payment.InvoicePayment;
import com.fatoora.api.pdf.LetterheadPdf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Outside workers and their annual account ledger: dashboard figures, monthly entries, Excel and PDF. */
@RestController
@RequestMapping("/api/v1/workers")
public class WorkerController {

    private static final String WRITE = "hasAnyRole('ADMIN','ACCOUNTANT')";

    public record WorkerRequest(@NotBlank String name, String phone, String iqamaNumber, String nationality, String notes,
                                Boolean active, Boolean daily, @PositiveOrZero BigDecimal dailyRate,
                                @PositiveOrZero BigDecimal days) {}

    /** For a daily-wage worker, {@code days} and {@code rate} set the work (days × rate); {@code paidMethod} is how the salary was paid. */
    public record MonthRequest(@PositiveOrZero BigDecimal work, @PositiveOrZero BigDecimal paid,
                               @PositiveOrZero BigDecimal days, @PositiveOrZero BigDecimal rate,
                               InvoicePayment.Method paidMethod) {}

    public record DayRequest(@PositiveOrZero BigDecimal work, @PositiveOrZero BigDecimal paid, String notes,
                             InvoicePayment.Method paidMethod) {}

    /** One worker's figures on one day. */
    public record DayEntry(Long id, Long workerId, String workerName, LocalDate date, BigDecimal work, BigDecimal paid,
                           BigDecimal due, String notes, String method, String createdBy, java.time.Instant createdAt,
                           String updatedBy, java.time.Instant updatedAt) {}

    public record ImportResult(int year, int workers, int created, int months) {}

    /** One line of a day-work sheet, with the saved worker it probably means and what that month holds now. */
    public record DayPreviewLine(String name, BigDecimal days, BigDecimal rate, BigDecimal work, BigDecimal paid,
                                 Long suggestedWorkerId, BigDecimal currentWork, BigDecimal currentPaid) {}

    public record DayPreviewSheet(String sheet, int year, int month, List<DayPreviewLine> lines) {}

    public record DayPreview(List<DayPreviewSheet> sheets, List<WorkerLedger.Row> workers) {}

    public record DayApplyResult(int sheets, int months, int created, int skipped) {}

    /** Create/edit a leave; {@code deduction} is an optional salary cut (null or 0 means none). */
    public record LeaveRequest(@NotNull LocalDate startDate, @NotNull LocalDate endDate, WorkerLeave.Type type,
                               @PositiveOrZero BigDecimal deduction, String notes) {}

    /** One worker's leave, with the inclusive day count. */
    public record LeaveEntry(Long id, Long workerId, String workerName, LocalDate startDate, LocalDate endDate,
                             long days, String type, BigDecimal deduction, String notes, String createdBy,
                             java.time.Instant createdAt, String updatedBy, java.time.Instant updatedAt) {}

    private final WorkerRepository workers;
    private final WorkerMonthRepository months;
    private final WorkerDayRepository days;
    private final WorkerLeaveRepository leaves;
    private final OrganizationRepository orgs;
    private final LetterheadPdf letterhead;
    private final AuditService audit;

    public WorkerController(WorkerRepository workers, WorkerMonthRepository months, WorkerDayRepository days,
                            WorkerLeaveRepository leaves, OrganizationRepository orgs, LetterheadPdf letterhead, AuditService audit) {
        this.workers = workers;
        this.months = months;
        this.days = days;
        this.leaves = leaves;
        this.orgs = orgs;
        this.letterhead = letterhead;
        this.audit = audit;
    }

    @GetMapping("/ledger")
    @Transactional(readOnly = true)
    WorkerLedger.Result ledger(@RequestParam(required = false) Integer year) {
        return build(CurrentUser.orgId(), year == null ? thisYear() : year);
    }

    @GetMapping("/ledger.xlsx")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> excel(@RequestParam(required = false) Integer year) throws IOException {
        Long orgId = CurrentUser.orgId();
        WorkerLedger.Result r = build(orgId, year == null ? thisYear() : year);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("worker-ledger-" + r.year() + ".xlsx").build().toString())
                .body(WorkerExcel.write(r, org(orgId)));
    }

    @GetMapping("/ledger/pdf")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> pdf(@RequestParam(required = false) Integer year) {
        Long orgId = CurrentUser.orgId();
        WorkerLedger.Result r = build(orgId, year == null ? thisYear() : year);
        byte[] pdf = letterhead.render(org(orgId), "Worker ledger " + r.year(), WorkerLedger.html(r));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename("worker-ledger-" + r.year() + ".pdf").build().toString())
                .body(pdf);
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    WorkerLedger.Row create(@Valid @RequestBody WorkerRequest req) {
        Long orgId = CurrentUser.orgId();
        Worker w = new Worker();
        w.setOrganizationId(orgId);
        w.setPosition(workers.maxPosition(orgId) + 1);
        apply(w, req);
        workers.save(w);
        postDailyWork(w);
        audit.record(orgId, "WORKER_CREATED", "worker", w.getId(), w.getName());
        return row(w);
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    WorkerLedger.Row update(@PathVariable Long id, @Valid @RequestBody WorkerRequest req) {
        Worker w = load(id);
        apply(w, req);
        postDailyWork(w);
        audit.record(w.getOrganizationId(), "WORKER_UPDATED", "worker", w.getId(), w.getName());
        return row(w);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable Long id) {
        Worker w = load(id);
        workers.delete(w);
        audit.record(w.getOrganizationId(), "WORKER_DELETED", "worker", w.getId(), w.getName());
    }

    /** Sets one month's work and paid amounts; both empty removes the month. */
    @PutMapping("/{id}/months/{year}/{month}")
    @PreAuthorize(WRITE)
    @Transactional
    void setMonth(@PathVariable Long id, @PathVariable int year, @PathVariable int month, @Valid @RequestBody MonthRequest req) {
        if (month < 1 || month > 12 || year < 2000 || year > 2100) throw new IllegalArgumentException("Invalid month");
        Worker w = load(id);
        BigDecimal work = req.days() != null && req.rate() != null ? req.days().multiply(req.rate()) : req.work();
        putMonth(w.getId(), year, month, work, req.paid(), req.days(), req.rate(), req.paidMethod());
    }

    /** Every worker's daily entries in a month, by date. */
    @GetMapping("/days")
    @Transactional(readOnly = true)
    List<DayEntry> days(@RequestParam int year, @RequestParam int month) {
        if (month < 1 || month > 12) throw new IllegalArgumentException("Invalid month");
        Map<Long, Worker> byId = workers.findByOrganizationIdOrderByPositionAscIdAsc(CurrentUser.orgId()).stream()
                .collect(Collectors.toMap(Worker::getId, Function.identity()));
        if (byId.isEmpty()) return List.of();
        java.time.YearMonth ym = java.time.YearMonth.of(year, month);
        return days.findByWorkerIdInAndEntryDateBetweenOrderByEntryDateAscIdAsc(byId.keySet(), ym.atDay(1), ym.atEndOfMonth())
                .stream().map(d -> new DayEntry(d.getId(), d.getWorkerId(), byId.get(d.getWorkerId()).getName(), d.getEntryDate(),
                        d.getWork(), d.getPaid(), nz(d.getWork()).subtract(nz(d.getPaid())), d.getNotes(),
                        d.getMethod() == null ? null : d.getMethod().name(),
                        d.getCreatedBy(), d.getCreatedAt(), d.getUpdatedBy(), d.getUpdatedAt())).toList();
    }

    /**
     * Sets one worker's figures for a day; work, paid and notes all empty removes the day. The month total moves
     * by the difference, so a day typed here shows in the monthly ledger, the statement and Accounts.
     */
    @PutMapping("/{id}/days/{date}")
    @PreAuthorize(WRITE)
    @Transactional
    void setDay(@PathVariable Long id, @PathVariable @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate date,
                @Valid @RequestBody DayRequest req) {
        Worker w = load(id);
        WorkerDay d = days.findByWorkerIdAndEntryDate(w.getId(), date).orElse(null);
        BigDecimal oldWork = d == null ? null : d.getWork(), oldPaid = d == null ? null : d.getPaid();
        String notes = blank(req.notes());
        if (req.work() == null && req.paid() == null && notes == null) {
            if (d != null) days.delete(d);
        } else {
            if (d == null) {
                d = new WorkerDay();
                d.setWorkerId(w.getId());
                d.setEntryDate(date);
                d.setCreatedBy(CurrentUser.get().email());
            } else {
                d.setUpdatedBy(CurrentUser.get().email());
                d.setUpdatedAt(java.time.Instant.now());
            }
            d.setWork(req.work());
            d.setPaid(req.paid());
            d.setMethod(req.paidMethod());
            d.setNotes(notes);
            days.save(d);
        }
        BigDecimal dWork = nz(req.work()).subtract(nz(oldWork)), dPaid = nz(req.paid()).subtract(nz(oldPaid));
        if (dWork.signum() != 0 || dPaid.signum() != 0) shift(w.getId(), date, dWork, dPaid);
        // Record the payment method on the month when a paid amount is entered for the day.
        if (req.paidMethod() != null && nz(req.paid()).signum() != 0)
            months.findByWorkerIdAndYearAndMonth(w.getId(), date.getYear(), date.getMonthValue())
                    .ifPresent(m -> m.setPaidMethod(req.paidMethod()));
        audit.record(w.getOrganizationId(), "WORKER_DAY_SET", "worker", w.getId(),
                w.getName() + " · " + date + " · " + nz(req.work()) + " / " + nz(req.paid()));
    }

    /** Reads the company's Excel register (worker rows, WORK / PAID per month) into the ledger for that year. */
    @PutMapping("/import")
    @PreAuthorize(WRITE)
    @Transactional
    ImportResult importExcel(@RequestBody byte[] file, @RequestParam(required = false) Integer year) {
        WorkerExcel.Parsed parsed;
        try {
            parsed = WorkerExcel.read(new ByteArrayInputStream(file));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("BAD_FILE", e.getMessage());
        } catch (Exception e) {
            throw ApiException.badRequest("BAD_FILE", "This is not an Excel worker ledger (.xlsx)");
        }
        if (parsed.workers().isEmpty()) throw ApiException.badRequest("BAD_FILE", "No workers found in this file");
        int y = year != null ? year : parsed.year() != null ? parsed.year() : thisYear();
        Long orgId = CurrentUser.orgId();
        Map<String, Worker> byName = workers.findByOrganizationIdOrderByPositionAscIdAsc(orgId).stream()
                .collect(Collectors.toMap(w -> key(w.getName()), Function.identity(), (a, b) -> a));
        int created = 0, filled = 0, position = workers.maxPosition(orgId);
        for (WorkerExcel.Imported im : parsed.workers()) {
            Worker w = byName.get(key(im.name()));
            if (w == null) {
                w = new Worker();
                w.setOrganizationId(orgId);
                w.setName(im.name());
                w.setPosition(++position);
                workers.save(w);
                byName.put(key(im.name()), w);
                created++;
            }
            for (int m = 0; m < 12; m++) {
                if (put(w.getId(), y, m + 1, im.work()[m], im.paid()[m])) filled++;
            }
        }
        audit.record(orgId, "WORKER_LEDGER_IMPORTED", "worker", null,
                y + " · " + parsed.workers().size() + " workers · " + filled + " months");
        return new ImportResult(y, parsed.workers().size(), created, filled);
    }

    /** Reads a day-work workbook (a sheet per month) and suggests a saved worker for each name; nothing is saved. */
    @PutMapping("/import-days")
    @PreAuthorize(WRITE)
    @Transactional(readOnly = true)
    DayPreview previewDays(@RequestBody byte[] file) {
        Long orgId = CurrentUser.orgId();
        List<WorkerExcel.DaySheet> sheets = readDays(file);
        List<Worker> list = workers.findByOrganizationIdOrderByPositionAscIdAsc(orgId);
        List<DayPreviewSheet> out = sheets.stream().map(sh -> new DayPreviewSheet(sh.sheet(), sh.year(), sh.month(),
                sh.lines().stream().map(l -> {
                    Long id = WorkerMatcher.suggest(l.name(), list);
                    WorkerMonth cur = id == null ? null : months.findByWorkerIdAndYearAndMonth(id, sh.year(), sh.month()).orElse(null);
                    return new DayPreviewLine(l.name(), l.days(), l.rate(), l.work(), l.paid(), id,
                            cur == null ? null : cur.getWork(), cur == null ? null : cur.getPaid());
                }).toList())).toList();
        return new DayPreview(out, build(orgId, thisYear()).workers());
    }

    /**
     * Saves a day-work workbook with the matches the user confirmed: {@code map} is JSON from each name on the
     * sheets to a worker id, 0 to add the name as a new worker, or -1 to skip it. Each line sets that worker's
     * month to days × rate and the amount paid.
     */
    @PutMapping("/import-days/apply")
    @PreAuthorize(WRITE)
    @Transactional
    DayApplyResult applyDays(@RequestBody byte[] file, @RequestParam String map) {
        Long orgId = CurrentUser.orgId();
        Map<String, Long> chosen;
        try {
            chosen = new tools.jackson.databind.json.JsonMapper().readValue(map,
                    new tools.jackson.core.type.TypeReference<Map<String, Long>>() {});
        } catch (RuntimeException e) {
            throw ApiException.badRequest("BAD_MAP", "The worker matches could not be read");
        }
        Map<Long, Worker> byId = workers.findByOrganizationIdOrderByPositionAscIdAsc(orgId).stream()
                .collect(Collectors.toMap(Worker::getId, Function.identity()));
        Map<String, Worker> created = new java.util.HashMap<>();
        int position = workers.maxPosition(orgId), filled = 0, skipped = 0;
        List<WorkerExcel.DaySheet> sheets = readDays(file);
        for (WorkerExcel.DaySheet sh : sheets) {
            for (WorkerExcel.DayLine l : sh.lines()) {
                Long id = chosen.get(l.name());
                if (id == null || id < 0) {
                    skipped++;
                    continue;
                }
                Worker w;
                if (id == 0) {
                    w = created.get(key(l.name()));
                    if (w == null) {
                        w = new Worker();
                        w.setOrganizationId(orgId);
                        w.setName(l.name());
                        w.setPosition(++position);
                        workers.save(w);
                        created.put(key(l.name()), w);
                    }
                } else {
                    w = byId.get(id);
                    if (w == null) throw ApiException.notFound("Worker");
                }
                put(w.getId(), sh.year(), sh.month(), l.work(), l.paid());
                filled++;
            }
        }
        audit.record(orgId, "WORKER_DAYWORK_IMPORTED", "worker", null,
                sheets.size() + " sheets · " + filled + " months · " + created.size() + " new workers");
        return new DayApplyResult(sheets.size(), filled, created.size(), skipped);
    }

    /** A month's payment statement on the letterhead, with a signature space for each worker. */
    @GetMapping("/statement/pdf")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> statement(@RequestParam int year, @RequestParam int month) {
        if (month < 1 || month > 12) throw new IllegalArgumentException("Invalid month");
        Long orgId = CurrentUser.orgId();
        byte[] pdf = letterhead.render(org(orgId), "Worker payment statement " + month + "/" + year,
                WorkerLedger.statementHtml(build(orgId, year), month));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename("worker-statement-" + year + "-" + String.format("%02d", month) + ".pdf").build().toString())
                .body(pdf);
    }

    // --- leave -------------------------------------------------------------------------------------

    /**
     * Worker leaves. With {@code month} it returns that month's leaves (the monthly view); without it, the whole
     * year. With {@code workerId} it returns just that worker's history. A leave counts if it overlaps the range.
     */
    @GetMapping("/leaves")
    @Transactional(readOnly = true)
    List<LeaveEntry> leaves(@RequestParam(required = false) Integer year, @RequestParam(required = false) Integer month,
                            @RequestParam(required = false) Long workerId) {
        Map<Long, Worker> byId = workers.findByOrganizationIdOrderByPositionAscIdAsc(CurrentUser.orgId()).stream()
                .collect(Collectors.toMap(Worker::getId, Function.identity()));
        if (byId.isEmpty()) return List.of();
        int y = year == null ? thisYear() : year;
        LocalDate from, to;
        if (month != null) {
            if (month < 1 || month > 12) throw new IllegalArgumentException("Invalid month");
            java.time.YearMonth ym = java.time.YearMonth.of(y, month);
            from = ym.atDay(1);
            to = ym.atEndOfMonth();
        } else {
            from = LocalDate.of(y, 1, 1);
            to = LocalDate.of(y, 12, 31);
        }
        List<Long> ids = workerId != null ? (byId.containsKey(workerId) ? List.of(workerId) : List.of())
                : List.copyOf(byId.keySet());
        if (ids.isEmpty()) return List.of();
        return leaves.inRange(ids, from, to).stream().map(l -> leaveEntry(l, byId.get(l.getWorkerId()))).toList();
    }

    @PostMapping("/{id}/leaves")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    LeaveEntry addLeave(@PathVariable Long id, @Valid @RequestBody LeaveRequest req) {
        Worker w = load(id);
        WorkerLeave l = new WorkerLeave();
        l.setWorkerId(w.getId());
        l.setCreatedBy(CurrentUser.get().email());
        apply(l, req);
        leaves.save(l);
        audit.record(w.getOrganizationId(), "WORKER_LEAVE_ADDED", "worker", w.getId(), leaveLabel(w, l));
        return leaveEntry(l, w);
    }

    @PutMapping("/leaves/{leaveId}")
    @PreAuthorize(WRITE)
    @Transactional
    LeaveEntry updateLeave(@PathVariable Long leaveId, @Valid @RequestBody LeaveRequest req) {
        WorkerLeave l = loadLeave(leaveId);
        Worker w = load(l.getWorkerId());
        apply(l, req);
        l.setUpdatedBy(CurrentUser.get().email());
        l.setUpdatedAt(java.time.Instant.now());
        audit.record(w.getOrganizationId(), "WORKER_LEAVE_UPDATED", "worker", w.getId(), leaveLabel(w, l));
        return leaveEntry(l, w);
    }

    @DeleteMapping("/leaves/{leaveId}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void deleteLeave(@PathVariable Long leaveId) {
        WorkerLeave l = loadLeave(leaveId);
        Worker w = load(l.getWorkerId());
        leaves.delete(l);
        audit.record(w.getOrganizationId(), "WORKER_LEAVE_DELETED", "worker", w.getId(), leaveLabel(w, l));
    }

    // --- helpers -----------------------------------------------------------------------------------

    private static List<WorkerExcel.DaySheet> readDays(byte[] file) {
        List<WorkerExcel.DaySheet> sheets;
        try {
            sheets = WorkerExcel.readDayWork(new ByteArrayInputStream(file), thisYear());
        } catch (Exception e) {
            throw ApiException.badRequest("BAD_FILE", "This is not an Excel day-work statement (.xlsx)");
        }
        if (sheets.isEmpty())
            throw ApiException.badRequest("BAD_FILE", "No day-work sheets found (WORKER NAME, DAYS, DAILY RATE, PAID)");
        return sheets;
    }

    private WorkerLedger.Result build(Long orgId, int year) {
        List<Worker> list = workers.findByOrganizationIdOrderByPositionAscIdAsc(orgId);
        List<Long> ids = list.stream().map(Worker::getId).toList();
        TreeSet<Integer> years = new TreeSet<>(ids.isEmpty() ? List.of() : months.years(ids));
        years.add(thisYear());
        years.add(year);
        return WorkerLedger.build(year, List.copyOf(years), list,
                ids.isEmpty() ? List.of() : months.findByWorkerIdInAndYear(ids, year));
    }

    /** Stores a month with the daily-wage breakdown and salary method; both work and paid empty removes it. */
    private void putMonth(Long workerId, int year, int month, BigDecimal work, BigDecimal paid, BigDecimal days,
                          BigDecimal rate, InvoicePayment.Method method) {
        WorkerMonth m = months.findByWorkerIdAndYearAndMonth(workerId, year, month).orElse(null);
        if (work == null && paid == null) {
            if (m != null) months.delete(m);
            return;
        }
        if (m == null) {
            m = new WorkerMonth();
            m.setWorkerId(workerId);
            m.setYear(year);
            m.setMonth(month);
        }
        m.setWork(work);
        m.setPaid(paid);
        m.setDays(days);
        m.setRate(rate);
        m.setPaidMethod(method);
        months.save(m);
    }

    /** Stores a month; returns whether it has any figure. */
    private boolean put(Long workerId, int year, int month, BigDecimal work, BigDecimal paid) {
        WorkerMonth m = months.findByWorkerIdAndYearAndMonth(workerId, year, month).orElse(null);
        if (work == null && paid == null) {
            if (m != null) months.delete(m);
            return false;
        }
        if (m == null) {
            m = new WorkerMonth();
            m.setWorkerId(workerId);
            m.setYear(year);
            m.setMonth(month);
        }
        m.setWork(work);
        m.setPaid(paid);
        months.save(m);
        return true;
    }

    /** Moves a month's work and paid by a day's change; never below zero, and a month left with nothing is removed. */
    private void shift(Long workerId, LocalDate date, BigDecimal dWork, BigDecimal dPaid) {
        WorkerMonth m = months.findByWorkerIdAndYearAndMonth(workerId, date.getYear(), date.getMonthValue()).orElse(null);
        BigDecimal work = m == null ? null : m.getWork(), paid = m == null ? null : m.getPaid();
        if (dWork.signum() != 0) work = nz(work).add(dWork).max(BigDecimal.ZERO);
        if (dPaid.signum() != 0) paid = nz(paid).add(dPaid).max(BigDecimal.ZERO);
        if (nz(work).signum() == 0 && nz(paid).signum() == 0) {
            work = null;
            paid = null;
        }
        put(workerId, date.getYear(), date.getMonthValue(), work, paid);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private WorkerLedger.Row row(Worker w) {
        return WorkerLedger.build(thisYear(), List.of(thisYear()), List.of(w), List.of()).workers().getFirst();
    }

    private static void apply(Worker w, WorkerRequest req) {
        w.setName(req.name().strip().replaceAll("\\s+", " "));
        w.setPhone(blank(req.phone()));
        w.setIqamaNumber(blank(req.iqamaNumber()));
        w.setNationality(blank(req.nationality()));
        w.setNotes(blank(req.notes()));
        if (req.active() != null) w.setActive(req.active());
        if (req.daily() != null) w.setDaily(req.daily());
        w.setDailyRate(req.dailyRate());
        w.setDays(req.days());
    }

    /**
     * For a daily-wage worker, records the work total (days × daily rate) as the current month's cost, keeping
     * any amount already paid that month. Clearing the days or rate removes the work figure but keeps paid.
     */
    private void postDailyWork(Worker w) {
        if (!w.isDaily()) return;
        LocalDate now = LocalDate.now(InvoiceApiApplication.KSA);
        WorkerMonth m = months.findByWorkerIdAndYearAndMonth(w.getId(), now.getYear(), now.getMonthValue()).orElse(null);
        BigDecimal work = w.getDays() != null && w.getDailyRate() != null ? w.getDays().multiply(w.getDailyRate()) : null;
        BigDecimal paid = m == null ? null : m.getPaid();
        if (work == null && (paid == null || paid.signum() == 0)) {
            if (m != null) months.delete(m);
            return;
        }
        if (m == null) {
            m = new WorkerMonth();
            m.setWorkerId(w.getId());
            m.setYear(now.getYear());
            m.setMonth(now.getMonthValue());
        }
        m.setWork(work);
        m.setDays(w.getDays());
        m.setRate(w.getDailyRate());
        months.save(m);
    }

    private Worker load(Long id) {
        return workers.findByIdAndOrganizationId(id, CurrentUser.orgId()).orElseThrow(() -> ApiException.notFound("Worker"));
    }

    private WorkerLeave loadLeave(Long id) {
        return leaves.findById(id).orElseThrow(() -> ApiException.notFound("Leave"));
    }

    private static void apply(WorkerLeave l, LeaveRequest req) {
        if (req.endDate().isBefore(req.startDate()))
            throw new ApiException.Validation(Map.of("endDate", "End date must be on or after the start date"));
        l.setStartDate(req.startDate());
        l.setEndDate(req.endDate());
        l.setType(req.type() == null ? WorkerLeave.Type.ANNUAL : req.type());
        l.setDeduction(req.deduction() == null || req.deduction().signum() == 0 ? null : req.deduction());
        l.setNotes(blank(req.notes()));
    }

    private static LeaveEntry leaveEntry(WorkerLeave l, Worker w) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(l.getStartDate(), l.getEndDate()) + 1;
        return new LeaveEntry(l.getId(), l.getWorkerId(), w == null ? null : w.getName(), l.getStartDate(), l.getEndDate(),
                days, l.getType().name(), l.getDeduction(), l.getNotes(), l.getCreatedBy(), l.getCreatedAt(),
                l.getUpdatedBy(), l.getUpdatedAt());
    }

    private static String leaveLabel(Worker w, WorkerLeave l) {
        return w.getName() + " · " + l.getStartDate() + "→" + l.getEndDate() + " · " + l.getType();
    }

    private Organization org(Long id) {
        return orgs.findById(id).orElseThrow();
    }

    private static String key(String name) {
        return name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static int thisYear() {
        return LocalDate.now(InvoiceApiApplication.KSA).getYear();
    }
}
