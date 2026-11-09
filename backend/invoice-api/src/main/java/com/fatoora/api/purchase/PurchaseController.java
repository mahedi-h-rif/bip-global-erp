package com.fatoora.api.purchase;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.AmountInWords;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.inventory.StockService;
import com.fatoora.api.organization.Organization;
import com.fatoora.api.organization.OrganizationRepository;
import com.fatoora.api.pdf.InvoicePdfService;
import com.fatoora.api.pdf.LetterheadPdf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Requisitions / purchase orders (PO-) and goods receipts (GRN-): PENDING → APPROVED → PARTIALLY_RECEIVED → RECEIVED.
 * A goods receipt brings stock in at its landed cost in SAR (unit cost × exchange rate + its share of transport,
 * customs and other costs), which re-averages the item's cost.
 */
@RestController
@RequestMapping("/api/v1")
public class PurchaseController {

    private static final String WRITE = SupplierController.WRITE;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public record PoLine(Long id, Long productId, @NotBlank @Size(max = 1000) String name,
                         @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity, String unitCode,
                         @NotNull @DecimalMin("0") BigDecimal unitCost, @DecimalMin("0") BigDecimal vatRate,
                         /** Read-only. */ BigDecimal received, BigDecimal remaining) {}

    public record PoRequest(@NotNull Long supplierId, @Size(max = 100) String supplierRef, LocalDate orderDate, LocalDate deliveryDate,
                            Long warehouseId, @Pattern(regexp = "[A-Z]{3}") String currency,
                            @DecimalMin(value = "0", inclusive = false) BigDecimal exchangeRate, @Size(max = 4000) String remarks,
                            @NotEmpty List<@Valid PoLine> lines) {}

    public record Attachment(Long id, String fileName, String contentType, String createdBy, Instant createdAt) {}

    public record PoDto(Long id, String number, Long supplierId, String supplierName, String supplierRef, LocalDate orderDate,
                        LocalDate deliveryDate, Long warehouseId, String warehouseName, String currency, BigDecimal exchangeRate,
                        String status, String remarks, BigDecimal totalExclVat, BigDecimal vatTotal, BigDecimal totalInclVat,
                        BigDecimal totalSar, String approvedBy, Instant approvedAt, String createdBy, Instant createdAt,
                        List<PoLine> lines, List<Attachment> attachments) {}

    public record GrnLine(Long id, Long purchaseOrderLineId, Long productId, @NotBlank String name,
                          @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity, String unitCode,
                          @NotNull @DecimalMin("0") BigDecimal unitCost, @DecimalMin("0") BigDecimal vatRate,
                          /** Read-only: SAR cost per unit that entered stock; quantity already on supplier invoices. */
                          BigDecimal landedUnitCost, BigDecimal invoiced, BigDecimal remaining) {}

    public record Cost(@NotBlank @Size(max = 300) String description, @NotNull @DecimalMin("0") BigDecimal amount) {}

    public record GrnRequest(Long purchaseOrderId, Long supplierId, @Size(max = 100) String supplierRef, LocalDate receiptDate,
                             @NotNull Long warehouseId, @Pattern(regexp = "[A-Z]{3}") String currency,
                             @DecimalMin(value = "0", inclusive = false) BigDecimal exchangeRate, @Size(max = 4000) String remarks,
                             @NotEmpty List<@Valid GrnLine> lines, List<@Valid Cost> costs) {}

    public record GrnDto(Long id, String number, Long purchaseOrderId, String purchaseOrderNumber, Long supplierId, String supplierName,
                         String supplierRef, LocalDate receiptDate, Long warehouseId, String warehouseName, String currency,
                         BigDecimal exchangeRate, String status, BigDecimal landedCostTotal, String remarks, BigDecimal totalExclVat,
                         BigDecimal vatTotal, BigDecimal totalInclVat, String createdBy, Instant createdAt, List<GrnLine> lines,
                         List<Cost> costs) {}

    private final JdbcTemplate jdbc;
    private final StockService stock;
    private final OrganizationRepository orgs;
    private final LetterheadPdf letterhead;
    private final AuditService audit;

