package com.fatoora.api.invoice;

import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.invoice.InvoiceDtos.*;
import com.fatoora.api.notification.InvoiceWhatsApp;
import com.fatoora.api.notification.SendResult;
import com.fatoora.api.pdf.InvoicePdfService;
import com.fatoora.zatca.model.InvoiceKind;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceController {

    private static final String WRITE = "hasAnyRole('ADMIN','ACCOUNTANT','BRANCH')";

    private final InvoiceService service;
    private final InvoicePdfService pdf;
    private final InvoiceWhatsApp whatsApp;

    public InvoiceController(InvoiceService service, InvoicePdfService pdf, InvoiceWhatsApp whatsApp) {
        this.service = service;
        this.pdf = pdf;
        this.whatsApp = whatsApp;
    }

    @GetMapping
    Page<InvoiceSummary> list(@RequestParam(required = false) InvoiceStatus status,
                              @RequestParam(required = false) InvoiceKind kind,
                              @RequestParam(required = false) String q,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "25") int size) {
        return service.list(CurrentUser.orgId(), status, kind, q, page, size);
    }

    @GetMapping("/{id}")
    InvoiceDetail get(@PathVariable Long id) {
        return service.detail(CurrentUser.orgId(), id);
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    InvoiceDetail create(@Valid @RequestBody DraftRequest req) {
        var inv = service.createDraft(CurrentUser.orgId(), CurrentUser.get().email(), req);
        return service.detail(CurrentUser.orgId(), inv.getId());
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    InvoiceDetail update(@PathVariable Long id, @Valid @RequestBody DraftRequest req) {
        service.updateDraft(CurrentUser.orgId(), id, req);
        return service.detail(CurrentUser.orgId(), id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable Long id) {
        service.deleteDraft(CurrentUser.orgId(), id);
    }

    /** Sign and number the draft. sendMode NOW submits to ZATCA immediately; LATER (simplified only) queues it. */
    @PostMapping("/{id}/issue")
    @PreAuthorize(WRITE)
    InvoiceDetail issue(@PathVariable Long id, @RequestBody(required = false) IssueRequest req) {
        service.issue(CurrentUser.orgId(), id, req == null ? null : req.sendMode());
        return service.detail(CurrentUser.orgId(), id);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(WRITE)
    InvoiceDetail submit(@PathVariable Long id) {
        service.submitNow(CurrentUser.orgId(), id);
        return service.detail(CurrentUser.orgId(), id);
    }

    @PostMapping("/{id}/notes")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    InvoiceDetail note(@PathVariable Long id, @Valid @RequestBody NoteRequest req) {
        var note = service.createNote(CurrentUser.orgId(), CurrentUser.get().email(), id, req);
        return service.detail(CurrentUser.orgId(), note.getId());
    }

    /**
     * Cancel an issued invoice: a full credit note is created and issued to ZATCA; only when it is accepted is the
     * invoice marked cancelled. Goods go back to stock and the payment is refunded only when ticked.
     */
    @PostMapping("/{id}/cancel-invoice")
    @PreAuthorize(WRITE)
    InvoiceDetail cancelInvoice(@PathVariable Long id, @Valid @RequestBody InvoiceDtos.CancelRequest req) {
        Long orgId = CurrentUser.orgId();
        String user = CurrentUser.get().email();
        service.requireCancellable(orgId, id);
        var note = service.createNote(orgId, user, id, new NoteRequest(com.fatoora.zatca.model.DocumentType.CREDIT_NOTE,
                "Cancellation: " + req.reason()));
        service.setStockReturn(orgId, note.getId(), req.goodsReturned());
        service.issue(orgId, note.getId(), com.fatoora.api.invoice.SendMode.NOW);
        var issued = service.detail(orgId, note.getId());
        if (!issued.status().billable())
            throw com.fatoora.api.common.ApiException.conflict("cancel_not_accepted",
                    "ZATCA did not accept the cancelling credit note (" + issued.status() + "). The invoice is not cancelled; "
                            + "open the draft credit note " + (issued.number() == null ? "" : issued.number()) + " to see why.");
        service.completeCancel(orgId, user, id, note.getId(), req.reason(), req.paymentRefunded());
        return service.detail(orgId, id);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize(WRITE)
    InvoiceDetail close(@PathVariable Long id, @Valid @RequestBody(required = false) InvoiceDtos.CloseRequest req) {
        service.close(CurrentUser.orgId(), id, req == null ? null : req.reason());
        return service.detail(CurrentUser.orgId(), id);
    }

    @PostMapping("/{id}/reopen")
    @PreAuthorize(WRITE)
    InvoiceDetail reopen(@PathVariable Long id) {
        service.reopen(CurrentUser.orgId(), id);
        return service.detail(CurrentUser.orgId(), id);
    }

    @PostMapping("/{id}/duplicate")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    InvoiceDetail duplicate(@PathVariable Long id) {
        var copy = service.duplicate(CurrentUser.orgId(), CurrentUser.get().email(), id);
        return service.detail(CurrentUser.orgId(), copy.getId());
    }

    @GetMapping("/{id}/xml")
    ResponseEntity<byte[]> xml(@PathVariable Long id) {
        Invoice inv = downloadable(id);
        return file(inv.legalXml().getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_XML, inv.getNumber() + ".xml");
    }

    @GetMapping("/{id}/pdf")
    ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        Invoice inv = downloadable(id);
        return file(pdf.render(inv), MediaType.APPLICATION_PDF, inv.getNumber() + ".pdf");
    }

    public record WhatsAppRequest(String phone) {}

    /** Sends the invoice PDF to the customer on WhatsApp from the linked number. */
    @PostMapping("/{id}/whatsapp")
    @PreAuthorize(WRITE)
    SendResult whatsapp(@PathVariable Long id, @RequestBody(required = false) WhatsAppRequest req) {
        return whatsApp.send(CurrentUser.orgId(), id, req == null ? null : req.phone(), CurrentUser.get().email());
    }

    private Invoice downloadable(Long id) {
        Invoice inv = service.load(CurrentUser.orgId(), id);
        if (!InvoiceService.downloadable(inv))
            throw ApiException.conflict("not_cleared", inv.getKind() == InvoiceKind.STANDARD
                    ? "A standard invoice can only be shared after ZATCA clears it"
                    : "Issue the invoice first");
        return inv;
    }

    private static ResponseEntity<byte[]> file(byte[] body, MediaType type, String name) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(body);
    }
}
