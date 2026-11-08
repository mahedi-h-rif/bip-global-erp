package com.fatoora.api.purchase;

import com.fatoora.api.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Replenishment (Odoo reordering rules): stock items at or below their low-stock alert, with a suggested quantity
 * (back up to twice the alert level, net of what is already on open purchase orders), turned into one requisition.
 */
@RestController
@RequestMapping("/api/v1/reorder")
public class ReorderController {

    public record Suggestion(Long productId, String number, String name, String unitCode, BigDecimal onHand, BigDecimal onOrder,
                             BigDecimal threshold, BigDecimal suggested, BigDecimal lastCost) {}

    public record Line(@NotNull Long productId, @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity) {}

    public record ToPo(@NotNull Long supplierId, Long warehouseId, @NotEmpty List<@Valid Line> lines) {}

    private final JdbcTemplate jdbc;
    private final PurchaseController purchases;

    public ReorderController(JdbcTemplate jdbc, PurchaseController purchases) {
        this.jdbc = jdbc;
        this.purchases = purchases;
    }

    @GetMapping
    List<Suggestion> suggestions() {
        return jdbc.query("""
                SELECT p.id, p.number, p.name, p.unit_code, p.low_stock_threshold AS th, COALESCE(p.avg_cost, p.cost_price, 0) AS cost,
                       COALESCE((SELECT sum(s.quantity) FROM stock_level s JOIN warehouse w ON w.id = s.warehouse_id WHERE s.product_id = p.id), 0) AS on_hand,
                       COALESCE((SELECT sum(l.quantity) FROM purchase_order_line l JOIN purchase_order o ON o.id = l.purchase_order_id
                                 WHERE l.product_id = p.id AND o.status IN ('PENDING','APPROVED','PARTIALLY_RECEIVED')), 0)
                       - COALESCE((SELECT sum(g.quantity) FROM goods_receipt_line g JOIN purchase_order_line l ON l.id = g.purchase_order_line_id
                                   JOIN purchase_order o ON o.id = l.purchase_order_id JOIN goods_receipt r ON r.id = g.goods_receipt_id
                                   WHERE l.product_id = p.id AND o.status IN ('PENDING','APPROVED','PARTIALLY_RECEIVED') AND r.status = 'POSTED'), 0) AS on_order
                FROM product p WHERE p.organization_id = ? AND p.active AND p.track_stock AND p.is_purchase_item AND p.low_stock_threshold IS NOT NULL""",
                (rs, i) -> {
                    BigDecimal onHand = rs.getBigDecimal("on_hand"), onOrder = rs.getBigDecimal("on_order").max(BigDecimal.ZERO), th = rs.getBigDecimal("th");
                    BigDecimal suggested = th.multiply(BigDecimal.TWO).subtract(onHand).subtract(onOrder).max(BigDecimal.ZERO);
                    return new Suggestion(rs.getLong("id"), rs.getString("number"), rs.getString("name"), rs.getString("unit_code"), onHand, onOrder,
                            th, suggested, rs.getBigDecimal("cost"));
                }, CurrentUser.orgId()).stream().filter(s -> s.onHand().add(s.onOrder()).compareTo(s.threshold()) <= 0 && s.suggested().signum() > 0).toList();
    }

    /** One pending requisition for the chosen supplier with the chosen quantities, at the items' current average cost. */
    @PostMapping("/purchase-order")
    @PreAuthorize(SupplierController.WRITE)
    @Transactional
    PurchaseController.PoDto toPurchaseOrder(@Valid @RequestBody ToPo req) {
        String currency = jdbc.queryForList("SELECT currency FROM supplier WHERE id = ? AND organization_id = ?", String.class, req.supplierId(), CurrentUser.orgId())
                .stream().findFirst().orElse("SAR");
        List<PurchaseController.PoLine> lines = req.lines().stream().map(l -> {
            var p = jdbc.queryForMap("SELECT name, unit_code, COALESCE(avg_cost, cost_price, 0) AS cost FROM product WHERE id = ? AND organization_id = ?",
                    l.productId(), CurrentUser.orgId());
            return new PurchaseController.PoLine(null, l.productId(), (String) p.get("name"), l.quantity(), (String) p.get("unit_code"),
                    "SAR".equals(currency) ? (BigDecimal) p.get("cost") : BigDecimal.ZERO, new BigDecimal("15"), null, null);
        }).toList();
        return purchases.createPo(new PurchaseController.PoRequest(req.supplierId(), null, null, null, req.warehouseId(), currency, BigDecimal.ONE,
                "Replenishment of low-stock items", lines));
    }
}
