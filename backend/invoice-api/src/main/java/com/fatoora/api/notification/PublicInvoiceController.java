package com.fatoora.api.notification;

import com.fatoora.api.common.ApiException;
import com.fatoora.api.invoice.Invoice;
import com.fatoora.api.invoice.InvoiceRepository;
import com.fatoora.api.invoice.InvoiceService;
import com.fatoora.api.pdf.InvoicePdfService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The invoice PDF behind a reminder link. No login; the HMAC token in the URL is the only key. */
@RestController
@RequestMapping("/api/v1/public/invoices")
public class PublicInvoiceController {

    private final InvoiceRepository invoices;
    private final InvoicePdfService pdf;
    private final InvoiceLinks links;

    public PublicInvoiceController(InvoiceRepository invoices, InvoicePdfService pdf, InvoiceLinks links) {
        this.invoices = invoices;
        this.pdf = pdf;
        this.links = links;
    }

    @GetMapping("/{id}/{token}")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> pdf(@PathVariable Long id, @PathVariable String token) {
        // Same answer for a wrong token and a missing invoice, so ids can't be probed.
        if (!links.valid(id, token)) throw ApiException.notFound("Invoice");
        Invoice inv = invoices.findById(id).orElseThrow(() -> ApiException.notFound("Invoice"));
        if (!InvoiceService.downloadable(inv)) throw ApiException.notFound("Invoice");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(inv.getNumber() + ".pdf").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Robots-Tag", "noindex")
                .body(pdf.render(inv));
    }
}
