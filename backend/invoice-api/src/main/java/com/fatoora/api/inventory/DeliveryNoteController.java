package com.fatoora.api.inventory;

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
import com.fatoora.api.quotation.QuotationController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Delivery notes: created from an issued invoice (whole or partial delivery) or on their own.
 * An invoice already took its stock when it was issued, so only standalone notes move stock, when delivered.
 */
@RestController
@RequestMapping("/api/v1/delivery-notes")
public class DeliveryNoteController {

    private static final String WRITE = "hasAnyRole('ADMIN','ACCOUNTANT','BRANCH')";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public record Line(Long invoiceLineId, Long productId, @NotBlank @Size(max = 1000) String name,
                       @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity, String unitCode,
                       /** Read-only on the prefill: invoice quantity still to deliver. */
                       BigDecimal remaining) {}

    public record Request(Long invoiceId, Long customerId, @Size(max = 500) String clientName,
                          @Size(max = 1000) String deliveryAddress, Long warehouseId, LocalDate deliveryDate,
                          @Size(max = 200) String driverName, @Size(max = 40) String driverPhone,
                          @Size(max = 60) String vehicleNo, @Size(max = 2000) String note,
                          @NotEmpty List<@Valid Line> lines) {}

    public record DeliverRequest(@NotBlank @Size(max = 200) String receivedBy) {}

    public record Dto(Long id, String number, Long invoiceId, String invoiceNumber, Long customerId, String customerName,
                      String clientName, String deliveryAddress, Long warehouseId, String warehouseName, LocalDate deliveryDate,
                      String status, String driverName, String driverPhone, String vehicleNo, String receivedBy,
                      Instant receivedAt, String note, String createdBy, Instant createdAt, List<Line> lines) {}

    private final JdbcTemplate jdbc;
    private final StockService stock;
    private final CustomerRepository customers;
    private final OrganizationRepository orgs;
    private final LetterheadPdf letterhead;
    private final AuditService audit;

