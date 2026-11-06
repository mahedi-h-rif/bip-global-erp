package com.fatoora.api.quotation;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.customer.Customer;
import com.fatoora.api.customer.CustomerRepository;
import com.fatoora.api.organization.Organization;
import com.fatoora.api.organization.OrganizationRepository;
import com.fatoora.api.pdf.InvoicePdfService;
import com.fatoora.api.pdf.LetterheadPdf;
import com.fatoora.zatca.model.InvoiceTotals;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** Quotations: like invoices, but internal price offers that never go to ZATCA. */
@RestController
@RequestMapping("/api/v1/quotations")
public class QuotationController {

    private static final String WRITE = "hasAnyRole('ADMIN','ACCOUNTANT','BRANCH')";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public record LineDto(
            @NotBlank String name,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
            String unitCode,
            @NotNull @DecimalMin("0") BigDecimal unitPrice,
            BigDecimal vatRate,
            Long productId) {}

    public record QuotationRequest(
            Long customerId,
            String clientName,
            LocalDate issueDate,
            LocalDate validUntil,
            Quotation.Status status,
            String notes,
            String terms,
            @NotEmpty List<@Valid LineDto> lines) {}

    public record QuotationDto(
            Long id, String number, Long customerId, String customerName, String clientName,
            LocalDate issueDate, LocalDate validUntil, Quotation.Status status, String notes, String terms,
            BigDecimal totalExclVat, BigDecimal vatTotal, BigDecimal totalInclVat, Long invoiceId,
            List<LineDto> lines, String createdBy, Instant createdAt) {}

    public record InvoiceLinkRequest(@NotNull Long invoiceId) {}

    private final QuotationRepository repo;
    private final CustomerRepository customers;
    private final OrganizationRepository orgs;
    private final LetterheadPdf letterhead;
    private final AuditService audit;

    public QuotationController(QuotationRepository repo, CustomerRepository customers, OrganizationRepository orgs,
                               LetterheadPdf letterhead, AuditService audit) {
        this.repo = repo;
        this.customers = customers;
        this.orgs = orgs;
        this.letterhead = letterhead;
        this.audit = audit;
    }

