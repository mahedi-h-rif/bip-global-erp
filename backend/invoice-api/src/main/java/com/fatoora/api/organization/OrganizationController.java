package com.fatoora.api.organization;

import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import com.fatoora.zatca.ZatcaEnvironment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/organization")
public class OrganizationController {

    public record OrganizationDto(
            Long id,
            String legalNameAr,
            String legalNameEn,
            @Pattern(regexp = "3\\d{13}3", message = "15 digits, starting and ending with 3") String vatNumber,
            @NotBlank String otherId,
            @NotBlank String otherIdScheme,
            @NotBlank String street,
            @Pattern(regexp = "\\d{4}", message = "4 digits") String buildingNumber,
            @Pattern(regexp = "(\\d{4})?", message = "4 digits") String additionalNumber,
            @NotBlank String district,
            @NotBlank String city,
            @Pattern(regexp = "\\d{5}", message = "5 digits") String postalCode,
            String phone,
            String email,
            String website,
            String signatoryName,
            String signatoryTitle,
            @Pattern(regexp = "#[0-9A-Fa-f]{6}") String brandColor,
            @Pattern(regexp = "CLASSIC|MODERN|SKYLINE") String letterheadStyle,
            @Size(max = 160) String tagline,
            String logoUrl,
            @NotBlank String invoicePrefix,
            @NotBlank String creditNotePrefix,
            @NotBlank String debitNotePrefix,
            ZatcaEnvironment zatcaEnvironment) {

        static OrganizationDto of(Organization o) {
            return new OrganizationDto(o.getId(), o.getLegalNameAr(), o.getLegalNameEn(), o.getVatNumber(),
                    o.getOtherId(), o.getOtherIdScheme(), o.getStreet(), o.getBuildingNumber(), o.getAdditionalNumber(),
                    o.getDistrict(), o.getCity(), o.getPostalCode(), o.getPhone(), o.getEmail(), o.getWebsite(),
                    o.getSignatoryName(), o.getSignatoryTitle(), o.getBrandColor(), o.getLetterheadStyle(), o.getTagline(),
                    o.getLogoUrl(), o.getInvoicePrefix(), o.getCreditNotePrefix(), o.getDebitNotePrefix(),
                    o.getZatcaEnvironment());
        }
    }

    private final OrganizationRepository repo;
    private final AuditService audit;

