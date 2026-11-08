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

    /** What a customer still owes: issued invoices ± their notes − payments, never below zero per invoice. */
    public BigDecimal outstanding(Long customerId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(GREATEST(open, 0)), 0) FROM (
                  SELECT i.total_incl_vat
                         + COALESCE((SELECT sum(CASE n.doc_type WHEN 'DEBIT_NOTE' THEN n.total_incl_vat ELSE -n.total_incl_vat END) FROM invoice n
                                     WHERE n.original_invoice_id = i.id AND n.status NOT IN ('DRAFT','REJECTED')), 0)
                         - COALESCE((SELECT sum(p.amount) FROM invoice_payment p WHERE p.invoice_id = i.id), 0) AS open
                  FROM invoice i WHERE i.customer_id = ? AND i.doc_type = 'INVOICE' AND i.status NOT IN ('DRAFT','REJECTED')) x""",
                BigDecimal.class, customerId);
    }

    /**
     * Credit control (Odoo / Oracle): refuses new business that would take a customer above their credit limit.
     * {@code extra} is the amount being added. No limit (null or 0) means no check.
     */
    public void checkCredit(Long customerId, BigDecimal extra) {
        if (customerId == null) return;
        Map<String, Object> c = jdbc.queryForMap("SELECT credit_limit, COALESCE(name_en, name_ar) AS name FROM customer WHERE id = ?", customerId);
        BigDecimal limit = (BigDecimal) c.get("credit_limit");
        if (limit == null || limit.signum() <= 0) return;
        BigDecimal owed = outstanding(customerId);
        if (owed.add(extra).compareTo(limit) > 0)
            throw com.fatoora.api.common.ApiException.badRequest("credit_limit", "%s would go over the credit limit of %s SAR (owes %s, this adds %s). Collect a payment or raise the limit."
                    .formatted(c.get("name"), limit.toPlainString(), owed.toPlainString(), extra.toPlainString()));
    }

    /** At issue: due date from the customer's payment terms (when not set), and the credit-limit check. */
    public void applyCustomerTerms(com.fatoora.api.invoice.Invoice inv) {
        if (inv.getCustomerId() == null || inv.getType() != com.fatoora.zatca.model.DocumentType.INVOICE) return;
        Integer terms = jdbc.queryForObject("SELECT payment_terms_days FROM customer WHERE id = ?", Integer.class, inv.getCustomerId());
        if (inv.getDueDate() == null && terms != null && terms > 0)
            inv.setDueDate(java.time.LocalDate.now(com.fatoora.api.InvoiceApiApplication.KSA).plusDays(terms));
        checkCredit(inv.getCustomerId(), inv.getTotalInclVat());
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
