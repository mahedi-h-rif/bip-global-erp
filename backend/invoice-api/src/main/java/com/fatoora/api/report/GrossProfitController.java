package com.fatoora.api.report;

import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Gross profit per document (video §1.1 step 4: "GP value / GP %" on the A/R invoice).
 * <p>Cost of an invoice line = the moving-average value of the stock that actually left for it — on the invoice itself,
 * or on the delivery the line was copied from. Lines without stock movements (services, non-stock items) fall back to
 * the item's current average or standard cost; free-text lines cost nothing.
 */
@RestController
@RequestMapping("/api/v1")
public class GrossProfitController {

    public record Line(String name, BigDecimal quantity, BigDecimal revenue, BigDecimal unitCost, BigDecimal cost,
                       BigDecimal grossProfit, BigDecimal gpPct) {}

    public record Result(String basis, BigDecimal revenue, BigDecimal cost, BigDecimal grossProfit, BigDecimal gpPct, List<Line> lines) {}

    /**
     * One row per issued invoice/credit/debit-note line with its revenue and cost, both signed (credit notes negative).
     * Columns: invoice_id, number, issued_at, customer, product, quantity, revenue, cost.
     */
    static final String LINES_SQL = """
            SELECT i.id AS invoice_id, i.number, i.issued_at, COALESCE(c.name_en, c.name_ar, 'Walk-in customer') AS customer, l.name AS product,
                   s.sign * l.quantity AS quantity, s.sign * round(l.quantity * l.unit_price, 2) AS revenue,
                   CASE WHEN i.doc_type = 'DEBIT_NOTE' OR l.product_id IS NULL THEN 0
                        ELSE s.sign * round(l.quantity * COALESCE(
                            (SELECT sum(m.value) / NULLIF(sum(m.quantity), 0) FROM stock_movement m WHERE m.product_id = l.product_id
                               AND ((m.ref_type = 'invoice' AND m.ref_id = i.id) OR (m.ref_type = 'delivery_note' AND m.ref_id = dl.delivery_note_id))),
                            p.avg_cost, p.cost_price, 0), 2) END AS cost
            FROM invoice_line l JOIN invoice i ON i.id = l.invoice_id
            CROSS JOIN LATERAL (SELECT CASE i.doc_type WHEN 'CREDIT_NOTE' THEN -1 ELSE 1 END AS sign) s
            LEFT JOIN delivery_note_line dl ON dl.id = l.delivery_note_line_id
            LEFT JOIN product p ON p.id = l.product_id LEFT JOIN customer c ON c.id = i.customer_id
            """;

    private final JdbcTemplate jdbc;

    public GrossProfitController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/invoices/{id}/gross-profit")
    Result invoice(@PathVariable Long id) {
        Long orgId = CurrentUser.orgId();
        Integer n = jdbc.queryForObject("SELECT count(*) FROM invoice WHERE id = ? AND organization_id = ?", Integer.class, id, orgId);
        if (n == null || n == 0) throw ApiException.notFound("Invoice");
        List<Line> lines = jdbc.query(LINES_SQL + " WHERE i.id = ? ORDER BY l.position", (rs, i) ->
                line(rs.getString("product"), rs.getBigDecimal("quantity"), rs.getBigDecimal("revenue"), rs.getBigDecimal("cost")), id);
        return result("Actual cost of the stock that left (moving average)", lines);
    }

    /** An order has not shipped yet, so its gross profit is an estimate at today's average cost. */
    @GetMapping("/sales-orders/{id}/gross-profit")
    Result salesOrder(@PathVariable Long id) {
        Long orgId = CurrentUser.orgId();
        Integer n = jdbc.queryForObject("SELECT count(*) FROM sales_order WHERE id = ? AND organization_id = ?", Integer.class, id, orgId);
        if (n == null || n == 0) throw ApiException.notFound("Sales order");
        List<Line> lines = jdbc.query("""
                SELECT l.name, l.quantity, round(l.quantity * l.unit_price * (1 - l.discount_pct / 100), 2) AS revenue,
                       CASE WHEN l.product_id IS NULL THEN 0 ELSE round(l.quantity * COALESCE(p.avg_cost, p.cost_price, 0), 2) END AS cost
                FROM sales_order_line l LEFT JOIN product p ON p.id = l.product_id WHERE l.sales_order_id = ? ORDER BY l.position""",
                (rs, i) -> line(rs.getString("name"), rs.getBigDecimal("quantity"), rs.getBigDecimal("revenue"), rs.getBigDecimal("cost")), id);
        return result("Estimate at today's average cost", lines);
    }

    static BigDecimal pct(BigDecimal gp, BigDecimal revenue) {
        return revenue.signum() == 0 ? BigDecimal.ZERO : gp.multiply(new BigDecimal("100")).divide(revenue.abs(), 2, RoundingMode.HALF_UP);
    }

    private static Line line(String name, BigDecimal qty, BigDecimal revenue, BigDecimal cost) {
        BigDecimal gp = revenue.subtract(cost);
        BigDecimal unit = qty.signum() == 0 ? BigDecimal.ZERO : cost.divide(qty, 4, RoundingMode.HALF_UP);
        return new Line(name, qty, revenue, unit, cost, gp, pct(gp, revenue));
    }

    private static Result result(String basis, List<Line> lines) {
        BigDecimal rev = BigDecimal.ZERO, cost = BigDecimal.ZERO;
        for (Line l : lines) { rev = rev.add(l.revenue()); cost = cost.add(l.cost()); }
        BigDecimal gp = rev.subtract(cost);
        return new Result(basis, rev, cost, gp, pct(gp, rev), new ArrayList<>(lines));
    }
}
