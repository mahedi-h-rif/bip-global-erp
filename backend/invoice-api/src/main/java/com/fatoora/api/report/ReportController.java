package com.fatoora.api.report;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.customer.Customer;
import com.fatoora.api.customer.CustomerRepository;
import com.fatoora.api.due.DueService;
import com.fatoora.api.invoice.InvoiceRepository;
import com.fatoora.api.payment.PaymentService;
import com.fatoora.zatca.model.InvoiceKind;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Sales report for the client's admin. Normal staff (ACCOUNTANT) get 403. */
@RestController
@RequestMapping("/api/v1/reports")
@PreAuthorize("hasRole('ADMIN')")
public class ReportController {

    private final InvoiceRepository invoices;
    private final CustomerRepository customers;
    private final PaymentService payments;

    public ReportController(InvoiceRepository invoices, CustomerRepository customers, PaymentService payments) {
        this.invoices = invoices;
        this.customers = customers;
        this.payments = payments;
    }

    /**
     * @param from     first day (KSA), default 1 January of this year
     * @param to       last day (KSA), default today
     * @param q        customer name search (English or Arabic)
     * @param hourFrom time-of-day window start, 0-23 (KSA)
     * @param hourTo   time-of-day window end, exclusive; smaller than hourFrom means it wraps past midnight
     */
    @GetMapping("/sales")
    @Transactional(readOnly = true)
    SalesReport.Result sales(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                             @RequestParam(required = false) Long customerId,
                             @RequestParam(required = false) InvoiceKind kind,
                             @RequestParam(required = false) String q,
                             @RequestParam(required = false) Integer hourFrom,
                             @RequestParam(required = false) Integer hourTo) {
        Long orgId = CurrentUser.orgId();
        LocalDate today = LocalDate.now(InvoiceApiApplication.KSA);
        LocalDate start = from != null ? from : today.withDayOfYear(1);
        LocalDate end = to != null ? to : today;
        if (end.isBefore(start)) throw new IllegalArgumentException("'to' must not be before 'from'");
        if (hourFrom != null && (hourFrom < 0 || hourFrom > 23) || hourTo != null && (hourTo < 0 || hourTo > 24))
            throw new IllegalArgumentException("Hours must be between 0 and 24");

        Map<Long, Customer> byId = customers.findByOrganizationId(orgId, Sort.unsorted()).stream()
                .collect(Collectors.toMap(Customer::getId, Function.identity()));
        Predicate<Long> match = null;
        if (q != null && !q.isBlank()) {
            String needle = q.strip().toLowerCase();
            Set<Long> ids = byId.values().stream()
                    .filter(c -> contains(c.getNameEn(), needle) || contains(c.getNameAr(), needle))
                    .map(Customer::getId).collect(Collectors.toSet());
            match = id -> id != null && ids.contains(id);
        }
        return SalesReport.build(invoices.findIssuedFigures(orgId), payments.balances(orgId),
                new SalesReport.Filter(start, end, customerId, kind, hourFrom, hourTo, match),
                id -> byId.containsKey(id) ? DueService.displayName(byId.get(id)) : "#" + id);
    }

    private static boolean contains(String s, String needle) {
        return s != null && s.toLowerCase().contains(needle);
    }
}