    @GetMapping
    @Transactional(readOnly = true)
    List<QuotationDto> list() {
        return repo.findByOrganizationIdOrderByCreatedAtDesc(CurrentUser.orgId()).stream()
                .filter(q -> CurrentUser.owns(q.getCreatedBy())).map(this::dto).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    QuotationDto get(@PathVariable Long id) {
        return dto(load(id));
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    QuotationDto create(@Valid @RequestBody QuotationRequest req) {
        Long orgId = CurrentUser.orgId();
        Quotation q = new Quotation();
        q.setOrganizationId(orgId);
        q.setCreatedBy(CurrentUser.get().email());
        q.setNumber(nextNumber(orgId));
        apply(q, req);
        repo.save(q);
        audit.record(orgId, "QUOTATION_CREATED", "quotation", q.getId(), q.getNumber());
        return dto(q);
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    QuotationDto update(@PathVariable Long id, @Valid @RequestBody QuotationRequest req) {
        Quotation q = load(id);
        apply(q, req);
        audit.record(q.getOrganizationId(), "QUOTATION_UPDATED", "quotation", q.getId(), q.getNumber());
        return dto(q);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable Long id) {
        Quotation q = load(id);
        repo.delete(q);
        audit.record(q.getOrganizationId(), "QUOTATION_DELETED", "quotation", q.getId(), q.getNumber());
    }

    /** Records the invoice draft made from this quotation. */
    @PostMapping("/{id}/invoiced")
    @PreAuthorize(WRITE)
    @Transactional
    QuotationDto invoiced(@PathVariable Long id, @Valid @RequestBody InvoiceLinkRequest req) {
        Quotation q = load(id);
        q.setInvoiceId(req.invoiceId());
        q.setStatus(Quotation.Status.INVOICED);
        audit.record(q.getOrganizationId(), "QUOTATION_INVOICED", "quotation", q.getId(), q.getNumber() + " → invoice " + req.invoiceId());
        return dto(q);
    }

    @GetMapping("/{id}/pdf")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        Quotation q = load(id);
        Organization org = orgs.findById(q.getOrganizationId()).orElseThrow();
        byte[] pdf = letterhead.render(org, "Quotation " + q.getNumber(), html(q, org));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(q.getNumber() + ".pdf").build().toString())
                .body(pdf);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void apply(Quotation q, QuotationRequest req) {
        if (req.customerId() != null) customer(req.customerId());
        if (req.customerId() == null && (req.clientName() == null || req.clientName().isBlank()))
            throw new ApiException.Validation(Map.of("clientName", "Choose a customer or enter the client's name"));
        q.setCustomerId(req.customerId());
        q.setClientName(blankToNull(req.clientName()));
        q.setIssueDate(req.issueDate() == null ? LocalDate.now(InvoiceApiApplication.KSA) : req.issueDate());
        q.setValidUntil(req.validUntil());
        if (req.status() != null) q.setStatus(req.status());
        q.setNotes(blankToNull(req.notes()));
        q.setTerms(blankToNull(req.terms()));
        q.replaceLines(req.lines().stream().map(l -> {
            QuotationLine line = new QuotationLine();
            line.setName(l.name().strip());
            line.setQuantity(l.quantity());
            line.setUnitCode(l.unitCode() == null || l.unitCode().isBlank() ? "PCE" : l.unitCode());
            line.setUnitPrice(l.unitPrice());
            line.setVatRate(l.vatRate() == null ? new BigDecimal("15") : l.vatRate());
            line.setProductId(l.productId());
            return line;
        }).toList());
        BigDecimal net = BigDecimal.ZERO, vat = BigDecimal.ZERO;
        for (QuotationLine l : q.getLines()) {
            BigDecimal lineNet = InvoiceTotals.round(l.getQuantity().multiply(l.getUnitPrice()));
            net = net.add(lineNet);
            vat = vat.add(InvoiceTotals.round(lineNet.multiply(l.getVatRate()).divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)));
        }
        q.setTotalExclVat(net);
        q.setVatTotal(vat);
        q.setTotalInclVat(net.add(vat));
    }

    /** Gap-free enough for an internal document: QT-2026-000001, QT-2026-000002 … per year. */
    private String nextNumber(Long orgId) {
        String prefix = "QT-" + LocalDate.now(InvoiceApiApplication.KSA).getYear() + "-";
        String max = repo.maxNumber(orgId, prefix);
        int next = max == null ? 1 : Integer.parseInt(max.substring(prefix.length())) + 1;
        return prefix + String.format("%06d", next);
    }

    private QuotationDto dto(Quotation q) {
        Customer c = q.getCustomerId() == null ? null : customers.findById(q.getCustomerId()).orElse(null);
        return new QuotationDto(q.getId(), q.getNumber(), q.getCustomerId(), c == null ? null : displayName(c),
                q.getClientName(), q.getIssueDate(), q.getValidUntil(), q.getStatus(), q.getNotes(), q.getTerms(),
                q.getTotalExclVat(), q.getVatTotal(), q.getTotalInclVat(), q.getInvoiceId(),
                q.getLines().stream().map(l -> new LineDto(l.getName(), l.getQuantity(), l.getUnitCode(), l.getUnitPrice(),
                        l.getVatRate(), l.getProductId())).toList(),
                q.getCreatedBy(), q.getCreatedAt());
    }

    private String html(Quotation q, Organization org) {
        Customer c = q.getCustomerId() == null ? null : customers.findById(q.getCustomerId()).orElse(null);
        String clientName = c == null ? q.getClientName() : displayName(c);
        String client = LetterheadPdf.box("Quotation for", "عرض سعر إلى", new String[][]{
                {"Client", clientName},
                {"Arabic name", c == null || c.getNameAr() == null || c.getNameAr().equals(clientName) ? null : c.getNameAr()},
                {"VAT number", c == null ? null : c.getVatNumber()},
                {"Address", c == null ? null : address(c)},
                {"Mobile", c == null ? null : c.getPhone()},
                {"Email", c == null ? null : c.getEmail()},
        });
        String meta = LetterheadPdf.box("Quotation details", "تفاصيل العرض", new String[][]{
                {"Quotation no.", q.getNumber()},
                {"Date", DATE.format(q.getIssueDate())},
                {"Valid until", q.getValidUntil() == null ? null : DATE.format(q.getValidUntil())},
        });

        StringBuilder rows = new StringBuilder();
        int n = 1;
        for (QuotationLine l : q.getLines()) {
            BigDecimal net = InvoiceTotals.round(l.getQuantity().multiply(l.getUnitPrice()));
            BigDecimal vat = InvoiceTotals.round(net.multiply(l.getVatRate()).divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
            rows.append("<tr>")
                    .append(td(Integer.toString(n++), "center"))
                    .append(td(LetterheadPdf.esc(l.getName()), InvoicePdfService.startsRtl(l.getName()) ? "right; direction:rtl" : "left"))
                    .append(td(l.getQuantity().stripTrailingZeros().toPlainString() + " " + LetterheadPdf.esc(l.getUnitCode()), "right"))
                    .append(td(LetterheadPdf.money(l.getUnitPrice()), "right"))
                    .append(td(l.getVatRate().stripTrailingZeros().toPlainString() + "%", "right"))
                    .append(td(LetterheadPdf.money(vat), "right"))
                    .append(td(LetterheadPdf.money(net.add(vat)), "right"))
                    .append("</tr>");
        }
        String th = "background:%s; color:#fff; font-size:8pt; padding:5px 4px; text-align:center"
                .formatted(org.getBrandColor() == null ? "#0B2A6F" : org.getBrandColor());

        return """
                <table><tr><td><h1>Quotation</h1></td><td class="rtl"><h1>عرض سعر</h1></td></tr></table>
                <table style="margin-top:6px"><tr>
                  <td style="width:58%%; vertical-align:top; padding-right:6px">%s</td>
                  <td style="width:42%%; vertical-align:top">%s</td>
                </tr></table>
                <table style="margin-top:12px">
                  <tr><th style="%s">#</th><th style="%s">Item name<br/>اسم الصنف</th><th style="%s">Qty<br/>الكمية</th>
                  <th style="%s">Unit price<br/>سعر الوحدة</th><th style="%s">VAT %%<br/>النسبة</th>
                  <th style="%s">VAT<br/>الضريبة</th><th style="%s">Total<br/>الإجمالي</th></tr>
                  %s
                </table>
                <table style="margin-top:10px; page-break-inside:avoid"><tr>
                  <td style="width:50%%"></td>
                  <td style="width:50%%">
                    <table>
                      <tr><td style="padding:4px 8px">Total excl. VAT · الإجمالي غير شامل الضريبة</td><td style="padding:4px 8px; text-align:right; font-weight:700">%s SAR</td></tr>
                      <tr><td style="padding:4px 8px">VAT 15%% · ضريبة القيمة المضافة</td><td style="padding:4px 8px; text-align:right; font-weight:700">%s SAR</td></tr>
                      <tr><td style="padding:6px 8px; background:%s; color:#fff">Total incl. VAT · الإجمالي شامل الضريبة</td><td style="padding:6px 8px; background:%s; color:#fff; text-align:right; font-weight:700">%s SAR</td></tr>
                    </table>
                  </td>
                </tr></table>
                %s
                %s
                <p class="muted" style="margin-top:10px; font-size:8pt">This is a quotation, not a tax invoice. A tax invoice is issued on confirmation of the order.<br/>هذا عرض سعر وليس فاتورة ضريبية، وتصدر الفاتورة الضريبية عند تأكيد الطلب.</p>
                %s
                """.formatted(
                client, meta,
                th, th, th, th, th, th, th,
                rows,
                LetterheadPdf.money(q.getTotalExclVat()), LetterheadPdf.money(q.getVatTotal()),
                org.getBrandColor() == null ? "#0B2A6F" : org.getBrandColor(),
                org.getBrandColor() == null ? "#0B2A6F" : org.getBrandColor(),
                LetterheadPdf.money(q.getTotalInclVat()),
                q.getNotes() == null ? "" : "<h2>Notes · ملاحظات</h2><p>" + LetterheadPdf.text(q.getNotes()) + "</p>",
                q.getTerms() == null ? "" : "<h2>Terms &amp; conditions · الشروط والأحكام</h2><p>" + LetterheadPdf.text(q.getTerms()) + "</p>",
                LetterheadPdf.signatures(org, clientName, false));
    }

    private static String td(String content, String align) {
        return "<td style=\"border-bottom:1px solid #e3e7ef; padding:6px 4px; text-align:" + align + "\">" + content + "</td>";
    }

    static String displayName(Customer c) {
        return c.getNameEn() != null && !c.getNameEn().isBlank() ? c.getNameEn() : c.getNameAr();
    }

    /** Street, district and city on one line and the numbers on the next, so Arabic text and digits never mix. */
    public static String address(Customer c) {
        String place = String.join("، ", java.util.stream.Stream.of(c.getStreet(), c.getDistrict(), c.getCity())
                .filter(s -> s != null && !s.isBlank()).toList());
        String numbers = String.join(" · ", java.util.stream.Stream.of(
                        c.getBuildingNumber() == null || c.getBuildingNumber().isBlank() ? null : "Bldg " + c.getBuildingNumber(),
                        c.getPostalCode() == null || c.getPostalCode().isBlank() ? null : "Postal " + c.getPostalCode())
                .filter(s -> s != null).toList());
        String structured = numbers.isBlank() ? place : place.isBlank() ? numbers : place + "\n" + numbers;
        return structured.isBlank() ? c.getAddress() : structured;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private Customer customer(Long id) {
        return customers.findByIdAndOrganizationId(id, CurrentUser.orgId()).orElseThrow(() -> ApiException.notFound("Customer"));
    }

    private Quotation load(Long id) {
        return repo.findByIdAndOrganizationId(id, CurrentUser.orgId()).filter(q -> CurrentUser.owns(q.getCreatedBy()))
                .orElseThrow(() -> ApiException.notFound("Quotation"));
    }
}
