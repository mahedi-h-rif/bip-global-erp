package com.fatoora.api.hr;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Employees, company holidays, the leave ledger and salary advances. */
@RestController
@RequestMapping("/api/v1/hr")
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','OPERATOR')")
public class HrController {

    static final String WRITE = "hasAnyRole('ADMIN','ACCOUNTANT')";

    public record Employee(Long id, @Size(max = 30) String code, @NotBlank @Size(max = 255) String name, @Size(max = 255) String nameAr,
                           @Pattern(regexp = "[A-Z]{2}") String nationality, @Size(max = 40) String idNumber, LocalDate idExpiry,
                           @Size(max = 40) String passportNumber, LocalDate passportExpiry, @Size(max = 150) String jobTitle,
                           @Size(max = 100) String department, @NotNull LocalDate joinDate, LocalDate endDate, String status,
                           @Size(max = 40) String phone, @Email String email, @Size(max = 100) String bankName, @Size(max = 40) String iban,
                           @NotNull @DecimalMin("0") BigDecimal basic, @DecimalMin("0") BigDecimal housing, @DecimalMin("0") BigDecimal transport,
                           @DecimalMin("0") BigDecimal otherAllowance, @DecimalMin("0") @DecimalMax("100") BigDecimal gosiPct,
                           @DecimalMin("0") BigDecimal annualLeaveDays, @Size(max = 2000) String notes,
                           /** Read-only. */ BigDecimal grossSalary, BigDecimal leaveBalance, BigDecimal advanceOutstanding,
                           Long idExpiresInDays, Long passportExpiresInDays) {}

    public record Holiday(Long id, @NotNull LocalDate date, @NotBlank @Size(max = 200) String name) {}

    public record Leave(Long id, @NotNull Long employeeId, String employeeName, @NotNull @Pattern(regexp = "ANNUAL|SICK|UNPAID|OTHER") String type,
                        @NotNull LocalDate startDate, @NotNull LocalDate endDate, BigDecimal days, @Size(max = 1000) String notes) {}

    public record Advance(Long id, @NotNull Long employeeId, String employeeName, LocalDate date,
                          @NotNull @DecimalMin("0.01") BigDecimal amount, @NotNull @DecimalMin("0.01") BigDecimal installment,
                          @Size(max = 1000) String notes, /** Read-only. */ BigDecimal repaid, BigDecimal outstanding) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public HrController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // --- employees -------------------------------------------------------------------------------

    @GetMapping("/employees")
    List<Employee> employees(@RequestParam(defaultValue = "false") boolean all) {
        Long orgId = CurrentUser.orgId();
        LocalDate today = LocalDate.now(InvoiceApiApplication.KSA);
        Map<Long, BigDecimal> outstanding = advancesOutstanding(orgId);
        return jdbc.query("SELECT * FROM employee WHERE organization_id = ? AND (? OR status = 'ACTIVE') ORDER BY lower(name)", (rs, i) -> {
            long id = rs.getLong("id");
            LocalDate idExp = rs.getObject("id_expiry", LocalDate.class), ppExp = rs.getObject("passport_expiry", LocalDate.class);
            BigDecimal gross = rs.getBigDecimal("basic").add(rs.getBigDecimal("housing")).add(rs.getBigDecimal("transport")).add(rs.getBigDecimal("other_allowance"));
            return new Employee(id, rs.getString("code"), rs.getString("name"), rs.getString("name_ar"), rs.getString("nationality"),
                    rs.getString("id_number"), idExp, rs.getString("passport_number"), ppExp, rs.getString("job_title"), rs.getString("department"),
                    rs.getObject("join_date", LocalDate.class), rs.getObject("end_date", LocalDate.class), rs.getString("status"),
                    rs.getString("phone"), rs.getString("email"), rs.getString("bank_name"), rs.getString("iban"), rs.getBigDecimal("basic"),
                    rs.getBigDecimal("housing"), rs.getBigDecimal("transport"), rs.getBigDecimal("other_allowance"), rs.getBigDecimal("gosi_pct"),
                    rs.getBigDecimal("annual_leave_days"), rs.getString("notes"), gross,
                    leaveBalance(id, rs.getBigDecimal("annual_leave_days"), rs.getObject("join_date", LocalDate.class), today.getYear()),
                    outstanding.getOrDefault(id, BigDecimal.ZERO),
                    idExp == null ? null : ChronoUnit.DAYS.between(today, idExp), ppExp == null ? null : ChronoUnit.DAYS.between(today, ppExp));
        }, orgId, all);
    }

