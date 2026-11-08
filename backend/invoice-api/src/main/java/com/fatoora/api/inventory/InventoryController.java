package com.fatoora.api.inventory;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Warehouses, stock levels, the movement ledger, manual adjustments and transfers between warehouses. */
@RestController
@RequestMapping("/api/v1")
public class InventoryController {

    private static final String WRITE = "hasAnyRole('ADMIN','ACCOUNTANT')";

    public record WarehouseDto(Long id, @NotBlank @Size(max = 20) String code, @NotBlank @Size(max = 200) String name,
                               @Size(max = 500) String location, Boolean isDefault, Boolean active, BigDecimal stockValue) {}

    public record StockRow(Long productId, String productNumber, String productName, String unitCode,
                           Long warehouseId, String warehouseName, BigDecimal quantity, BigDecimal costPrice,
                           BigDecimal value, BigDecimal lowStockThreshold, boolean low) {}

    public record MovementRow(Long id, Instant at, Long productId, String productName, Long warehouseId, String warehouseName,
                              BigDecimal quantity, BigDecimal balanceAfter, String kind, String refType, Long refId,
                              String refNumber, String note, String createdBy, BigDecimal unitCost, BigDecimal value) {}

    /** {@code quantity} is the change (+ in, − out); {@code countedQuantity} instead sets the level to a physical count. */
    public record AdjustRequest(@NotNull Long productId, @NotNull Long warehouseId, BigDecimal quantity,
                                BigDecimal countedQuantity, @Size(max = 1000) String note, Boolean opening,
                                /** SAR cost of stock added from outside (opening stock); re-averages the item cost. */
                                @DecimalMin("0") BigDecimal unitCost) {}

    public record TransferLine(@NotNull Long productId, String productName, @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity) {}

    public record TransferRequest(@NotNull Long fromWarehouseId, @NotNull Long toWarehouseId, LocalDate transferDate,
                                  @Size(max = 1000) String note, @NotEmpty List<@Valid TransferLine> lines) {}

    public record TransferDto(Long id, String number, Long fromWarehouseId, String fromWarehouse, Long toWarehouseId,
                              String toWarehouse, LocalDate transferDate, String note, String createdBy, Instant createdAt,
                              List<TransferLine> lines) {}

    private final JdbcTemplate jdbc;
    private final StockService stock;
    private final AuditService audit;