    public OrganizationController(OrganizationRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @GetMapping
    OrganizationDto get() {
        return OrganizationDto.of(load());
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    OrganizationDto update(@Valid @RequestBody OrganizationDto dto) {
        Organization o = load();
        if (!CurrentUser.isOperator()) {
            // Legal identity and the ZATCA environment are tied to the company's ZATCA certificate,
            // so only the e-invoicing provider may change them. Client admins manage contact details and branding.
            if (legalIdentityChanged(o, dto))
                throw new ApiException(HttpStatus.FORBIDDEN, "provider_only",
                        "Company legal details (name, VAT, CR, address) are managed by your e-invoicing provider. Contact them to change these.");
            dto = keepProtected(o, dto);
        }
        if (nz(dto.legalNameAr()).isBlank() && nz(dto.legalNameEn()).isBlank())
            throw new ApiException.Validation(java.util.Map.of("legalNameEn", "Enter the legal name in English or Arabic"));
        String idProblem = com.fatoora.api.customer.OtherIdRules.check(dto.otherIdScheme(), dto.otherId());
        if (idProblem != null) throw new ApiException.Validation(java.util.Map.of("otherId", idProblem));
        o.setLegalNameAr(dto.legalNameAr());
        o.setLegalNameEn(dto.legalNameEn());
        o.setVatNumber(dto.vatNumber());
        o.setOtherId(dto.otherId());
        o.setOtherIdScheme(dto.otherIdScheme());
        o.setStreet(dto.street());
        o.setBuildingNumber(dto.buildingNumber());
        o.setAdditionalNumber(emptyToNull(dto.additionalNumber()));
        o.setDistrict(dto.district());
        o.setCity(dto.city());
        o.setPostalCode(dto.postalCode());
        o.setPhone(emptyToNull(dto.phone()));
        o.setEmail(emptyToNull(dto.email()));
        o.setWebsite(emptyToNull(dto.website()));
        o.setSignatoryName(emptyToNull(dto.signatoryName()));
        o.setSignatoryTitle(emptyToNull(dto.signatoryTitle()));
        if (dto.brandColor() != null) o.setBrandColor(dto.brandColor());
        if (dto.letterheadStyle() != null) o.setLetterheadStyle(dto.letterheadStyle());
        o.setTagline(emptyToNull(dto.tagline()));
        o.setInvoicePrefix(dto.invoicePrefix());
        o.setCreditNotePrefix(dto.creditNotePrefix());
        o.setDebitNotePrefix(dto.debitNotePrefix());
        if (dto.zatcaEnvironment() != null && dto.zatcaEnvironment() != o.getZatcaEnvironment()) {
            audit.record(o.getId(), "ZATCA_ENVIRONMENT_CHANGED", "organization", o.getId(),
                    o.getZatcaEnvironment() + " -> " + dto.zatcaEnvironment());
            o.setZatcaEnvironment(dto.zatcaEnvironment());
        }
        audit.record(o.getId(), "ORGANIZATION_UPDATED", "organization", o.getId(), null);
        return OrganizationDto.of(o);
    }

    private static final int MAX_LOGO_BYTES = 1024 * 1024;

    /**
     * The company logo. Public, because the login page shows it before anyone signs in; one deployment
     * serves one company, so this is the first organization.
     */
    @GetMapping("/logo")
    ResponseEntity<byte[]> logo() {
        return repo.findAll(Sort.by("id")).stream().findFirst()
                .filter(o -> o.getLogo() != null)
                .map(o -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(o.getLogoContentType()))
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                        .body(java.util.Base64.getDecoder().decode(o.getLogo())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping(value = "/logo", consumes = {MediaType.IMAGE_PNG_VALUE, MediaType.IMAGE_JPEG_VALUE})
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    OrganizationDto uploadLogo(@RequestBody byte[] image, @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType) {
        if (image.length == 0 || image.length > MAX_LOGO_BYTES)
            throw new ApiException.Validation(java.util.Map.of("logo", "PNG or JPEG up to 1 MB"));
        Organization o = load();
        o.setLogo(java.util.Base64.getEncoder().encodeToString(image));
        o.setLogoContentType(MediaType.parseMediaType(contentType).getSubtype().equals("png")
                ? MediaType.IMAGE_PNG_VALUE : MediaType.IMAGE_JPEG_VALUE);
        // The version parameter makes browsers fetch the new image instead of a cached one.
        o.setLogoUrl("/api/v1/organization/logo?v=" + System.currentTimeMillis());
        audit.record(o.getId(), "LOGO_UPDATED", "organization", o.getId(), image.length + " bytes");
        return OrganizationDto.of(o);
    }

    @DeleteMapping("/logo")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    OrganizationDto deleteLogo() {
        Organization o = load();
        o.setLogo(null);
        o.setLogoContentType(null);
        o.setLogoUrl(null);
        audit.record(o.getId(), "LOGO_REMOVED", "organization", o.getId(), null);
        return OrganizationDto.of(o);
    }

    private static boolean legalIdentityChanged(Organization o, OrganizationDto d) {
        return !java.util.Objects.equals(nz(o.getLegalNameAr()).trim(), nz(d.legalNameAr()).trim())
                || !java.util.Objects.equals(nz(o.getLegalNameEn()).trim(), nz(d.legalNameEn()).trim())
                || !java.util.Objects.equals(o.getVatNumber(), d.vatNumber())
                || !java.util.Objects.equals(o.getOtherId(), d.otherId())
                || !java.util.Objects.equals(o.getOtherIdScheme(), d.otherIdScheme())
                || !java.util.Objects.equals(o.getStreet(), d.street())
                || !java.util.Objects.equals(o.getBuildingNumber(), d.buildingNumber())
                || !java.util.Objects.equals(nz(o.getAdditionalNumber()), nz(d.additionalNumber()))
                || !java.util.Objects.equals(o.getDistrict(), d.district())
                || !java.util.Objects.equals(o.getCity(), d.city())
                || !java.util.Objects.equals(o.getPostalCode(), d.postalCode())
                || (d.zatcaEnvironment() != null && d.zatcaEnvironment() != o.getZatcaEnvironment());
    }

    /** For client admins: everything protected keeps its stored value. */
    private static OrganizationDto keepProtected(Organization o, OrganizationDto d) {
        return new OrganizationDto(o.getId(), o.getLegalNameAr(), o.getLegalNameEn(), o.getVatNumber(), o.getOtherId(),
                o.getOtherIdScheme(), o.getStreet(), o.getBuildingNumber(), o.getAdditionalNumber(), o.getDistrict(),
                o.getCity(), o.getPostalCode(), d.phone(), d.email(), d.website(), d.signatoryName(), d.signatoryTitle(), d.brandColor(), d.letterheadStyle(), d.tagline(), d.logoUrl(), d.invoicePrefix(),
                d.creditNotePrefix(), d.debitNotePrefix(), o.getZatcaEnvironment());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private Organization load() {
        return repo.findById(CurrentUser.orgId()).orElseThrow(() -> ApiException.notFound("Organization"));
    }
}