    @PostMapping("/employees")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Employee createEmployee(@Valid @RequestBody Employee e) {
        Long orgId = CurrentUser.orgId();
        String code = e.code() == null || e.code().isBlank()
                ? "E%04d".formatted(jdbc.queryForObject("SELECT count(*) + 1 FROM employee WHERE organization_id = ?", Integer.class, orgId))
                : e.code().trim();
        requireUniqueCode(orgId, code, null);
        String nat = e.nationality() == null ? "SA" : e.nationality();
        Long id = jdbc.queryForObject("""
                INSERT INTO employee (organization_id, code, name, join_date, nationality, gosi_pct) VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
                Long.class, orgId, code, e.name().trim(), e.joinDate(), nat,
                e.gosiPct() != null ? e.gosiPct() : "SA".equals(nat) ? new BigDecimal("9.75") : BigDecimal.ZERO);
        write(id, e, code);
        audit.record(orgId, "EMPLOYEE_CREATED", "employee", id, code + " " + e.name());
        return employee(id);
    }

    @PutMapping("/employees/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    Employee updateEmployee(@PathVariable Long id, @Valid @RequestBody Employee e) {
        Employee old = employee(id);
        String code = e.code() == null || e.code().isBlank() ? old.code() : e.code().trim();
        requireUniqueCode(CurrentUser.orgId(), code, id);
        write(id, e, code);
        audit.record(CurrentUser.orgId(), "EMPLOYEE_UPDATED", "employee", id, code + " " + e.name());
        return employee(id);
    }

    private void write(Long id, Employee e, String code) {
        jdbc.update("""
                UPDATE employee SET code = ?, name = ?, name_ar = ?, nationality = COALESCE(?, nationality), id_number = ?, id_expiry = ?,
                  passport_number = ?, passport_expiry = ?, job_title = ?, department = ?, join_date = ?, end_date = ?,
                  status = ?, phone = ?, email = ?, bank_name = ?, iban = ?, basic = ?, housing = ?, transport = ?, other_allowance = ?,
                  gosi_pct = COALESCE(?, gosi_pct), annual_leave_days = COALESCE(?, annual_leave_days), notes = ? WHERE id = ?""",
                code, e.name().trim(), b(e.nameAr()), e.nationality(), b(e.idNumber()), e.idExpiry(), b(e.passportNumber()), e.passportExpiry(),
                b(e.jobTitle()), b(e.department()), e.joinDate(), e.endDate(),
                e.endDate() != null && !e.endDate().isAfter(LocalDate.now(InvoiceApiApplication.KSA)) ? "TERMINATED"
                        : "TERMINATED".equals(e.status()) ? "TERMINATED" : "ACTIVE",
                b(e.phone()), b(e.email()), b(e.bankName()), b(e.iban()), e.basic(), z(e.housing()), z(e.transport()), z(e.otherAllowance()),
                e.gosiPct(), e.annualLeaveDays(), b(e.notes()), id);
    }

    Employee employee(Long id) {
        return employees(true).stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Employee"));
    }

    // --- holidays --------------------------------------------------------------------------------

    @GetMapping("/holidays")
    List<Holiday> holidays(@RequestParam(required = false) Integer year) {
        int y = year == null ? LocalDate.now(InvoiceApiApplication.KSA).getYear() : year;
        return jdbc.query("SELECT * FROM holiday WHERE organization_id = ? AND extract(year FROM holiday_date) = ? ORDER BY holiday_date",
                (rs, i) -> new Holiday(rs.getLong("id"), rs.getObject("holiday_date", LocalDate.class), rs.getString("name")), CurrentUser.orgId(), y);
    }

    @PostMapping("/holidays")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Holiday addHoliday(@Valid @RequestBody Holiday h) {
        Long orgId = CurrentUser.orgId();
        Long id = jdbc.queryForObject("""
                INSERT INTO holiday (organization_id, holiday_date, name) VALUES (?, ?, ?)
                ON CONFLICT (organization_id, holiday_date) DO UPDATE SET name = EXCLUDED.name RETURNING id""",
                Long.class, orgId, h.date(), h.name().trim());
        audit.record(orgId, "HOLIDAY_SET", "holiday", id, h.date() + " " + h.name());
        return new Holiday(id, h.date(), h.name().trim());
    }

    @DeleteMapping("/holidays/{id}")
    @PreAuthorize(WRITE)
    void deleteHoliday(@PathVariable Long id) {
        jdbc.update("DELETE FROM holiday WHERE id = ? AND organization_id = ?", id, CurrentUser.orgId());
    }

    // --- leave -----------------------------------------------------------------------------------

    @GetMapping("/leaves")
    List<Leave> leaves(@RequestParam(required = false) Long employeeId, @RequestParam(required = false) Integer year) {
        return jdbc.query("""
                SELECT l.*, e.name AS ename FROM leave_entry l JOIN employee e ON e.id = l.employee_id
                WHERE l.organization_id = ? AND (?::bigint IS NULL OR l.employee_id = ?::bigint)
                  AND (?::int IS NULL OR extract(year FROM l.start_date) = ?::int) ORDER BY l.start_date DESC""",
                (rs, i) -> new Leave(rs.getLong("id"), rs.getLong("employee_id"), rs.getString("ename"), rs.getString("leave_type"),
                        rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class), rs.getBigDecimal("days"),
                        rs.getString("notes")), CurrentUser.orgId(), employeeId, employeeId, year, year);
    }

    @PostMapping("/leaves")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Leave addLeave(@Valid @RequestBody Leave l) {
        Long orgId = CurrentUser.orgId();
        Employee e = employee(l.employeeId());
        if (l.endDate().isBefore(l.startDate())) throw new ApiException.Validation(Map.of("endDate", "The end date is before the start date"));
        Integer overlap = jdbc.queryForObject("""
                SELECT count(*) FROM leave_entry WHERE employee_id = ? AND start_date <= ? AND end_date >= ?""", Integer.class,
                l.employeeId(), l.endDate(), l.startDate());
        if (overlap != null && overlap > 0) throw ApiException.badRequest("leave_overlap", "This employee already has leave on some of these days");
        BigDecimal days = BigDecimal.valueOf(workingDays(orgId, l.startDate(), l.endDate()));
        BigDecimal balance = leaveBalance(e.id(), e.annualLeaveDays(), e.joinDate(), l.startDate().getYear());
        if ("ANNUAL".equals(l.type()) && days.compareTo(balance) > 0)
            throw ApiException.badRequest("leave_balance", "Only " + balance.stripTrailingZeros().toPlainString()
                    + " annual leave days left this year; record the rest as unpaid leave");
        Long id = jdbc.queryForObject("""
                INSERT INTO leave_entry (organization_id, employee_id, leave_type, start_date, end_date, days, notes, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, orgId, l.employeeId(), l.type(), l.startDate(), l.endDate(),
                days, b(l.notes()), CurrentUser.get().email());
        audit.record(orgId, "LEAVE_RECORDED", "employee", l.employeeId(), l.type() + " " + l.startDate() + "–" + l.endDate() + " (" + days + " days)");
        return new Leave(id, l.employeeId(), e.name(), l.type(), l.startDate(), l.endDate(), days, b(l.notes()));
    }

    @DeleteMapping("/leaves/{id}")
    @PreAuthorize(WRITE)
    void deleteLeave(@PathVariable Long id) {
        jdbc.update("DELETE FROM leave_entry WHERE id = ? AND organization_id = ?", id, CurrentUser.orgId());
    }

    // --- salary advances -------------------------------------------------------------------------

    @GetMapping("/advances")
    List<Advance> advances(@RequestParam(required = false) Long employeeId) {
        Long orgId = CurrentUser.orgId();
        Map<Long, BigDecimal> repaid = repaidPerAdvance(orgId);
        return jdbc.query("""
                SELECT a.*, e.name AS ename FROM salary_advance a JOIN employee e ON e.id = a.employee_id
                WHERE a.organization_id = ? AND (?::bigint IS NULL OR a.employee_id = ?::bigint) ORDER BY a.advance_date DESC, a.id DESC""",
                (rs, i) -> {
                    BigDecimal r = repaid.getOrDefault(rs.getLong("id"), BigDecimal.ZERO);
                    return new Advance(rs.getLong("id"), rs.getLong("employee_id"), rs.getString("ename"), rs.getObject("advance_date", LocalDate.class),
                            rs.getBigDecimal("amount"), rs.getBigDecimal("installment"), rs.getString("notes"), r, rs.getBigDecimal("amount").subtract(r));
                }, orgId, employeeId, employeeId);
    }

    @PostMapping("/advances")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Advance addAdvance(@Valid @RequestBody Advance a) {
        Long orgId = CurrentUser.orgId();
        Employee e = employee(a.employeeId());
        if (a.installment().compareTo(a.amount()) > 0) throw new ApiException.Validation(Map.of("installment", "The installment is more than the advance"));
        Long id = jdbc.queryForObject("""
                INSERT INTO salary_advance (organization_id, employee_id, advance_date, amount, installment, notes, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, orgId, a.employeeId(),
                a.date() == null ? LocalDate.now(InvoiceApiApplication.KSA) : a.date(), a.amount(), a.installment(), b(a.notes()), CurrentUser.get().email());
        audit.record(orgId, "SALARY_ADVANCE", "employee", a.employeeId(), e.name() + " " + a.amount() + " SAR, " + a.installment() + "/month");
        return advances(a.employeeId()).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    // --- shared rules (used by payroll) ----------------------------------------------------------

    /** Working days between two dates (Saudi weekend Friday + Saturday, company holidays excluded). */
    long workingDays(Long orgId, LocalDate from, LocalDate to) {
        Set<LocalDate> hol = new HashSet<>(jdbc.queryForList("SELECT holiday_date FROM holiday WHERE organization_id = ? AND holiday_date BETWEEN ? AND ?",
                LocalDate.class, orgId, from, to));
        long n = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1))
            if (d.getDayOfWeek() != DayOfWeek.FRIDAY && d.getDayOfWeek() != DayOfWeek.SATURDAY && !hol.contains(d)) n++;
        return n;
    }

    /** Annual entitlement (pro-rated in the joining year) minus annual leave taken in that year. */
    BigDecimal leaveBalance(Long employeeId, BigDecimal annualDays, LocalDate joinDate, int year) {
        BigDecimal entitlement = annualDays;
        if (joinDate.getYear() == year)
            entitlement = annualDays.multiply(BigDecimal.valueOf(13 - joinDate.getMonthValue())).divide(BigDecimal.valueOf(12), 1, RoundingMode.HALF_UP);
        else if (joinDate.getYear() > year) entitlement = BigDecimal.ZERO;
        BigDecimal used = jdbc.queryForObject("""
                SELECT COALESCE(sum(days), 0) FROM leave_entry WHERE employee_id = ? AND leave_type = 'ANNUAL' AND extract(year FROM start_date) = ?""",
                BigDecimal.class, employeeId, year);
        return entitlement.subtract(used);
    }

    /** Advance repayments come from approved or paid payroll runs, applied to each employee's advances oldest first. */
    Map<Long, BigDecimal> repaidPerAdvance(Long orgId) {
        Map<Long, BigDecimal> deducted = new HashMap<>();
        jdbc.query("""
                SELECT l.employee_id, sum(l.advance_deduction) FROM payroll_line l JOIN payroll_run r ON r.id = l.payroll_run_id
                WHERE r.organization_id = ? AND r.status IN ('APPROVED','PAID') GROUP BY l.employee_id""",
                rs -> { deducted.put(rs.getLong(1), rs.getBigDecimal(2)); }, orgId);
        Map<Long, BigDecimal> out = new HashMap<>();
        Map<Long, BigDecimal> left = new HashMap<>(deducted);
        jdbc.query("SELECT id, employee_id, amount FROM salary_advance WHERE organization_id = ? ORDER BY advance_date, id", rs -> {
            long emp = rs.getLong(2);
            BigDecimal pool = left.getOrDefault(emp, BigDecimal.ZERO);
            BigDecimal take = pool.min(rs.getBigDecimal(3));
            out.put(rs.getLong(1), take);
            left.put(emp, pool.subtract(take));
        }, orgId);
        return out;
    }

    Map<Long, BigDecimal> advancesOutstanding(Long orgId) {
        Map<Long, BigDecimal> repaid = repaidPerAdvance(orgId);
        Map<Long, BigDecimal> out = new HashMap<>();
        jdbc.query("SELECT id, employee_id, amount FROM salary_advance WHERE organization_id = ?", rs -> {
            out.merge(rs.getLong(2), rs.getBigDecimal(3).subtract(repaid.getOrDefault(rs.getLong(1), BigDecimal.ZERO)), BigDecimal::add);
        }, orgId);
        return out;
    }

    /** This month's advance deduction: the installment of each advance still open, capped at what is left on it. */
    BigDecimal advanceDeduction(Long orgId, Long employeeId, LocalDate monthEnd) {
        Map<Long, BigDecimal> repaid = repaidPerAdvance(orgId);
        BigDecimal total = BigDecimal.ZERO;
        for (var row : jdbc.queryForList("SELECT id, amount, installment FROM salary_advance WHERE employee_id = ? AND advance_date <= ?", employeeId, monthEnd)) {
            BigDecimal left = ((BigDecimal) row.get("amount")).subtract(repaid.getOrDefault(((Number) row.get("id")).longValue(), BigDecimal.ZERO));
            if (left.signum() > 0) total = total.add(left.min((BigDecimal) row.get("installment")));
        }
        return total;
    }

    private void requireUniqueCode(Long orgId, String code, Long exceptId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM employee WHERE organization_id = ? AND lower(code) = lower(?) AND (?::bigint IS NULL OR id <> ?::bigint)",
                Integer.class, orgId, code, exceptId, exceptId);
        if (n != null && n > 0) throw new ApiException.Validation(Map.of("code", "An employee with this code already exists"));
    }

    static String b(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static BigDecimal z(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