    public InventoryController(JdbcTemplate jdbc, StockService stock, AuditService audit) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.audit = audit;
    }

    // --- warehouses --------------------------------------------------------------------------------

    @GetMapping("/warehouses")
    @Transactional
    List<WarehouseDto> warehouses(@RequestParam(defaultValue = "false") boolean all) {
        Long orgId = CurrentUser.orgId();
        stock.defaultWarehouse(orgId);
        return jdbc.query("""
                SELECT w.*, COALESCE((SELECT sum(s.quantity * COALESCE(p.avg_cost, p.cost_price, 0)) FROM stock_level s
                                      JOIN product p ON p.id = s.product_id WHERE s.warehouse_id = w.id), 0) AS value
                FROM warehouse w WHERE w.organization_id = ? AND (? OR w.active) ORDER BY w.is_default DESC, w.name""",
                (rs, i) -> new WarehouseDto(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("location"), rs.getBoolean("is_default"), rs.getBoolean("active"), rs.getBigDecimal("value")),
                orgId, all);
    }

    @PostMapping("/warehouses")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    WarehouseDto createWarehouse(@Valid @RequestBody WarehouseDto dto) {
        Long orgId = CurrentUser.orgId();
        requireUniqueCode(orgId, dto.code(), null);
        if (Boolean.TRUE.equals(dto.isDefault())) jdbc.update("UPDATE warehouse SET is_default = FALSE WHERE organization_id = ?", orgId);
        Long id = jdbc.queryForObject("""
                INSERT INTO warehouse (organization_id, code, name, location, is_default) VALUES (?, ?, ?, ?, ?) RETURNING id""",
                Long.class, orgId, dto.code().trim().toUpperCase(), dto.name().trim(), blank(dto.location()),
                Boolean.TRUE.equals(dto.isDefault()));
        audit.record(orgId, "WAREHOUSE_CREATED", "warehouse", id, dto.code() + " " + dto.name());
        return warehouse(orgId, id);
    }

    @PutMapping("/warehouses/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    WarehouseDto updateWarehouse(@PathVariable Long id, @Valid @RequestBody WarehouseDto dto) {
        Long orgId = CurrentUser.orgId();
        stock.requireWarehouse(orgId, id);
        requireUniqueCode(orgId, dto.code(), id);
        if (Boolean.TRUE.equals(dto.isDefault())) jdbc.update("UPDATE warehouse SET is_default = FALSE WHERE organization_id = ?", orgId);
        boolean active = dto.active() == null || dto.active();
        if (!active) {
            BigDecimal held = jdbc.queryForObject("SELECT COALESCE(sum(abs(quantity)), 0) FROM stock_level WHERE warehouse_id = ?",
                    BigDecimal.class, id);
            if (held.signum() != 0)
                throw ApiException.badRequest("warehouse_not_empty", "Move or adjust the stock out of this warehouse before deactivating it");
        }
        jdbc.update("UPDATE warehouse SET code = ?, name = ?, location = ?, active = ?, is_default = (is_default OR ?) WHERE id = ?",
                dto.code().trim().toUpperCase(), dto.name().trim(), blank(dto.location()), active,
                Boolean.TRUE.equals(dto.isDefault()), id);
        audit.record(orgId, "WAREHOUSE_UPDATED", "warehouse", id, dto.code() + " " + dto.name());
        return warehouse(orgId, id);
    }

    // --- stock levels and ledger -------------------------------------------------------------------

    /** One row per stock-tracked product and warehouse. {@code low=true} keeps only rows at or below the threshold. */
    @GetMapping("/stock")
    List<StockRow> levels(@RequestParam(required = false) Long warehouseId, @RequestParam(required = false) String q,
                          @RequestParam(defaultValue = "false") boolean low) {
        Long orgId = CurrentUser.orgId();
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        List<StockRow> rows = jdbc.query("""
                SELECT p.id AS pid, p.number, p.name AS pname, p.unit_code, COALESCE(p.avg_cost, p.cost_price) AS cost_price, p.low_stock_threshold,
                       w.id AS wid, w.name AS wname, COALESCE(s.quantity, 0) AS qty
                FROM product p CROSS JOIN warehouse w
                LEFT JOIN stock_level s ON s.product_id = p.id AND s.warehouse_id = w.id
                WHERE p.organization_id = ? AND w.organization_id = ? AND p.track_stock AND p.active AND w.active
                  AND (?::bigint IS NULL OR w.id = ?::bigint)
                  AND (?::text IS NULL OR lower(p.name) LIKE ?::text OR lower(p.number) LIKE ?::text)
                ORDER BY lower(p.name), w.is_default DESC, w.name""",
                InventoryController::stockRow, orgId, orgId, warehouseId, warehouseId, like, like, like);
        return low ? rows.stream().filter(StockRow::low).toList() : rows;
    }

    @GetMapping("/stock/movements")
    List<MovementRow> movements(@RequestParam(required = false) Long productId, @RequestParam(required = false) Long warehouseId,
                                @RequestParam(defaultValue = "200") int limit) {
        return jdbc.query("""
                SELECT m.*, p.name AS pname, w.name AS wname FROM stock_movement m
                JOIN product p ON p.id = m.product_id JOIN warehouse w ON w.id = m.warehouse_id
                WHERE m.organization_id = ? AND (?::bigint IS NULL OR m.product_id = ?::bigint)
                  AND (?::bigint IS NULL OR m.warehouse_id = ?::bigint)
                ORDER BY m.created_at DESC, m.id DESC LIMIT ?""",
                (rs, i) -> new MovementRow(rs.getLong("id"), rs.getTimestamp("created_at").toInstant(), rs.getLong("product_id"),
                        rs.getString("pname"), rs.getLong("warehouse_id"), rs.getString("wname"), rs.getBigDecimal("quantity"),
                        rs.getBigDecimal("balance_after"), rs.getString("kind"), rs.getString("ref_type"),
                        (Long) rs.getObject("ref_id"), rs.getString("ref_number"), rs.getString("note"), rs.getString("created_by"),
                        rs.getBigDecimal("unit_cost"), rs.getBigDecimal("value")),
                CurrentUser.orgId(), productId, productId, warehouseId, warehouseId, Math.min(Math.max(limit, 1), 1000));
    }

    @PostMapping("/stock/adjust")
    @PreAuthorize(WRITE)
    @Transactional
    List<StockRow> adjust(@Valid @RequestBody AdjustRequest req) {
        Long orgId = CurrentUser.orgId();
        requireTrackedProduct(orgId, req.productId());
        stock.requireWarehouse(orgId, req.warehouseId());
        BigDecimal delta;
        if (req.countedQuantity() != null) {
            BigDecimal current = jdbc.queryForList("SELECT quantity FROM stock_level WHERE warehouse_id = ? AND product_id = ?",
                    BigDecimal.class, req.warehouseId(), req.productId()).stream().findFirst().orElse(BigDecimal.ZERO);
            delta = req.countedQuantity().subtract(current);
        } else if (req.quantity() != null) {
            delta = req.quantity();
        } else {
            throw new ApiException.Validation(Map.of("quantity", "Enter the change or the counted quantity"));
        }
        if (delta.signum() == 0) return levels(req.warehouseId(), null, false);
        StockService.Kind kind = Boolean.TRUE.equals(req.opening()) ? StockService.Kind.OPENING : StockService.Kind.ADJUST;
        stock.apply(orgId, List.of(new StockService.Move(req.productId(), req.warehouseId(), delta, kind,
                "adjustment", null, null, blank(req.note()), delta.signum() > 0 ? req.unitCost() : null)), false, CurrentUser.get().email());
        audit.record(orgId, "STOCK_ADJUSTED", "product", req.productId(),
                kind + " " + StockService.plain(delta) + " @ warehouse " + req.warehouseId() + (req.note() == null ? "" : ": " + req.note()));
        return levels(req.warehouseId(), null, false);
    }

    // --- inventory audit report (moving-average valuation ledger) ---------------------------------

    public record AuditRow(Instant systemDate, LocalDate postingDate, String document, String kind, String warehouseName,
                           BigDecimal quantity, BigDecimal cost, BigDecimal transValue, BigDecimal cumulativeQty, BigDecimal cumulativeValue) {}

    public record AuditReport(Long productId, String productName, String productNumber, BigDecimal openingQty, BigDecimal openingValue,
                              List<AuditRow> rows, BigDecimal closingQty, BigDecimal closingValue) {}

    /** SAP-style inventory audit: every movement of one item with its cost and the running quantity and value. */
    @GetMapping("/stock/audit")
    AuditReport audit(@RequestParam Long productId, @RequestParam(required = false) Long warehouseId,
                      @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        Long orgId = CurrentUser.orgId();
        Map<String, Object> p = jdbc.queryForList("SELECT name, number FROM product WHERE id = ? AND organization_id = ?", productId, orgId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Product"));
        java.sql.Timestamp start = from == null ? null : java.sql.Timestamp.from(from.atStartOfDay(com.fatoora.api.InvoiceApiApplication.KSA).toInstant());
        java.sql.Timestamp end = to == null ? null : java.sql.Timestamp.from(to.plusDays(1).atStartOfDay(com.fatoora.api.InvoiceApiApplication.KSA).toInstant());
        Map<String, Object> opening = jdbc.queryForMap("""
                SELECT COALESCE(sum(quantity), 0) AS q, COALESCE(sum(value), 0) AS v FROM stock_movement
                WHERE organization_id = ? AND product_id = ? AND (?::bigint IS NULL OR warehouse_id = ?::bigint)
                  AND ?::timestamptz IS NOT NULL AND created_at < ?::timestamptz""", orgId, productId, warehouseId, warehouseId, start, start);
        BigDecimal cq = (BigDecimal) opening.get("q"), cv = (BigDecimal) opening.get("v");
        BigDecimal oq = cq, ov = cv;
        List<AuditRow> rows = new ArrayList<>();
        for (Map<String, Object> m : jdbc.queryForList("""
                SELECT m.*, w.name AS wname FROM stock_movement m JOIN warehouse w ON w.id = m.warehouse_id
                WHERE m.organization_id = ? AND m.product_id = ? AND (?::bigint IS NULL OR m.warehouse_id = ?::bigint)
                  AND (?::timestamptz IS NULL OR m.created_at >= ?::timestamptz) AND (?::timestamptz IS NULL OR m.created_at < ?::timestamptz)
                ORDER BY m.created_at, m.id""", orgId, productId, warehouseId, warehouseId, start, start, end, end)) {
            BigDecimal q = (BigDecimal) m.get("quantity");
            BigDecimal v = m.get("value") == null ? BigDecimal.ZERO : (BigDecimal) m.get("value");
            cq = cq.add(q);
            cv = cv.add(v);
            Instant at = ((java.sql.Timestamp) m.get("created_at")).toInstant();
            String doc = m.get("ref_number") != null ? (String) m.get("ref_number") : String.valueOf(m.get("kind"));
            rows.add(new AuditRow(at, at.atZone(com.fatoora.api.InvoiceApiApplication.KSA).toLocalDate(), doc, (String) m.get("kind"),
                    (String) m.get("wname"), q, (BigDecimal) m.get("unit_cost"), v, cq, cv));
        }
        return new AuditReport(productId, (String) p.get("name"), (String) p.get("number"), oq, ov, rows, cq, cv);
    }

    @GetMapping("/stock/audit.xlsx")
    org.springframework.http.ResponseEntity<byte[]> auditExcel(@RequestParam Long productId, @RequestParam(required = false) Long warehouseId,
                                                               @RequestParam(required = false) LocalDate from,
                                                               @RequestParam(required = false) LocalDate to) throws java.io.IOException {
        AuditReport r = audit(productId, warehouseId, from, to);
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out = new java.io.ByteArrayOutputStream()) {
            var sh = wb.createSheet("Inventory audit");
            String[] head = {"Posting date", "Document", "Type", "Warehouse", "Quantity", "Cost", "Trans. value", "Cumulative qty", "Cumulative value"};
            var hr = sh.createRow(0);
            for (int i = 0; i < head.length; i++) hr.createCell(i).setCellValue(head[i]);
            int n = 1;
            var open = sh.createRow(n++);
            open.createCell(1).setCellValue("Opening balance");
            open.createCell(7).setCellValue(r.openingQty().doubleValue());
            open.createCell(8).setCellValue(r.openingValue().doubleValue());
            for (AuditRow a : r.rows()) {
                var row = sh.createRow(n++);
                row.createCell(0).setCellValue(a.postingDate().toString());
                row.createCell(1).setCellValue(a.document());
                row.createCell(2).setCellValue(a.kind());
                row.createCell(3).setCellValue(a.warehouseName());
                row.createCell(4).setCellValue(a.quantity().doubleValue());
                if (a.cost() != null) row.createCell(5).setCellValue(a.cost().doubleValue());
                row.createCell(6).setCellValue(a.transValue().doubleValue());
                row.createCell(7).setCellValue(a.cumulativeQty().doubleValue());
                row.createCell(8).setCellValue(a.cumulativeValue().doubleValue());
            }
            for (int i = 0; i < head.length; i++) sh.autoSizeColumn(i);
            wb.write(out);
            String name = "inventory-audit-" + (r.productNumber() == null ? r.productId() : r.productNumber()) + ".xlsx";
            return org.springframework.http.ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + name).body(out.toByteArray());
        }
    }

    // --- transfers ---------------------------------------------------------------------------------

    @GetMapping("/stock/transfers")
    List<TransferDto> transfers() {
        Long orgId = CurrentUser.orgId();
        List<Long> ids = jdbc.queryForList("SELECT id FROM stock_transfer WHERE organization_id = ? ORDER BY id DESC LIMIT 500",
                Long.class, orgId);
        List<TransferDto> out = new ArrayList<>();
        for (Long id : ids) out.add(transfer(orgId, id));
        return out;
    }

    @GetMapping("/stock/transfers/{id}")
    TransferDto getTransfer(@PathVariable Long id) {
        return transfer(CurrentUser.orgId(), id);
    }

    @PostMapping("/stock/transfers")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    TransferDto createTransfer(@Valid @RequestBody TransferRequest req) {
        Long orgId = CurrentUser.orgId();
        if (req.fromWarehouseId().equals(req.toWarehouseId()))
            throw new ApiException.Validation(Map.of("toWarehouseId", "Choose a different destination warehouse"));
        stock.requireWarehouse(orgId, req.fromWarehouseId());
        stock.requireWarehouse(orgId, req.toWarehouseId());
        for (TransferLine l : req.lines()) requireTrackedProduct(orgId, l.productId());
        String number = stock.nextNumber(orgId, "STOCK_TRANSFER", "TR");
        String actor = CurrentUser.get().email();
        Long id = jdbc.queryForObject("""
                INSERT INTO stock_transfer (organization_id, number, from_warehouse_id, to_warehouse_id, transfer_date, note, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, orgId, number, req.fromWarehouseId(), req.toWarehouseId(),
                req.transferDate() == null ? LocalDate.now(InvoiceApiApplication.KSA) : req.transferDate(), blank(req.note()), actor);
        List<StockService.Move> moves = new ArrayList<>();
        for (TransferLine l : req.lines()) {
            jdbc.update("INSERT INTO stock_transfer_line (transfer_id, product_id, quantity) VALUES (?, ?, ?)", id, l.productId(), l.quantity());
            moves.add(new StockService.Move(l.productId(), req.fromWarehouseId(), l.quantity().negate(),
                    StockService.Kind.TRANSFER_OUT, "transfer", id, number, null));
            moves.add(new StockService.Move(l.productId(), req.toWarehouseId(), l.quantity(),
                    StockService.Kind.TRANSFER_IN, "transfer", id, number, null));
        }
        stock.apply(orgId, moves, false, actor);
        audit.record(orgId, "STOCK_TRANSFERRED", "stock_transfer", id, number + ", " + req.lines().size() + " line(s)");
        return transfer(orgId, id);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private TransferDto transfer(Long orgId, Long id) {
        List<TransferLine> lines = jdbc.query("""
                SELECT l.product_id, p.name, l.quantity FROM stock_transfer_line l JOIN product p ON p.id = l.product_id
                WHERE l.transfer_id = ? ORDER BY l.id""",
                (rs, i) -> new TransferLine(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3)), id);
        return jdbc.query("""
                SELECT t.*, f.name AS fname, d.name AS tname FROM stock_transfer t
                JOIN warehouse f ON f.id = t.from_warehouse_id JOIN warehouse d ON d.id = t.to_warehouse_id
                WHERE t.id = ? AND t.organization_id = ?""",
                (rs, i) -> new TransferDto(rs.getLong("id"), rs.getString("number"), rs.getLong("from_warehouse_id"),
                        rs.getString("fname"), rs.getLong("to_warehouse_id"), rs.getString("tname"),
                        rs.getObject("transfer_date", LocalDate.class), rs.getString("note"), rs.getString("created_by"),
                        rs.getTimestamp("created_at").toInstant(), lines), id, orgId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Transfer"));
    }

    private WarehouseDto warehouse(Long orgId, Long id) {
        return warehouses(true).stream().filter(w -> w.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Warehouse"));
    }

    private void requireUniqueCode(Long orgId, String code, Long exceptId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM warehouse WHERE organization_id = ? AND lower(code) = lower(?) AND (?::bigint IS NULL OR id <> ?::bigint)",
                Integer.class, orgId, code.trim(), exceptId, exceptId);
        if (n != null && n > 0) throw new ApiException.Validation(Map.of("code", "A warehouse with this code already exists"));
    }

    private void requireTrackedProduct(Long orgId, Long productId) {
        List<Boolean> t = jdbc.queryForList("SELECT track_stock FROM product WHERE id = ? AND organization_id = ?",
                Boolean.class, productId, orgId);
        if (t.isEmpty()) throw ApiException.notFound("Product");
        if (!t.getFirst())
            throw ApiException.badRequest("not_stock_item", "This product does not track stock. Turn on \"Track stock\" on the product first.");
    }

    private static StockRow stockRow(ResultSet rs, int i) throws SQLException {
        BigDecimal qty = rs.getBigDecimal("qty");
        BigDecimal cost = rs.getBigDecimal("cost_price");
        BigDecimal threshold = rs.getBigDecimal("low_stock_threshold");
        return new StockRow(rs.getLong("pid"), rs.getString("number"), rs.getString("pname"), rs.getString("unit_code"),
                rs.getLong("wid"), rs.getString("wname"), qty, cost,
                cost == null ? BigDecimal.ZERO : qty.multiply(cost).setScale(2, java.math.RoundingMode.HALF_UP),
                threshold, threshold != null && qty.compareTo(threshold) <= 0);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
