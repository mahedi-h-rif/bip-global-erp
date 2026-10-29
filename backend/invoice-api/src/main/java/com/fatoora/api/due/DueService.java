package com.fatoora.api.due;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.customer.Customer;
import com.fatoora.api.customer.CustomerRepository;
import com.fatoora.api.invoice.InvoiceFigures;
import com.fatoora.api.invoice.InvoiceRepository;
import com.fatoora.api.invoice.InvoiceStatus;
import com.fatoora.api.payment.BalanceCalculator.Balance;
import com.fatoora.api.payment.BalanceCalculator.PaymentStatus;
import com.fatoora.api.payment.PaymentService;
import com.fatoora.zatca.model.InvoiceKind;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Outstanding balances, per invoice and per customer. */
@Service
public class DueService {

    /** One issued invoice that is not fully paid. {@code daysOverdue} is null when it has no due date. */
    public record DueInvoice(Long invoiceId, String number, InvoiceKind kind, Long customerId, String customerName,
                             Instant issuedAt, LocalDate dueDate, long ageDays, Long daysOverdue,
                             BigDecimal total, BigDecimal adjustedTotal, BigDecimal paid, BigDecimal due,
                             PaymentStatus paymentStatus, boolean shareable) {}

    /** All invoices of one customer ({@code customerId} null = walk-in customers) that still have money due. */
    public record CustomerDue(Long customerId, String customerName, String email, String phone, boolean remindersEnabled,
                              Instant lastReminderAt, int invoiceCount, BigDecimal totalInvoiced, BigDecimal paid,
                              BigDecimal due, Instant oldestIssuedAt, long maxDaysOverdue) {}

    private final InvoiceRepository invoices;
    private final CustomerRepository customers;
    private final PaymentService payments;

    public DueService(InvoiceRepository invoices, CustomerRepository customers, PaymentService payments) {
        this.invoices = invoices;
        this.customers = customers;
        this.payments = payments;
    }

    /**
     * @param q           matches the invoice number or the customer's name
     * @param overdueOnly only invoices whose due date has passed
     */
    @Transactional(readOnly = true)
    public List<DueInvoice> dues(Long orgId, String q, Long customerId, boolean overdueOnly) {
        Map<Long, Customer> byId = customers(orgId);
        String needle = q == null || q.isBlank() ? null : q.strip().toLowerCase();
        return allDues(orgId, byId).stream()
                .filter(d -> customerId == null || customerId.equals(d.customerId()))
                .filter(d -> !overdueOnly || (d.daysOverdue() != null && d.daysOverdue() > 0))
                .filter(d -> needle == null || contains(d.number(), needle) || contains(d.customerName(), needle)
                        || (d.customerId() != null && contains(byId.get(d.customerId()).getNameAr(), needle)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CustomerDue> byCustomer(Long orgId, String q) {
        Map<Long, Customer> byId = customers(orgId);
        String needle = q == null || q.isBlank() ? null : q.strip().toLowerCase();
        Map<Long, List<DueInvoice>> grouped = new LinkedHashMap<>();
        for (DueInvoice d : allDues(orgId, byId)) grouped.computeIfAbsent(d.customerId(), k -> new ArrayList<>()).add(d);

        List<CustomerDue> result = new ArrayList<>();
        grouped.forEach((cid, list) -> {
            Customer c = cid == null ? null : byId.get(cid);
            String name = c == null ? "Walk-in customers" : displayName(c);
            if (needle != null && !contains(name, needle) && (c == null || !contains(c.getNameAr(), needle))) return;
            result.add(new CustomerDue(cid, name, c == null ? null : c.getEmail(), c == null ? null : c.getPhone(),
                    c != null && c.isRemindersEnabled(), c == null ? null : c.getLastReminderAt(), list.size(),
                    sum(list, DueInvoice::adjustedTotal), sum(list, DueInvoice::paid), sum(list, DueInvoice::due),
                    list.stream().map(DueInvoice::issuedAt).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null),
                    list.stream().map(DueInvoice::daysOverdue).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(0L)));
        });
        result.sort(Comparator.comparing(CustomerDue::due).reversed());
        return result;
    }

    /** One customer's due invoices, oldest first. Empty when nothing is owed. */
    @Transactional(readOnly = true)
    public List<DueInvoice> forCustomer(Long orgId, Long customerId) {
        return dues(orgId, null, customerId, false);
    }

    private List<DueInvoice> allDues(Long orgId, Map<Long, Customer> byId) {
        List<InvoiceFigures> docs = invoices.findIssuedFigures(orgId);
        Map<Long, InvoiceFigures> docById = docs.stream().collect(Collectors.toMap(InvoiceFigures::id, Function.identity()));
        Map<Long, Balance> balances = payments.balances(orgId);
        LocalDate today = LocalDate.now(InvoiceApiApplication.KSA);
        List<DueInvoice> result = new ArrayList<>();
        for (Balance b : balances.values()) {
            if (b.due().signum() <= 0) continue;
            InvoiceFigures f = docById.get(b.invoiceId());
            Customer c = f.customerId() == null ? null : byId.get(f.customerId());
            LocalDate issued = f.issuedAt() == null ? today : f.issuedAt().atZone(InvoiceApiApplication.KSA).toLocalDate();
            Long overdue = f.dueDate() == null ? null : Math.max(0, ChronoUnit.DAYS.between(f.dueDate(), today));
            result.add(new DueInvoice(f.id(), f.number(), f.kind(), f.customerId(),
                    c == null ? null : displayName(c), f.issuedAt(), f.dueDate(),
                    ChronoUnit.DAYS.between(issued, today), overdue, b.total(), b.adjusted(), b.paid(), b.due(), b.status(),
                    shareable(f)));
        }
        result.sort(Comparator.comparing(DueInvoice::issuedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    private Map<Long, Customer> customers(Long orgId) {
        return customers.findByOrganizationId(orgId, Sort.unsorted()).stream()
                .collect(Collectors.toMap(Customer::getId, Function.identity()));
    }

    /** Mirrors InvoiceService.downloadable: a standard invoice may only reach the buyer once ZATCA has cleared it. */
    private static boolean shareable(InvoiceFigures f) {
        return f.kind() == InvoiceKind.SIMPLIFIED
                || f.status() == InvoiceStatus.CLEARED || f.status() == InvoiceStatus.CLEARED_WITH_WARNINGS;
    }

    public static String displayName(Customer c) {
        return c.getNameEn() != null && !c.getNameEn().isBlank() ? c.getNameEn() : c.getNameAr();
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle);
    }

    private static BigDecimal sum(List<DueInvoice> list, Function<DueInvoice, BigDecimal> f) {
        return list.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
