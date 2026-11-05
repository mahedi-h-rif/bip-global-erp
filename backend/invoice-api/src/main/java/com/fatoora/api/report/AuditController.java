package com.fatoora.api.report;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.account.AccountReport;
import com.fatoora.api.account.ExpenseEntry;
import com.fatoora.api.account.ExpenseEntryRepository;
import com.fatoora.api.account.IncomeEntry;
import com.fatoora.api.account.IncomeEntryRepository;
import com.fatoora.api.account.PartnerWithdrawal;
import com.fatoora.api.account.PartnerWithdrawalRepository;
import com.fatoora.api.audit.AuditLogRepository;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.customer.Customer;
import com.fatoora.api.customer.CustomerRepository;
import com.fatoora.api.document.CompanyDocumentRepository;
import com.fatoora.api.due.DueService;
import com.fatoora.api.invoice.InvoiceRepository;
import com.fatoora.api.organization.Organization;
import com.fatoora.api.organization.OrganizationRepository;
import com.fatoora.api.payment.PaymentService;
import com.fatoora.api.pdf.LetterheadPdf;
import com.fatoora.api.user.AppUser;
import com.fatoora.api.user.AppUserRepository;
import com.fatoora.api.worker.WorkerMonthRepository;
import com.fatoora.api.worker.WorkerRepository;
import com.fatoora.zatca.ZatcaEnvironment;
import com.fatoora.zatca.model.VatCategory;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The business audit of a period (admin only), on screen and as a bilingual PDF. */
@RestController
@RequestMapping("/api/v1/business-audit")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {

    private final InvoiceRepository invoices;
    private final CustomerRepository customers;
    private final PaymentService payments;
    private final IncomeEntryRepository income;
    private final ExpenseEntryRepository expenses;
    private final PartnerWithdrawalRepository withdrawals;
    private final WorkerMonthRepository workerMonths;
    private final WorkerRepository workers;
    private final CompanyDocumentRepository documents;
    private final AuditLogRepository auditLog;
    private final OrganizationRepository orgs;
    private final AppUserRepository users;
    private final LetterheadPdf letterhead;
    private final AuditService audit;

    public AuditController(InvoiceRepository invoices, CustomerRepository customers, PaymentService payments,
                           IncomeEntryRepository income, ExpenseEntryRepository expenses, PartnerWithdrawalRepository withdrawals,
                           WorkerMonthRepository workerMonths, WorkerRepository workers, CompanyDocumentRepository documents,
                           AuditLogRepository auditLog, OrganizationRepository orgs, AppUserRepository users,
                           LetterheadPdf letterhead, AuditService audit) {
        this.invoices = invoices;
        this.customers = customers;
        this.payments = payments;
        this.income = income;
        this.expenses = expenses;
        this.withdrawals = withdrawals;
        this.workerMonths = workerMonths;
        this.workers = workers;
        this.documents = documents;
        this.auditLog = auditLog;
        this.orgs = orgs;
        this.users = users;
        this.letterhead = letterhead;
        this.audit = audit;
    }

    /** @param from first day (KSA), default the start of this quarter; @param to last day, default today */
    @GetMapping
    @Transactional(readOnly = true)
    AuditReport.Result report(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return build(CurrentUser.orgId(), from, to);
    }

    @GetMapping("/pdf")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> pdf(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long orgId = CurrentUser.orgId();
        AuditReport.Result r = build(orgId, from, to);
        byte[] pdf = letterhead.render(orgs.findById(orgId).orElseThrow(), "Business audit " + r.from() + " to " + r.to(), AuditReportPdf.html(r));
        audit.record(orgId, "AUDIT_REPORT_GENERATED", "audit", null, r.from() + " to " + r.to() + " score " + r.score().score());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename("business-audit-" + r.from() + "-to-" + r.to() + ".pdf").build().toString())
                .body(pdf);
    }

    private AuditReport.Result build(Long orgId, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.now(InvoiceApiApplication.KSA);
        LocalDate start = from != null ? from : today.withDayOfMonth(1).withMonth((today.getMonthValue() - 1) / 3 * 3 + 1);
        LocalDate end = to != null ? to : today;
        if (end.isBefore(start)) throw new IllegalArgumentException("'to' must not be before 'from'");
        Instant fromAt = start.atStartOfDay(InvoiceApiApplication.KSA).toInstant();
        Instant toAt = end.plusDays(1).atStartOfDay(InvoiceApiApplication.KSA).toInstant();

        Organization org = orgs.findById(orgId).orElseThrow();
        Map<Long, AuditReport.Party> parties = customers.findByOrganizationId(orgId, Sort.unsorted()).stream()
                .collect(Collectors.toMap(Customer::getId, c -> new AuditReport.Party(c.getId(), DueService.displayName(c),
                        notBlank(c.getVatNumber()) || notBlank(c.getOtherId()), c.getCountryCode()), (a, b) -> a, LinkedHashMap::new));

        List<AuditReport.LineNet> lines = invoices.lineNetsByCategory(orgId, fromAt, toAt).stream()
                .map(r -> new AuditReport.LineNet((Long) r[0], (VatCategory) r[1], (BigDecimal) r[2])).toList();
        List<AuditReport.ChainLink> chain = invoices.findChain(orgId).stream()
                .map(r -> new AuditReport.ChainLink((Long) r[0], ((Number) r[1]).longValue())).toList();

        List<IncomeEntry> in = income.findByOrganizationIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(orgId, start, end);
        List<ExpenseEntry> out = expenses.findByOrganizationIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(orgId, start, end);
        List<PartnerWithdrawal> drawn = withdrawals.findByOrganizationIdAndEntryDateBetweenOrderByEntryDateAscIdAsc(orgId, start, end);
        BigDecimal workerPaid = workerPaid(orgId, start, end);
        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        for (ExpenseEntry e : out) byCategory.merge(e.getCategory().name(), e.getAmount(), BigDecimal::add);
        if (workerPaid.signum() != 0) byCategory.merge(AccountReport.WORKERS, workerPaid, BigDecimal::add);
        List<AccountReport.Category> categories = byCategory.entrySet().stream()
                .map(e -> new AccountReport.Category(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(AccountReport.Category::amount).reversed()).toList();
        AuditReport.Cash cash = new AuditReport.Cash(
                sum(in.stream().map(IncomeEntry::getPaid)),
                sum(out.stream().map(ExpenseEntry::getAmount)).add(workerPaid),
                sum(drawn.stream().map(PartnerWithdrawal::getAmount)), categories);

        Map<String, Long> actions = new LinkedHashMap<>();
        for (Object[] r : auditLog.countActions(orgId, fromAt, toAt)) actions.put((String) r[0], ((Number) r[1]).longValue());

        String preparedBy = users.findById(CurrentUser.get().userId()).map(AppUser::getFullName).orElse(CurrentUser.get().email());

        return AuditReport.build(new AuditReport.Inputs(company(org), start, end, today, Instant.now(), preparedBy,
                invoices.findAuditRows(orgId, fromAt, toAt), lines, chain,
                invoices.findIssuedFigures(orgId), payments.balances(orgId), parties, cash,
                documents.findByOrganizationId(orgId).stream()
                        .map(d -> new AuditReport.FileOnRecord(d.getType(), d.getWorkerId(), d.getNumber(), d.getExpiryDate())).toList(),
                workers.findByOrganizationIdOrderByPositionAscIdAsc(orgId).stream()
                        .map(w -> new AuditReport.Staff(w.getId(), w.getName(), w.isActive())).toList(),
                new AuditReport.Activity(actions, auditLog.countActors(orgId, fromAt, toAt))));
    }

    private static AuditReport.Company company(Organization o) {
        List<String> missing = new ArrayList<>();
        if (!notBlank(o.getLegalNameAr()) && !notBlank(o.getLegalNameEn())) missing.add("Legal name");
        if (!notBlank(o.getVatNumber())) missing.add("VAT number");
        if (!notBlank(o.getOtherId())) missing.add("CR number");
        if (!notBlank(o.getBuildingNumber()) || !notBlank(o.getStreet()) || !notBlank(o.getDistrict()) || !notBlank(o.getCity())
                || !notBlank(o.getPostalCode())) missing.add("national address");
        String address = Stream.of(o.getBuildingNumber(), o.getStreet(), o.getDistrict(), o.getCity(), o.getPostalCode(), o.getAdditionalNumber())
                .filter(AuditController::notBlank).collect(Collectors.joining(", "));
        return new AuditReport.Company(o.getLegalNameEn(), o.getLegalNameAr(), o.getVatNumber(), o.getOtherId(), address,
                o.getZatcaEnvironment() == ZatcaEnvironment.PRODUCTION, missing);
    }

    /** Paid to workers (Workers ledger) in the months the period touches. */
    private BigDecimal workerPaid(Long orgId, LocalDate from, LocalDate to) {
        BigDecimal total = BigDecimal.ZERO;
        YearMonth first = YearMonth.from(from), last = YearMonth.from(to);
        for (int year = first.getYear(); year <= last.getYear(); year++)
            for (Object[] row : workerMonths.paidByMonth(orgId, year)) {
                YearMonth ym = YearMonth.of(year, ((Number) row[0]).intValue());
                if (!ym.isBefore(first) && !ym.isAfter(last)) total = total.add((BigDecimal) row[1]);
            }
        return total;
    }

    private static BigDecimal sum(Stream<BigDecimal> values) {
        return values.map(v -> v == null ? BigDecimal.ZERO : v).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