    public PurchaseController(JdbcTemplate jdbc, StockService stock, OrganizationRepository orgs, LetterheadPdf letterhead, AuditService audit) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.orgs = orgs;
        this.letterhead = letterhead;
        this.audit = audit;
    }

    // --- purchase orders -------------------------------------------------------------------------

    @GetMapping("/purchase-orders")
    List<PoDto> listPo(@RequestParam(required = false) String status, @RequestParam(required = false) Long supplierId) {
        Long orgId = CurrentUser.orgId();
        List<PoDto> out = new ArrayList<>();
        for (Long id : jdbc.queryForList("""
                SELECT id FROM purchase_order WHERE organization_id = ? AND (?::text IS NULL OR status = ?::text)
                  AND (?::bigint IS NULL OR supplier_id = ?::bigint) ORDER BY id DESC LIMIT 500""",
                Long.class, orgId, status, status, supplierId, supplierId)) out.add(po(orgId, id));
        return out;
    }

    @GetMapping("/purchase-orders/{id}")
    PoDto getPo(@PathVariable Long id) {
        return po(CurrentUser.orgId(), id);
    }

    @PostMapping("/purchase-orders")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    PoDto createPo(@Valid @RequestBody PoRequest req) {
        Long orgId = CurrentUser.orgId();
        validatePo(orgId, req);
        String number = stock.nextNumber(orgId, "PURCHASE_ORDER", "PO");
        Long id = jdbc.queryForObject("""
                INSERT INTO purchase_order (organization_id, number, supplier_id, order_date, created_by) VALUES (?, ?, ?, ?, ?) RETURNING id""",
                Long.class, orgId, number, req.supplierId(), LocalDate.now(InvoiceApiApplication.KSA), CurrentUser.get().email());
        writePo(id, req);
        audit.record(orgId, "PURCHASE_ORDER_CREATED", "purchase_order", id, number);
        return po(orgId, id);
    }

    @PutMapping("/purchase-orders/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    PoDto updatePo(@PathVariable Long id, @Valid @RequestBody PoRequest req) {
        Long orgId = CurrentUser.orgId();
        PoDto d = po(orgId, id);
        if (!"PENDING".equals(d.status()))
            throw ApiException.conflict("po_not_pending", "Only a pending requisition can be edited");
        validatePo(orgId, req);
        writePo(id, req);
        audit.record(orgId, "PURCHASE_ORDER_UPDATED", "purchase_order", id, d.number());
        return po(orgId, id);
    }

    /** Approval (ADMIN only): the requisition becomes an order that can be received. */
    @PostMapping("/purchase-orders/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    PoDto approve(@PathVariable Long id) {
        Long orgId = CurrentUser.orgId();
        PoDto d = po(orgId, id);
        if (!"PENDING".equals(d.status())) throw ApiException.conflict("po_not_pending", "Only a pending requisition can be approved");
        jdbc.update("UPDATE purchase_order SET status = 'APPROVED', approved_by = ?, approved_at = now(), updated_at = now() WHERE id = ?",
                CurrentUser.get().email(), id);
        audit.record(orgId, "PURCHASE_ORDER_APPROVED", "purchase_order", id, d.number());
        return po(orgId, id);
    }

    @PostMapping("/purchase-orders/{id}/cancel")
    @PreAuthorize(WRITE)
    @Transactional
    PoDto cancelPo(@PathVariable Long id) {
        Long orgId = CurrentUser.orgId();
        PoDto d = po(orgId, id);
        if (jdbc.queryForObject("SELECT count(*) FROM goods_receipt WHERE purchase_order_id = ? AND status = 'POSTED'", Integer.class, id) > 0)
            throw ApiException.conflict("po_received", "Goods were received on this order; cancel those receipts first");
        jdbc.update("UPDATE purchase_order SET status = 'CANCELLED', updated_at = now() WHERE id = ?", id);
        audit.record(orgId, "PURCHASE_ORDER_CANCELLED", "purchase_order", id, d.number());
        return po(orgId, id);
    }

    /** Raw upload (the portal's uploadFile convention): body = file bytes, {@code name} = original file name. */
    @PutMapping("/purchase-orders/{id}/attachments")
    @PreAuthorize(WRITE)
    @Transactional
    PoDto attach(@PathVariable Long id, @RequestParam String name, @RequestBody byte[] data,
                 @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType) {
        Long orgId = CurrentUser.orgId();
        PoDto d = po(orgId, id);
        if (data.length == 0 || data.length > 10 * 1024 * 1024)
            throw ApiException.badRequest("file_size", "Attach a file of at most 10 MB");
        String type = contentType == null || contentType.isBlank() ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType;
        String file = name.isBlank() ? "attachment" : name.length() > 255 ? name.substring(0, 255) : name;
        jdbc.update("INSERT INTO purchase_attachment (purchase_order_id, file_name, content_type, data, created_by) VALUES (?, ?, ?, ?, ?)",
                id, file, type, data, CurrentUser.get().email());
        audit.record(orgId, "PURCHASE_ATTACHMENT_ADDED", "purchase_order", id, d.number() + " " + file);
        return po(orgId, id);
    }

    @GetMapping("/purchase-orders/{id}/attachments/{attachmentId}")
    ResponseEntity<byte[]> attachment(@PathVariable Long id, @PathVariable Long attachmentId) {
        po(CurrentUser.orgId(), id);
        Map<String, Object> a = jdbc.queryForList("SELECT * FROM purchase_attachment WHERE id = ? AND purchase_order_id = ?", attachmentId, id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Attachment"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType((String) a.get("content_type")))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename((String) a.get("file_name"), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body((byte[]) a.get("data"));
    }

    @GetMapping("/purchase-orders/{id}/pdf")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> poPdf(@PathVariable Long id) {
        Long orgId = CurrentUser.orgId();
        PoDto d = po(orgId, id);
        Organization org = orgs.findById(orgId).orElseThrow();
        byte[] pdf = letterhead.render(org, "Purchase order " + d.number(), poHtml(d, org));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(d.number() + ".pdf").build().toString()).body(pdf);
    }

    // --- goods receipts --------------------------------------------------------------------------

    /** Copy To Goods Receipt: the approved order's quantities not yet received. */
    @GetMapping("/goods-receipts/from-purchase-order/{poId}")
    GrnRequest grnPrefill(@PathVariable Long poId) {
        Long orgId = CurrentUser.orgId();
        PoDto p = po(orgId, poId);
        List<GrnLine> lines = p.lines().stream().filter(l -> l.remaining().signum() > 0)
                .map(l -> new GrnLine(null, l.id(), l.productId(), l.name(), l.remaining(), l.unitCode(), l.unitCost(), l.vatRate(), null, null, null))
                .toList();
        Long wh = p.warehouseId() != null ? p.warehouseId() : stock.defaultWarehouse(orgId);
        return new GrnRequest(poId, p.supplierId(), null, LocalDate.now(InvoiceApiApplication.KSA), wh, p.currency(), p.exchangeRate(),
                "Based on Purchase Order " + p.number() + ".", lines, List.of());
    }

    @GetMapping("/goods-receipts")
    List<GrnDto> listGrn(@RequestParam(required = false) Long supplierId, @RequestParam(required = false) Long purchaseOrderId) {
        Long orgId = CurrentUser.orgId();
        List<GrnDto> out = new ArrayList<>();
        for (Long id : jdbc.queryForList("""
                SELECT id FROM goods_receipt WHERE organization_id = ? AND (?::bigint IS NULL OR supplier_id = ?::bigint)
                  AND (?::bigint IS NULL OR purchase_order_id = ?::bigint) ORDER BY id DESC LIMIT 500""",
                Long.class, orgId, supplierId, supplierId, purchaseOrderId, purchaseOrderId)) out.add(grn(orgId, id));
        return out;
    }

    @GetMapping("/goods-receipts/{id}")
    GrnDto getGrn(@PathVariable Long id) {
        return grn(CurrentUser.orgId(), id);
    }

    @PostMapping("/goods-receipts")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    GrnDto createGrn(@Valid @RequestBody GrnRequest req) {
        Long orgId = CurrentUser.orgId();
        stock.requireWarehouse(orgId, req.warehouseId());
        Long supplierId = req.supplierId();
        String currency = req.currency() == null ? "SAR" : req.currency();
        BigDecimal rate = req.exchangeRate() == null ? BigDecimal.ONE : req.exchangeRate();
        if (req.purchaseOrderId() != null) {
            PoDto p = po(orgId, req.purchaseOrderId());
            if (!"APPROVED".equals(p.status()) && !"PARTIALLY_RECEIVED".equals(p.status()))
                throw ApiException.badRequest("po_not_approved", "Goods can only be received on an approved purchase order");
            supplierId = p.supplierId();
            currency = p.currency();
            if (req.exchangeRate() == null) rate = p.exchangeRate();
            Map<Long, PoLine> byId = new HashMap<>();
            p.lines().forEach(l -> byId.put(l.id(), l));
            Map<Long, BigDecimal> asked = new HashMap<>();
            for (GrnLine l : req.lines()) {
                if (l.purchaseOrderLineId() == null || !byId.containsKey(l.purchaseOrderLineId()))
                    throw ApiException.badRequest("unknown_po_line", "Every line must come from the purchase order");
                asked.merge(l.purchaseOrderLineId(), l.quantity(), BigDecimal::add);
            }
            for (var e : asked.entrySet())
                if (e.getValue().compareTo(byId.get(e.getKey()).remaining()) > 0)
                    throw ApiException.badRequest("over_receipt", "A line exceeds the ordered quantity still to receive ("
                            + StockService.plainOf(byId.get(e.getKey()).remaining()) + ")");
        } else if (supplierId == null) {
            throw new ApiException.Validation(Map.of("supplierId", "Choose the supplier"));
        }
        requireSupplier(orgId, supplierId);

        // Landed cost: spread the extra costs over the lines by their SAR value.
        BigDecimal costs = req.costs() == null ? BigDecimal.ZERO : req.costs().stream().map(Cost::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal goodsSar = BigDecimal.ZERO;
        for (GrnLine l : req.lines()) goodsSar = goodsSar.add(l.quantity().multiply(l.unitCost()).multiply(rate));
        String number = stock.nextNumber(orgId, "GOODS_RECEIPT", "GRN");
        LocalDate date = req.receiptDate() == null ? LocalDate.now(InvoiceApiApplication.KSA) : req.receiptDate();
        Long id = jdbc.queryForObject("""
                INSERT INTO goods_receipt (organization_id, number, purchase_order_id, supplier_id, supplier_ref, receipt_date, warehouse_id,
                                           currency, exchange_rate, landed_cost_total, remarks, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, orgId, number, req.purchaseOrderId(), supplierId,
                SupplierController.b(req.supplierRef()), date, req.warehouseId(), currency, rate, costs, SupplierController.b(req.remarks()),
                CurrentUser.get().email());
        BigDecimal net = BigDecimal.ZERO, vat = BigDecimal.ZERO;
        List<StockService.Move> moves = new ArrayList<>();
        int pos = 1;
        for (GrnLine l : req.lines()) {
            Long productId = l.productId();
            if (l.purchaseOrderLineId() != null)
                productId = jdbc.queryForList("SELECT product_id FROM purchase_order_line WHERE id = ?", Long.class, l.purchaseOrderLineId())
                        .stream().filter(Objects::nonNull).findFirst().orElse(null);
            BigDecimal lineSar = l.quantity().multiply(l.unitCost()).multiply(rate);
            BigDecimal share = goodsSar.signum() == 0 ? BigDecimal.ZERO : costs.multiply(lineSar).divide(goodsSar, 6, RoundingMode.HALF_UP);
            BigDecimal landed = lineSar.add(share).divide(l.quantity(), 6, RoundingMode.HALF_UP);
            BigDecimal rateVat = l.vatRate() == null ? new BigDecimal("15") : l.vatRate();
            jdbc.update("""
                    INSERT INTO goods_receipt_line (goods_receipt_id, position, purchase_order_line_id, product_id, name, quantity, unit_code,
                                                    unit_cost, vat_rate, landed_unit_cost) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    id, pos++, l.purchaseOrderLineId(), productId, l.name().strip(), l.quantity(),
                    l.unitCode() == null || l.unitCode().isBlank() ? "PCE" : l.unitCode(), l.unitCost(), rateVat, landed);
            BigDecimal ln = l.quantity().multiply(l.unitCost()).setScale(2, RoundingMode.HALF_UP);
            net = net.add(ln);
            vat = vat.add(ln.multiply(rateVat).divide(HUNDRED, 2, RoundingMode.HALF_UP));
            if (productId != null && stock.tracked(productId))
                moves.add(new StockService.Move(productId, req.warehouseId(), l.quantity(), StockService.Kind.RECEIPT,
                        "goods_receipt", id, number, null, landed));
        }
        if (req.costs() != null)
            for (Cost c : req.costs())
                jdbc.update("INSERT INTO goods_receipt_cost (goods_receipt_id, description, amount) VALUES (?, ?, ?)", id, c.description().strip(), c.amount());
        jdbc.update("UPDATE goods_receipt SET total_excl_vat = ?, vat_total = ?, total_incl_vat = ? WHERE id = ?", net, vat, net.add(vat), id);
        stock.apply(orgId, moves, false, CurrentUser.get().email());
        refreshPo(req.purchaseOrderId());
        audit.record(orgId, "GOODS_RECEIVED", "goods_receipt", id, number + (costs.signum() > 0 ? ", landed costs " + costs + " SAR" : ""));
        return grn(orgId, id);
    }

    /** Cancels a receipt that is not on a supplier invoice; its stock goes back out at the cost it came in. */
    @PostMapping("/goods-receipts/{id}/cancel")
    @PreAuthorize(WRITE)
    @Transactional
    GrnDto cancelGrn(@PathVariable Long id) {
        Long orgId = CurrentUser.orgId();
        GrnDto g = grn(orgId, id);
        if ("CANCELLED".equals(g.status())) return g;
        if (g.lines().stream().anyMatch(l -> l.invoiced().signum() > 0))
            throw ApiException.conflict("grn_invoiced", "This receipt is on a supplier invoice; cancel that invoice first");
        Integer returns = jdbc.queryForObject("SELECT count(*) FROM goods_return WHERE goods_receipt_id = ? AND status = 'POSTED'", Integer.class, id);
        if (returns != null && returns > 0)
            throw ApiException.conflict("grn_returned", "This receipt has goods returns; cancel them first");
        List<StockService.Move> back = jdbc.query("SELECT product_id, warehouse_id, quantity FROM stock_movement WHERE ref_type = 'goods_receipt' AND ref_id = ?",
                (rs, i) -> new StockService.Move(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3).negate(), StockService.Kind.ADJUST,
                        "goods_receipt", id, g.number(), "Goods receipt cancelled"), id);
        stock.apply(orgId, back, false, CurrentUser.get().email());
        jdbc.update("UPDATE goods_receipt SET status = 'CANCELLED' WHERE id = ?", id);
        refreshPo(g.purchaseOrderId());
        audit.record(orgId, "GOODS_RECEIPT_CANCELLED", "goods_receipt", id, g.number());
        return grn(orgId, id);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void validatePo(Long orgId, PoRequest req) {
        requireSupplier(orgId, req.supplierId());
        if (req.warehouseId() != null) stock.requireWarehouse(orgId, req.warehouseId());
        for (PoLine l : req.lines())
            if (l.productId() != null && jdbc.queryForObject("SELECT count(*) FROM product WHERE id = ? AND organization_id = ?",
                    Integer.class, l.productId(), orgId) == 0) throw ApiException.notFound("Product");
    }

    private void requireSupplier(Long orgId, Long supplierId) {
        if (jdbc.queryForObject("SELECT count(*) FROM supplier WHERE id = ? AND organization_id = ?", Integer.class, supplierId, orgId) == 0)
            throw ApiException.notFound("Supplier");
    }

    private void writePo(Long id, PoRequest req) {
        String currency = req.currency() == null ? "SAR" : req.currency();
        BigDecimal rate = "SAR".equals(currency) ? BigDecimal.ONE : req.exchangeRate() == null ? BigDecimal.ONE : req.exchangeRate();
        BigDecimal net = BigDecimal.ZERO, vat = BigDecimal.ZERO;
        jdbc.update("DELETE FROM purchase_order_line WHERE purchase_order_id = ?", id);
        int pos = 1;
        for (PoLine l : req.lines()) {
            BigDecimal r = l.vatRate() == null ? new BigDecimal("15") : l.vatRate();
            BigDecimal ln = l.quantity().multiply(l.unitCost()).setScale(2, RoundingMode.HALF_UP);
            net = net.add(ln);
            vat = vat.add(ln.multiply(r).divide(HUNDRED, 2, RoundingMode.HALF_UP));
            jdbc.update("""
                    INSERT INTO purchase_order_line (purchase_order_id, position, product_id, name, quantity, unit_code, unit_cost, vat_rate)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", id, pos++, l.productId(), l.name().strip(), l.quantity(),
                    l.unitCode() == null || l.unitCode().isBlank() ? "PCE" : l.unitCode(), l.unitCost(), r);
        }
        jdbc.update("""
                UPDATE purchase_order SET supplier_id = ?, supplier_ref = ?, order_date = COALESCE(?, order_date), delivery_date = ?,
                  warehouse_id = ?, currency = ?, exchange_rate = ?, remarks = ?, total_excl_vat = ?, vat_total = ?, total_incl_vat = ?,
                  updated_at = now() WHERE id = ?""", req.supplierId(), SupplierController.b(req.supplierRef()), req.orderDate(),
                req.deliveryDate(), req.warehouseId(), currency, rate, SupplierController.b(req.remarks()), net, vat, net.add(vat), id);
    }

    private void refreshPo(Long poId) {
        if (poId == null) return;
        String status = jdbc.queryForObject("SELECT status FROM purchase_order WHERE id = ?", String.class, poId);
        if ("CANCELLED".equals(status) || "PENDING".equals(status)) return;
        Map<Long, BigDecimal> got = received(poId);
        boolean any = false, all = true;
        for (var row : jdbc.queryForList("SELECT id, quantity FROM purchase_order_line WHERE purchase_order_id = ?", poId)) {
            BigDecimal r = got.getOrDefault(((Number) row.get("id")).longValue(), BigDecimal.ZERO);
            if (r.signum() > 0) any = true;
            if (r.compareTo((BigDecimal) row.get("quantity")) < 0) all = false;
        }
        jdbc.update("UPDATE purchase_order SET status = ?, updated_at = now() WHERE id = ?",
                all && any ? "RECEIVED" : any ? "PARTIALLY_RECEIVED" : "APPROVED", poId);
    }

    private Map<Long, BigDecimal> received(Long poId) {
        Map<Long, BigDecimal> m = new HashMap<>();
        jdbc.query("""
                SELECT l.purchase_order_line_id, sum(l.quantity) FROM goods_receipt_line l JOIN goods_receipt g ON g.id = l.goods_receipt_id
                WHERE g.purchase_order_id = ? AND g.status = 'POSTED' AND l.purchase_order_line_id IS NOT NULL GROUP BY l.purchase_order_line_id""",
                rs -> { m.put(rs.getLong(1), rs.getBigDecimal(2)); }, poId);
        return m;
    }

    PoDto po(Long orgId, Long id) {
        Map<Long, BigDecimal> got = received(id);
        List<PoLine> lines = jdbc.query("SELECT * FROM purchase_order_line WHERE purchase_order_id = ? ORDER BY position", (rs, i) -> {
            BigDecimal q = rs.getBigDecimal("quantity");
            BigDecimal r = got.getOrDefault(rs.getLong("id"), BigDecimal.ZERO);
            return new PoLine(rs.getLong("id"), (Long) rs.getObject("product_id"), rs.getString("name"), q, rs.getString("unit_code"),
                    rs.getBigDecimal("unit_cost"), rs.getBigDecimal("vat_rate"), r, q.subtract(r).max(BigDecimal.ZERO));
        }, id);
        List<Attachment> att = jdbc.query("SELECT id, file_name, content_type, created_by, created_at FROM purchase_attachment WHERE purchase_order_id = ? ORDER BY id",
                (rs, i) -> new Attachment(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()), id);
        return jdbc.query("""
                SELECT p.*, s.name AS sname, w.name AS wname FROM purchase_order p JOIN supplier s ON s.id = p.supplier_id
                LEFT JOIN warehouse w ON w.id = p.warehouse_id WHERE p.id = ? AND p.organization_id = ?""",
                (rs, i) -> new PoDto(rs.getLong("id"), rs.getString("number"), rs.getLong("supplier_id"), rs.getString("sname"),
                        rs.getString("supplier_ref"), rs.getObject("order_date", LocalDate.class), rs.getObject("delivery_date", LocalDate.class),
                        (Long) rs.getObject("warehouse_id"), rs.getString("wname"), rs.getString("currency"), rs.getBigDecimal("exchange_rate"),
                        rs.getString("status"), rs.getString("remarks"), rs.getBigDecimal("total_excl_vat"), rs.getBigDecimal("vat_total"),
                        rs.getBigDecimal("total_incl_vat"), rs.getBigDecimal("total_incl_vat").multiply(rs.getBigDecimal("exchange_rate")).setScale(2, RoundingMode.HALF_UP),
                        rs.getString("approved_by"), rs.getTimestamp("approved_at") == null ? null : rs.getTimestamp("approved_at").toInstant(),
                        rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(), lines, att), id, orgId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Purchase order"));
    }

    GrnDto grn(Long orgId, Long id) {
        Map<Long, BigDecimal> billed = new HashMap<>();
        jdbc.query("""
                SELECT sl.goods_receipt_line_id, sum(sl.quantity) FROM supplier_invoice_line sl JOIN supplier_invoice v ON v.id = sl.supplier_invoice_id
                JOIN goods_receipt_line gl ON gl.id = sl.goods_receipt_line_id WHERE gl.goods_receipt_id = ? AND v.status = 'OPEN'
                GROUP BY sl.goods_receipt_line_id""", rs -> { billed.put(rs.getLong(1), rs.getBigDecimal(2)); }, id);
        // Quantities sent back to the supplier (goods returns) can't be billed either.
        Map<Long, BigDecimal> returned = new HashMap<>();
        jdbc.query("""
                SELECT rl.goods_receipt_line_id, sum(rl.quantity) FROM goods_return_line rl JOIN goods_return r ON r.id = rl.goods_return_id
                JOIN goods_receipt_line gl ON gl.id = rl.goods_receipt_line_id WHERE gl.goods_receipt_id = ? AND r.status = 'POSTED'
                GROUP BY rl.goods_receipt_line_id""", rs -> { returned.put(rs.getLong(1), rs.getBigDecimal(2)); }, id);
        List<GrnLine> lines = jdbc.query("SELECT * FROM goods_receipt_line WHERE goods_receipt_id = ? ORDER BY position", (rs, i) -> {
            BigDecimal q = rs.getBigDecimal("quantity");
            BigDecimal b = billed.getOrDefault(rs.getLong("id"), BigDecimal.ZERO);
            return new GrnLine(rs.getLong("id"), (Long) rs.getObject("purchase_order_line_id"), (Long) rs.getObject("product_id"),
                    rs.getString("name"), q, rs.getString("unit_code"), rs.getBigDecimal("unit_cost"), rs.getBigDecimal("vat_rate"),
                    rs.getBigDecimal("landed_unit_cost"), b, q.subtract(b).subtract(returned.getOrDefault(rs.getLong("id"), BigDecimal.ZERO)).max(BigDecimal.ZERO));
        }, id);
        List<Cost> costs = jdbc.query("SELECT description, amount FROM goods_receipt_cost WHERE goods_receipt_id = ? ORDER BY id",
                (rs, i) -> new Cost(rs.getString(1), rs.getBigDecimal(2)), id);
        return jdbc.query("""
                SELECT g.*, s.name AS sname, w.name AS wname, p.number AS ponumber FROM goods_receipt g JOIN supplier s ON s.id = g.supplier_id
                JOIN warehouse w ON w.id = g.warehouse_id LEFT JOIN purchase_order p ON p.id = g.purchase_order_id
                WHERE g.id = ? AND g.organization_id = ?""",
                (rs, i) -> new GrnDto(rs.getLong("id"), rs.getString("number"), (Long) rs.getObject("purchase_order_id"), rs.getString("ponumber"),
                        rs.getLong("supplier_id"), rs.getString("sname"), rs.getString("supplier_ref"), rs.getObject("receipt_date", LocalDate.class),
                        rs.getLong("warehouse_id"), rs.getString("wname"), rs.getString("currency"), rs.getBigDecimal("exchange_rate"),
                        rs.getString("status"), rs.getBigDecimal("landed_cost_total"), rs.getString("remarks"), rs.getBigDecimal("total_excl_vat"),
                        rs.getBigDecimal("vat_total"), rs.getBigDecimal("total_incl_vat"), rs.getString("created_by"),
                        rs.getTimestamp("created_at").toInstant(), lines, costs), id, orgId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Goods receipt"));
    }

    private String poHtml(PoDto d, Organization org) {
        Map<String, Object> s = jdbc.queryForMap("SELECT * FROM supplier WHERE id = ?", d.supplierId());
        String brand = org.getBrandColor() == null ? "#0B2A6F" : org.getBrandColor();
        String vendor = LetterheadPdf.box("Vendor", "المورد", new String[][]{
                {"Vendor", d.supplierName()}, {"Arabic name", (String) s.get("name_ar")}, {"VAT number", (String) s.get("vat_number")},
                {"Address", (String) s.get("address")}, {"Contact", (String) s.get("contact_person")}, {"Phone", (String) s.get("phone")}});
        String meta = LetterheadPdf.box("Order details", "تفاصيل الطلب", new String[][]{
                {"PO no.", d.number()}, {"PO date", DATE.format(d.orderDate())},
                {"Delivery date", d.deliveryDate() == null ? null : DATE.format(d.deliveryDate())},
                {"Your quotation", d.supplierRef()}, {"Deliver to", d.warehouseName()},
                {"Currency", d.currency() + ("SAR".equals(d.currency()) ? "" : " · rate " + d.exchangeRate().stripTrailingZeros().toPlainString())}});
        String th = "background:%s; color:#fff; font-size:8pt; padding:5px 4px; text-align:center".formatted(brand);
        StringBuilder rows = new StringBuilder();
        int n = 1;
        for (PoLine l : d.lines()) {
            BigDecimal ln = l.quantity().multiply(l.unitCost()).setScale(2, RoundingMode.HALF_UP);
            rows.append("<tr>").append(td(Integer.toString(n++), "center"))
                    .append(td(LetterheadPdf.esc(l.name()), InvoicePdfService.startsRtl(l.name()) ? "right; direction:rtl" : "left"))
                    .append(td(StockService.plainOf(l.quantity()) + " " + LetterheadPdf.esc(l.unitCode()), "right"))
                    .append(td(LetterheadPdf.money(l.unitCost()), "right")).append(td(LetterheadPdf.money(ln), "right")).append("</tr>");
        }
        return """
                <table><tr><td><h1>Purchase Order</h1></td><td class="rtl"><h1>أمر شراء</h1></td></tr></table>
                <table style="margin-top:6px"><tr><td style="width:55%%; vertical-align:top; padding-right:6px">%s</td><td style="width:45%%; vertical-align:top">%s</td></tr></table>
                <p style="margin-top:8px">With reference to your quotation, we are pleased to confirm our order as follows · نود تأكيد طلبنا كما يلي:</p>
                <table style="margin-top:6px"><tr><th style="%s">#</th><th style="%s">Description<br/>الوصف</th><th style="%s">Qty<br/>الكمية</th>
                <th style="%s">Unit price (%s)<br/>سعر الوحدة</th><th style="%s">Total (%s)<br/>الإجمالي</th></tr>%s</table>
                <table style="margin-top:10px; page-break-inside:avoid"><tr><td style="width:50%%"></td><td style="width:50%%"><table>
                  <tr><td style="padding:4px 8px">Order subtotal · المجموع</td><td style="padding:4px 8px; text-align:right; font-weight:700">%s %s</td></tr>
                  <tr><td style="padding:4px 8px">VAT · الضريبة</td><td style="padding:4px 8px; text-align:right; font-weight:700">%s %s</td></tr>
                  <tr><td style="padding:6px 8px; background:%s; color:#fff">Total amount · الإجمالي</td><td style="padding:6px 8px; background:%s; color:#fff; text-align:right; font-weight:700">%s %s</td></tr>
                </table></td></tr></table>
                <p style="margin-top:6px"><b>Total amount in words:</b> %s</p>
                %s
                %s
                """.formatted(vendor, meta, th, th, th, th, d.currency(), th, d.currency(), rows,
                LetterheadPdf.money(d.totalExclVat()).replace(" SAR", ""), d.currency(), LetterheadPdf.money(d.vatTotal()).replace(" SAR", ""), d.currency(),
                brand, brand, LetterheadPdf.money(d.totalInclVat()).replace(" SAR", ""), d.currency(),
                LetterheadPdf.esc(AmountInWords.english(d.totalInclVat(), d.currency())),
                d.remarks() == null ? "" : "<h2>Terms · الشروط</h2><p>" + LetterheadPdf.text(d.remarks()) + "</p>",
                LetterheadPdf.signatures(org, d.supplierName()));
    }

    private static String td(String content, String align) {
        return "<td style=\"border-bottom:1px solid #e3e7ef; padding:6px 4px; text-align:" + align + "\">" + content + "</td>";
    }
}
