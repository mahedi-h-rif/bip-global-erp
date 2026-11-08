package com.fatoora.api.flow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** Quantities already copied along the chain, and the statuses that follow from them. */
@Service
public class FlowService {

    private final JdbcTemplate jdbc;

    public FlowService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Per quotation line: quantity already on non-cancelled sales orders (optionally ignoring one order). */
    public Map<Long, BigDecimal> orderedByQuotationLine(Long quotationId, Long exceptOrderId) {
        return sums("""
                SELECT l.quotation_line_id, sum(l.quantity) FROM sales_order_line l JOIN sales_order o ON o.id = l.sales_order_id
                WHERE o.quotation_id = ? AND o.status <> 'CANCELLED' AND (?::bigint IS NULL OR o.id <> ?::bigint)
                  AND l.quotation_line_id IS NOT NULL GROUP BY l.quotation_line_id""", quotationId, exceptOrderId, exceptOrderId);
    }

    /** Per sales-order line: quantity on non-cancelled delivery notes (drafts reserve it too). */
    public Map<Long, BigDecimal> deliveredBySalesOrderLine(Long salesOrderId, Long exceptNoteId, boolean deliveredOnly) {
        return sums("""
                SELECT l.sales_order_line_id, sum(l.quantity) FROM delivery_note_line l JOIN delivery_note n ON n.id = l.delivery_note_id
                WHERE n.sales_order_id = ? AND n.status <> 'CANCELLED' AND (NOT ? OR n.status = 'DELIVERED')
                  AND (?::bigint IS NULL OR n.id <> ?::bigint) AND l.sales_order_line_id IS NOT NULL
                GROUP BY l.sales_order_line_id""", salesOrderId, deliveredOnly, exceptNoteId, exceptNoteId);
    }

    /** Per delivery line: quantity already on invoices that count (issued, not rejected) or on another open draft. */
    public Map<Long, BigDecimal> invoicedByDeliveryLine(Long deliveryNoteId, Long exceptInvoiceId) {
        return sums("""
                SELECT il.delivery_note_line_id, sum(il.quantity) FROM invoice_line il JOIN invoice i ON i.id = il.invoice_id
                JOIN delivery_note_line dl ON dl.id = il.delivery_note_line_id
                WHERE dl.delivery_note_id = ? AND i.doc_type = 'INVOICE' AND i.status <> 'REJECTED'
                  AND (?::bigint IS NULL OR i.id <> ?::bigint) GROUP BY il.delivery_note_line_id""",
                deliveryNoteId, exceptInvoiceId, exceptInvoiceId);
    }

    /**
     * Before an invoice is issued: every line copied from a delivery must point at a delivered note, and the quantity
     * billed for that delivery line over all invoices must not exceed what was delivered.
     */
    public void checkDeliveryLines(com.fatoora.api.invoice.Invoice inv) {
        Map<Long, BigDecimal> asked = new HashMap<>();
        for (var l : inv.getLines())
            if (l.getDeliveryLineId() != null) asked.merge(l.getDeliveryLineId(), l.getQuantity(), BigDecimal::add);
        for (var e : asked.entrySet()) {
            var row = jdbc.queryForList("""
                    SELECT dl.quantity, dl.delivery_note_id, n.status, n.number, n.organization_id FROM delivery_note_line dl
                    JOIN delivery_note n ON n.id = dl.delivery_note_id WHERE dl.id = ?""", e.getKey());
            if (row.isEmpty() || !inv.getOrganizationId().equals(((Number) row.getFirst().get("organization_id")).longValue()))
                throw com.fatoora.api.common.ApiException.notFound("Delivery line");
            var r = row.getFirst();
            if (!"DELIVERED".equals(r.get("status")))
                throw com.fatoora.api.common.ApiException.badRequest("not_delivered", r.get("number") + " is not delivered");
            BigDecimal already = invoicedByDeliveryLine(((Number) r.get("delivery_note_id")).longValue(), inv.getId())
                    .getOrDefault(e.getKey(), BigDecimal.ZERO);
            BigDecimal left = ((BigDecimal) r.get("quantity")).subtract(already);
            if (e.getValue().compareTo(left) > 0)
                throw com.fatoora.api.common.ApiException.badRequest("over_invoicing",
                        "A line bills more than is left to invoice on " + r.get("number") + " (" + left.stripTrailingZeros().toPlainString() + ")");
        }
    }

    /** OPEN → PARTIALLY_DELIVERED → CLOSED from delivered quantities; CANCELLED is left alone. */
    public void refreshSalesOrder(Long salesOrderId) {
        if (salesOrderId == null) return;
        String status = jdbc.queryForObject("SELECT status FROM sales_order WHERE id = ?", String.class, salesOrderId);
        if ("CANCELLED".equals(status)) return;
        Map<Long, BigDecimal> delivered = deliveredBySalesOrderLine(salesOrderId, null, true);
        boolean any = false, all = true;
        for (var row : jdbc.queryForList("SELECT id, quantity FROM sales_order_line WHERE sales_order_id = ?", salesOrderId)) {
            BigDecimal d = delivered.getOrDefault(((Number) row.get("id")).longValue(), BigDecimal.ZERO);
            if (d.signum() > 0) any = true;
            if (d.compareTo((BigDecimal) row.get("quantity")) < 0) all = false;
        }
        String next = all && any ? "CLOSED" : any ? "PARTIALLY_DELIVERED" : "OPEN";
        jdbc.update("UPDATE sales_order SET status = ?, updated_at = now() WHERE id = ? AND status <> ?", next, salesOrderId, next);
    }

    private Map<Long, BigDecimal> sums(String sql, Object... args) {
        Map<Long, BigDecimal> m = new HashMap<>();
        jdbc.query(sql, rs -> { m.put(rs.getLong(1), rs.getBigDecimal(2)); }, args);
        return m;
    }
}
