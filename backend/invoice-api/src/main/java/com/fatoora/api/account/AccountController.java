package com.fatoora.api.account;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.organization.Organization;
import com.fatoora.api.organization.OrganizationRepository;
import com.fatoora.api.pdf.LetterheadPdf;
import com.fatoora.api.worker.WorkerMonthRepository;
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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The company cash book (admin only): monthly income and expenses, the yearly account and partner withdrawals. */
@RestController
@RequestMapping("/api/v1/accounts")
@PreAuthorize("hasRole('ADMIN')")
public class AccountController {

    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    public record IncomeRequest(@NotNull LocalDate date, String customerName, String mobile, String details,
                                @NotNull @PositiveOrZero BigDecimal amount, @PositiveOrZero BigDecimal paid) {}

    public record ExpenseRequest(@NotNull LocalDate date, @NotBlank String description, ExpenseEntry.Category category,
                                 @NotNull @PositiveOrZero BigDecimal amount) {}

    public record PartnerRequest(@NotBlank String name, Boolean active) {}

    public record EntitlementRequest(@PositiveOrZero BigDecimal amount) {}

    public record WithdrawalRequest(@NotNull Long partnerId, @NotNull LocalDate date, @NotNull @PositiveOrZero BigDecimal amount,
                                    String reference, String approvedBy, String notes) {}

    public record ImportedMonth(String sheet, int year, int month, int income, int expenses, int datesGuessed) {}

    public record ImportResult(List<ImportedMonth> months, List<String> memos, List<Integer> yearMonths,
                               List<Integer> yearMonthsSkipped, int withdrawals, int partnersCreated) {}

    private final IncomeEntryRepository income;
    private final ExpenseEntryRepository expenses;
    private final PartnerRepository partners;
    private final PartnerEntitlementRepository entitlements;
    private final PartnerWithdrawalRepository withdrawals;
    private final WorkerMonthRepository workerMonths;
    private final OrganizationRepository orgs;
    private final LetterheadPdf letterhead;
    private final AuditService audit;

    public AccountController(IncomeEntryRepository income, ExpenseEntryRepository expenses, PartnerRepository partners,
                             PartnerEntitlementRepository entitlements, PartnerWithdrawalRepository withdrawals,
                             WorkerMonthRepository workerMonths, OrganizationRepository orgs, LetterheadPdf letterhead,
                             AuditService audit) {
        this.income = income;
        this.expenses = expenses;
        this.partners = partners;
        this.entitlements = entitlements;
        this.withdrawals = withdrawals;
        this.workerMonths = workerMonths;
        this.orgs = orgs;
        this.letterhead = letterhead;
        this.audit = audit;
    }

    // --- reports ----------------------------------------------------------------------------------

    @GetMapping("/month")
    @Transactional(readOnly = true)
    AccountReport.MonthResult month(@RequestParam int year, @RequestParam int month) {
        return monthResult(CurrentUser.orgId(), year, month);
    }

    @GetMapping("/year")
    @Transactional(readOnly = true)
    AccountReport.YearResult year(@RequestParam int year) {
        return yearResult(CurrentUser.orgId(), year);
    }

    @GetMapping("/month.xlsx")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> monthExcel(@RequestParam int year, @RequestParam int month) throws IOException {
        Long orgId = CurrentUser.orgId();
        return file(AccountExcel.writeMonth(monthResult(orgId, year, month), org(orgId)), XLSX,
                "accounts-" + year + "-" + String.format("%02d", month) + ".xlsx", false);
    }

    @GetMapping("/month/pdf")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> monthPdf(@RequestParam int year, @RequestParam int month) {
        Long orgId = CurrentUser.orgId();
        byte[] pdf = letterhead.render(org(orgId), "Monthly accounts " + month + "/" + year,
                AccountReport.monthHtml(monthResult(orgId, year, month)));
        return file(pdf, MediaType.APPLICATION_PDF, "accounts-" + year + "-" + String.format("%02d", month) + ".pdf", true);
    }

    @GetMapping("/year.xlsx")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> yearExcel(@RequestParam int year) throws IOException {
        Long orgId = CurrentUser.orgId();
        return file(AccountExcel.writeYear(yearResult(orgId, year), org(orgId)), XLSX, "company-cashflow-" + year + ".xlsx", false);
    }

