package com.fatoora.api.hr;

import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.AmountInWords;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.organization.Organization;
import com.fatoora.api.organization.OrganizationRepository;
import com.fatoora.api.pdf.LetterheadPdf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Monthly payroll runs (PAY-YYYY-MM): DRAFT (generated, editable) → APPROVED (admin; locks figures and counts advance
 * repayments) → PAID (posts the total as a salary expense in Accounts). Net = basic + housing + transport + other
 * allowances (pro-rated for joiners/leavers) + overtime + additions − GOSI − advance − unpaid leave − other deductions.
 */
@RestController
@RequestMapping("/api/v1/payroll")
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','OPERATOR')")
public class PayrollController {

    private static final BigDecimal THIRTY = new BigDecimal("30");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public record Line(Long id, Long employeeId, String employeeCode, String employeeName, String jobTitle, String iban,
                       BigDecimal basic, BigDecimal housing, BigDecimal transport, BigDecimal otherAllowance,
                       @DecimalMin("0") BigDecimal overtime, @DecimalMin("0") BigDecimal additions, BigDecimal gosi,
                       @DecimalMin("0") BigDecimal advanceDeduction, BigDecimal unpaidLeaveDays, BigDecimal unpaidLeaveDeduction,
                       @DecimalMin("0") BigDecimal otherDeductions, BigDecimal daysWorked, BigDecimal gross, BigDecimal deductions,
                       BigDecimal net, @Size(max = 1000) String notes) {}

    public record Run(Long id, String number, int year, int month, String status, String paymentMethod, String approvedBy,
                      Instant approvedAt, String paidBy, Instant paidAt, String createdBy, Instant createdAt,
                      BigDecimal totalGross, BigDecimal totalDeductions, BigDecimal totalNet, List<Line> lines) {}

    public record CreateRequest(@NotNull @Min(2020) @Max(2100) Integer year, @NotNull @Min(1) @Max(12) Integer month) {}

    public record LineUpdate(@DecimalMin("0") BigDecimal overtime, @DecimalMin("0") BigDecimal additions,
                             @DecimalMin("0") BigDecimal advanceDeduction, @DecimalMin("0") BigDecimal otherDeductions,
                             @DecimalMin("0") BigDecimal gosi, @Size(max = 1000) String notes) {}

    public record PayRequest(@NotBlank String method) {}

    private final JdbcTemplate jdbc;
    private final HrController hr;
    private final OrganizationRepository orgs;
    private final LetterheadPdf letterhead;
    private final AuditService audit;

    public PayrollController(JdbcTemplate jdbc, HrController hr, OrganizationRepository orgs, LetterheadPdf letterhead, AuditService audit) {
        this.jdbc = jdbc;
        this.hr = hr;
        this.orgs = orgs;
        this.letterhead = letterhead;
        this.audit = audit;
    }

    @GetMapping("/runs")
    List<Run> runs() {
        Long orgId = CurrentUser.orgId();
        List<Run> out = new ArrayList<>();
        for (Long id : jdbc.queryForList("SELECT id FROM payroll_run WHERE organization_id = ? ORDER BY year DESC, month DESC", Long.class, orgId))
            out.add(run(orgId, id));
        return out;
    }

    @GetMapping("/runs/{id}")
    Run get(@PathVariable Long id) {
        return run(CurrentUser.orgId(), id);
    }

