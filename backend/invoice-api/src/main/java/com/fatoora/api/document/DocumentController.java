package com.fatoora.api.document;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.notification.SendResult;
import com.fatoora.api.ocr.OcrInput;
import com.fatoora.api.worker.Worker;
import com.fatoora.api.worker.WorkerRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.PageRequest;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Company and worker documents: scan to fill in, expiry watch and reminders, and the notification bell. */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private static final String WRITE = "hasAnyRole('ADMIN','ACCOUNTANT')";
    private static final int MAX_FILE = 10 * 1024 * 1024;
    private static final Set<String> FILE_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/pjpeg", "image/png", "image/bmp", "image/tiff", "application/pdf");

    public record DocumentRequest(@NotNull CompanyDocument.Type type, Long workerId, String title, String number,
                                  String holderName, String nationality, LocalDate issueDate, LocalDate expiryDate,
                                  String notes, Boolean remindersEnabled, Boolean remindAfterExpiry) {}

    public record ReminderRequest(boolean remindersEnabled, boolean remindAfterExpiry) {}

    public record DocumentDto(Long id, CompanyDocument.Type type, Long workerId, String workerName, String workerPhone,
                              String title, String number, String holderName, String nationality, LocalDate issueDate,
                              LocalDate expiryDate, Long daysLeft, ExpiryRule.Stage stage, boolean remindersEnabled,
                              boolean remindAfterExpiry, LocalDate lastReminderOn, LocalDate nextReminderOn, String notes,
                              String fileName, String fileType, Integer fileSize, String createdBy, Instant createdAt,
                              Instant updatedAt, String message, String whatsappUrl) {}

    public record AlertDto(Long id, Long documentId, String title, String workerName, String workerPhone, int daysLeft,
                           LocalDate expiryDate, String message, String emailStatus, Instant readAt, Instant createdAt,
                           String whatsappUrl) {}

    public record Alerts(long unread, List<AlertDto> items) {}

    private final CompanyDocumentRepository documents;
    private final DocumentFileRepository files;
    private final DocumentAlertRepository alerts;
    private final WorkerRepository workers;
    private final DocumentService service;
    private final AuditService audit;

    public DocumentController(CompanyDocumentRepository documents, DocumentFileRepository files,
                              DocumentAlertRepository alerts, WorkerRepository workers, DocumentService service,
                              AuditService audit) {
        this.documents = documents;
        this.files = files;
        this.alerts = alerts;
        this.workers = workers;
        this.service = service;
        this.audit = audit;
    }

    @GetMapping
    @Transactional(readOnly = true)
    List<DocumentDto> list() {
        Long orgId = CurrentUser.orgId();
        Map<Long, Worker> byId = workers();
        return documents.findByOrganizationId(orgId).stream()
                .map(d -> dto(d, byId))
                .sorted(Comparator.comparing(DocumentDto::expiryDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    DocumentDto get(@PathVariable Long id) {
        return dto(load(id), workers());
    }

    /** Reads a scan or photo and suggests the fields; nothing is stored. */
    @PutMapping("/scan")
    @PreAuthorize(WRITE)
    DocumentService.Extraction scan(@RequestBody byte[] file, @RequestParam CompanyDocument.Type type,
                                    @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType) {
        check(file, contentType);
        return service.extract(type, new OcrInput(file, base(contentType), type.name()));
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    DocumentDto create(@Valid @RequestBody DocumentRequest req) {
        Long orgId = CurrentUser.orgId();
        CompanyDocument d = new CompanyDocument();
        d.setOrganizationId(orgId);
        d.setCreatedBy(CurrentUser.get().email());
        apply(d, req);
        documents.save(d);
        audit.record(orgId, "DOCUMENT_CREATED", "document", d.getId(), service.describe(d));
        return dto(d, workers());
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    DocumentDto update(@PathVariable Long id, @Valid @RequestBody DocumentRequest req) {
        CompanyDocument d = load(id);
        apply(d, req);
        audit.record(d.getOrganizationId(), "DOCUMENT_UPDATED", "document", d.getId(), service.describe(d));
        return dto(d, workers());
    }

    /** Turns this document's expiry reminders on or off. */
    @PutMapping("/{id}/reminders")
    @PreAuthorize(WRITE)
    @Transactional
    DocumentDto reminders(@PathVariable Long id, @RequestBody ReminderRequest req) {
        CompanyDocument d = load(id);
        d.setRemindersEnabled(req.remindersEnabled());
        d.setRemindAfterExpiry(req.remindAfterExpiry());
        d.setUpdatedAt(Instant.now());
        audit.record(d.getOrganizationId(), req.remindersEnabled() ? "DOCUMENT_REMINDERS_ON" : "DOCUMENT_REMINDERS_OFF",
                "document", d.getId(), service.describe(d));
        return dto(d, workers());
    }

    /** Sends the expiry message to the worker now, from the linked WhatsApp number. */
    @PostMapping("/{id}/whatsapp")
    @PreAuthorize(WRITE)
    @Transactional(readOnly = true)
    SendResult whatsapp(@PathVariable Long id) {
        CompanyDocument d = load(id);
        var r = service.sendWhatsApp(d);
        if (r.delivered()) audit.record(d.getOrganizationId(), "DOCUMENT_WHATSAPP_SENT", "document", d.getId(), service.describe(d));
        return r;
    }

    @PutMapping("/{id}/file")
    @PreAuthorize(WRITE)
    @Transactional
    DocumentDto upload(@PathVariable Long id, @RequestBody byte[] file,
                       @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
                       @RequestParam(required = false) String name) {
        check(file, contentType);
        CompanyDocument d = load(id);
        DocumentFile f = files.findById(d.getId()).orElseGet(() -> {
            DocumentFile n = new DocumentFile();
            n.setDocumentId(d.getId());
            return n;
        });
        f.setData(file);
        files.save(f);
        d.setFileType(base(contentType));
        d.setFileSize(file.length);
        d.setFileName(name == null || name.isBlank() ? "document" : name.strip());
        d.setUpdatedAt(Instant.now());
        return dto(d, workers());
    }

    @GetMapping("/{id}/file")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> file(@PathVariable Long id) {
        CompanyDocument d = load(id);
        byte[] data = files.findById(d.getId()).map(DocumentFile::getData).orElseThrow(() -> ApiException.notFound("File"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(d.getFileType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(d.getFileName()).build().toString())
                .body(data);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@PathVariable Long id) {
        CompanyDocument d = load(id);
        String what = service.describe(d);
        documents.delete(d);
        audit.record(d.getOrganizationId(), "DOCUMENT_DELETED", "document", d.getId(), what);
    }

    // --- notification bell ----------------------------------------------------------------------------

    @GetMapping("/alerts")
    @Transactional(readOnly = true)
    Alerts alerts() {
        Long orgId = CurrentUser.orgId();
        Map<Long, CompanyDocument> docs = documents.findByOrganizationId(orgId).stream()
                .collect(Collectors.toMap(CompanyDocument::getId, Function.identity()));
        Map<Long, Worker> byId = workers();
        List<AlertDto> items = alerts.findByOrganizationIdOrderByCreatedAtDesc(orgId, PageRequest.of(0, 40)).stream()
                .map(a -> {
                    CompanyDocument d = docs.get(a.getDocumentId());
                    Worker w = d == null || d.getWorkerId() == null ? null : byId.get(d.getWorkerId());
                    return new AlertDto(a.getId(), a.getDocumentId(), d == null ? null : d.getTitle(),
                            w == null ? null : w.getName(), w == null ? null : w.getPhone(), a.getDaysLeft(),
                            a.getExpiryDate(), a.getMessage(), a.getEmailStatus(), a.getReadAt(), a.getCreatedAt(),
                            DocumentService.whatsapp(w == null ? null : w.getPhone(), a.getMessage()));
                }).toList();
        return new Alerts(alerts.countByOrganizationIdAndReadAtIsNull(orgId), items);
    }

    @PostMapping("/alerts/{id}/read")
    @Transactional
    void read(@PathVariable Long id) {
        DocumentAlert a = alerts.findByIdAndOrganizationId(id, CurrentUser.orgId()).orElseThrow(() -> ApiException.notFound("Notification"));
        if (a.getReadAt() == null) {
            a.setReadAt(Instant.now());
            a.setReadBy(CurrentUser.get().email());
        }
    }

    @PostMapping("/alerts/read-all")
    @Transactional
    void readAll() {
        alerts.markAllRead(CurrentUser.orgId(), Instant.now(), CurrentUser.get().email());
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void apply(CompanyDocument d, DocumentRequest req) {
        if (req.workerId() != null) workers.findByIdAndOrganizationId(req.workerId(), CurrentUser.orgId())
                .orElseThrow(() -> ApiException.notFound("Worker"));
        if (req.issueDate() != null && req.expiryDate() != null && req.expiryDate().isBefore(req.issueDate()))
            throw new ApiException.Validation(Map.of("expiryDate", "The expiry date is before the issue date"));
        // A new expiry date is a renewal: the reminder cycle starts over.
        if (!Objects.equals(d.getExpiryDate(), req.expiryDate())) d.setLastReminderOn(null);
        d.setType(req.type());
        d.setWorkerId(req.workerId());
        d.setTitle(blank(req.title()) != null ? req.title().strip() : DocumentService.typeTitle(req.type()));
        d.setNumber(blank(req.number()));
        d.setHolderName(blank(req.holderName()));
        d.setNationality(blank(req.nationality()));
        d.setIssueDate(req.issueDate());
        d.setExpiryDate(req.expiryDate());
        d.setNotes(blank(req.notes()));
        if (req.remindersEnabled() != null) d.setRemindersEnabled(req.remindersEnabled());
        if (req.remindAfterExpiry() != null) d.setRemindAfterExpiry(req.remindAfterExpiry());
        d.setUpdatedAt(Instant.now());
    }

    private DocumentDto dto(CompanyDocument d, Map<Long, Worker> byId) {
        LocalDate today = LocalDate.now(InvoiceApiApplication.KSA);
        Worker w = d.getWorkerId() == null ? null : byId.get(d.getWorkerId());
        LocalDate expiry = d.getExpiryDate();
        String message = service.message(d, today);
        return new DocumentDto(d.getId(), d.getType(), d.getWorkerId(), w == null ? null : w.getName(),
                w == null ? null : w.getPhone(), d.getTitle(), d.getNumber(), d.getHolderName(), d.getNationality(),
                d.getIssueDate(), expiry, expiry == null ? null : ExpiryRule.daysLeft(expiry, today),
                expiry == null ? null : ExpiryRule.stage(expiry, today), d.isRemindersEnabled(), d.isRemindAfterExpiry(),
                d.getLastReminderOn(),
                expiry == null || !d.isRemindersEnabled() ? null
                        : ExpiryRule.next(expiry, today, d.getLastReminderOn(), d.isRemindAfterExpiry()),
                d.getNotes(), d.getFileName(), d.getFileType(), d.getFileSize(), d.getCreatedBy(), d.getCreatedAt(),
                d.getUpdatedAt(), message, DocumentService.whatsapp(w == null ? null : w.getPhone(), message));
    }

    private Map<Long, Worker> workers() {
        return workers.findByOrganizationIdOrderByPositionAscIdAsc(CurrentUser.orgId()).stream()
                .collect(Collectors.toMap(Worker::getId, Function.identity()));
    }

    private CompanyDocument load(Long id) {
        return documents.findByIdAndOrganizationId(id, CurrentUser.orgId()).orElseThrow(() -> ApiException.notFound("Document"));
    }

    private static void check(byte[] file, String contentType) {
        if (file == null || file.length == 0) throw ApiException.badRequest("BAD_FILE", "The file is empty");
        if (file.length > MAX_FILE) throw ApiException.badRequest("BAD_FILE", "The file is larger than 10 MB");
        if (!FILE_TYPES.contains(base(contentType)))
            throw ApiException.badRequest("BAD_FILE", "Upload a photo (JPG or PNG) or a PDF");
    }

    private static String base(String contentType) {
        return contentType == null ? "" : contentType.split(";")[0].strip().toLowerCase(java.util.Locale.ROOT);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
