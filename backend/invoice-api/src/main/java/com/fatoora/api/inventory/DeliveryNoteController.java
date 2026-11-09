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
                       /** Read-only on the prefill: invoice / order quantity still to deliver. */
                       BigDecimal remaining,
                       Long salesOrderLineId, BigDecimal unitPrice, BigDecimal vatRate,
                       /** Read-only: quantity of this line already invoiced. */
                       BigDecimal invoiced,
                       /** Read-only: base units per unit. */
                       BigDecimal uomFactor) {

        public Line(Long invoiceLineId, Long productId, String name, BigDecimal quantity, String unitCode, BigDecimal remaining,
                    Long salesOrderLineId, BigDecimal unitPrice, BigDecimal vatRate, BigDecimal invoiced) {
            this(invoiceLineId, productId, name, quantity, unitCode, remaining, salesOrderLineId, unitPrice, vatRate, invoiced, null);
        }
    }

    public record Request(Long invoiceId, Long customerId, @Size(max = 500) String clientName,
                          @Size(max = 1000) String deliveryAddress, Long warehouseId, LocalDate deliveryDate,
                          @Size(max = 200) String driverName, @Size(max = 40) String driverPhone,
                          @Size(max = 60) String vehicleNo, @Size(max = 2000) String note,
                          @NotEmpty List<@Valid Line> lines,
                          Long salesOrderId, @Size(max = 100) String customerRef, @Size(max = 200) String salesEmployee) {}

    /** Copy To Invoice: one or more delivered notes of the same customer. */
    public record InvoiceFromDeliveries(@NotEmpty List<Long> deliveryNoteIds, com.fatoora.zatca.model.InvoiceKind kind) {}

    public record DeliverRequest(@NotBlank @Size(max = 200) String receivedBy) {}

    public record Dto(Long id, String number, Long invoiceId, String invoiceNumber, Long customerId, String customerName,
                      String clientName, String deliveryAddress, Long warehouseId, String warehouseName, LocalDate deliveryDate,
                      String status, String driverName, String driverPhone, String vehicleNo, String receivedBy,
                      Instant receivedAt, String note, String createdBy, Instant createdAt, List<Line> lines,
                      Long salesOrderId, String salesOrderNumber, String customerRef, String salesEmployee,
                      /** NONE, PARTIAL or FULL: how much of a delivered note is on invoices. */
                      String invoicing,
                      /** Every sales order the lines come from (one delivery may combine several). */
                      List<String> salesOrderNumbers) {}

    private final JdbcTemplate jdbc;
    private final StockService stock;
    private final CustomerRepository customers;
    private final OrganizationRepository orgs;
    private final LetterheadPdf letterhead;
    private final AuditService audit;
    private final com.fatoora.api.flow.FlowService flow;
    private final com.fatoora.api.invoice.InvoiceService invoices;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.fatoora.api.product.UomService uom;

    public DeliveryNoteController(JdbcTemplate jdbc, StockService stock, CustomerRepository customers,
                                  OrganizationRepository orgs, LetterheadPdf letterhead, AuditService audit,
                                  com.fatoora.api.flow.FlowService flow, com.fatoora.api.invoice.InvoiceService invoices) {
        this.flow = flow;
        this.invoices = invoices;
        this.jdbc = jdbc;
        this.stock = stock;
        this.customers = customers;
        this.orgs = orgs;
        this.letterhead = letterhead;
        this.audit = audit;
    }

    @GetMapping
    List<Dto> list(@RequestParam(required = false) String status, @RequestParam(required = false) Long invoiceId,
              @RequestParam(required = false) Long salesOrderId) {
        Long orgId = CurrentUser.orgId();
        String owner = CurrentUser.owner();
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM delivery_note WHERE organization_id = ? AND (?::text IS NULL OR status = ?::text)
                  AND (?::bigint IS NULL OR invoice_id = ?::bigint) AND (?::bigint IS NULL OR sales_order_id = ?::bigint OR EXISTS (SELECT 1 FROM delivery_note_line dl JOIN sales_order_line sl ON sl.id = dl.sales_order_line_id WHERE dl.delivery_note_id = delivery_note.id AND sl.sales_order_id = ?::bigint))
                  AND (?::text IS NULL OR lower(created_by) = lower(?::text))
                ORDER BY id DESC LIMIT 500""", Long.class, orgId, status, status, invoiceId, invoiceId, salesOrderId, salesOrderId, salesOrderId, owner, owner);
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
                                    remaining, rs.getString("unit_code"), remaining, null, null, null, null);
                        }, invoiceId)
                .stream().filter(l -> l.remaining().signum() > 0).toList();
        Long customerId = (Long) inv.get("customer_id");
        Customer c = customerId == null ? null : customers.findById(customerId).orElse(null);
        return new Request(invoiceId, customerId, null, c == null ? null : QuotationController.address(c),
                (Long) inv.get("warehouse_id"), LocalDate.now(InvoiceApiApplication.KSA), null, null, null, null, lines,
                null, null, null);
    }

    /** Copy To Delivery: a new note's lines from a sales order, each with the quantity not yet on a delivery. */
    @GetMapping("/from-sales-order/{salesOrderId}")
    Request prefillFromOrder(@PathVariable Long salesOrderId) {
        return prefillFromOrders(List.of(salesOrderId));
    }

    /**
     * Copy To Delivery from one or more open orders of the same customer (SAP B1 "Copy From" several base documents):
     * every line with the quantity still open on its order.
     */
    @GetMapping("/from-sales-orders")
    Request prefillFromOrders(@RequestParam List<Long> ids) {
        Long orgId = CurrentUser.orgId();
        List<Line> lines = new ArrayList<>();
        List<String> numbers = new ArrayList<>();
        Map<String, Object> first = null;
        for (Long soId : new LinkedHashSet<>(ids)) {
            Map<String, Object> so = salesOrder(orgId, soId);
            String status = (String) so.get("status");
            if ("CANCELLED".equals(status) || "CLOSED".equals(status))
                throw ApiException.badRequest("order_closed", "Sales order " + so.get("number") + " is " + status.toLowerCase());
            if (first == null) first = so;
            else if (!Objects.equals(first.get("customer_id"), so.get("customer_id")) || first.get("customer_id") == null)
                throw ApiException.badRequest("mixed_customers", "All sales orders must be for the same customer");
            Map<Long, BigDecimal> open = flow.openBySalesOrderLine(soId, null, null);
            lines.addAll(jdbc.query("SELECT * FROM sales_order_line WHERE sales_order_id = ? ORDER BY position", (rs, i) -> {
                BigDecimal left = open.getOrDefault(rs.getLong("id"), BigDecimal.ZERO);
                return new Line(null, (Long) rs.getObject("product_id"), rs.getString("name"), left, rs.getString("unit_code"), left,
                        rs.getLong("id"), netUnitPrice(rs.getBigDecimal("unit_price"), rs.getBigDecimal("discount_pct")),
                        rs.getBigDecimal("vat_rate"), null, rs.getBigDecimal("uom_factor"));
            }, soId).stream().filter(l -> l.remaining().signum() > 0).toList());
            numbers.add((String) so.get("number"));
        }
        Long customerId = (Long) first.get("customer_id");
        Customer c = customerId == null ? null : customers.findById(customerId).orElse(null);
        return new Request(null, customerId, (String) first.get("client_name"), c == null ? null : QuotationController.address(c),
                (Long) first.get("warehouse_id"), LocalDate.now(InvoiceApiApplication.KSA), null, null, null,
                (numbers.size() == 1 ? "Based on Sales Order " : "Based on Sales Orders ") + String.join(", ", numbers) + ".", lines,
                ids.getFirst(), (String) first.get("customer_ref"), (String) first.get("sales_employee"));
    }

    /** Delivered notes of a customer with quantities not yet invoiced (the Copy To Invoice picker). */
    @GetMapping("/to-invoice")
    List<Dto> toInvoice(@RequestParam Long customerId) {
        Long orgId = CurrentUser.orgId();
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM delivery_note WHERE organization_id = ? AND customer_id = ? AND status = 'DELIVERED' AND invoice_id IS NULL
                ORDER BY id""", Long.class, orgId, customerId);
        List<Dto> out = new ArrayList<>();
        for (Long id : ids) {
            Dto d = dto(orgId, id);
            if (!"FULL".equals(d.invoicing())) out.add(d);
        }
        return out;
    }

    /**
     * Copy To Invoice: a draft invoice holding the not-yet-invoiced quantities of the chosen delivered notes.
     * Its lines keep the delivery line, so issuing it does not take the stock out a second time.
     */
    @PostMapping("/invoice")
    @PreAuthorize(WRITE)
    @Transactional
    Map<String, Object> invoiceFromDeliveries(@Valid @RequestBody InvoiceFromDeliveries req) {
        Long orgId = CurrentUser.orgId();
        List<Dto> notes = new ArrayList<>();
        for (Long nid : new LinkedHashSet<>(req.deliveryNoteIds())) notes.add(load(nid));
        Long customerId = notes.getFirst().customerId();
        if (customerId == null)
            throw ApiException.badRequest("no_customer", "Only deliveries to a customer from the customer list can be invoiced this way");
        List<com.fatoora.api.invoice.InvoiceDtos.LineDto> lines = new ArrayList<>();
        Set<String> orders = new LinkedHashSet<>(), deliveries = new LinkedHashSet<>(), refs = new LinkedHashSet<>();
        String employee = null;
        LocalDate supply = null;
        for (Dto n : notes) {
            if (!"DELIVERED".equals(n.status())) throw ApiException.badRequest("not_delivered", n.number() + " is not delivered yet");
            if (n.invoiceId() != null) throw ApiException.badRequest("already_invoiced", n.number() + " was made from an invoice");
            if (!customerId.equals(n.customerId())) throw ApiException.badRequest("mixed_customers", "All deliveries must be for the same customer");
            Map<Long, BigDecimal> billed = flow.invoicedByDeliveryLine(n.id(), null);
            List<Long> lineIds = jdbc.queryForList("SELECT id FROM delivery_note_line WHERE delivery_note_id = ? ORDER BY position", Long.class, n.id());
            for (int i = 0; i < n.lines().size(); i++) {
                Line l = n.lines().get(i);
                Long lineId = lineIds.get(i);
                BigDecimal left = l.quantity().subtract(billed.getOrDefault(lineId, BigDecimal.ZERO));
                if (left.signum() <= 0) continue;
                BigDecimal price = l.unitPrice() != null ? l.unitPrice() : l.productId() == null ? BigDecimal.ZERO
                        : jdbc.queryForObject("SELECT unit_price FROM product WHERE id = ?", BigDecimal.class, l.productId());
                lines.add(new com.fatoora.api.invoice.InvoiceDtos.LineDto(l.name(), left, l.unitCode(), price,
                        com.fatoora.zatca.model.VatCategory.S, l.vatRate() == null ? new BigDecimal("15") : l.vatRate(),
                        null, null, l.productId(), lineId));
            }
            deliveries.add(n.number());
            orders.addAll(n.salesOrderNumbers());
            if (n.customerRef() != null) refs.add(n.customerRef());
            if (employee == null) employee = n.salesEmployee();
            if (supply == null || n.deliveryDate().isAfter(supply)) supply = n.deliveryDate();
        }
        if (lines.isEmpty()) throw ApiException.badRequest("nothing_to_invoice", "These deliveries are already fully invoiced");
        Customer c = customers.findById(customerId).orElseThrow();
        com.fatoora.zatca.model.InvoiceKind kind = req.kind() != null ? req.kind()
                : c.getVatNumber() != null && !c.getVatNumber().isBlank() ? com.fatoora.zatca.model.InvoiceKind.STANDARD
                : com.fatoora.zatca.model.InvoiceKind.SIMPLIFIED;
        String basedOn = (orders.isEmpty() ? "" : "Based on Sales Orders " + String.join(", ", orders) + ". ")
                + "Based on Deliveries " + String.join(", ", deliveries) + ".";
        var draft = invoices.createDraft(orgId, CurrentUser.get().email(), new com.fatoora.api.invoice.InvoiceDtos.DraftRequest(
                kind, com.fatoora.zatca.model.DocumentType.INVOICE, customerId, supply, null, com.fatoora.api.invoice.SendMode.NOW,
                null, null, basedOn, null, lines, notes.getFirst().warehouseId(), refs.isEmpty() ? null : String.join(", ", refs), employee));
        audit.record(orgId, "INVOICE_FROM_DELIVERIES", "invoice", draft.getId(), basedOn);
        return Map.of("invoiceId", draft.getId(), "basedOn", basedOn);
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
        flow.ordersOfDelivery(id).forEach(flow::refreshSalesOrder);
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
        List<Long> before = flow.ordersOfDelivery(id);
        write(orgId, id, req);
        before.forEach(flow::refreshSalesOrder);
        flow.ordersOfDelivery(id).forEach(flow::refreshSalesOrder);
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
                if (l.productId() != null && stock.tracked(l.productId()))
                    qty.merge(l.productId(), com.fatoora.api.product.UomService.base(l.quantity(), l.uomFactor()), BigDecimal::add);
            stock.apply(orgId, qty.entrySet().stream().map(e -> new StockService.Move(e.getKey(), wh, e.getValue().negate(),
                    StockService.Kind.DELIVERY, "delivery_note", id, d.number(), null)).toList(), false, CurrentUser.get().email());
        }
        jdbc.update("UPDATE delivery_note SET status = 'DELIVERED', received_by = ?, received_at = now(), updated_at = now() WHERE id = ?",
                req.receivedBy().trim(), id);
        flow.ordersOfDelivery(id).forEach(flow::refreshSalesOrder);
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
        if (!"NONE".equals(d.invoicing()))
            throw ApiException.conflict("delivery_invoiced", "This delivery is on an invoice. Raise a credit note on the invoice instead.");
        Long orgId = CurrentUser.orgId();
        if ("DELIVERED".equals(d.status()) && d.invoiceId() == null) {
            List<StockService.Move> back = jdbc.query("SELECT product_id, warehouse_id, quantity FROM stock_movement WHERE ref_type = 'delivery_note' AND ref_id = ?",
                    (rs, i) -> new StockService.Move(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3).negate(),
                            StockService.Kind.RETURN, "delivery_note", id, d.number(), "Delivery note cancelled"), id);
            stock.apply(orgId, back, true, CurrentUser.get().email());
        }
        jdbc.update("UPDATE delivery_note SET status = 'CANCELLED', updated_at = now() WHERE id = ?", id);
        flow.ordersOfDelivery(id).forEach(flow::refreshSalesOrder);
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
        flow.refreshSalesOrder(d.salesOrderId());
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
        boolean fromOrders = req.salesOrderId() != null || req.lines().stream().anyMatch(l -> l.salesOrderLineId() != null);
        if (fromOrders) {
            if (req.invoiceId() != null) throw ApiException.badRequest("two_bases", "A delivery comes from orders or an invoice, not both");
            validateAgainstOrders(orgId, req, exceptNoteId);
            return;
        }
        for (Line l : req.lines()) if (l.salesOrderLineId() != null)
            throw ApiException.badRequest("order_line_without_order", "Order lines need the sales order");
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

    /** Lines from one or more open orders of one customer, none taking more than is still open on its order line. */
    private void validateAgainstOrders(Long orgId, Request req, Long exceptNoteId) {
        if (req.salesOrderId() != null) salesOrder(orgId, req.salesOrderId());
        Map<Long, BigDecimal> asked = new LinkedHashMap<>();
        for (Line l : req.lines()) {
            if (l.salesOrderLineId() == null)
                throw ApiException.badRequest("unknown_order_line", "Every line must come from a sales order");
            asked.merge(l.salesOrderLineId(), l.quantity(), BigDecimal::add);
        }
        flow.checkOrderLines(orgId, asked, req.customerId(), exceptNoteId, null);
    }

    private Map<String, Object> salesOrder(Long orgId, Long salesOrderId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM sales_order WHERE id = ? AND organization_id = ?", salesOrderId, orgId);
        if (rows.isEmpty()) throw ApiException.notFound("Sales order");
        return rows.getFirst();
    }

    private static BigDecimal netUnitPrice(BigDecimal price, BigDecimal discountPct) {
        if (discountPct == null || discountPct.signum() == 0) return price;
        return price.subtract(price.multiply(discountPct).divide(new BigDecimal("100"), 6, java.math.RoundingMode.HALF_UP));
    }

    private void write(Long orgId, Long id, Request req) {
        Long customerId = req.customerId();
        Long warehouseId = req.warehouseId();
        String customerRef = req.customerRef(), salesEmployee = req.salesEmployee();
        Long headerOrder = req.salesOrderId();
        if (headerOrder == null)
            headerOrder = req.lines().stream().map(Line::salesOrderLineId).filter(Objects::nonNull).findFirst()
                    .map(x -> jdbc.queryForObject("SELECT sales_order_id FROM sales_order_line WHERE id = ?", Long.class, x)).orElse(null);
        if (headerOrder != null) {
            Map<String, Object> so = salesOrder(orgId, headerOrder);
            if (customerId == null) customerId = (Long) so.get("customer_id");
            if (warehouseId == null) warehouseId = (Long) so.get("warehouse_id");
            if (customerRef == null) customerRef = (String) so.get("customer_ref");
            if (salesEmployee == null) salesEmployee = (String) so.get("sales_employee");
        }
        if (req.invoiceId() != null) {
            Map<String, Object> inv = invoice(orgId, req.invoiceId());
            if (customerId == null) customerId = (Long) inv.get("customer_id");
            if (warehouseId == null) warehouseId = (Long) inv.get("warehouse_id");
        }
        jdbc.update("""
                UPDATE delivery_note SET invoice_id = ?, customer_id = ?, client_name = ?, delivery_address = ?, warehouse_id = ?,
                  delivery_date = ?, driver_name = ?, driver_phone = ?, vehicle_no = ?, note = ?, sales_order_id = ?, customer_ref = ?,
                  sales_employee = ?, updated_at = now() WHERE id = ?""",
                req.invoiceId(), customerId, blank(req.clientName()), blank(req.deliveryAddress()), warehouseId,
                req.deliveryDate() == null ? LocalDate.now(InvoiceApiApplication.KSA) : req.deliveryDate(),
                blank(req.driverName()), blank(req.driverPhone()), blank(req.vehicleNo()), blank(req.note()),
                headerOrder, blank(customerRef), blank(salesEmployee), id);
        jdbc.update("DELETE FROM delivery_note_line WHERE delivery_note_id = ?", id);
        int pos = 1;
        for (Line l : req.lines()) {
            Long productId = l.productId();
            BigDecimal unitPrice = l.unitPrice(), vatRate = l.vatRate();
            String unit = l.unitCode() == null || l.unitCode().isBlank() ? "PCE" : l.unitCode().trim().toUpperCase();
            if (l.salesOrderLineId() != null) {
                Map<String, Object> sl = jdbc.queryForMap("SELECT product_id, unit_price, discount_pct, vat_rate, unit_code FROM sales_order_line WHERE id = ?",
                        l.salesOrderLineId());
                productId = (Long) sl.get("product_id");
                unitPrice = netUnitPrice((BigDecimal) sl.get("unit_price"), (BigDecimal) sl.get("discount_pct"));
                vatRate = (BigDecimal) sl.get("vat_rate");
                unit = (String) sl.get("unit_code");
            } else if (l.invoiceLineId() != null) {
                Map<String, Object> il = jdbc.queryForMap("SELECT product_id, unit_code FROM invoice_line WHERE id = ?", l.invoiceLineId());
                productId = (Long) il.get("product_id");
                unit = (String) il.get("unit_code");
            } else if (productId != null && jdbc.queryForObject("SELECT count(*) FROM product WHERE id = ? AND organization_id = ?",
                    Integer.class, productId, orgId) == 0)
                throw ApiException.notFound("Product");
            jdbc.update("""
                    INSERT INTO delivery_note_line (delivery_note_id, position, invoice_line_id, product_id, name, quantity, unit_code,
                                                    sales_order_line_id, unit_price, vat_rate, uom_factor)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, pos++, l.invoiceLineId(), productId, l.name().strip(), l.quantity(),
                    unit, l.salesOrderLineId(), unitPrice, vatRate, uom == null ? BigDecimal.ONE : uom.factor(productId, unit));
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
        Map<Long, BigDecimal> billed = flow.invoicedByDeliveryLine(id, null);
        List<Line> lines = jdbc.query("SELECT * FROM delivery_note_line WHERE delivery_note_id = ? ORDER BY position",
                (rs, i) -> new Line((Long) rs.getObject("invoice_line_id"), (Long) rs.getObject("product_id"), rs.getString("name"),
                        rs.getBigDecimal("quantity"), rs.getString("unit_code"), null, (Long) rs.getObject("sales_order_line_id"),
                        rs.getBigDecimal("unit_price"), rs.getBigDecimal("vat_rate"), billed.getOrDefault(rs.getLong("id"), BigDecimal.ZERO),
                        rs.getBigDecimal("uom_factor")), id);
        List<String> orderNumbers = jdbc.queryForList("""
                SELECT DISTINCT o.number FROM delivery_note_line dl JOIN sales_order_line sl ON sl.id = dl.sales_order_line_id
                JOIN sales_order o ON o.id = sl.sales_order_id WHERE dl.delivery_note_id = ? ORDER BY o.number""", String.class, id);
        boolean anyBilled = lines.stream().anyMatch(l -> l.invoiced().signum() > 0);
        boolean allBilled = !lines.isEmpty() && lines.stream().allMatch(l -> l.invoiced().compareTo(l.quantity()) >= 0);
        String invoicing = allBilled ? "FULL" : anyBilled ? "PARTIAL" : "NONE";
        return jdbc.query("""
                SELECT n.*, i.number AS inumber, w.name AS wname, COALESCE(c.name_en, c.name_ar) AS cname, so.number AS sonumber
                FROM delivery_note n LEFT JOIN invoice i ON i.id = n.invoice_id LEFT JOIN warehouse w ON w.id = n.warehouse_id
                LEFT JOIN sales_order so ON so.id = n.sales_order_id
                LEFT JOIN customer c ON c.id = n.customer_id WHERE n.id = ? AND n.organization_id = ?""",
                (rs, i) -> new Dto(rs.getLong("id"), rs.getString("number"), (Long) rs.getObject("invoice_id"), rs.getString("inumber"),
                        (Long) rs.getObject("customer_id"), rs.getString("cname"), rs.getString("client_name"),
                        rs.getString("delivery_address"), (Long) rs.getObject("warehouse_id"), rs.getString("wname"),
                        rs.getObject("delivery_date", LocalDate.class), rs.getString("status"), rs.getString("driver_name"),
                        rs.getString("driver_phone"), rs.getString("vehicle_no"), rs.getString("received_by"),
                        rs.getTimestamp("received_at") == null ? null : rs.getTimestamp("received_at").toInstant(),
                        rs.getString("note"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(), lines,
                        (Long) rs.getObject("sales_order_id"), rs.getString("sonumber"), rs.getString("customer_ref"),
                        rs.getString("sales_employee"), invoicing, orderNumbers), id, orgId)
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
                {"Sales order", d.salesOrderNumber()},
                {"Customer PO", d.customerRef()},
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
