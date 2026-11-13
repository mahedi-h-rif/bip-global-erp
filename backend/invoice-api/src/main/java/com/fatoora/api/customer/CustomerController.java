package com.fatoora.api.customer;

import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.invoice.InvoiceRepository;
import com.fatoora.api.notification.PhoneNumbers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    public record CustomerDto(
            Long id,
            String nameAr,
            @NotBlank String nameEn,
            @Pattern(regexp = "(3\\d{13}3)?", message = "15 digits, starting and ending with 3") String vatNumber,
            String otherId,
            String otherIdScheme,
            String address,
            String street,
            String buildingNumber,
            String additionalNumber,
            String district,
            String city,
            String postalCode,
            String countryCode,
            @Email String email,
            @NotBlank(message = "Mobile number is required") String phone,
            Boolean remindersEnabled,
            Instant lastReminderAt) {

        static CustomerDto of(Customer c) {
            return new CustomerDto(c.getId(), c.getNameAr(), c.getNameEn(), c.getVatNumber(), c.getOtherId(),
                    c.getOtherIdScheme(), c.getAddress(), c.getStreet(), c.getBuildingNumber(), c.getAdditionalNumber(),
                    c.getDistrict(), c.getCity(), c.getPostalCode(), c.getCountryCode(), c.getEmail(), c.getPhone(),
                    c.isRemindersEnabled(), c.getLastReminderAt());
        }

        void applyTo(Customer c) {
            c.setNameAr(emptyToNull(nameAr));
            c.setNameEn(nameEn.strip());
            c.setVatNumber(emptyToNull(vatNumber));
            c.setOtherId(emptyToNull(otherId));
            c.setOtherIdScheme(emptyToNull(otherIdScheme));
            c.setAddress(emptyToNull(address));
            c.setStreet(emptyToNull(street));
            c.setBuildingNumber(emptyToNull(buildingNumber));
            c.setAdditionalNumber(emptyToNull(additionalNumber));
            c.setDistrict(emptyToNull(district));
            c.setCity(emptyToNull(city));
            c.setPostalCode(emptyToNull(postalCode));
            c.setCountryCode(countryCode == null || countryCode.isBlank() ? "SA" : countryCode);
            c.setEmail(emptyToNull(email));
            c.setPhone(emptyToNull(phone));
            if (remindersEnabled != null) c.setRemindersEnabled(remindersEnabled);
        }

        private static String emptyToNull(String s) {
            return s == null || s.isBlank() ? null : s.strip();
        }

        /**
         * Saudi national address rules (ZATCA BR-KSA-37 / BR-KSA-66), checked when the customer is saved
         * so a bad address is caught here rather than when an invoice is issued. Foreign addresses are exempt.
         */
        void validateAddress() {
            boolean ksa = countryCode == null || countryCode.isBlank() || "SA".equalsIgnoreCase(countryCode);
            Map<String, String> errors = new LinkedHashMap<>();
            if (countryCode != null && !countryCode.isBlank() && !countryCode.matches("[A-Za-z]{2}"))
                errors.put("countryCode", "Use the 2-letter country code, e.g. SA, BD, AE");
            if (ksa && postalCode != null && !postalCode.isBlank() && !postalCode.matches("\\d{5}"))
                errors.put("postalCode", "Saudi postal codes have 5 digits (for a foreign address, change the country)");
            if (ksa && buildingNumber != null && !buildingNumber.isBlank() && !buildingNumber.matches("\\d{4}"))
                errors.put("buildingNumber", "Saudi building numbers have 4 digits (for a foreign address, change the country)");
            if (ksa && additionalNumber != null && !additionalNumber.isBlank() && !additionalNumber.matches("\\d{4}"))
                errors.put("additionalNumber", "Saudi additional numbers have 4 digits");
            if (phone != null && !phone.isBlank() && PhoneNumbers.normalize(phone) == null)
                errors.put("phone", "Enter a mobile number, e.g. 05XXXXXXXX or +9665XXXXXXXX");
            String idProblem = OtherIdRules.check(otherIdScheme, otherId);
            if (idProblem != null) errors.put("otherId", idProblem);
            if (!errors.isEmpty()) throw new ApiException.Validation(errors);
        }
    }

    private static final String WRITE = "hasAnyRole('ADMIN','ACCOUNTANT','BRANCH')";

    private final CustomerRepository repo;
    private final InvoiceRepository invoices;
    private final AuditService audit;

    public CustomerController(CustomerRepository repo, InvoiceRepository invoices, AuditService audit) {
        this.repo = repo;
        this.invoices = invoices;
        this.audit = audit;
    }

    @GetMapping
    List<CustomerDto> list() {
        return repo.findByOrganizationId(CurrentUser.orgId(), Sort.by("nameEn")).stream().map(CustomerDto::of).toList();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired
    private com.fatoora.api.payment.PaymentService payments;

    /** One recent invoice in the customer preview. */
    public record RecentInvoice(Long id, String number, java.time.Instant issuedAt, java.math.BigDecimal total,
                                java.math.BigDecimal due, String status, String lifecycle) {}

    /** The customer at a glance: details, what they were invoiced, paid and still owe, and their latest invoices. */
    public record Overview(CustomerDto customer, long invoiceCount, java.math.BigDecimal invoiced, java.math.BigDecimal paid,
                           java.math.BigDecimal due, java.math.BigDecimal credit, long quotations, long salesOrders,
                           List<RecentInvoice> recent) {}

    @GetMapping("/{id}/overview")
    @Transactional(readOnly = true)
    Overview overview(@PathVariable Long id) {
        Customer c = load(id);
        Long orgId = CurrentUser.orgId();
        // An own-data login sees only the documents it created.
        String owner = CurrentUser.owner();
        List<java.util.Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, number, issued_at, total_incl_vat, status, cancelled_at, closed_at FROM invoice
                WHERE organization_id = ? AND customer_id = ? AND doc_type = 'INVOICE' AND status NOT IN ('DRAFT','REJECTED')
                  AND (?::text IS NULL OR lower(created_by) = lower(?::text))
                ORDER BY issued_at DESC NULLS LAST, id DESC""", orgId, id, owner, owner);
        List<Long> ids = rows.stream().map(r -> ((Number) r.get("id")).longValue()).toList();
        var balances = payments.balancesFor(ids);
        java.math.BigDecimal invoiced = java.math.BigDecimal.ZERO, paid = java.math.BigDecimal.ZERO,
                due = java.math.BigDecimal.ZERO, credit = java.math.BigDecimal.ZERO;
        for (var b : balances.values()) {
            invoiced = invoiced.add(b.adjusted());
            paid = paid.add(b.paid());
            due = due.add(b.due());
            credit = credit.add(b.overpaid());
        }
        List<RecentInvoice> recent = rows.stream().limit(8).map(r -> {
            Long iid = ((Number) r.get("id")).longValue();
            var b = balances.get(iid);
            String lifecycle = r.get("cancelled_at") != null ? "CANCELLED" : r.get("closed_at") != null ? "CLOSED" : null;
            Object at = r.get("issued_at");
            java.time.Instant issued = at instanceof java.sql.Timestamp ts ? ts.toInstant()
                    : at instanceof java.time.OffsetDateTime o ? o.toInstant() : null;
            return new RecentInvoice(iid, (String) r.get("number"), issued, (java.math.BigDecimal) r.get("total_incl_vat"),
                    b == null ? null : b.due(), (String) r.get("status"), lifecycle);
        }).toList();
        Long quotations = jdbc.queryForObject("""
                SELECT count(*) FROM quotation WHERE organization_id = ? AND customer_id = ?
                  AND (?::text IS NULL OR lower(created_by) = lower(?::text))""", Long.class, orgId, id, owner, owner);
        Long orders = jdbc.queryForObject("""
                SELECT count(*) FROM sales_order WHERE organization_id = ? AND customer_id = ?
                  AND (?::text IS NULL OR lower(created_by) = lower(?::text))""", Long.class, orgId, id, owner, owner);
        return new Overview(CustomerDto.of(c), rows.size(), invoiced, paid, due, credit,
                quotations == null ? 0 : quotations, orders == null ? 0 : orders, recent);
    }

    @GetMapping("/{id}")
    CustomerDto get(@PathVariable Long id) {
        return CustomerDto.of(load(id));
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    CustomerDto create(@Valid @RequestBody CustomerDto dto) {
        dto.validateAddress();
        Customer c = new Customer();
        c.setOrganizationId(CurrentUser.orgId());
        dto.applyTo(c);
        repo.save(c);
        audit.record(c.getOrganizationId(), "CUSTOMER_CREATED", "customer", c.getId(), c.getNameEn());
        return CustomerDto.of(c);
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    CustomerDto update(@PathVariable Long id, @Valid @RequestBody CustomerDto dto) {
        dto.validateAddress();
        Customer c = load(id);
        dto.applyTo(c);
        audit.record(c.getOrganizationId(), "CUSTOMER_UPDATED", "customer", c.getId(), c.getNameEn());
        return CustomerDto.of(c);
    }

    /** Customers that appear on any document stay, because issued invoices reference them. */
    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable Long id) {
        Customer c = load(id);
        if (invoices.existsByCustomerId(c.getId()))
            throw ApiException.conflict("customer_in_use", "This customer has invoices or drafts, so it can't be deleted. "
                    + "Turn off reminders instead, or delete its drafts first.");
        repo.delete(c);
        audit.record(c.getOrganizationId(), "CUSTOMER_DELETED", "customer", c.getId(), c.getNameEn());
    }

    private Customer load(Long id) {
        return repo.findByIdAndOrganizationId(id, CurrentUser.orgId()).orElseThrow(() -> ApiException.notFound("Customer"));
    }
}
