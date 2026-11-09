package com.fatoora.api.purchase;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.inventory.StockService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * The rest of the SAP B1 purchasing chain shown in the meeting video:
 * <ul>
 *   <li><b>Purchase request</b> (PR-): a department asks for items; "Copy To" a purchase order for a chosen supplier.</li>
 *   <li><b>Goods return</b> (GRT-): items sent back to the supplier from a goods receipt; stock leaves at moving average,
 *       and the returned quantity can no longer be billed.</li>
 *   <li><b>A/P credit memo</b> (SCN-): the supplier's credit against one of its invoices; it lowers what is owed and the input VAT.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class PurchaseFlowController {

    private static final String WRITE = SupplierController.WRITE;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    // --- purchase requests -----------------------------------------------------------------------

    public record PrLine(Long productId, @NotBlank @Size(max = 1000) String name,
                         @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity, String unitCode) {}

    public record PrRequest(@NotBlank @Size(max = 200) String requester, @Size(max = 100) String department, LocalDate requiredDate,
                            Long warehouseId, @Size(max = 4000) String remarks, @NotEmpty List<@Valid PrLine> lines) {}

    public record PrDto(Long id, String number, String requester, String department, LocalDate requestDate, LocalDate requiredDate,
                        Long warehouseId, String warehouseName, String status, String remarks, String createdBy, Instant createdAt,
                        List<PrLine> lines, List<Map<String, Object>> purchaseOrders) {}

    public record ToPo(@NotNull Long supplierId, Long warehouseId) {}

    // --- goods returns ---------------------------------------------------------------------------

    public record ReturnLine(@NotNull Long goodsReceiptLineId, @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity) {}

    public record ReturnRequest(@NotNull Long goodsReceiptId, LocalDate returnDate, @Size(max = 1000) String reason,
                                @NotEmpty List<@Valid ReturnLine> lines) {}

    public record ReturnLineDto(Long goodsReceiptLineId, Long productId, String name, BigDecimal quantity, BigDecimal unitCost, BigDecimal vatRate) {}

    public record ReturnDto(Long id, String number, Long goodsReceiptId, String goodsReceiptNumber, Long supplierId, String supplierName,
                            LocalDate returnDate, Long warehouseId, String warehouseName, String currency, String reason, String status,
                            BigDecimal totalExclVat, BigDecimal vatTotal, BigDecimal totalInclVat, String createdBy, Instant createdAt,
                            List<ReturnLineDto> lines, Long creditNoteId, String creditNoteNumber) {}

    // --- A/P credit memos ------------------------------------------------------------------------

    public record CreditLine(Long productId, @NotBlank @Size(max = 1000) String name,
                             @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
                             @NotNull @DecimalMin("0") BigDecimal unitCost, @DecimalMin("0") BigDecimal vatRate) {}

    public record CreditRequest(@NotNull Long supplierInvoiceId, Long goodsReturnId, @Size(max = 100) String supplierRef, LocalDate creditDate,
                                @Size(max = 4000) String remarks, @NotEmpty List<@Valid CreditLine> lines) {}

    public record CreditDto(Long id, String number, Long supplierId, String supplierName, Long supplierInvoiceId, String supplierInvoiceNumber,
                            Long goodsReturnId, String goodsReturnNumber, String supplierRef, LocalDate creditDate, String currency,
                            BigDecimal exchangeRate, String status, String remarks, BigDecimal totalExclVat, BigDecimal vatTotal,
                            BigDecimal totalInclVat, BigDecimal totalSar, BigDecimal vatSar, String createdBy, Instant createdAt,
                            List<CreditLine> lines) {}

    public record CancelRequest(@Size(max = 500) String reason) {}

    private final JdbcTemplate jdbc;
    private final StockService stock;
    private final PurchaseController purchases;
    private final AuditService audit;

    public PurchaseFlowController(JdbcTemplate jdbc, StockService stock, PurchaseController purchases, AuditService audit) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.purchases = purchases;
        this.audit = audit;
    }

    // =============================================================================================
    // Purchase requests
    // =============================================================================================

    @GetMapping("/purchase-requests")
    List<PrDto> listPr(@RequestParam(required = false) String status) {
        Long orgId = CurrentUser.orgId();
        List<PrDto> out = new ArrayList<>();
        for (Long id : jdbc.queryForList("SELECT id FROM purchase_request WHERE organization_id = ? AND (?::text IS NULL OR status = ?::text) ORDER BY id DESC LIMIT 500",
                Long.class, orgId, status, status)) out.add(pr(orgId, id));
        return out;
    }

    @GetMapping("/purchase-requests/{id}")
    PrDto getPr(@PathVariable Long id) {
        return pr(CurrentUser.orgId(), id);
    }

    /** Any signed-in user may raise a request (it commits nothing); turning it into an order needs purchasing rights. */
    @PostMapping("/purchase-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    PrDto createPr(@Valid @RequestBody PrRequest req) {
        Long orgId = CurrentUser.orgId();
        if (req.warehouseId() != null) stock.requireWarehouse(orgId, req.warehouseId());
        String number = stock.nextNumber(orgId, "PURCHASE_REQUEST", "PR");
        Long id = jdbc.queryForObject("""
                INSERT INTO purchase_request (organization_id, number, requester, department, request_date, required_date, warehouse_id, remarks, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, orgId, number, req.requester().strip(), SupplierController.b(req.department()),
                LocalDate.now(InvoiceApiApplication.KSA), req.requiredDate(), req.warehouseId(), SupplierController.b(req.remarks()), CurrentUser.get().email());
        int pos = 1;
        for (PrLine l : req.lines()) {
            if (l.productId() != null) requireProduct(orgId, l.productId());
            jdbc.update("INSERT INTO purchase_request_line (purchase_request_id, position, product_id, name, quantity, unit_code) VALUES (?, ?, ?, ?, ?, ?)",
                    id, pos++, l.productId(), l.name().strip(), l.quantity(), unit(l.unitCode()));
        }
        audit.record(orgId, "PURCHASE_REQUEST_CREATED", "purchase_request", id, number + " by " + req.requester().strip());
        return pr(orgId, id);
    }

    @PostMapping("/purchase-requests/{id}/cancel")
    @Transactional
    PrDto cancelPr(@PathVariable Long id) {
        Long orgId = CurrentUser.orgId();
        PrDto p = pr(orgId, id);
        if (!"OPEN".equals(p.status())) throw ApiException.conflict("request_closed", "Only an open request can be cancelled");
        jdbc.update("UPDATE purchase_request SET status = 'CANCELLED' WHERE id = ?", id);
        audit.record(orgId, "PURCHASE_REQUEST_CANCELLED", "purchase_request", id, p.number());
        return pr(orgId, id);
    }

    /** Copy To → Purchase Order: a PENDING order for the supplier, lines priced at the item's current cost (editable before approval). */
    @PostMapping("/purchase-requests/{id}/purchase-order")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Map<String, Object> toPo(@PathVariable Long id, @Valid @RequestBody ToPo req) {
        Long orgId = CurrentUser.orgId();
        PrDto p = pr(orgId, id);
        if (!"OPEN".equals(p.status())) throw ApiException.conflict("request_closed", "This request is already ordered or cancelled");
        Map<String, Object> s = jdbc.queryForList("SELECT currency FROM supplier WHERE id = ? AND organization_id = ?", req.supplierId(), orgId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Supplier"));
        Long warehouseId = req.warehouseId() != null ? req.warehouseId() : p.warehouseId();
        if (warehouseId != null) stock.requireWarehouse(orgId, warehouseId);
        String currency = (String) s.get("currency");
        String number = stock.nextNumber(orgId, "PURCHASE_ORDER", "PO");
        LocalDate today = LocalDate.now(InvoiceApiApplication.KSA);
        Long poId = jdbc.queryForObject("""
                INSERT INTO purchase_order (organization_id, number, supplier_id, order_date, delivery_date, warehouse_id, currency, remarks,
                                            purchase_request_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, orgId, number, req.supplierId(), today, p.requiredDate(),
                warehouseId, currency, "Based on Purchase Request " + p.number() + ".", id, CurrentUser.get().email());
        BigDecimal net = BigDecimal.ZERO, vat = BigDecimal.ZERO, fifteen = new BigDecimal("15");
        int pos = 1;
        for (PrLine l : p.lines()) {
            // Only a SAR order can reuse the SAR cost as its price; foreign-currency orders are priced by the buyer.
            BigDecimal cost = BigDecimal.ZERO;
            if (l.productId() != null && "SAR".equals(currency))
                cost = jdbc.queryForObject("SELECT COALESCE(avg_cost, cost_price, 0) FROM product WHERE id = ?", BigDecimal.class, l.productId());
            jdbc.update("""
                    INSERT INTO purchase_order_line (purchase_order_id, position, product_id, name, quantity, unit_code, unit_cost, vat_rate)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", poId, pos++, l.productId(), l.name(), l.quantity(), l.unitCode(), cost, fifteen);
            BigDecimal ln = l.quantity().multiply(cost).setScale(2, RoundingMode.HALF_UP);
            net = net.add(ln);
            vat = vat.add(ln.multiply(fifteen).divide(HUNDRED, 2, RoundingMode.HALF_UP));
        }
        jdbc.update("UPDATE purchase_order SET total_excl_vat = ?, vat_total = ?, total_incl_vat = ? WHERE id = ?", net, vat, net.add(vat), poId);
        jdbc.update("UPDATE purchase_request SET status = 'ORDERED' WHERE id = ?", id);
        audit.record(orgId, "PURCHASE_ORDER_CREATED", "purchase_order", poId, number + " from " + p.number());
        return Map.of("id", poId, "number", number);
    }

    private PrDto pr(Long orgId, Long id) {
        List<PrLine> lines = jdbc.query("SELECT * FROM purchase_request_line WHERE purchase_request_id = ? ORDER BY position",
                (rs, i) -> new PrLine((Long) rs.getObject("product_id"), rs.getString("name"), rs.getBigDecimal("quantity"), rs.getString("unit_code")), id);
        List<Map<String, Object>> pos = jdbc.queryForList("SELECT id, number, status FROM purchase_order WHERE purchase_request_id = ? ORDER BY id", id);
        return jdbc.query("""
                SELECT r.*, w.name AS wname FROM purchase_request r LEFT JOIN warehouse w ON w.id = r.warehouse_id
                WHERE r.id = ? AND r.organization_id = ?""", (rs, i) -> new PrDto(rs.getLong("id"), rs.getString("number"), rs.getString("requester"),
                rs.getString("department"), rs.getObject("request_date", LocalDate.class), rs.getObject("required_date", LocalDate.class),
                (Long) rs.getObject("warehouse_id"), rs.getString("wname"), rs.getString("status"), rs.getString("remarks"),
                rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(), lines, pos), id, orgId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Purchase request"));
    }

    // =============================================================================================
    // Goods returns
    // =============================================================================================

    @GetMapping("/goods-returns")
    List<ReturnDto> listReturns(@RequestParam(required = false) Long supplierId) {
        Long orgId = CurrentUser.orgId();
        List<ReturnDto> out = new ArrayList<>();
        for (Long id : jdbc.queryForList("SELECT id FROM goods_return WHERE organization_id = ? AND (?::bigint IS NULL OR supplier_id = ?::bigint) ORDER BY id DESC LIMIT 500",
                Long.class, orgId, supplierId, supplierId)) out.add(ret(orgId, id));
        return out;
    }

    @GetMapping("/goods-returns/{id}")
    ReturnDto getReturn(@PathVariable Long id) {
        return ret(CurrentUser.orgId(), id);
    }

    /** What can still go back on each line of a goods receipt: received − already returned. */
    @GetMapping("/goods-returns/from-goods-receipt/{grnId}")
    List<Map<String, Object>> returnable(@PathVariable Long grnId) {
        Long orgId = CurrentUser.orgId();
        var g = purchases.grn(orgId, grnId);
        if (!"POSTED".equals(g.status())) throw ApiException.badRequest("grn_cancelled", g.number() + " is cancelled");
        List<Map<String, Object>> out = new ArrayList<>();
        for (var l : g.lines()) {
            BigDecimal left = l.quantity().subtract(returned(l.id()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("goodsReceiptLineId", l.id());
            m.put("productId", l.productId());
            m.put("name", l.name());
            m.put("received", l.quantity());
            m.put("returnable", left.max(BigDecimal.ZERO));
            m.put("invoiced", l.invoiced());
            m.put("unitCost", l.unitCost());
            m.put("unitCode", l.unitCode());
            out.add(m);
        }
        return out;
    }

    @PostMapping("/goods-returns")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    ReturnDto createReturn(@Valid @RequestBody ReturnRequest req) {
        Long orgId = CurrentUser.orgId();
        var g = purchases.grn(orgId, req.goodsReceiptId());
        if (!"POSTED".equals(g.status())) throw ApiException.badRequest("grn_cancelled", g.number() + " is cancelled");
        Map<Long, PurchaseController.GrnLine> byId = new HashMap<>();
        g.lines().forEach(l -> byId.put(l.id(), l));
        Map<Long, BigDecimal> asked = new LinkedHashMap<>();
        for (ReturnLine l : req.lines()) {
            if (!byId.containsKey(l.goodsReceiptLineId())) throw ApiException.badRequest("unknown_grn_line", "Every line must come from " + g.number());
            asked.merge(l.goodsReceiptLineId(), l.quantity(), BigDecimal::add);
        }
        for (var e : asked.entrySet()) {
            BigDecimal left = byId.get(e.getKey()).quantity().subtract(returned(e.getKey()));
            if (e.getValue().compareTo(left) > 0)
                throw ApiException.badRequest("over_return", "\"" + byId.get(e.getKey()).name() + "\": only " + StockService.plainOf(left.max(BigDecimal.ZERO)) + " can still be returned");
        }
        String number = stock.nextNumber(orgId, "GOODS_RETURN", "GRT");
        LocalDate date = req.returnDate() == null ? LocalDate.now(InvoiceApiApplication.KSA) : req.returnDate();
        Long id = jdbc.queryForObject("""
                INSERT INTO goods_return (organization_id, number, goods_receipt_id, supplier_id, return_date, warehouse_id, reason, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, orgId, number, g.id(), g.supplierId(), date, g.warehouseId(),
                SupplierController.b(req.reason()), CurrentUser.get().email());
        BigDecimal net = BigDecimal.ZERO, vat = BigDecimal.ZERO;
        List<StockService.Move> moves = new ArrayList<>();
        int pos = 1;
        for (var e : asked.entrySet()) {
            var l = byId.get(e.getKey());
            jdbc.update("""
                    INSERT INTO goods_return_line (goods_return_id, position, goods_receipt_line_id, product_id, name, quantity, unit_cost, vat_rate)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", id, pos++, l.id(), l.productId(), l.name(), e.getValue(), l.unitCost(), l.vatRate());
            BigDecimal ln = e.getValue().multiply(l.unitCost()).setScale(2, RoundingMode.HALF_UP);
            net = net.add(ln);
            vat = vat.add(ln.multiply(l.vatRate()).divide(HUNDRED, 2, RoundingMode.HALF_UP));
            if (l.productId() != null && stock.tracked(l.productId()))
                moves.add(new StockService.Move(l.productId(), g.warehouseId(), e.getValue().negate(), StockService.Kind.ADJUST,
                        "goods_return", id, number, "Returned to supplier (" + g.number() + ")"));
        }
        jdbc.update("UPDATE goods_return SET total_excl_vat = ?, vat_total = ?, total_incl_vat = ? WHERE id = ?", net, vat, net.add(vat), id);
        stock.apply(orgId, moves, false, CurrentUser.get().email());
        audit.record(orgId, "GOODS_RETURNED", "goods_return", id, number + " from " + g.number());
        return ret(orgId, id);
    }

    @PostMapping("/goods-returns/{id}/cancel")
    @PreAuthorize(WRITE)
    @Transactional
    ReturnDto cancelReturn(@PathVariable Long id) {
        Long orgId = CurrentUser.orgId();
        ReturnDto r = ret(orgId, id);
        if ("CANCELLED".equals(r.status())) return r;
        if (r.creditNoteId() != null) throw ApiException.conflict("return_credited", "Cancel credit memo " + r.creditNoteNumber() + " first");
        List<StockService.Move> back = jdbc.query("SELECT product_id, warehouse_id, quantity, unit_cost FROM stock_movement WHERE ref_type = 'goods_return' AND ref_id = ?",
                (rs, i) -> new StockService.Move(rs.getLong(1), rs.getLong(2), rs.getBigDecimal(3).negate(), StockService.Kind.ADJUST,
                        "goods_return", id, r.number(), "Goods return cancelled", rs.getBigDecimal(4)), id);
        stock.apply(orgId, back, true, CurrentUser.get().email());
        jdbc.update("UPDATE goods_return SET status = 'CANCELLED' WHERE id = ?", id);
        audit.record(orgId, "GOODS_RETURN_CANCELLED", "goods_return", id, r.number());
        return ret(orgId, id);
    }

    /** Quantity of a goods-receipt line already sent back (posted returns only). */
    BigDecimal returned(Long grnLineId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(l.quantity), 0) FROM goods_return_line l JOIN goods_return r ON r.id = l.goods_return_id
                WHERE l.goods_receipt_line_id = ? AND r.status = 'POSTED'""", BigDecimal.class, grnLineId);
    }

    private ReturnDto ret(Long orgId, Long id) {
        List<ReturnLineDto> lines = jdbc.query("SELECT * FROM goods_return_line WHERE goods_return_id = ? ORDER BY position",
                (rs, i) -> new ReturnLineDto(rs.getLong("goods_receipt_line_id"), (Long) rs.getObject("product_id"), rs.getString("name"),
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("unit_cost"), rs.getBigDecimal("vat_rate")), id);
        return jdbc.query("""
                SELECT r.*, g.number AS gnumber, g.currency, s.name AS sname, w.name AS wname,
                       (SELECT c.id FROM supplier_credit_note c WHERE c.goods_return_id = r.id AND c.status = 'POSTED' LIMIT 1) AS cid,
                       (SELECT c.number FROM supplier_credit_note c WHERE c.goods_return_id = r.id AND c.status = 'POSTED' LIMIT 1) AS cnumber
                FROM goods_return r JOIN goods_receipt g ON g.id = r.goods_receipt_id JOIN supplier s ON s.id = r.supplier_id
                JOIN warehouse w ON w.id = r.warehouse_id WHERE r.id = ? AND r.organization_id = ?""",
                (rs, i) -> new ReturnDto(rs.getLong("id"), rs.getString("number"), rs.getLong("goods_receipt_id"), rs.getString("gnumber"),
                        rs.getLong("supplier_id"), rs.getString("sname"), rs.getObject("return_date", LocalDate.class), rs.getLong("warehouse_id"),
                        rs.getString("wname"), rs.getString("currency"), rs.getString("reason"), rs.getString("status"),
                        rs.getBigDecimal("total_excl_vat"), rs.getBigDecimal("vat_total"), rs.getBigDecimal("total_incl_vat"),
                        rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(), lines, (Long) rs.getObject("cid"), rs.getString("cnumber")),
                id, orgId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Goods return"));
    }

    // =============================================================================================
    // A/P credit memos
    // =============================================================================================

    @GetMapping("/supplier-credit-notes")
    List<CreditDto> listCredits(@RequestParam(required = false) Long supplierId) {
        Long orgId = CurrentUser.orgId();
        List<CreditDto> out = new ArrayList<>();
        for (Long id : jdbc.queryForList("SELECT id FROM supplier_credit_note WHERE organization_id = ? AND (?::bigint IS NULL OR supplier_id = ?::bigint) ORDER BY id DESC LIMIT 500",
                Long.class, orgId, supplierId, supplierId)) out.add(credit(orgId, id));
        return out;
    }

    @GetMapping("/supplier-credit-notes/{id}")
    CreditDto getCredit(@PathVariable Long id) {
        return credit(CurrentUser.orgId(), id);
    }

    /**
     * Copy To → A/P Credit Memo from a goods return: its lines at the receipt cost, against the supplier invoice
     * that billed those receipt lines.
     */
    @GetMapping("/supplier-credit-notes/from-goods-return/{returnId}")
    CreditRequest fromReturn(@PathVariable Long returnId) {
        Long orgId = CurrentUser.orgId();
        ReturnDto r = ret(orgId, returnId);
        if (!"POSTED".equals(r.status())) throw ApiException.badRequest("return_cancelled", r.number() + " is cancelled");
        if (r.creditNoteId() != null) throw ApiException.conflict("return_credited", r.number() + " already has credit memo " + r.creditNoteNumber());
        List<Long> invoices = jdbc.queryForList("""
                SELECT DISTINCT v.id FROM supplier_invoice_line sl JOIN supplier_invoice v ON v.id = sl.supplier_invoice_id
                JOIN goods_return_line rl ON rl.goods_receipt_line_id = sl.goods_receipt_line_id
                WHERE rl.goods_return_id = ? AND v.status = 'OPEN' ORDER BY v.id""", Long.class, returnId);
        if (invoices.isEmpty())
            throw ApiException.badRequest("not_invoiced", "The returned items are not on a supplier invoice yet — nothing to credit. "
                    + "Bill only what you kept: the returned quantity is already excluded.");
        return new CreditRequest(invoices.getFirst(), returnId, null, LocalDate.now(InvoiceApiApplication.KSA),
                "Based on Goods Return " + r.number() + ".",
                r.lines().stream().map(l -> new CreditLine(l.productId(), l.name(), l.quantity(), l.unitCost(), l.vatRate())).toList());
    }

    /** Copy To → A/P Credit Memo from a supplier invoice (price correction, discount, damaged goods kept). */
    @GetMapping("/supplier-credit-notes/from-supplier-invoice/{invoiceId}")
    CreditRequest fromInvoice(@PathVariable Long invoiceId) {
        Long orgId = CurrentUser.orgId();
        Map<String, Object> v = invoice(orgId, invoiceId);
        List<CreditLine> lines = jdbc.query("SELECT * FROM supplier_invoice_line WHERE supplier_invoice_id = ? ORDER BY position",
                (rs, i) -> new CreditLine((Long) rs.getObject("product_id"), rs.getString("name"), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("unit_cost"), rs.getBigDecimal("vat_rate")), invoiceId);
        return new CreditRequest(invoiceId, null, null, LocalDate.now(InvoiceApiApplication.KSA), "Based on Supplier Invoice " + v.get("number") + ".", lines);
    }

    @PostMapping("/supplier-credit-notes")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    CreditDto createCredit(@Valid @RequestBody CreditRequest req) {
        Long orgId = CurrentUser.orgId();
        Map<String, Object> v = invoice(orgId, req.supplierInvoiceId());
        if (!"OPEN".equals(v.get("status"))) throw ApiException.badRequest("invoice_cancelled", v.get("number") + " is cancelled");
        Long supplierId = ((Number) v.get("supplier_id")).longValue();
        if (req.goodsReturnId() != null) {
            ReturnDto r = ret(orgId, req.goodsReturnId());
            if (!r.supplierId().equals(supplierId)) throw ApiException.badRequest("other_supplier", r.number() + " is from another supplier");
            if (!"POSTED".equals(r.status())) throw ApiException.badRequest("return_cancelled", r.number() + " is cancelled");
            if (r.creditNoteId() != null) throw ApiException.conflict("return_credited", r.number() + " already has credit memo " + r.creditNoteNumber());
        }
        BigDecimal rate = (BigDecimal) v.get("exchange_rate");
        BigDecimal net = BigDecimal.ZERO, vat = BigDecimal.ZERO;
        for (CreditLine l : req.lines()) {
            BigDecimal ln = l.quantity().multiply(l.unitCost()).setScale(2, RoundingMode.HALF_UP);
            net = net.add(ln);
            vat = vat.add(ln.multiply(rateOf(l)).divide(HUNDRED, 2, RoundingMode.HALF_UP));
        }
        BigDecimal total = net.add(vat), totalSar = total.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        // A credit can't exceed what is still owed on the invoice (payments and earlier credits included).
        BigDecimal owed = ((BigDecimal) v.get("total_sar")).subtract(paid(req.supplierInvoiceId())).subtract(credited(req.supplierInvoiceId()));
        if (totalSar.compareTo(owed) > 0)
            throw ApiException.badRequest("over_credit", "The credit (" + totalSar + " SAR) is more than is still owed on " + v.get("number")
                    + " (" + owed.max(BigDecimal.ZERO) + " SAR). Cancel a payment first, or credit less.");
        String number = stock.nextNumber(orgId, "SUPPLIER_CREDIT_NOTE", "SCN");
        LocalDate date = req.creditDate() == null ? LocalDate.now(InvoiceApiApplication.KSA) : req.creditDate();
        Long id = jdbc.queryForObject("""
                INSERT INTO supplier_credit_note (organization_id, number, supplier_id, supplier_invoice_id, goods_return_id, supplier_ref, credit_date,
                                                  remarks, total_excl_vat, vat_total, total_incl_vat, total_sar, vat_sar, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, orgId, number, supplierId, req.supplierInvoiceId(),
                req.goodsReturnId(), SupplierController.b(req.supplierRef()), date, SupplierController.b(req.remarks()), net, vat, total, totalSar,
                vat.multiply(rate).setScale(2, RoundingMode.HALF_UP), CurrentUser.get().email());
        int pos = 1;
        for (CreditLine l : req.lines())
            jdbc.update("""
                    INSERT INTO supplier_credit_note_line (supplier_credit_note_id, position, product_id, name, quantity, unit_cost, vat_rate)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", id, pos++, l.productId(), l.name().strip(), l.quantity(), l.unitCost(), rateOf(l));
        audit.record(orgId, "SUPPLIER_CREDIT_NOTE_CREATED", "supplier_credit_note", id, number + " on " + v.get("number") + ", " + totalSar + " SAR");
        return credit(orgId, id);
    }

    @PostMapping("/supplier-credit-notes/{id}/cancel")
    @PreAuthorize(WRITE)
    @Transactional
    CreditDto cancelCredit(@PathVariable Long id, @RequestBody(required = false) CancelRequest req) {
        Long orgId = CurrentUser.orgId();
        CreditDto c = credit(orgId, id);
        if ("CANCELLED".equals(c.status())) return c;
        jdbc.update("UPDATE supplier_credit_note SET status = 'CANCELLED' WHERE id = ?", id);
        audit.record(orgId, "SUPPLIER_CREDIT_NOTE_CANCELLED", "supplier_credit_note", id,
                c.number() + (req == null || req.reason() == null ? "" : ": " + req.reason()));
        return credit(orgId, id);
    }

    private BigDecimal paid(Long invoiceId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(a.amount), 0) FROM supplier_payment_allocation a JOIN supplier_payment p ON p.id = a.supplier_payment_id
                WHERE a.supplier_invoice_id = ? AND p.status = 'POSTED'""", BigDecimal.class, invoiceId);
    }

    private BigDecimal credited(Long invoiceId) {
        return jdbc.queryForObject("SELECT COALESCE(sum(total_sar), 0) FROM supplier_credit_note WHERE supplier_invoice_id = ? AND status = 'POSTED'",
                BigDecimal.class, invoiceId);
    }

    private Map<String, Object> invoice(Long orgId, Long invoiceId) {
        return jdbc.queryForList("SELECT * FROM supplier_invoice WHERE id = ? AND organization_id = ?", invoiceId, orgId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Supplier invoice"));
    }

    private CreditDto credit(Long orgId, Long id) {
        List<CreditLine> lines = jdbc.query("SELECT * FROM supplier_credit_note_line WHERE supplier_credit_note_id = ? ORDER BY position",
                (rs, i) -> new CreditLine((Long) rs.getObject("product_id"), rs.getString("name"), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("unit_cost"), rs.getBigDecimal("vat_rate")), id);
        return jdbc.query("""
                SELECT c.*, s.name AS sname, v.number AS vnumber, v.currency, v.exchange_rate, r.number AS rnumber
                FROM supplier_credit_note c JOIN supplier s ON s.id = c.supplier_id JOIN supplier_invoice v ON v.id = c.supplier_invoice_id
                LEFT JOIN goods_return r ON r.id = c.goods_return_id WHERE c.id = ? AND c.organization_id = ?""",
                (rs, i) -> new CreditDto(rs.getLong("id"), rs.getString("number"), rs.getLong("supplier_id"), rs.getString("sname"),
                        rs.getLong("supplier_invoice_id"), rs.getString("vnumber"), (Long) rs.getObject("goods_return_id"), rs.getString("rnumber"),
                        rs.getString("supplier_ref"), rs.getObject("credit_date", LocalDate.class), rs.getString("currency"), rs.getBigDecimal("exchange_rate"),
                        rs.getString("status"), rs.getString("remarks"), rs.getBigDecimal("total_excl_vat"), rs.getBigDecimal("vat_total"),
                        rs.getBigDecimal("total_incl_vat"), rs.getBigDecimal("total_sar"), rs.getBigDecimal("vat_sar"), rs.getString("created_by"),
                        rs.getTimestamp("created_at").toInstant(), lines), id, orgId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Credit memo"));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void requireProduct(Long orgId, Long productId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM product WHERE id = ? AND organization_id = ?", Integer.class, productId, orgId);
        if (n == null || n == 0) throw ApiException.notFound("Product");
    }

    private static BigDecimal rateOf(CreditLine l) {
        return l.vatRate() == null ? new BigDecimal("15") : l.vatRate();
    }

    private static String unit(String u) {
        return u == null || u.isBlank() ? "PCE" : u;
    }
}