    /** Generates the month's draft: one line per employee employed during the month. */
    @PostMapping("/runs")
    @PreAuthorize(HrController.WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Run create(@Valid @RequestBody CreateRequest req) {
        Long orgId = CurrentUser.orgId();
        if (jdbc.queryForObject("SELECT count(*) FROM payroll_run WHERE organization_id = ? AND year = ? AND month = ?",
                Integer.class, orgId, req.year(), req.month()) > 0)
            throw ApiException.conflict("payroll_exists", "A payroll for %d-%02d already exists".formatted(req.year(), req.month()));
        String number = "PAY-%d-%02d".formatted(req.year(), req.month());
        Long id = jdbc.queryForObject("INSERT INTO payroll_run (organization_id, number, year, month, created_by) VALUES (?, ?, ?, ?, ?) RETURNING id",
                Long.class, orgId, number, req.year(), req.month(), CurrentUser.get().email());
        generate(orgId, id, YearMonth.of(req.year(), req.month()));
        audit.record(orgId, "PAYROLL_CREATED", "payroll_run", id, number);
        return run(orgId, id);
    }

    /** Rebuilds a draft from current employee data (e.g. after a salary change or new leave). Manual extras are kept. */
    @PostMapping("/runs/{id}/recalculate")
    @PreAuthorize(HrController.WRITE)
    @Transactional
    Run recalculate(@PathVariable Long id) {
        Long orgId = CurrentUser.orgId();
        Run r = requireDraft(orgId, id);
        Map<Long, Line> keep = new HashMap<>();
        r.lines().forEach(l -> keep.put(l.employeeId(), l));
        jdbc.update("DELETE FROM payroll_line WHERE payroll_run_id = ?", id);
        generate(orgId, id, YearMonth.of(r.year(), r.month()));
        for (Line l : run(orgId, id).lines()) {
            Line old = keep.get(l.employeeId());
            if (old != null) update(id, l.id(), new LineUpdate(old.overtime(), old.additions(), null, old.otherDeductions(), null, old.notes()));
        }
        return run(orgId, id);
    }

    @PutMapping("/runs/{id}/lines/{lineId}")
    @PreAuthorize(HrController.WRITE)
    @Transactional
    Run update(@PathVariable Long id, @PathVariable Long lineId, @Valid @RequestBody LineUpdate u) {
        Long orgId = CurrentUser.orgId();
        requireDraft(orgId, id);
        Map<String, Object> l = jdbc.queryForList("SELECT * FROM payroll_line WHERE id = ? AND payroll_run_id = ?", lineId, id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Payroll line"));
        BigDecimal overtime = pick(u.overtime(), l.get("overtime")), additions = pick(u.additions(), l.get("additions"));
        BigDecimal advance = pick(u.advanceDeduction(), l.get("advance_deduction")), other = pick(u.otherDeductions(), l.get("other_deductions"));
        BigDecimal gosi = pick(u.gosi(), l.get("gosi"));
        BigDecimal gross = sum(l, "basic", "housing", "transport", "other_allowance").add(overtime).add(additions);
        BigDecimal net = gross.subtract(gosi).subtract(advance).subtract((BigDecimal) l.get("unpaid_leave_deduction")).subtract(other);
        jdbc.update("""
                UPDATE payroll_line SET overtime = ?, additions = ?, advance_deduction = ?, other_deductions = ?, gosi = ?, net = ?, notes = ?
                WHERE id = ?""", overtime, additions, advance, other, gosi, net, u.notes() == null ? l.get("notes") : HrController.b(u.notes()), lineId);
        return run(orgId, id);
    }

    @PostMapping("/runs/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    Run approve(@PathVariable Long id) {
        Long orgId = CurrentUser.orgId();
        Run r = requireDraft(orgId, id);
        if (r.lines().stream().anyMatch(l -> l.net().signum() < 0))
            throw ApiException.badRequest("negative_net", "A payslip has a negative net salary; lower its deductions first");
        jdbc.update("UPDATE payroll_run SET status = 'APPROVED', approved_by = ?, approved_at = now() WHERE id = ?", CurrentUser.get().email(), id);
        audit.record(orgId, "PAYROLL_APPROVED", "payroll_run", id, r.number() + " net " + r.totalNet());
        return run(orgId, id);
    }

    /** Marks the run paid and posts the net total as one SALARY expense in Accounts. */
    @PostMapping("/runs/{id}/pay")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    Run pay(@PathVariable Long id, @Valid @RequestBody PayRequest req) {
        Long orgId = CurrentUser.orgId();
        Run r = run(orgId, id);
        if (!"APPROVED".equals(r.status())) throw ApiException.conflict("payroll_not_approved", "Approve the payroll before paying it");
        com.fatoora.api.payment.InvoicePayment.Method method;
        try {
            method = com.fatoora.api.payment.InvoicePayment.Method.valueOf(req.method());
        } catch (IllegalArgumentException e) {
            throw new ApiException.Validation(Map.of("method", "Unknown payment method"));
        }
        LocalDate payDate = YearMonth.of(r.year(), r.month()).atEndOfMonth();
        LocalDate today = LocalDate.now(com.fatoora.api.InvoiceApiApplication.KSA);
        jdbc.update("""
                INSERT INTO account_expense (organization_id, entry_date, description, category, amount, method, invoice_number, source, created_by)
                VALUES (?, ?, ?, 'SALARY', ?, ?, ?, 'MANUAL', ?)""", orgId, payDate.isAfter(today) ? today : payDate,
                "Salaries " + r.number() + " (" + r.lines().size() + " employees)", r.totalNet(), method.name(), r.number(), CurrentUser.get().email());
        jdbc.update("UPDATE payroll_run SET status = 'PAID', payment_method = ?, paid_by = ?, paid_at = now() WHERE id = ?", method.name(), CurrentUser.get().email(), id);
        audit.record(orgId, "PAYROLL_PAID", "payroll_run", id, r.number() + " " + r.totalNet() + " SAR by " + method);
        return run(orgId, id);
    }

    @DeleteMapping("/runs/{id}")
    @PreAuthorize(HrController.WRITE)
    @Transactional
    void delete(@PathVariable Long id) {
        Long orgId = CurrentUser.orgId();
        Run r = requireDraft(orgId, id);
        jdbc.update("DELETE FROM payroll_run WHERE id = ?", id);
        audit.record(orgId, "PAYROLL_DELETED", "payroll_run", id, r.number());
    }

    @GetMapping("/runs/{id}/payslip/{lineId}")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> payslip(@PathVariable Long id, @PathVariable Long lineId) {
        Long orgId = CurrentUser.orgId();
        Run r = run(orgId, id);
        Line l = r.lines().stream().filter(x -> x.id().equals(lineId)).findFirst().orElseThrow(() -> ApiException.notFound("Payslip"));
        Organization org = orgs.findById(orgId).orElseThrow();
        String name = "Payslip-" + r.number() + "-" + l.employeeCode() + ".pdf";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name).build().toString())
                .body(letterhead.render(org, "Payslip " + r.number(), payslipHtml(r, l, org)));
    }