    public DeliveryNoteController(JdbcTemplate jdbc, StockService stock, CustomerRepository customers,
                                  OrganizationRepository orgs, LetterheadPdf letterhead, AuditService audit) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.customers = customers;
        this.orgs = orgs;
        this.letterhead = letterhead;
        this.audit = audit;
    }

    @GetMapping
    List<Dto> list(@RequestParam(required = false) String status, @RequestParam(required = false) Long invoiceId) {
        Long orgId = CurrentUser.orgId();
        String owner = CurrentUser.owner();
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM delivery_note WHERE organization_id = ? AND (?::text IS NULL OR status = ?::text)
                  AND (?::bigint IS NULL OR invoice_id = ?::bigint) AND (?::text IS NULL OR lower(created_by) = lower(?::text))
                ORDER BY id DESC LIMIT 500""", Long.class, orgId, status, status, invoiceId, invoiceId, owner, owner);
        List<Dto> out = new ArrayList<>();
        for (Long id : ids) out.add(dto(orgId, id));
        return out;
    }

    @GetMapping("/{id}")
    Dto get(@PathVariable Long id) {
        return load(id);
    }

    /** A new note's lines for an issued invoice: each line with the quantity not yet on a delivery note. */
    @GetMapping("/from-invoice/{invoiceId}")
    Request prefill(@PathVariable Long invoiceId) {
        Long orgId = CurrentUser.orgId();
        Map<String, Object> inv = invoice(orgId, invoiceId);
        Map<Long, BigDecimal> delivered = deliveredByLine(invoiceId, null);
        List<Line> lines = jdbc.query("SELECT id, product_id, name, quantity, unit_code FROM invoice_line WHERE invoice_id = ? ORDER BY position",
                        (rs, i) -> {
                            BigDecimal remaining = rs.getBigDecimal("quantity").subtract(delivered.getOrDefault(rs.getLong("id"), BigDecimal.ZERO));
                            return new Line(rs.getLong("id"), (Long) rs.getObject("product_id"), rs.getString("name"),
                                    remaining, rs.getString("unit_code"), remaining);
                        }, invoiceId)
                .stream().filter(l -> l.remaining().signum() > 0).toList();
        Long customerId = (Long) inv.get("customer_id");
        Customer c = customerId == null ? null : customers.findById(customerId).orElse(null);
        return new Request(invoiceId, customerId, null, c == null ? null : QuotationController.address(c),
                (Long) inv.get("warehouse_id"), LocalDate.now(InvoiceApiApplication.KSA), null, null, null, null, lines);
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Dto create(@Valid @RequestBody Request req) {
        Long orgId = CurrentUser.orgId();
        validate(orgId, req, null);
        String number = stock.nextNumber(orgId, "DELIVERY_NOTE", "DN");
        Long id = jdbc.queryForObject("""
                INSERT INTO delivery_note (organization_id, number, created_by, delivery_date) VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, orgId, number, CurrentUser.get().email(), LocalDate.now(InvoiceApiApplication.KSA));
        write(orgId, id, req);
        audit.record(orgId, "DELIVERY_NOTE_CREATED", "delivery_note", id, number);
        return dto(orgId, id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    Dto update(@PathVariable Long id, @Valid @RequestBody Request req) {
        Dto d = load(id);
        requireDraft(d);
        Long orgId = CurrentUser.orgId();
        validate(orgId, req, id);
        write(orgId, id, req);
        audit.record(orgId, "DELIVERY_NOTE_UPDATED", "delivery_note", id, d.number());
        return dto(orgId, id);
    }

    /** Marks the goods as handed over and signed for. Standalone notes take their stock-tracked lines out now. */
    @PostMapping("/{id}/deliver")
    @PreAuthorize(WRITE)
    @Transactional
    Dto deliver(@PathVariable Long id, @Valid @RequestBody DeliverRequest req) {
        Dto d = load(id);
        requireDraft(d);
        Long orgId = CurrentUser.orgId();
        if (d.invoiceId() == null) {
            Long wh = d.warehouseId() != null ? d.warehouseId() : stock.defaultWarehouse(orgId);
            jdbc.update("UPDATE delivery_note SET warehouse_id = ? WHERE id = ?", wh, id);
            Map<Long, BigDecimal> qty = new LinkedHashMap<>();
            for (Line l : d.lines())
                if (l.productId() != null && stock.tracked(l.productId())) qty.merge(l.productId(), l.quantity(), BigDecimal::add);
            stock.apply(orgId, qty.entrySet().stream().map(e -> new StockService.Move(e.getKey(), wh, e.getValue().negate(),
                    StockService.Kind.DELIVERY, "delivery_note", id, d.number(), null)).toList(), false, CurrentUser.get().email());
        }
        jdbc.update("UPDATE delivery_note SET status = 'DELIVERED', received_by = ?, received_at = now(), updated_at = now() WHERE id = ?",
                req.receivedBy().trim(), id);
        audit.record(orgId, "DELIVERY_NOTE_DELIVERED", "delivery_note", id, d.number() + " received by " + req.receivedBy());
        return dto(orgId, id);
    }

    /** Cancels a note. A delivered standalone note puts its stock back. */
    @PostMapping("/{id}/cancel")
    @PreAuthorize(WRITE)
    @Transactional
    Dto cancel(@PathVariable Long id) {
        Dto d = load(id);
        if ("CANCELLED".equals(d.status())) return d;
        Long orgId = CurrentUser.orgId();
        if ("DELIVERED".equals(d.status()) && d.invoiceId() == null) {
            List<StockService.Move> back = jdbc.query("SELECT product_id, warehouse_id, quantity FROM stock_movement WHERE ref_type = 'delivery_note' AND ref_id = ?",
                    (rs, i) -> new StockService.Move(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3).negate(),
                            StockService.Kind.RETURN, "delivery_note", id, d.number(), "Delivery note cancelled"), id);
            stock.apply(orgId, back, true, CurrentUser.get().email());
        }
        jdbc.update("UPDATE delivery_note SET status = 'CANCELLED', updated_at = now() WHERE id = ?", id);
        audit.record(orgId, "DELIVERY_NOTE_CANCELLED", "delivery_note", id, d.number());
        return dto(orgId, id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    void delete(@PathVariable Long id) {
        Dto d = load(id);
        requireDraft(d);
        jdbc.update("DELETE FROM delivery_note WHERE id = ?", id);
        audit.record(CurrentUser.orgId(), "DELIVERY_NOTE_DELETED", "delivery_note", id, d.number());
    }

    @GetMapping("/{id}/pdf")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        Dto d = load(id);
        Organization org = orgs.findById(CurrentUser.orgId()).orElseThrow();
        byte[] pdf = letterhead.render(org, "Delivery note " + d.number(), html(d, org));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(d.number() + ".pdf").build().toString())
                .body(pdf);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void validate(Long orgId, Request req, Long exceptNoteId) {
        if (req.warehouseId() != null) stock.requireWarehouse(orgId, req.warehouseId());
        if (req.customerId() != null && customers.findByIdAndOrganizationId(req.customerId(), orgId).isEmpty())
            throw ApiException.notFound("Customer");
        if (req.invoiceId() == null) {
            if (req.customerId() == null && (req.clientName() == null || req.clientName().isBlank()))
                throw new ApiException.Validation(Map.of("clientName", "Choose a customer or enter the client's name"));
            for (Line l : req.lines()) if (l.invoiceLineId() != null)
                throw ApiException.badRequest("invoice_line_without_invoice", "Invoice lines need the invoice");
            return;
        }
        invoice(orgId, req.invoiceId());
        Map<Long, BigDecimal> invoiced = new HashMap<>();
        jdbc.query("SELECT id, quantity FROM invoice_line WHERE invoice_id = ?",
                rs -> { invoiced.put(rs.getLong(1), rs.getBigDecimal(2)); }, req.invoiceId());
        Map<Long, BigDecimal> delivered = deliveredByLine(req.invoiceId(), exceptNoteId);
        Map<Long, BigDecimal> asked = new HashMap<>();
        for (Line l : req.lines()) {
            if (l.invoiceLineId() == null || !invoiced.containsKey(l.invoiceLineId()))
                throw ApiException.badRequest("unknown_invoice_line", "Every line must come from the invoice");
            asked.merge(l.invoiceLineId(), l.quantity(), BigDecimal::add);
        }
        for (var e : asked.entrySet()) {
            BigDecimal remaining = invoiced.get(e.getKey()).subtract(delivered.getOrDefault(e.getKey(), BigDecimal.ZERO));
            if (e.getValue().compareTo(remaining) > 0)
                throw ApiException.badRequest("over_delivery", "A line exceeds the quantity still to deliver ("
                        + StockService.plain(remaining) + ")");
        }
    }

    private void write(Long orgId, Long id, Request req) {
        Long customerId = req.customerId();
        Long warehouseId = req.warehouseId();
        if (req.invoiceId() != null) {
            Map<String, Object> inv = invoice(orgId, req.invoiceId());
            if (customerId == null) customerId = (Long) inv.get("customer_id");
            if (warehouseId == null) warehouseId = (Long) inv.get("warehouse_id");
        }
        jdbc.update("""
                UPDATE delivery_note SET invoice_id = ?, customer_id = ?, client_name = ?, delivery_address = ?, warehouse_id = ?,
                  delivery_date = ?, driver_name = ?, driver_phone = ?, vehicle_no = ?, note = ?, updated_at = now() WHERE id = ?""",
                req.invoiceId(), customerId, blank(req.clientName()), blank(req.deliveryAddress()), warehouseId,
                req.deliveryDate() == null ? LocalDate.now(InvoiceApiApplication.KSA) : req.deliveryDate(),
                blank(req.driverName()), blank(req.driverPhone()), blank(req.vehicleNo()), blank(req.note()), id);
        jdbc.update("DELETE FROM delivery_note_line WHERE delivery_note_id = ?", id);
        int pos = 1;
        for (Line l : req.lines()) {
            Long productId = l.productId();
            if (l.invoiceLineId() != null)
                productId = jdbc.queryForObject("SELECT product_id FROM invoice_line WHERE id = ?", Long.class, l.invoiceLineId());
            else if (productId != null && jdbc.queryForObject("SELECT count(*) FROM product WHERE id = ? AND organization_id = ?",
                    Integer.class, productId, orgId) == 0)
                throw ApiException.notFound("Product");
            jdbc.update("""
                    INSERT INTO delivery_note_line (delivery_note_id, position, invoice_line_id, product_id, name, quantity, unit_code)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", id, pos++, l.invoiceLineId(), productId, l.name().strip(), l.quantity(),
                    l.unitCode() == null || l.unitCode().isBlank() ? "PCE" : l.unitCode());
        }
    }

    /** Quantities already on non-cancelled notes, per invoice line. */
    private Map<Long, BigDecimal> deliveredByLine(Long invoiceId, Long exceptNoteId) {
        Map<Long, BigDecimal> m = new HashMap<>();
        jdbc.query("""
                SELECT l.invoice_line_id, sum(l.quantity) FROM delivery_note_line l JOIN delivery_note n ON n.id = l.delivery_note_id
                WHERE n.invoice_id = ? AND n.status <> 'CANCELLED' AND (?::bigint IS NULL OR n.id <> ?::bigint)
                GROUP BY l.invoice_line_id""", rs -> { m.put(rs.getLong(1), rs.getBigDecimal(2)); }, invoiceId, exceptNoteId, exceptNoteId);
        return m;
    }

    private Map<String, Object> invoice(Long orgId, Long invoiceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, number, status, doc_type, customer_id, warehouse_id FROM invoice WHERE id = ? AND organization_id = ?", invoiceId, orgId);
        if (rows.isEmpty()) throw ApiException.notFound("Invoice");
        Map<String, Object> inv = rows.getFirst();
        if (!"INVOICE".equals(inv.get("doc_type")) || "DRAFT".equals(inv.get("status")) || "REJECTED".equals(inv.get("status")))
            throw ApiException.badRequest("invoice_not_issued", "Delivery notes can only be made from an issued invoice");
        return inv;
    }

    private Dto load(Long id) {
        Dto d = dto(CurrentUser.orgId(), id);
        if (!CurrentUser.owns(d.createdBy())) throw ApiException.notFound("Delivery note");
        return d;
    }

    private static void requireDraft(Dto d) {
        if (!"DRAFT".equals(d.status()))
            throw ApiException.conflict("not_draft", "Only a draft delivery note can be changed");
    }

    private Dto dto(Long orgId, Long id) {
        List<Line> lines = jdbc.query("SELECT * FROM delivery_note_line WHERE delivery_note_id = ? ORDER BY position",
                (rs, i) -> new Line((Long) rs.getObject("invoice_line_id"), (Long) rs.getObject("product_id"), rs.getString("name"),
                        rs.getBigDecimal("quantity"), rs.getString("unit_code"), null), id);
        return jdbc.query("""
                SELECT n.*, i.number AS inumber, w.name AS wname, COALESCE(c.name_en, c.name_ar) AS cname
                FROM delivery_note n LEFT JOIN invoice i ON i.id = n.invoice_id LEFT JOIN warehouse w ON w.id = n.warehouse_id
                LEFT JOIN customer c ON c.id = n.customer_id WHERE n.id = ? AND n.organization_id = ?""",
                (rs, i) -> new Dto(rs.getLong("id"), rs.getString("number"), (Long) rs.getObject("invoice_id"), rs.getString("inumber"),
                        (Long) rs.getObject("customer_id"), rs.getString("cname"), rs.getString("client_name"),
                        rs.getString("delivery_address"), (Long) rs.getObject("warehouse_id"), rs.getString("wname"),
                        rs.getObject("delivery_date", LocalDate.class), rs.getString("status"), rs.getString("driver_name"),
                        rs.getString("driver_phone"), rs.getString("vehicle_no"), rs.getString("received_by"),
                        rs.getTimestamp("received_at") == null ? null : rs.getTimestamp("received_at").toInstant(),
                        rs.getString("note"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(), lines), id, orgId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Delivery note"));
    }

    private String html(Dto d, Organization org) {
        Customer c = d.customerId() == null ? null : customers.findById(d.customerId()).orElse(null);
        String clientName = c != null ? QuotationController.displayName(c) : d.clientName();
        String brand = org.getBrandColor() == null ? "#0B2A6F" : org.getBrandColor();
        String client = LetterheadPdf.box("Deliver to", "التسليم إلى", new String[][]{
                {"Client", clientName},
                {"VAT number", c == null ? null : c.getVatNumber()},
                {"Address", d.deliveryAddress()},
                {"Mobile", c == null ? null : c.getPhone()},
        });
        String meta = LetterheadPdf.box("Delivery details", "تفاصيل التسليم", new String[][]{
                {"Delivery note no.", d.number()},
                {"Date", DATE.format(d.deliveryDate())},
                {"Invoice no.", d.invoiceNumber()},
                {"Warehouse", d.warehouseName()},
                {"Driver", d.driverName() == null ? null : d.driverName() + (d.driverPhone() == null ? "" : " · " + d.driverPhone())},
                {"Vehicle", d.vehicleNo()},
        });
        String th = "background:%s; color:#fff; font-size:8pt; padding:5px 4px; text-align:center".formatted(brand);
        StringBuilder rows = new StringBuilder();
        int n = 1;
        for (Line l : d.lines())
            rows.append("<tr>").append(td(Integer.toString(n++), "center"))
                    .append(td(LetterheadPdf.esc(l.name()), InvoicePdfService.startsRtl(l.name()) ? "right; direction:rtl" : "left"))
                    .append(td(StockService.plain(l.quantity()) + " " + LetterheadPdf.esc(l.unitCode()), "right"))
                    .append(td("", "center")).append("</tr>");
        String received = d.receivedBy() == null ? "" : "<p style=\"margin-top:8px\">Received by · استلمها: <b>"
                + LetterheadPdf.esc(d.receivedBy()) + "</b>"
                + (d.receivedAt() == null ? "" : " — " + DATE.format(d.receivedAt().atZone(ZoneId.of("Asia/Riyadh")))) + "</p>";
        return """
                <table><tr><td><h1>Delivery Note</h1></td><td class="rtl"><h1>سند تسليم</h1></td></tr></table>
                <table style="margin-top:6px"><tr>
                  <td style="width:55%%; vertical-align:top; padding-right:6px">%s</td>
                  <td style="width:45%%; vertical-align:top">%s</td>
                </tr></table>
                <table style="margin-top:12px">
                  <tr><th style="%s">#</th><th style="%s">Item<br/>الصنف</th><th style="%s">Qty<br/>الكمية</th>
                  <th style="%s">Condition / remarks<br/>الحالة / ملاحظات</th></tr>
                  %s
                </table>
                %s
                %s
                <p class="muted" style="margin-top:10px; font-size:8pt">Goods received in good order and condition.<br/>تم استلام البضاعة بحالة جيدة.</p>
                %s
                """.formatted(client, meta, th, th, th, th, rows,
                d.note() == null ? "" : "<h2>Notes · ملاحظات</h2><p>" + LetterheadPdf.text(d.note()) + "</p>",
                received, LetterheadPdf.signatures(org, clientName == null ? "" : clientName));
    }

    private static String td(String content, String align) {
        return "<td style=\"border-bottom:1px solid #e3e7ef; padding:6px 4px; text-align:" + align + "\">" + content + "</td>";
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
