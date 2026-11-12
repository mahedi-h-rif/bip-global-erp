package com.fatoora.api.agreement;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.customer.Customer;
import com.fatoora.api.customer.CustomerRepository;
import com.fatoora.api.organization.Organization;
import com.fatoora.api.organization.OrganizationRepository;
import com.fatoora.api.pdf.LetterheadPdf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Agreements on the company letterhead, and the admin-edited standard agreement they start from. */
@RestController
@RequestMapping("/api/v1")
public class AgreementController {

    private static final String WRITE = "hasAnyRole('ADMIN','ACCOUNTANT','BRANCH')";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public record TemplateDto(@NotBlank String title, @NotBlank String body, boolean standard, String updatedBy,
                              Instant updatedAt) {}

    public record AgreementRequest(
            Long customerId,
            String clientName,
            String clientDetails,
            String title,
            String body,
            LocalDate agreementDate,
            Agreement.Status status,
            LocalDate signedOn) {}

    public record AgreementDto(
            Long id, String number, Long customerId, String clientName, String clientDetails, String title, String body,
            LocalDate agreementDate, Agreement.Status status, LocalDate signedOn, String createdBy, Instant createdAt,
            Instant updatedAt) {

        static AgreementDto of(Agreement a) {
            return new AgreementDto(a.getId(), a.getNumber(), a.getCustomerId(), a.getClientName(), a.getClientDetails(),
                    a.getTitle(), a.getBody(), a.getAgreementDate(), a.getStatus(), a.getSignedOn(), a.getCreatedBy(),
                    a.getCreatedAt(), a.getUpdatedAt());
        }
    }

    private final AgreementRepository agreements;
    private final AgreementTemplateRepository templates;
    private final CustomerRepository customers;
    private final OrganizationRepository orgs;
    private final LetterheadPdf letterhead;
    private final AuditService audit;

    public AgreementController(AgreementRepository agreements, AgreementTemplateRepository templates,
                               CustomerRepository customers, OrganizationRepository orgs, LetterheadPdf letterhead,
                               AuditService audit) {
        this.agreements = agreements;
        this.templates = templates;
        this.customers = customers;
        this.orgs = orgs;
        this.letterhead = letterhead;
        this.audit = audit;
    }

    // --- standard agreement (template) ---------------------------------------------------------------

    @GetMapping("/agreement-template")
    TemplateDto template() {
        return templates.findByOrganizationId(CurrentUser.orgId())
                .map(t -> new TemplateDto(t.getTitle(), t.getBody(), false, t.getUpdatedBy(), t.getUpdatedAt()))
                .orElse(new TemplateDto(StandardAgreement.TITLE, StandardAgreement.BODY, true, null, null));
    }

    @PutMapping("/agreement-template")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    TemplateDto saveTemplate(@Valid @RequestBody TemplateDto dto) {
        Long orgId = CurrentUser.orgId();
        AgreementTemplate t = templates.findByOrganizationId(orgId).orElseGet(() -> {
            AgreementTemplate n = new AgreementTemplate();
            n.setOrganizationId(orgId);
            return n;
        });
        t.setTitle(dto.title().strip());
        t.setBody(dto.body());
        t.setUpdatedBy(CurrentUser.get().email());
        templates.save(t);
        audit.record(orgId, "AGREEMENT_TEMPLATE_UPDATED", "agreement_template", t.getId(), t.getTitle());
        return template();
    }

    /** Back to the built-in standard agreement. */
    @DeleteMapping("/agreement-template")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    TemplateDto resetTemplate() {
        Long orgId = CurrentUser.orgId();
        templates.findByOrganizationId(orgId).ifPresent(templates::delete);
        audit.record(orgId, "AGREEMENT_TEMPLATE_RESET", "agreement_template", null, null);
        return new TemplateDto(StandardAgreement.TITLE, StandardAgreement.BODY, true, null, null);
    }

    /** The saved standard agreement on the letterhead, with sample client details: the "Sample agreement" button. */
    @GetMapping("/agreement-template/pdf")
    ResponseEntity<byte[]> templatePdf() {
        TemplateDto t = template();
        return pdfResponse(sample(t.title(), t.body()), "agreement-sample.pdf");
    }