    /** Payroll & leave report for a run, as Excel (salary sheet + leave sheet). */
    @GetMapping("/runs/{id}/report.xlsx")
    ResponseEntity<byte[]> report(@PathVariable Long id) throws java.io.IOException {
        Long orgId = CurrentUser.orgId();
        Run r = run(orgId, id);
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out = new java.io.ByteArrayOutputStream()) {
            var sh = wb.createSheet("Payroll " + r.number());
            String[] head = {"Code", "Employee", "Job", "IBAN", "Days", "Basic", "Housing", "Transport", "Other allow.", "Overtime", "Additions",
                    "Gross", "GOSI", "Advance", "Unpaid leave days", "Unpaid leave", "Other ded.", "Net"};
            var hr0 = sh.createRow(0);
            for (int i = 0; i < head.length; i++) hr0.createCell(i).setCellValue(head[i]);
            int n = 1;
            for (Line l : r.lines()) {
                var row = sh.createRow(n++);
                Object[] v = {l.employeeCode(), l.employeeName(), l.jobTitle(), l.iban(), l.daysWorked(), l.basic(), l.housing(), l.transport(),
                        l.otherAllowance(), l.overtime(), l.additions(), l.gross(), l.gosi(), l.advanceDeduction(), l.unpaidLeaveDays(),
                        l.unpaidLeaveDeduction(), l.otherDeductions(), l.net()};
                for (int i = 0; i < v.length; i++) {
                    if (v[i] instanceof BigDecimal b) row.createCell(i).setCellValue(b.doubleValue());
                    else row.createCell(i).setCellValue(v[i] == null ? "" : v[i].toString());
                }
            }
            var tot = sh.createRow(n);
            tot.createCell(1).setCellValue("Total");
            tot.createCell(11).setCellValue(r.totalGross().doubleValue());
            tot.createCell(17).setCellValue(r.totalNet().doubleValue());
            for (int i = 0; i < head.length; i++) sh.autoSizeColumn(i);

            var lv = wb.createSheet("Leave " + r.year());
            String[] lh = {"Employee", "Type", "From", "To", "Days", "Notes"};
            var lr0 = lv.createRow(0);
            for (int i = 0; i < lh.length; i++) lr0.createCell(i).setCellValue(lh[i]);
            int m = 1;
            for (var x : hr.leaves(null, r.year())) {
                var row = lv.createRow(m++);
                row.createCell(0).setCellValue(x.employeeName());
                row.createCell(1).setCellValue(x.type());
                row.createCell(2).setCellValue(x.startDate().toString());
                row.createCell(3).setCellValue(x.endDate().toString());
                row.createCell(4).setCellValue(x.days().doubleValue());
                row.createCell(5).setCellValue(x.notes() == null ? "" : x.notes());
            }
            for (int i = 0; i < lh.length; i++) lv.autoSizeColumn(i);
            wb.write(out);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + r.number() + ".xlsx").body(out.toByteArray());
        }
    }

    // --- calculation -----------------------------------------------------------------------------

    private void generate(Long orgId, Long runId, YearMonth ym) {
        LocalDate first = ym.atDay(1), last = ym.atEndOfMonth();
        for (Map<String, Object> e : jdbc.queryForList("""
                SELECT * FROM employee WHERE organization_id = ? AND join_date <= ? AND (end_date IS NULL OR end_date >= ?) ORDER BY lower(name)""",
                orgId, last, first)) {
            long empId = ((Number) e.get("id")).longValue();
            LocalDate join = ((java.sql.Date) e.get("join_date")).toLocalDate();
            LocalDate end = e.get("end_date") == null ? null : ((java.sql.Date) e.get("end_date")).toLocalDate();
            LocalDate from = join.isAfter(first) ? join : first, to = end != null && end.isBefore(last) ? end : last;
            // 30-day month basis: a full month is 30 days whatever its length.
            BigDecimal days = from.equals(first) && to.equals(last) ? THIRTY
                    : BigDecimal.valueOf(ChronoUnit.DAYS.between(from, to) + 1).multiply(THIRTY)
                    .divide(BigDecimal.valueOf(ym.lengthOfMonth()), 1, RoundingMode.HALF_UP).min(THIRTY);
            BigDecimal f = days.divide(THIRTY, 6, RoundingMode.HALF_UP);
            BigDecimal basic = pr(e, "basic", f), housing = pr(e, "housing", f), transport = pr(e, "transport", f), other = pr(e, "other_allowance", f);
            BigDecimal gosi = basic.add(housing).multiply((BigDecimal) e.get("gosi_pct")).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            BigDecimal unpaidDays = BigDecimal.valueOf(unpaidCalendarDays(empId, first, last));
            BigDecimal full = sum(e, "basic", "housing", "transport", "other_allowance");
            BigDecimal unpaid = full.divide(THIRTY, 6, RoundingMode.HALF_UP).multiply(unpaidDays).setScale(2, RoundingMode.HALF_UP);
            BigDecimal gross = basic.add(housing).add(transport).add(other);
            BigDecimal advance = hr.advanceDeduction(orgId, empId, last).min(gross.subtract(gosi).subtract(unpaid).max(BigDecimal.ZERO));
            BigDecimal net = gross.subtract(gosi).subtract(advance).subtract(unpaid);
            jdbc.update("""
                    INSERT INTO payroll_line (payroll_run_id, employee_id, basic, housing, transport, other_allowance, gosi, advance_deduction,
                                              unpaid_leave_days, unpaid_leave_deduction, days_worked, net)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", runId, empId, basic, housing, transport, other, gosi, advance,
                    unpaidDays, unpaid, days, net);
        }
    }

    /** Calendar days of unpaid leave inside the month (30-day pay basis counts calendar days). */
    private long unpaidCalendarDays(long employeeId, LocalDate first, LocalDate last) {
        long n = 0;
        for (var r : jdbc.queryForList("SELECT start_date, end_date FROM leave_entry WHERE employee_id = ? AND leave_type = 'UNPAID' AND start_date <= ? AND end_date >= ?",
                employeeId, last, first)) {
            LocalDate s = ((java.sql.Date) r.get("start_date")).toLocalDate(), e = ((java.sql.Date) r.get("end_date")).toLocalDate();
            LocalDate a = s.isBefore(first) ? first : s, b = e.isAfter(last) ? last : e;
            n += ChronoUnit.DAYS.between(a, b) + 1;
        }
        return Math.min(n, 30);
    }

    private Run requireDraft(Long orgId, Long id) {
        Run r = run(orgId, id);
        if (!"DRAFT".equals(r.status())) throw ApiException.conflict("payroll_locked", "This payroll is " + r.status().toLowerCase() + " and can't be changed");
        return r;
    }

    private Run run(Long orgId, Long id) {
        List<Line> lines = jdbc.query("""
                SELECT l.*, e.code, e.name, e.job_title, e.iban FROM payroll_line l JOIN employee e ON e.id = l.employee_id
                WHERE l.payroll_run_id = ? ORDER BY lower(e.name)""", (rs, i) -> {
            BigDecimal gross = rs.getBigDecimal("basic").add(rs.getBigDecimal("housing")).add(rs.getBigDecimal("transport"))
                    .add(rs.getBigDecimal("other_allowance")).add(rs.getBigDecimal("overtime")).add(rs.getBigDecimal("additions"));
            BigDecimal ded = rs.getBigDecimal("gosi").add(rs.getBigDecimal("advance_deduction")).add(rs.getBigDecimal("unpaid_leave_deduction"))
                    .add(rs.getBigDecimal("other_deductions"));
            return new Line(rs.getLong("id"), rs.getLong("employee_id"), rs.getString("code"), rs.getString("name"), rs.getString("job_title"),
                    rs.getString("iban"), rs.getBigDecimal("basic"), rs.getBigDecimal("housing"), rs.getBigDecimal("transport"),
                    rs.getBigDecimal("other_allowance"), rs.getBigDecimal("overtime"), rs.getBigDecimal("additions"), rs.getBigDecimal("gosi"),
                    rs.getBigDecimal("advance_deduction"), rs.getBigDecimal("unpaid_leave_days"), rs.getBigDecimal("unpaid_leave_deduction"),
                    rs.getBigDecimal("other_deductions"), rs.getBigDecimal("days_worked"), gross, ded, rs.getBigDecimal("net"), rs.getString("notes"));
        }, id);
        BigDecimal g = lines.stream().map(Line::gross).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal d = lines.stream().map(Line::deductions).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal n = lines.stream().map(Line::net).reduce(BigDecimal.ZERO, BigDecimal::add);
        return jdbc.query("SELECT * FROM payroll_run WHERE id = ? AND organization_id = ?", (rs, i) -> new Run(rs.getLong("id"), rs.getString("number"),
                        rs.getInt("year"), rs.getInt("month"), rs.getString("status"), rs.getString("payment_method"), rs.getString("approved_by"),
                        ts(rs.getTimestamp("approved_at")), rs.getString("paid_by"), ts(rs.getTimestamp("paid_at")), rs.getString("created_by"),
                        rs.getTimestamp("created_at").toInstant(), g, d, n, lines), id, orgId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Payroll"));
    }

    private String payslipHtml(Run r, Line l, Organization org) {
        String brand = org.getBrandColor() == null ? "#0B2A6F" : org.getBrandColor();
        String emp = LetterheadPdf.box("Employee", "الموظف", new String[][]{
                {"Name", l.employeeName()}, {"Code", l.employeeCode()}, {"Job title", l.jobTitle()}, {"IBAN", l.iban()}});
        String meta = LetterheadPdf.box("Payslip", "قسيمة الراتب", new String[][]{
                {"Period", "%d-%02d".formatted(r.year(), r.month())}, {"Payroll no.", r.number()},
                {"Days paid", l.daysWorked().stripTrailingZeros().toPlainString() + " / 30"}, {"Status", r.status()}});
        String row = "<tr><td style=\"padding:5px 8px; border-bottom:1px solid #e3e7ef\">%s</td><td style=\"padding:5px 8px; border-bottom:1px solid #e3e7ef; text-align:right\">%s</td></tr>";
        StringBuilder earn = new StringBuilder(), ded = new StringBuilder();
        for (Object[] x : new Object[][]{{"Basic salary · الراتب الأساسي", l.basic()}, {"Housing · بدل السكن", l.housing()},
                {"Transport · بدل النقل", l.transport()}, {"Other allowances · بدلات أخرى", l.otherAllowance()},
                {"Overtime · العمل الإضافي", l.overtime()}, {"Additions · إضافات", l.additions()}})
            if (((BigDecimal) x[1]).signum() != 0) earn.append(row.formatted(x[0], LetterheadPdf.money((BigDecimal) x[1])));
        for (Object[] x : new Object[][]{{"GOSI · التأمينات", l.gosi()}, {"Salary advance · سلفة", l.advanceDeduction()},
                {"Unpaid leave (" + l.unpaidLeaveDays().stripTrailingZeros().toPlainString() + " d) · إجازة بدون راتب", l.unpaidLeaveDeduction()},
                {"Other deductions · خصومات أخرى", l.otherDeductions()}})
            if (((BigDecimal) x[1]).signum() != 0) ded.append(row.formatted(x[0], LetterheadPdf.money((BigDecimal) x[1])));
        String th = "background:%s; color:#fff; padding:6px 8px; text-align:left".formatted(brand);
        return """
                <table><tr><td><h1>Payslip</h1></td><td class="rtl"><h1>قسيمة راتب</h1></td></tr></table>
                <table style="margin-top:6px"><tr><td style="width:55%%; vertical-align:top; padding-right:6px">%s</td><td style="width:45%%; vertical-align:top">%s</td></tr></table>
                <table style="margin-top:12px"><tr>
                  <td style="width:50%%; vertical-align:top; padding-right:6px"><table><tr><th style="%s" colspan="2">Earnings · الاستحقاقات</th></tr>%s
                    <tr><td style="padding:6px 8px; font-weight:700">Gross · الإجمالي</td><td style="padding:6px 8px; text-align:right; font-weight:700">%s</td></tr></table></td>
                  <td style="width:50%%; vertical-align:top"><table><tr><th style="%s" colspan="2">Deductions · الاستقطاعات</th></tr>%s
                    <tr><td style="padding:6px 8px; font-weight:700">Total deductions · مجموع الاستقطاعات</td><td style="padding:6px 8px; text-align:right; font-weight:700">%s</td></tr></table></td>
                </tr></table>
                <table style="margin-top:12px"><tr><td style="padding:8px; background:%s; color:#fff; font-weight:700">Net salary · صافي الراتب</td>
                  <td style="padding:8px; background:%s; color:#fff; text-align:right; font-weight:700">%s</td></tr></table>
                <p style="margin-top:6px">%s<br/><span class="rtl">%s</span></p>
                %s
                %s
                """.formatted(emp, meta, th, earn, LetterheadPdf.money(l.gross()), th, ded.isEmpty() ? row.formatted("—", LetterheadPdf.money(BigDecimal.ZERO)) : ded,
                LetterheadPdf.money(l.deductions()), brand, brand, LetterheadPdf.money(l.net()),
                LetterheadPdf.esc(AmountInWords.english(l.net(), "SAR")), LetterheadPdf.esc(AmountInWords.arabic(l.net())),
                l.notes() == null ? "" : "<p class=\"muted\">" + LetterheadPdf.esc(l.notes()) + "</p>",
                LetterheadPdf.signatures(org, l.employeeName()));
    }

    private static BigDecimal pr(Map<String, Object> e, String k, BigDecimal f) {
        return ((BigDecimal) e.get(k)).multiply(f).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(Map<String, Object> m, String... keys) {
        BigDecimal s = BigDecimal.ZERO;
        for (String k : keys) s = s.add((BigDecimal) m.get(k));
        return s;
    }

    private static BigDecimal pick(BigDecimal v, Object fallback) {
        return v != null ? v : (BigDecimal) fallback;
    }

    private static Instant ts(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
