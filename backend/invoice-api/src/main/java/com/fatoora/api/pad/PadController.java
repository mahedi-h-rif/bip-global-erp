package com.fatoora.api.pad;

import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.organization.Organization;
import com.fatoora.api.organization.OrganizationRepository;
import com.fatoora.api.pdf.LetterheadPdf;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The company letterhead pad: a blank pad to download (PDF to print, Word to type on and send to clients),
 * and documents written on it in the browser, saved and downloaded as PDF or Word.
 */
@RestController
@RequestMapping("/api/v1/pads")
public class PadController {

    private static final String WRITE = "hasAnyRole('ADMIN','ACCOUNTANT')";
    private static final MediaType DOCX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    /** Pasted images are kept inside the text, so allow a few of them. */
    private static final int MAX_BODY = 8 * 1024 * 1024;

    public record PadRequest(String title, String body) {}

    public record PadSummary(Long id, String title, String createdBy, Instant createdAt, Instant updatedAt) {}

    public record PadDto(Long id, String title, String body, String createdBy, Instant createdAt, Instant updatedAt) {

        static PadDto of(PadDocument p) {
            return new PadDto(p.getId(), p.getTitle(), p.getBody(), p.getCreatedBy(), p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    private final PadRepository pads;
    private final OrganizationRepository orgs;
    private final LetterheadPdf letterhead;
    private final AuditService audit;

    public PadController(PadRepository pads, OrganizationRepository orgs, LetterheadPdf letterhead, AuditService audit) {
        this.pads = pads;
        this.orgs = orgs;
        this.letterhead = letterhead;
        this.audit = audit;
    }

    // --- the blank pad ---------------------------------------------------------------------------------

    @GetMapping("/blank/pdf")
    ResponseEntity<byte[]> blankPdf() {
        Organization org = org();
        return file(letterhead.render(org, "Letterhead", "", true), "letterhead-pad.pdf", MediaType.APPLICATION_PDF, true);
    }

    @GetMapping("/blank/docx")
    ResponseEntity<byte[]> blankWord() {
        return file(PadWord.build(org(), null, null), "letterhead-pad.docx", DOCX, false);
    }

    // --- documents on the pad ----------------------------------------------------------------------------

    @GetMapping
    List<PadSummary> list() {
        return pads.findByOrganizationIdOrderByUpdatedAtDesc(CurrentUser.orgId()).stream()
                .map(p -> new PadSummary(p.getId(), p.getTitle(), p.getCreatedBy(), p.getCreatedAt(), p.getUpdatedAt()))
                .toList();
    }

    @GetMapping("/{id}")
    PadDto get(@PathVariable Long id) {
        return PadDto.of(load(id));
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    PadDto create(@RequestBody PadRequest req) {
        PadDocument p = new PadDocument();
        p.setOrganizationId(CurrentUser.orgId());
        p.setCreatedBy(CurrentUser.get().email());
        apply(p, req);
        pads.save(p);
        audit.record(p.getOrganizationId(), "PAD_CREATED", "pad_document", p.getId(), p.getTitle());
        return PadDto.of(p);
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    PadDto update(@PathVariable Long id, @RequestBody PadRequest req) {
        PadDocument p = load(id);
        apply(p, req);
        p.setUpdatedAt(Instant.now());
        pads.save(p);
        audit.record(p.getOrganizationId(), "PAD_UPDATED", "pad_document", p.getId(), p.getTitle());
        return PadDto.of(p);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    void delete(@PathVariable Long id) {
        PadDocument p = load(id);
        pads.delete(p);
        audit.record(p.getOrganizationId(), "PAD_DELETED", "pad_document", p.getId(), p.getTitle());
    }

    @GetMapping("/{id}/pdf")
    ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        PadDocument p = load(id);
        return file(renderPdf(p.getTitle(), p.getBody()), fileName(p.getTitle()) + ".pdf", MediaType.APPLICATION_PDF, true);
    }

    @GetMapping("/{id}/docx")
    ResponseEntity<byte[]> word(@PathVariable Long id) {
        PadDocument p = load(id);
        return file(PadWord.build(org(), p.getTitle(), p.getBody()), fileName(p.getTitle()) + ".docx", DOCX, false);
    }

    private byte[] renderPdf(String title, String body) {
        String heading = title == null || title.isBlank() ? ""
                : "<h1 style=\"text-align:center\">" + LetterheadPdf.esc(title.strip()) + "</h1>";
        return letterhead.render(org(), title == null || title.isBlank() ? "Letter" : title.strip(),
                heading + "<div style=\"font-size:11pt; line-height:1.5\">" + PadHtml.xhtml(body) + "</div>", true);
    }

    private static void apply(PadDocument p, PadRequest req) {
        String title = req.title() == null ? "" : req.title().strip();
        if (title.isEmpty()) throw new ApiException.Validation(Map.of("title", "Give the document a title"));
        if (title.length() > 255) throw new ApiException.Validation(Map.of("title", "Keep the title under 255 characters"));
        if (req.body() != null && req.body().length() > MAX_BODY) {
            throw new ApiException.Validation(Map.of("body", "The document is too large; use smaller images"));
        }
        p.setTitle(title);
        p.setBody(PadHtml.clean(req.body()));
    }

    /** A safe file name from the title. */
    static String fileName(String title) {
        String s = title == null ? "" : title.strip().replaceAll("[\\\\/:*?\"<>|\\r\\n]+", " ").replaceAll("\\s+", "-");
        return s.isEmpty() ? "letter" : s.length() > 80 ? s.substring(0, 80) : s;
    }

    private static ResponseEntity<byte[]> file(byte[] data, String name, MediaType type, boolean inline) {
        ContentDisposition cd = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(name, java.nio.charset.StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(type).header(HttpHeaders.CONTENT_DISPOSITION, cd.toString()).body(data);
    }

    private Organization org() {
        return orgs.findById(CurrentUser.orgId()).orElseThrow();
    }

    private PadDocument load(Long id) {
        return pads.findByIdAndOrganizationId(id, CurrentUser.orgId()).orElseThrow(() -> ApiException.notFound("Document"));
    }
}