    /** The admin's unsaved edits as a sample PDF to download, before saving them. */
    @PostMapping("/agreement-template/pdf")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<byte[]> editedTemplatePdf(@Valid @RequestBody TemplateDto dto) {
        byte[] pdf = sample(dto.title().strip(), dto.body());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("agreement-sample-edited.pdf").build().toString())
                .body(pdf);
    }

    private byte[] sample(String title, String body) {
        Agreement sample = new Agreement();
        sample.setNumber("AGR-SAMPLE");
        sample.setClientName("Sample Client Trading Est.");
        sample.setClientDetails("CR 1010000000 · VAT 300000000000003\nRiyadh, Kingdom of Saudi Arabia\nMobile 0500000000 · client@example.com");
        sample.setTitle(title);
        sample.setBody(body);
        sample.setAgreementDate(LocalDate.now(InvoiceApiApplication.KSA));
        return render(sample, org(CurrentUser.orgId()));
    }

    // --- agreements ---------------------------------------------------------------------------------

    @GetMapping("/agreements")
    List<AgreementDto> list() {
        return agreements.findByOrganizationIdOrderByCreatedAtDesc(CurrentUser.orgId()).stream()
                .filter(a -> CurrentUser.owns(a.getCreatedBy())).map(AgreementDto::of).toList();
    }

    @GetMapping("/agreements/{id}")
    AgreementDto get(@PathVariable Long id) {
        return AgreementDto.of(load(id));
    }

    /** New agreement for a client; title and text default to the standard agreement. */
    @PostMapping("/agreements")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    AgreementDto create(@RequestBody AgreementRequest req) {
        Long orgId = CurrentUser.orgId();
        TemplateDto t = template();
        Agreement a = new Agreement();
        a.setOrganizationId(orgId);
        a.setCreatedBy(CurrentUser.get().email());
        a.setNumber(nextNumber(orgId));
        a.setTitle(t.title());
        a.setBody(t.body());
        apply(a, req);
        agreements.save(a);
        audit.record(orgId, "AGREEMENT_CREATED", "agreement", a.getId(), a.getNumber() + " · " + a.getClientName());
        return AgreementDto.of(a);
    }

    @PutMapping("/agreements/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    AgreementDto update(@PathVariable Long id, @RequestBody AgreementRequest req) {
        Agreement a = load(id);
        apply(a, req);
        audit.record(a.getOrganizationId(), "AGREEMENT_UPDATED", "agreement", a.getId(), a.getNumber() + " · " + a.getStatus());
        return AgreementDto.of(a);
    }

    @DeleteMapping("/agreements/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable Long id) {
        Agreement a = load(id);
        agreements.delete(a);
        audit.record(a.getOrganizationId(), "AGREEMENT_DELETED", "agreement", a.getId(), a.getNumber());
    }

    @GetMapping("/agreements/{id}/pdf")
    ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        Agreement a = load(id);
        return pdfResponse(render(a, org(a.getOrganizationId())), a.getNumber() + ".pdf");
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void apply(Agreement a, AgreementRequest req) {
        Customer c = req.customerId() == null ? null
                : customers.findByIdAndOrganizationId(req.customerId(), CurrentUser.orgId())
                        .orElseThrow(() -> ApiException.notFound("Customer"));
        String name = blank(req.clientName()) ? (c == null ? null : displayName(c)) : req.clientName().strip();
        if (name == null)
            throw new ApiException.Validation(Map.of("clientName", "Choose a customer or enter the client's name"));
        a.setCustomerId(req.customerId());
        a.setClientName(name);
        a.setClientDetails(blank(req.clientDetails()) ? (c == null ? null : clientDetails(c)) : req.clientDetails().strip());
        if (!blank(req.title())) a.setTitle(req.title().strip());
        if (!blank(req.body())) a.setBody(req.body());
        a.setAgreementDate(req.agreementDate() == null
                ? (a.getAgreementDate() == null ? LocalDate.now(InvoiceApiApplication.KSA) : a.getAgreementDate())
                : req.agreementDate());
        if (req.status() != null) {
            a.setStatus(req.status());
            a.setSignedOn(req.status() == Agreement.Status.SIGNED
                    ? (req.signedOn() == null ? LocalDate.now(InvoiceApiApplication.KSA) : req.signedOn())
                    : null);
        }
    }

    /** VAT / CR / ID, address and contact of a customer, one per line. */
    static String clientDetails(Customer c) {
        List<String> lines = new ArrayList<>();
        if (!blank(c.getNameAr()) && !c.getNameAr().equals(displayName(c))) lines.add(c.getNameAr());
        String ids = String.join(" · ", Stream.of(
                blank(c.getVatNumber()) ? null : "VAT " + c.getVatNumber(),
                blank(c.getOtherId()) ? null : (c.getOtherIdScheme() == null ? "ID" : c.getOtherIdScheme()) + " " + c.getOtherId())
                .filter(s -> s != null).toList());
        if (!ids.isBlank()) lines.add(ids);
        String address = com.fatoora.api.quotation.QuotationController.address(c);
        if (!blank(address)) lines.add(address);
        String contact = String.join(" · ", Stream.of(
                blank(c.getPhone()) ? null : "Mobile " + c.getPhone(),
                blank(c.getEmail()) ? null : c.getEmail()).filter(s -> s != null).toList());
        if (!contact.isBlank()) lines.add(contact);
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private byte[] render(Agreement a, Organization org) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("company_name", nz(org.displayName()));
        // The Arabic name only next to an English one; never the same name twice, never empty brackets.
        values.put("company_name_ar", blank(org.getLegalNameEn()) ? "" : nz(org.getLegalNameAr()));
        values.put("company_vat", nz(org.getVatNumber()));
        values.put("company_cr", nz(org.getOtherId()));
        // Numbers first, then the Arabic street, district and city, so digits never sit inside right-to-left text.
        values.put("company_address", String.join(" · ", Stream.of(
                blank(org.getBuildingNumber()) ? null : "Bldg " + org.getBuildingNumber(),
                blank(org.getPostalCode()) ? null : "Postal " + org.getPostalCode(),
                String.join("، ", Stream.of(org.getStreet(), org.getDistrict(), org.getCity()).filter(s -> !blank(s)).toList()))
                .filter(s -> !blank(s)).toList()));
        values.put("client_name", nz(a.getClientName()));
        values.put("client_details", nz(a.getClientDetails()));
        values.put("date", DATE.format(a.getAgreementDate()));
        values.put("agreement_number", nz(a.getNumber()));

        String meta = "<p class=\"muted\" style=\"text-align:center\">Agreement no. " + LetterheadPdf.esc(a.getNumber()) + " · Date "
                + DATE.format(a.getAgreementDate())
                + (a.getStatus() == Agreement.Status.SIGNED && a.getSignedOn() != null ? " · Signed " + DATE.format(a.getSignedOn()) : "")
                + "</p>";
        String html = "<h1 style=\"text-align:center; text-transform:uppercase; letter-spacing:0.5px\">"
                + LetterheadPdf.esc(a.getTitle()) + "</h1>" + meta
                + "<div style=\"margin-top:8px\">" + body(withoutEmptyArabicName(a.getBody(), values), values) + "</div>"
                + com.fatoora.api.pdf.BankDetailsBlock.html(org)
                + LetterheadPdf.signatures(org, a.getClientName());
        return letterhead.render(org, a.getTitle() + " " + a.getNumber(), html);
    }

    /** Drops " ({{company_name_ar}})" when the company has no separate Arabic name, so no "()" is printed. */
    static String withoutEmptyArabicName(String text, Map<String, String> values) {
        if (text == null) return "";
        if (!blank(values.get("company_name_ar"))) return text;
        return text.replace(" ({{company_name_ar}})", "").replace("({{company_name_ar}})", "");
    }

    /**
     * The agreement text as XHTML: "## " lines become clause headings, "- " lines bullets, blank lines
     * paragraph breaks; {{placeholders}} are replaced with the escaped values.
     */
    static String body(String text, Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        StringBuilder para = new StringBuilder();
        for (String raw : text.replace("\r\n", "\n").split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) {
                flush(out, para);
            } else if (line.startsWith("## ")) {
                flush(out, para);
                out.append("<h2>").append(fill(line.substring(3), values)).append("</h2>");
            } else if (line.startsWith("- ")) {
                flush(out, para);
                out.append("<p style=\"margin:0 0 3px 0; padding-left:14px\">• ").append(fill(line.substring(2), values)).append("</p>");
            } else {
                if (para.length() > 0) para.append("<br/>");
                para.append(fill(line, values));
            }
        }
        flush(out, para);
        return out.toString();
    }

    private static void flush(StringBuilder out, StringBuilder para) {
        if (para.length() == 0) return;
        out.append("<p>").append(para).append("</p>");
        para.setLength(0);
    }

    private static String fill(String line, Map<String, String> values) {
        String s = LetterheadPdf.esc(line);
        for (var e : values.entrySet()) s = s.replace("{{" + e.getKey() + "}}", LetterheadPdf.text(e.getValue()));
        return s;
    }

    private static ResponseEntity<byte[]> pdfResponse(byte[] pdf, String name) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name).build().toString())
                .body(pdf);
    }

    private String nextNumber(Long orgId) {
        String prefix = "AGR-" + LocalDate.now(InvoiceApiApplication.KSA).getYear() + "-";
        String max = agreements.maxNumber(orgId, prefix);
        int next = max == null ? 1 : Integer.parseInt(max.substring(prefix.length())) + 1;
        return prefix + String.format("%04d", next);
    }

    static String displayName(Customer c) {
        return !blank(c.getNameEn()) ? c.getNameEn() : c.getNameAr();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private Organization org(Long id) {
        return orgs.findById(id).orElseThrow();
    }

    private Agreement load(Long id) {
        return agreements.findByIdAndOrganizationId(id, CurrentUser.orgId()).filter(a -> CurrentUser.owns(a.getCreatedBy()))
                .orElseThrow(() -> ApiException.notFound("Agreement"));
    }
}
