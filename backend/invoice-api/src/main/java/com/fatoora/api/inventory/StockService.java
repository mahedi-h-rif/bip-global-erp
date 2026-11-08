package com.fatoora.api.inventory;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.invoice.Invoice;
import com.fatoora.api.invoice.InvoiceLineEntity;
import com.fatoora.zatca.model.DocumentType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The only writer of {@code stock_level}. Every change also writes a {@code stock_movement} row,
 * so a product's level is always the sum of its ledger.
 */
@Service
public class StockService {

    public enum Kind { OPENING, ADJUST, SALE, RETURN, TRANSFER_IN, TRANSFER_OUT, DELIVERY, RECEIPT }

    private final JdbcTemplate jdbc;

    public StockService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One stock change. {@code quantity} is signed (+ in, − out). Returns the new level. */
    public record Move(Long productId, Long warehouseId, BigDecimal quantity, Kind kind,
                       String refType, Long refId, String refNumber, String note) {}

    /**
     * Applies the moves atomically. With {@code allowNegative=false}, any move that would take a level below
     * zero aborts the whole batch with a validation error naming the product and what is available.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(Long orgId, List<Move> moves, boolean allowNegative, String actor) {
        for (Move m : moves) {
            if (m.quantity().signum() == 0) continue;
            requireWarehouse(orgId, m.warehouseId());
            // Lock the row (creating it first if needed) so concurrent sales cannot both take the last unit.
            jdbc.update("INSERT INTO stock_level (warehouse_id, product_id, quantity) VALUES (?, ?, 0) ON CONFLICT DO NOTHING",
                    m.warehouseId(), m.productId());
            BigDecimal current = jdbc.queryForObject(
                    "SELECT quantity FROM stock_level WHERE warehouse_id = ? AND product_id = ? FOR UPDATE",
                    BigDecimal.class, m.warehouseId(), m.productId());
            BigDecimal after = current.add(m.quantity());
            if (!allowNegative && after.signum() < 0) {
                String name = jdbc.queryForObject("SELECT name FROM product WHERE id = ?", String.class, m.productId());
                String wh = jdbc.queryForObject("SELECT name FROM warehouse WHERE id = ?", String.class, m.warehouseId());
                throw ApiException.badRequest("insufficient_stock", "Not enough stock of \"%s\" in %s: %s available, %s needed"
                        .formatted(name, wh, plain(current), plain(m.quantity().negate())));
            }
            jdbc.update("UPDATE stock_level SET quantity = ? WHERE warehouse_id = ? AND product_id = ?",
                    after, m.warehouseId(), m.productId());
            jdbc.update("""
                    INSERT INTO stock_movement (organization_id, product_id, warehouse_id, quantity, balance_after, kind,
                                                ref_type, ref_id, ref_number, note, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    orgId, m.productId(), m.warehouseId(), m.quantity(), after, m.kind().name(),
                    m.refType(), m.refId(), m.refNumber(), m.note(), actor);
        }
    }

    /**
     * Called inside the signing transaction, before the invoice number is consumed: an invoice takes its
     * stock-tracked lines out of its warehouse, a credit note puts them back. Debit notes change prices only.
     * Insufficient stock aborts the issue, so no number or ICV is used.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onIssue(Invoice inv, String actor) {
        if (inv.getType() == DocumentType.DEBIT_NOTE) return;
        Map<Long, BigDecimal> byProduct = new LinkedHashMap<>();
        for (InvoiceLineEntity l : inv.getLines())
            if (l.getProductId() != null && tracked(l.getProductId()))
                byProduct.merge(l.getProductId(), l.getQuantity(), BigDecimal::add);
        if (byProduct.isEmpty()) return;

        Long warehouseId = inv.getWarehouseId();
        if (warehouseId == null && inv.getOriginalInvoiceId() != null)
            warehouseId = jdbc.queryForList("SELECT warehouse_id FROM invoice WHERE id = ?", Long.class, inv.getOriginalInvoiceId())
                    .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (warehouseId == null) warehouseId = defaultWarehouse(inv.getOrganizationId());
        inv.setWarehouseId(warehouseId);

        boolean credit = inv.getType() == DocumentType.CREDIT_NOTE;
        Long wh = warehouseId;
        List<Move> moves = byProduct.entrySet().stream().map(e -> new Move(e.getKey(), wh,
                credit ? e.getValue() : e.getValue().negate(), credit ? Kind.RETURN : Kind.SALE,
                "invoice", inv.getId(), null, null)).toList();
        apply(inv.getOrganizationId(), moves, credit, actor);
    }

    /** After signing assigns the number, label this invoice's movements with it. */
    public void labelInvoiceMovements(Long invoiceId, String number) {
        jdbc.update("UPDATE stock_movement SET ref_number = ? WHERE ref_type = 'invoice' AND ref_id = ?", number, invoiceId);
    }

    public boolean tracked(Long productId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT track_stock FROM product WHERE id = ?", Boolean.class, productId));
    }

    /** The organization's default warehouse, creating "Main warehouse" on first use. */
    public Long defaultWarehouse(Long orgId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM warehouse WHERE organization_id = ? AND active ORDER BY is_default DESC, id LIMIT 1", Long.class, orgId);
        if (!ids.isEmpty()) return ids.getFirst();
        return jdbc.queryForObject("""
                INSERT INTO warehouse (organization_id, code, name, is_default) VALUES (?, 'MAIN', 'Main warehouse', TRUE)
                RETURNING id""", Long.class, orgId);
    }

    public void requireWarehouse(Long orgId, Long warehouseId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM warehouse WHERE id = ? AND organization_id = ?",
                Integer.class, warehouseId, orgId);
        if (n == null || n == 0) throw ApiException.notFound("Warehouse");
    }

    /** Gap-free per organization, kind and year, e.g. DN-2026-000001. */
    public String nextNumber(Long orgId, String docType, String prefix) {
        int year = LocalDate.now(InvoiceApiApplication.KSA).getYear();
        Long v = jdbc.queryForObject("""
                INSERT INTO document_sequence (organization_id, doc_type, year, next_value) VALUES (?, ?, ?, 2)
                ON CONFLICT (organization_id, doc_type, year)
                DO UPDATE SET next_value = document_sequence.next_value + 1
                RETURNING next_value - 1""", Long.class, orgId, docType, year);
        return "%s-%d-%06d".formatted(prefix, year, v);
    }

    static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