    @GetMapping("/year/pdf")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> yearPdf(@RequestParam int year) {
        Long orgId = CurrentUser.orgId();
        byte[] pdf = letterhead.render(org(orgId), "Yearly cash flow " + year, AccountReport.yearHtml(yearResult(orgId, year)));
        return file(pdf, MediaType.APPLICATION_PDF, "company-cashflow-" + year + ".pdf", true);
    }

    // --- income -----------------------------------------------------------------------------------

    @PostMapping("/income")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    void addIncome(@Valid @RequestBody IncomeRequest req) {
        IncomeEntry e = new IncomeEntry();
        e.setOrganizationId(CurrentUser.orgId());
        e.setCreatedBy(CurrentUser.get().email());
        apply(e, req);
        income.save(e);
        audit.record(e.getOrganizationId(), "ACCOUNT_INCOME_ADDED", "account_income", e.getId(), label(e));
    }

    @PutMapping("/income/{id}")
    @Transactional
    void updateIncome(@PathVariable Long id, @Valid @RequestBody IncomeRequest req) {
        IncomeEntry e = income.findByIdAndOrganizationId(id, CurrentUser.orgId()).orElseThrow(() -> ApiException.notFound("Income"));
        apply(e, req);
        e.setSource(AccountSource.MANUAL);
        audit.record(e.getOrganizationId(), "ACCOUNT_INCOME_UPDATED", "account_income", e.getId(), label(e));
    }

    @DeleteMapping("/income/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void deleteIncome(@PathVariable Long id) {
        IncomeEntry e = income.findByIdAndOrganizationId(id, CurrentUser.orgId()).orElseThrow(() -> ApiException.notFound("Income"));
        income.delete(e);
        audit.record(e.getOrganizationId(), "ACCOUNT_INCOME_DELETED", "account_income", e.getId(), label(e));
    }

    // --- expenses ---------------------------------------------------------------------------------

    @PostMapping("/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    void addExpense(@Valid @RequestBody ExpenseRequest req) {
        ExpenseEntry e = new ExpenseEntry();
        e.setOrganizationId(CurrentUser.orgId());
        e.setCreatedBy(CurrentUser.get().email());
        apply(e, req);
        expenses.save(e);
        audit.record(e.getOrganizationId(), "ACCOUNT_EXPENSE_ADDED", "account_expense", e.getId(), e.getDescription() + " · " + e.getAmount());
    }

    @PutMapping("/expenses/{id}")
    @Transactional
    void updateExpense(@PathVariable Long id, @Valid @RequestBody ExpenseRequest req) {
        ExpenseEntry e = expenses.findByIdAndOrganizationId(id, CurrentUser.orgId()).orElseThrow(() -> ApiException.notFound("Expense"));
        apply(e, req);
        e.setSource(AccountSource.MANUAL);
        audit.record(e.getOrganizationId(), "ACCOUNT_EXPENSE_UPDATED", "account_expense", e.getId(), e.getDescription() + " · " + e.getAmount());
    }

    @DeleteMapping("/expenses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void deleteExpense(@PathVariable Long id) {
        ExpenseEntry e = expenses.findByIdAndOrganizationId(id, CurrentUser.orgId()).orElseThrow(() -> ApiException.notFound("Expense"));
        expenses.delete(e);
        audit.record(e.getOrganizationId(), "ACCOUNT_EXPENSE_DELETED", "account_expense", e.getId(), e.getDescription() + " · " + e.getAmount());
    }

    // --- partners ---------------------------------------------------------------------------------

    @PostMapping("/partners")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    void addPartner(@Valid @RequestBody PartnerRequest req) {
        Partner p = new Partner();
        p.setOrganizationId(CurrentUser.orgId());
        p.setName(req.name().strip());
        partners.save(p);
        audit.record(p.getOrganizationId(), "PARTNER_ADDED", "partner", p.getId(), p.getName());
    }

    @PutMapping("/partners/{id}")
    @Transactional
    void updatePartner(@PathVariable Long id, @Valid @RequestBody PartnerRequest req) {
        Partner p = partner(id);
        p.setName(req.name().strip());
        if (req.active() != null) p.setActive(req.active());
        audit.record(p.getOrganizationId(), "PARTNER_UPDATED", "partner", p.getId(), p.getName());
    }

