package com.fatoora.api.payment;

import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Optional proof of a payment (a photo or PDF of the bank slip, transfer screenshot or cash receipt), one per
 * invoice payment or per incoming-payment receipt. Kept apart from the payment rows so lists stay light.
 */
@RestController
@RequestMapping("/api/v1")
public class PaymentProofController {

    private static final String WRITE = "hasAnyRole('ADMIN','ACCOUNTANT','BRANCH')";
    private static final int MAX_BYTES = 10 * 1024 * 1024;

    public record ProofInfo(Long paymentId, Long receiptId, String fileName, String contentType) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public PaymentProofController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // --- invoice payments ----------------------------------------------------------------------------

    /** Which payments of an invoice have a proof attached. */
    @GetMapping("/invoices/{id}/payment-proofs")
    List<ProofInfo> forInvoice(@PathVariable Long id) {
        return jdbc.query("""
                SELECT f.invoice_payment_id, f.file_name, f.content_type FROM payment_proof f
                JOIN invoice_payment p ON p.id = f.invoice_payment_id JOIN invoice i ON i.id = p.invoice_id
                WHERE i.id = ? AND i.organization_id = ? AND (?::text IS NULL OR lower(i.created_by) = lower(?::text))""",
                (rs, n) -> new ProofInfo(rs.getLong(1), null, rs.getString(2), rs.getString(3)),
                id, CurrentUser.orgId(), CurrentUser.owner(), CurrentUser.owner());
    }

    @PutMapping("/payments/{id}/proof")
    @PreAuthorize(WRITE)
    @Transactional
    ProofInfo attachToPayment(@PathVariable Long id, @RequestParam String name, @RequestBody byte[] data,
                              @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType) {
        requirePayment(id);
        String type = check(data, contentType);
        String file = fileName(name);
        jdbc.update("DELETE FROM payment_proof WHERE invoice_payment_id = ?", id);
        jdbc.update("INSERT INTO payment_proof (organization_id, invoice_payment_id, file_name, content_type, data, created_by) VALUES (?, ?, ?, ?, ?, ?)",
                CurrentUser.orgId(), id, file, type, data, CurrentUser.get().email());
        audit.record(CurrentUser.orgId(), "PAYMENT_PROOF_ADDED", "payment", id, file);
        return new ProofInfo(id, null, file, type);
    }

    @GetMapping("/payments/{id}/proof")
    ResponseEntity<byte[]> paymentProof(@PathVariable Long id) {
        requirePayment(id);
        return file(jdbc.queryForList("SELECT file_name, content_type, data FROM payment_proof WHERE invoice_payment_id = ?", id));
    }

    @DeleteMapping("/payments/{id}/proof")
    @PreAuthorize(WRITE)
    @Transactional
    void removePaymentProof(@PathVariable Long id) {
        requirePayment(id);
        jdbc.update("DELETE FROM payment_proof WHERE invoice_payment_id = ?", id);
        audit.record(CurrentUser.orgId(), "PAYMENT_PROOF_REMOVED", "payment", id, null);
    }

    // --- incoming-payment receipts --------------------------------------------------------------------

    @GetMapping("/receipts/{id}/proofs")
    List<ProofInfo> forReceipt(@PathVariable Long id) {
        requireReceipt(id);
        return jdbc.query("SELECT file_name, content_type FROM payment_proof WHERE receipt_id = ?",
                (rs, n) -> new ProofInfo(null, id, rs.getString(1), rs.getString(2)), id);
    }

    @PutMapping("/receipts/{id}/proof")
    @PreAuthorize(WRITE)
    @Transactional
    ProofInfo attachToReceipt(@PathVariable Long id, @RequestParam String name, @RequestBody byte[] data,
                              @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType) {
        requireReceipt(id);
        String type = check(data, contentType);
        String file = fileName(name);
        jdbc.update("DELETE FROM payment_proof WHERE receipt_id = ?", id);
        jdbc.update("INSERT INTO payment_proof (organization_id, receipt_id, file_name, content_type, data, created_by) VALUES (?, ?, ?, ?, ?, ?)",
                CurrentUser.orgId(), id, file, type, data, CurrentUser.get().email());
        audit.record(CurrentUser.orgId(), "PAYMENT_PROOF_ADDED", "receipt", id, file);
        return new ProofInfo(null, id, file, type);
    }

    @GetMapping("/receipts/{id}/proof")
    ResponseEntity<byte[]> receiptProof(@PathVariable Long id) {
        requireReceipt(id);
        return file(jdbc.queryForList("SELECT file_name, content_type, data FROM payment_proof WHERE receipt_id = ?", id));
    }

    @DeleteMapping("/receipts/{id}/proof")
    @PreAuthorize(WRITE)
    @Transactional
    void removeReceiptProof(@PathVariable Long id) {
        requireReceipt(id);
        jdbc.update("DELETE FROM payment_proof WHERE receipt_id = ?", id);
        audit.record(CurrentUser.orgId(), "PAYMENT_PROOF_REMOVED", "receipt", id, null);
    }

    // --- helpers ------------------------------------------------------------------------------------

    private void requirePayment(Long paymentId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM invoice_payment p JOIN invoice i ON i.id = p.invoice_id
                WHERE p.id = ? AND i.organization_id = ? AND (?::text IS NULL OR lower(i.created_by) = lower(?::text))""",
                Integer.class, paymentId, CurrentUser.orgId(), CurrentUser.owner(), CurrentUser.owner());
        if (n == null || n == 0) throw ApiException.notFound("Payment");
    }

    private void requireReceipt(Long receiptId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM receipt WHERE id = ? AND organization_id = ?
                  AND (?::text IS NULL OR lower(created_by) = lower(?::text))""",
                Integer.class, receiptId, CurrentUser.orgId(), CurrentUser.owner(), CurrentUser.owner());
        if (n == null || n == 0) throw ApiException.notFound("Receipt");
    }

    /** Photos and PDFs only, at most 10 MB. */
    private static String check(byte[] data, String contentType) {
        if (data == null || data.length == 0 || data.length > MAX_BYTES)
            throw ApiException.badRequest("file_size", "Attach a file of at most 10 MB");
        String type = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase();
        if (!type.startsWith("image/") && !type.equals("application/pdf"))
            throw ApiException.badRequest("file_type", "Attach a photo or a PDF of the receipt");
        return type;
    }

    private static String fileName(String name) {
        String n = name == null ? "" : name.strip();
        return n.isEmpty() ? "receipt" : n.length() > 255 ? n.substring(0, 255) : n;
    }

    private static ResponseEntity<byte[]> file(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) throw ApiException.notFound("Receipt file");
        Map<String, Object> f = rows.getFirst();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType((String) f.get("content_type")))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename((String) f.get("file_name"), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body((byte[]) f.get("data"));
    }
}
