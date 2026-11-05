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