    @DeleteMapping("/partners/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void deletePartner(@PathVariable Long id) {
        Partner p = partner(id);
        partners.delete(p);
        audit.record(p.getOrganizationId(), "PARTNER_DELETED", "partner", p.getId(), p.getName());
    }

    /** What the partner may draw in that year; empty removes it. */
    @PutMapping("/partners/{id}/entitlement/{year}")
    @Transactional
    void entitlement(@PathVariable Long id, @PathVariable int year, @Valid @RequestBody EntitlementRequest req) {
        Partner p = partner(id);
        PartnerEntitlement e = entitlements.findByPartnerIdAndYear(p.getId(), year).orElse(null);
        if (req.amount() == null) {
            if (e != null) entitlements.delete(e);
            return;
        }
        if (e == null) {
            e = new PartnerEntitlement();
            e.setPartnerId(p.getId());
            e.setYear(year);
        }
        e.setAmount(req.amount());
        entitlements.save(e);
    }

    @PostMapping("/withdrawals")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    void addWithdrawal(@Valid @RequestBody WithdrawalRequest req) {
        PartnerWithdrawal w = new PartnerWithdrawal();
        w.setOrganizationId(CurrentUser.orgId());
        w.setCreatedBy(CurrentUser.get().email());
        apply(w, req);
        withdrawals.save(w);
        audit.record(w.getOrganizationId(), "PARTNER_WITHDRAWAL_ADDED", "partner_withdrawal", w.getId(), w.getAmount().toPlainString());
    }

    @PutMapping("/withdrawals/{id}")
    @Transactional
    void updateWithdrawal(@PathVariable Long id, @Valid @RequestBody WithdrawalRequest req) {
        PartnerWithdrawal w = withdrawals.findByIdAndOrganizationId(id, CurrentUser.orgId()).orElseThrow(() -> ApiException.notFound("Withdrawal"));
        apply(w, req);
        w.setSource(AccountSource.MANUAL);
        audit.record(w.getOrganizationId(), "PARTNER_WITHDRAWAL_UPDATED", "partner_withdrawal", w.getId(), w.getAmount().toPlainString());
    }

    @DeleteMapping("/withdrawals/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void deleteWithdrawal(@PathVariable Long id) {
        PartnerWithdrawal w = withdrawals.findByIdAndOrganizationId(id, CurrentUser.orgId()).orElseThrow(() -> ApiException.notFound("Withdrawal"));
        withdrawals.delete(w);
        audit.record(w.getOrganizationId(), "PARTNER_WITHDRAWAL_DELETED", "partner_withdrawal", w.getId(), w.getAmount().toPlainString());
    }

    // --- import -----------------------------------------------------------------------------------

