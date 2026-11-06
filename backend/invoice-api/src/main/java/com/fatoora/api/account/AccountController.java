package com.fatoora.api.account;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.organization.Organization;
import com.fatoora.api.organization.OrganizationRepository;
import com.fatoora.api.pdf.LetterheadPdf;
import com.fatoora.api.payment.InvoicePayment;
import com.fatoora.api.user.AppUser;
import com.fatoora.api.user.AppUserRepository;
import com.fatoora.api.worker.WorkerMonthRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The company cash book: monthly income and expenses, the yearly account and partner withdrawals (admin). A branch
 * login keeps its own income and expenses here and sees only the entries it created; the year, partners, imports and
 * worker payments stay with the admin.
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    public record IncomeRequest(@NotNull LocalDate date, String customerName, String mobile, String details,
                                @Size(max = 255) String receivedBy,
                                @NotNull @PositiveOrZero BigDecimal amount, @PositiveOrZero BigDecimal paid,
                                InvoicePayment.Method method) {}

    public record ExpenseRequest(@NotNull LocalDate date, @NotBlank String description, ExpenseEntry.Category category,
                                 @Size(max = 255) String paidBy, @Size(max = 50) String invoiceNumber,
                                 @NotNull @PositiveOrZero BigDecimal amount, InvoicePayment.Method method) {}

    public record PartnerRequest(@NotBlank String name, Boolean active) {}

    /** The partner after an add: the caller uses the id to record a withdrawal right away. */
    public record PartnerRef(Long id, String name) {}

    public record EntitlementRequest(@PositiveOrZero BigDecimal amount) {}

    public record WithdrawalRequest(@NotNull Long partnerId, @NotNull LocalDate date, @NotNull @PositiveOrZero BigDecimal amount,
                                    String reference, String approvedBy, String notes) {}

    public record ImportedMonth(String sheet, int year, int month, int income, int expenses, int datesGuessed) {}

    public record ImportResult(List<ImportedMonth> months, List<String> memos, List<Integer> yearMonths,
                               List<Integer> yearMonthsSkipped, int withdrawals, int partnersCreated) {}

    /** Names already used for "received by" / "paid by", for the form dropdowns. */
    public record People(List<String> receivedBy, List<String> paidBy) {}

    /** A person's totals across a year: money they received (income) and money they paid (expense). */
    public record PersonRow(String name, BigDecimal income, int incomeCount, BigDecimal expense, int expenseCount, BigDecimal net) {}

    /** One person's income and expense entries, shown independently when their row is opened. */
    public record PersonHistory(String name, List<AccountReport.Income> income, List<AccountReport.Expense> expenses,
                                BigDecimal incomeTotal, BigDecimal expenseTotal) {}

    private final IncomeEntryRepository income;
    private final ExpenseEntryRepository expenses;
    private final PartnerRepository partners;
    private final PartnerEntitlementRepository entitlements;
    private final PartnerWithdrawalRepository withdrawals;
    private final WorkerMonthRepository workerMonths;
    private final OrganizationRepository orgs;
    private final LetterheadPdf letterhead;
    private final AuditService audit;
    private final AppUserRepository users;
    private final InvoiceScanService invoiceScan;

    public AccountController(IncomeEntryRepository income, ExpenseEntryRepository expenses, PartnerRepository partners,
                             PartnerEntitlementRepository entitlements, PartnerWithdrawalRepository withdrawals,
                             WorkerMonthRepository workerMonths, OrganizationRepository orgs, LetterheadPdf letterhead,
                             AuditService audit, AppUserRepository users, InvoiceScanService invoiceScan) {
        this.users = users;
        this.invoiceScan = invoiceScan;
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

    @PreAuthorize("hasAnyRole('ADMIN','BRANCH')")
    @GetMapping("/month")
    @Transactional(readOnly = true)
    AccountReport.MonthResult month(@RequestParam int year, @RequestParam int month) {
        return monthResult(CurrentUser.orgId(), year, month);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/year")
    @Transactional(readOnly = true)
    AccountReport.YearResult year(@RequestParam int year) {
        return yearResult(CurrentUser.orgId(), year);
    }

    @PreAuthorize("hasAnyRole('ADMIN','BRANCH')")
    @GetMapping("/month.xlsx")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> monthExcel(@RequestParam int year, @RequestParam int month) throws IOException {
        Long orgId = CurrentUser.orgId();
        return file(AccountExcel.writeMonth(monthResult(orgId, year, month), org(orgId)), XLSX,
                "accounts-" + year + "-" + String.format("%02d", month) + ".xlsx", false);
    }

    @PreAuthorize("hasAnyRole('ADMIN','BRANCH')")
    @GetMapping("/month/pdf")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> monthPdf(@RequestParam int year, @RequestParam int month) {
        Long orgId = CurrentUser.orgId();
        byte[] pdf = letterhead.render(org(orgId), "Monthly accounts " + month + "/" + year,
                AccountReport.monthHtml(monthResult(orgId, year, month)));
        return file(pdf, MediaType.APPLICATION_PDF, "accounts-" + year + "-" + String.format("%02d", month) + ".pdf", true);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/year.xlsx")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> yearExcel(@RequestParam int year) throws IOException {
        Long orgId = CurrentUser.orgId();
        return file(AccountExcel.writeYear(yearResult(orgId, year), org(orgId)), XLSX, "company-cashflow-" + year + ".xlsx", false);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/year/pdf")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> yearPdf(@RequestParam int year) {
        Long orgId = CurrentUser.orgId();
        byte[] pdf = letterhead.render(org(orgId), "Yearly cash flow " + year, AccountReport.yearHtml(yearResult(orgId, year)));
        return file(pdf, MediaType.APPLICATION_PDF, "company-cashflow-" + year + ".pdf", true);
    }

    // --- people, person-wise totals and invoice scan ---------------------------------------------

    /** Names already used for "received by" and "paid by", so the forms can offer them and keep new ones. */
    @PreAuthorize("hasAnyRole('ADMIN','BRANCH')")
    @GetMapping("/people")
    @Transactional(readOnly = true)
    People people() {
        Long orgId = CurrentUser.orgId();
        return new People(income.distinctReceivedBy(orgId), expenses.distinctPaidBy(orgId));
    }

    /** Each person's income and expense totals for the year, most active first. */
    @PreAuthorize("hasAnyRole('ADMIN','BRANCH')")
    @GetMapping("/persons")
    @Transactional(readOnly = true)
    List<PersonRow> persons(@RequestParam(required = false) Integer year) {
        Long orgId = CurrentUser.orgId();
        int y = year != null ? year : thisYear();
        LocalDate from = LocalDate.of(y, 1, 1), to = LocalDate.of(y, 12, 31);
        Map<String, String> display = new HashMap<>();
        Map<String, BigDecimal> inSum = new HashMap<>(), exSum = new HashMap<>();
        Map<String, Integer> inCount = new HashMap<>(), exCount = new HashMap<>();
        for (IncomeEntry e : income.findByOrganizationIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(orgId, from, to)) {
            if (!CurrentUser.owns(e.getCreatedBy())) continue;
            String name = blank(e.getReceivedBy());
            if (name == null) continue;
            String key = name.toLowerCase(Locale.ROOT);
            display.putIfAbsent(key, name);
            inSum.merge(key, AccountReport.nz(e.getPaid()), BigDecimal::add);
            inCount.merge(key, 1, Integer::sum);
        }
        for (ExpenseEntry e : expenses.findByOrganizationIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(orgId, from, to)) {
            if (!CurrentUser.owns(e.getCreatedBy())) continue;
            String name = blank(e.getPaidBy());
            if (name == null) continue;
            String key = name.toLowerCase(Locale.ROOT);
            display.putIfAbsent(key, name);
            exSum.merge(key, AccountReport.nz(e.getAmount()), BigDecimal::add);
            exCount.merge(key, 1, Integer::sum);
        }
        List<PersonRow> rows = new ArrayList<>();
        for (Map.Entry<String, String> p : display.entrySet()) {
            BigDecimal in = inSum.getOrDefault(p.getKey(), BigDecimal.ZERO), ex = exSum.getOrDefault(p.getKey(), BigDecimal.ZERO);
            rows.add(new PersonRow(p.getValue(), in, inCount.getOrDefault(p.getKey(), 0), ex, exCount.getOrDefault(p.getKey(), 0), in.subtract(ex)));
        }
        rows.sort(Comparator.comparing((PersonRow r) -> r.income().add(r.expense())).reversed());
        return rows;
    }

    /** One person's income and expense entries for the year. */
    @PreAuthorize("hasAnyRole('ADMIN','BRANCH')")
    @GetMapping("/persons/history")
    @Transactional(readOnly = true)
    PersonHistory personHistory(@RequestParam String name, @RequestParam(required = false) Integer year) {
        Long orgId = CurrentUser.orgId();
        int y = year != null ? year : thisYear();
        LocalDate from = LocalDate.of(y, 1, 1), to = LocalDate.of(y, 12, 31);
        String target = name.strip();
        Map<String, String> names = names(orgId);
        List<AccountReport.Income> in = income.findByOrganizationIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(orgId, from, to).stream()
                .filter(e -> CurrentUser.owns(e.getCreatedBy()))
                .filter(e -> target.equalsIgnoreCase(blank(e.getReceivedBy())))
                .map(e -> AccountReport.income(e, names)).toList();
        List<AccountReport.Expense> ex = expenses.findByOrganizationIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(orgId, from, to).stream()
                .filter(e -> CurrentUser.owns(e.getCreatedBy()))
                .filter(e -> target.equalsIgnoreCase(blank(e.getPaidBy())))
                .map(e -> AccountReport.expense(e, names)).toList();
        BigDecimal inTotal = AccountReport.sum(in.stream().map(AccountReport.Income::paid).toList());
        BigDecimal exTotal = AccountReport.sum(ex.stream().map(AccountReport.Expense::amount).toList());
        return new PersonHistory(target, in, ex, inTotal, exTotal);
    }

    /** Best-effort read of an uploaded invoice (ZATCA QR, then OCR) to prefill the income/expense form. */
    @PreAuthorize("hasAnyRole('ADMIN','BRANCH')")
    @PutMapping("/scan")
    InvoiceScanService.ScanResult scan(@RequestBody byte[] file,
                                       @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType) {
        return invoiceScan.scan(file, contentType);
    }

    // --- income -----------------------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('ADMIN','BRANCH')")
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

    @PreAuthorize("hasAnyRole('ADMIN','BRANCH')")
    @PutMapping("/income/{id}")
    @Transactional
    void updateIncome(@PathVariable Long id, @Valid @RequestBody IncomeRequest req) {
        IncomeEntry e = income.findByIdAndOrganizationId(id, CurrentUser.orgId()).filter(x -> CurrentUser.owns(x.getCreatedBy()))
                .orElseThrow(() -> ApiException.notFound("Income"));
        apply(e, req);
        e.setSource(AccountSource.MANUAL);
        e.setUpdatedBy(CurrentUser.get().email());
        e.setUpdatedAt(java.time.Instant.now());
        audit.record(e.getOrganizationId(), "ACCOUNT_INCOME_UPDATED", "account_income", e.getId(), label(e));
    }

    @PreAuthorize("hasAnyRole('ADMIN','BRANCH')")
    @DeleteMapping("/income/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void deleteIncome(@PathVariable Long id) {
        IncomeEntry e = income.findByIdAndOrganizationId(id, CurrentUser.orgId()).filter(x -> CurrentUser.owns(x.getCreatedBy()))
                .orElseThrow(() -> ApiException.notFound("Income"));
        income.delete(e);
        audit.record(e.getOrganizationId(), "ACCOUNT_INCOME_DELETED", "account_income", e.getId(), label(e));
    }

    // --- expenses ---------------------------------------------------------------------------------

    @PreAuthorize("hasAnyRole('ADMIN','BRANCH')")
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

    @PreAuthorize("hasAnyRole('ADMIN','BRANCH')")
    @PutMapping("/expenses/{id}")
    @Transactional
    void updateExpense(@PathVariable Long id, @Valid @RequestBody ExpenseRequest req) {
        ExpenseEntry e = expenses.findByIdAndOrganizationId(id, CurrentUser.orgId()).filter(x -> CurrentUser.owns(x.getCreatedBy()))
                .orElseThrow(() -> ApiException.notFound("Expense"));
        apply(e, req);
        e.setSource(AccountSource.MANUAL);
        e.setUpdatedBy(CurrentUser.get().email());
        e.setUpdatedAt(java.time.Instant.now());
        audit.record(e.getOrganizationId(), "ACCOUNT_EXPENSE_UPDATED", "account_expense", e.getId(), e.getDescription() + " · " + e.getAmount());
    }

    @PreAuthorize("hasAnyRole('ADMIN','BRANCH')")
    @DeleteMapping("/expenses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void deleteExpense(@PathVariable Long id) {
        ExpenseEntry e = expenses.findByIdAndOrganizationId(id, CurrentUser.orgId()).filter(x -> CurrentUser.owns(x.getCreatedBy()))
                .orElseThrow(() -> ApiException.notFound("Expense"));
        expenses.delete(e);
        audit.record(e.getOrganizationId(), "ACCOUNT_EXPENSE_DELETED", "account_expense", e.getId(), e.getDescription() + " · " + e.getAmount());
    }

    // --- partners ---------------------------------------------------------------------------------

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/partners")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    PartnerRef addPartner(@Valid @RequestBody PartnerRequest req) {
        Long orgId = CurrentUser.orgId();
        String name = req.name().strip();
        // Reuse a partner of the same name rather than making a duplicate (lets withdrawals add a partner inline).
        Partner p = partners.findByOrganizationIdOrderByIdAsc(orgId).stream()
                .filter(x -> x.getName() != null && x.getName().equalsIgnoreCase(name))
                .findFirst().orElse(null);
        if (p == null) {
            p = new Partner();
            p.setOrganizationId(orgId);
            p.setName(name);
            partners.save(p);
            audit.record(orgId, "PARTNER_ADDED", "partner", p.getId(), p.getName());
        }
        return new PartnerRef(p.getId(), p.getName());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/partners/{id}")
    @Transactional
    void updatePartner(@PathVariable Long id, @Valid @RequestBody PartnerRequest req) {
        Partner p = partner(id);
        p.setName(req.name().strip());
        if (req.active() != null) p.setActive(req.active());
        audit.record(p.getOrganizationId(), "PARTNER_UPDATED", "partner", p.getId(), p.getName());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/partners/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void deletePartner(@PathVariable Long id) {
        Partner p = partner(id);
        partners.delete(p);
        audit.record(p.getOrganizationId(), "PARTNER_DELETED", "partner", p.getId(), p.getName());
    }

    /** What the partner may draw in that year; empty removes it. */
    @PreAuthorize("hasRole('ADMIN')")
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

    @PreAuthorize("hasRole('ADMIN')")
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

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/withdrawals/{id}")
    @Transactional
    void updateWithdrawal(@PathVariable Long id, @Valid @RequestBody WithdrawalRequest req) {
        PartnerWithdrawal w = withdrawals.findByIdAndOrganizationId(id, CurrentUser.orgId()).orElseThrow(() -> ApiException.notFound("Withdrawal"));
        apply(w, req);
        w.setSource(AccountSource.MANUAL);
        w.setUpdatedBy(CurrentUser.get().email());
        w.setUpdatedAt(java.time.Instant.now());
        audit.record(w.getOrganizationId(), "PARTNER_WITHDRAWAL_UPDATED", "partner_withdrawal", w.getId(), w.getAmount().toPlainString());
    }

    @PreAuthorize("hasRole('ADMIN')")
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
    @PreAuthorize("hasRole('ADMIN')")
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
        boolean branch = CurrentUser.owner() != null;
        return AccountReport.month(year, month,
                income.findByOrganizationIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(orgId, ym.atDay(1), ym.atEndOfMonth())
                        .stream().filter(e -> CurrentUser.owns(e.getCreatedBy())).toList(),
                expenses.findByOrganizationIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(orgId, ym.atDay(1), ym.atEndOfMonth())
                        .stream().filter(e -> CurrentUser.owns(e.getCreatedBy())).toList(),
                branch ? null : workerPaid(orgId, year)[month - 1], names(orgId));
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
                withdrawals.findByOrganizationIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(orgId, from, to), names(orgId));
    }

    /** What the Workers ledger shows as paid to workers in each month of the year. */
    private BigDecimal[] workerPaid(Long orgId, int year) {
        BigDecimal[] out = new BigDecimal[12];
        for (Object[] row : workerMonths.paidByMonth(orgId, year)) out[((Number) row[0]).intValue() - 1] = (BigDecimal) row[1];
        return out;
    }

    /** Login (lower case) to full name, to show who created or edited an entry. */
    private Map<String, String> names(Long orgId) {
        Map<String, String> m = new HashMap<>();
        for (AppUser u : users.findByOrganizationId(orgId))
            m.put(u.getEmail().toLowerCase(Locale.ROOT), u.getFullName() == null || u.getFullName().isBlank() ? u.getEmail() : u.getFullName());
        return m;
    }

    private static void apply(IncomeEntry e, IncomeRequest r) {
        e.setEntryDate(r.date());
        e.setCustomerName(blank(r.customerName()));
        e.setMobile(blank(r.mobile()));
        e.setDetails(blank(r.details()));
        e.setReceivedBy(blank(r.receivedBy()));
        e.setAmount(r.amount());
        e.setPaid(r.paid() == null ? BigDecimal.ZERO : r.paid());
        if (r.method() != null) e.setMethod(r.method());
    }

    private static void apply(ExpenseEntry e, ExpenseRequest r) {
        e.setEntryDate(r.date());
        e.setDescription(r.description().strip());
        e.setCategory(r.category() == null ? AccountExcel.guess(r.description()) : r.category());
        e.setPaidBy(blank(r.paidBy()));
        e.setInvoiceNumber(blank(r.invoiceNumber()));
        e.setAmount(r.amount());
        if (r.method() != null) e.setMethod(r.method());
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
