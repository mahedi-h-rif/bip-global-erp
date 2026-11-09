package com.fatoora.api.egs;

import com.fatoora.api.auth.CurrentUser;
import com.fatoora.zatca.ZatcaEnvironment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** ZATCA device onboarding: the e-invoicing provider's job, never shown to the client's staff. */
@RestController
@RequestMapping("/api/v1/egs-units")
@PreAuthorize("hasRole('OPERATOR')")
public class EgsController {

    public record CreateRequest(
            @NotBlank String name,
            @NotBlank String branch,
            @NotBlank String location,
            @NotBlank String industry,
            @Pattern(regexp = "1100|1000|0100") String invoiceTypes) {}

    public record OtpRequest(@NotBlank @Pattern(regexp = "\\d{6}", message = "6-digit OTP") String otp) {}

    public record EgsDto(Long id, String name, String serialNumber, String branch, String location, String industry,
                         String invoiceTypes, ZatcaEnvironment environment, EgsUnit.Status status, long icv,
                         boolean isDefault, Instant activatedAt, Instant certificateExpiresAt) {}

    private final EgsService service;
    private final EgsUnitRepository units;

    public EgsController(EgsService service, EgsUnitRepository units) {
        this.service = service;
        this.units = units;
    }

    @GetMapping
    List<EgsDto> list() {
        return units.findByOrganizationIdOrderByCreatedAtDesc(CurrentUser.orgId()).stream().map(this::toDto).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    EgsDto create(@Valid @RequestBody CreateRequest req) {
        return toDto(service.create(CurrentUser.orgId(), req.name(), req.branch(), req.location(), req.industry(),
                req.invoiceTypes()));
    }

    @PostMapping("/{id}/onboard")
    EgsService.OnboardingResult onboard(@PathVariable Long id, @Valid @RequestBody OtpRequest req) {
        return service.onboard(CurrentUser.orgId(), id, req.otp());
    }

    @PostMapping("/{id}/renew")
    EgsService.OnboardingResult renew(@PathVariable Long id, @Valid @RequestBody OtpRequest req) {
        return service.renew(CurrentUser.orgId(), id, req.otp());
    }

    @PostMapping("/{id}/default")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void makeDefault(@PathVariable Long id) {
        service.makeDefault(CurrentUser.orgId(), id);
    }

    @PostMapping("/{id}/retire")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void retire(@PathVariable Long id) {
        service.retire(CurrentUser.orgId(), id);
    }

    private EgsDto toDto(EgsUnit u) {
        Instant expires = service.activeProductionCredentials(u.getId()).stream()
                .map(EgsCredential::getCertificateNotAfter).filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        return new EgsDto(u.getId(), u.getName(), u.getSerialNumber(), u.getBranch(), u.getLocation(), u.getIndustry(),
                u.getInvoiceTypes(), u.getEnvironment(), u.getStatus(), u.getIcv(), u.isDefault(), u.getActivatedAt(),
                expires);
    }
}