    /**
     * Reads the company's monthly accounts workbook (a sheet per month) or yearly cash-flow workbook. Each month
     * replaces only what an earlier import of the same kind put there; rows typed in by hand are kept. A month
     * total from the yearly sheet is skipped when that month already has its own entries, so nothing counts twice.
     */
    @PutMapping("/import")
    @Transactional
    ImportResult importExcel(@RequestBody byte[] file, @RequestParam int year) {
        Long orgId = CurrentUser.orgId();
        String who = CurrentUser.get().email();
        AccountExcel.Parsed p;
        try {
            p = AccountExcel.read(new ByteArrayInputStream(file), year);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("BAD_FILE", e.getMessage());
        } catch (Exception e) {
            throw ApiException.badRequest("BAD_FILE", "This is not an Excel accounts workbook (.xlsx)");
        }

        List<ImportedMonth> months = new ArrayList<>();
        List<String> memos = new ArrayList<>();
        for (AccountExcel.MonthSheet m : p.months()) {
            YearMonth ym = YearMonth.of(m.year(), m.month());
            income.deleteImported(orgId, AccountSource.IMPORT, ym.atDay(1), ym.atEndOfMonth());
            expenses.deleteImported(orgId, AccountSource.IMPORT, ym.atDay(1), ym.atEndOfMonth());
            int guessed = 0;
            for (AccountExcel.ImportedIncome i : m.income()) {
                IncomeEntry e = new IncomeEntry();
                e.setOrganizationId(orgId);
                e.setEntryDate(i.date());
                e.setCustomerName(i.customer());
                e.setMobile(i.mobile());
                e.setDetails(i.details());
                e.setAmount(i.amount());
                e.setPaid(i.paid());
                e.setSource(AccountSource.IMPORT);
                e.setCreatedBy(who);
                income.save(e);
                if (i.dateGuessed()) guessed++;
            }
            for (AccountExcel.ImportedExpense x : m.expenses()) {
                ExpenseEntry e = new ExpenseEntry();
                e.setOrganizationId(orgId);
                e.setEntryDate(x.date());
                e.setDescription(x.description());
                e.setCategory(x.category());
                e.setAmount(x.amount());
                e.setSource(AccountSource.IMPORT);
                e.setCreatedBy(who);
                expenses.save(e);
                if (x.dateGuessed()) guessed++;
            }
            m.memos().forEach(s -> memos.add(m.sheet() + ": " + s));
            months.add(new ImportedMonth(m.sheet(), m.year(), m.month(), m.income().size(), m.expenses().size(), guessed));
        }

        List<Integer> yearMonths = new ArrayList<>(), skipped = new ArrayList<>();
        int drawn = 0, created = 0;
        if (p.year() != null) {
            Map<String, Partner> byName = partners.findByOrganizationIdOrderByIdAsc(orgId).stream()
                    .collect(Collectors.toMap(x -> x.getName().strip().toLowerCase(Locale.ROOT), Function.identity(), (a, b) -> a));
            for (String name : p.year().withdrawals().keySet()) {
                if (byName.containsKey(name.toLowerCase(Locale.ROOT))) continue;
                Partner np = new Partner();
                np.setOrganizationId(orgId);
                np.setName(name);
                partners.save(np);
                byName.put(name.toLowerCase(Locale.ROOT), np);
                created++;
            }
            for (int m = 0; m < 12; m++) {
                YearMonth ym = YearMonth.of(year, m + 1);
                LocalDate from = ym.atDay(1), to = ym.atEndOfMonth();
                BigDecimal in = p.year().income().get(m), out = p.year().expense().get(m);
                withdrawals.deleteImported(orgId, AccountSource.IMPORT_YEAR, from, to);
                for (var e : p.year().withdrawals().entrySet()) {
                    BigDecimal amount = e.getValue().get(m);
                    if (amount == null || amount.signum() == 0) continue;
                    PartnerWithdrawal w = new PartnerWithdrawal();
                    w.setOrganizationId(orgId);
                    w.setPartnerId(byName.get(e.getKey().toLowerCase(Locale.ROOT)).getId());
                    w.setEntryDate(from);
                    w.setAmount(amount);
                    w.setNotes("From the yearly cash-flow sheet");
                    w.setSource(AccountSource.IMPORT_YEAR);
                    w.setCreatedBy(who);
                    withdrawals.save(w);
                    drawn++;
                }
                income.deleteImported(orgId, AccountSource.IMPORT_YEAR, from, to);
                expenses.deleteImported(orgId, AccountSource.IMPORT_YEAR, from, to);
                if (in == null && out == null) continue;
                boolean detailed = !income.findByOrganizationIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(orgId, from, to).isEmpty()
                        || !expenses.findByOrganizationIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(orgId, from, to).isEmpty();
                if (detailed) {
                    skipped.add(m + 1);
                    continue;
                }
                String monthName = ym.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH);
                if (in != null && in.signum() != 0) {
                    IncomeEntry e = new IncomeEntry();
                    e.setOrganizationId(orgId);
                    e.setEntryDate(from);
                    e.setCustomerName("Total for " + monthName);
                    e.setDetails("Month total from the yearly cash-flow sheet");
                    e.setAmount(in);
                    e.setPaid(in);
                    e.setSource(AccountSource.IMPORT_YEAR);
                    e.setCreatedBy(who);
                    income.save(e);
                }
                if (out != null && out.signum() != 0) {
                    ExpenseEntry e = new ExpenseEntry();
                    e.setOrganizationId(orgId);
                    e.setEntryDate(from);
                    e.setDescription("Total expenses for " + monthName + " (from the yearly cash-flow sheet)");
                    e.setAmount(out);
                    e.setSource(AccountSource.IMPORT_YEAR);
                    e.setCreatedBy(who);
                    expenses.save(e);
                }
                yearMonths.add(m + 1);
            }
        }
        audit.record(orgId, "ACCOUNTS_IMPORTED", "account_income", null,
                months.size() + " month sheets · " + yearMonths.size() + " yearly months · " + drawn + " withdrawals");
        return new ImportResult(months, memos, yearMonths, skipped, drawn, created);
    }

    // --- helpers ----------------------------------------------------------------------------------

    private AccountReport.MonthResult monthResult(Long orgId, int year, int month) {
        if (month < 1 || month > 12) throw new IllegalArgumentException("Invalid month");
        YearMonth ym = YearMonth.of(year, month);
        return AccountReport.month(year, month,
                income.findByOrganizationIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(orgId, ym.atDay(1), ym.atEndOfMonth()),
                expenses.findByOrganizationIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(orgId, ym.atDay(1), ym.atEndOfMonth()),
                workerPaid(orgId, year)[month - 1]);
    }

    private AccountReport.YearResult yearResult(Long orgId, int year) {
        LocalDate from = LocalDate.of(year, 1, 1), to = LocalDate.of(year, 12, 31);
        List<Partner> ps = partners.findByOrganizationIdOrderByIdAsc(orgId);
        Map<Long, BigDecimal> ent = new HashMap<>();
        if (!ps.isEmpty())
            entitlements.findByPartnerIdInAndYear(ps.stream().map(Partner::getId).toList(), year)
                    .forEach(e -> ent.put(e.getPartnerId(), e.getAmount()));
        return AccountReport.year(year,
                income.findByOrganizationIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(orgId, from, to),
                expenses.findByOrganizationIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(orgId, from, to),
                workerPaid(orgId, year), ps, ent,
                withdrawals.findByOrganizationIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(orgId, from, to));
    }

    /** What the Workers ledger shows as paid to workers in each month of the year. */
    private BigDecimal[] workerPaid(Long orgId, int year) {
        BigDecimal[] out = new BigDecimal[12];
        for (Object[] row : workerMonths.paidByMonth(orgId, year)) out[((Number) row[0]).intValue() - 1] = (BigDecimal) row[1];
        return out;
    }

    private static void apply(IncomeEntry e, IncomeRequest r) {
        e.setEntryDate(r.date());
        e.setCustomerName(blank(r.customerName()));
        e.setMobile(blank(r.mobile()));
        e.setDetails(blank(r.details()));
        e.setAmount(r.amount());
        e.setPaid(r.paid() == null ? BigDecimal.ZERO : r.paid());
    }

    private static void apply(ExpenseEntry e, ExpenseRequest r) {
        e.setEntryDate(r.date());
        e.setDescription(r.description().strip());
        e.setCategory(r.category() == null ? AccountExcel.guess(r.description()) : r.category());
        e.setAmount(r.amount());
    }

    private void apply(PartnerWithdrawal w, WithdrawalRequest r) {
        w.setPartnerId(partner(r.partnerId()).getId());
        w.setEntryDate(r.date());
        w.setAmount(r.amount());
        w.setReference(blank(r.reference()));
        w.setApprovedBy(blank(r.approvedBy()));
        w.setNotes(blank(r.notes()));
    }

    private Partner partner(Long id) {
        return partners.findByIdAndOrganizationId(id, CurrentUser.orgId()).orElseThrow(() -> ApiException.notFound("Partner"));
    }

    private Organization org(Long id) {
        return orgs.findById(id).orElseThrow();
    }

    private static String label(IncomeEntry e) {
        return (e.getCustomerName() == null ? "—" : e.getCustomerName()) + " · " + e.getAmount() + " / " + e.getPaid();
    }

    private static ResponseEntity<byte[]> file(byte[] body, MediaType type, String name, boolean inline) {
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                        .filename(name).build().toString())
                .body(body);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    static int thisYear() {
        return LocalDate.now(InvoiceApiApplication.KSA).getYear();
    }
}
