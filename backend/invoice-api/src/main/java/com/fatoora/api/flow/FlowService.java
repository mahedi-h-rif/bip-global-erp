package com.fatoora.api.flow;

import com.fatoora.api.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * Quantities already copied along the sales chain, and the statuses that follow from them (SAP B1 / Odoo rules).
 * <p>Everything is counted per <b>line</b>: a delivery line points at its sales-order line, an invoice line at its
 * delivery line or — for an invoice made straight from an order — at its sales-order line. So one delivery may combine
 * several orders, one invoice several deliveries or orders, and every remaining quantity stays exact.
 * <ul>
 *   <li>Order line fulfilled = delivered (DELIVERED notes) + invoiced directly (issued invoices).</li>
 *   <li>Order line still open for new deliveries/invoices = ordered − on non-cancelled notes (drafts reserve)
 *       − on direct invoice lines not rejected (drafts reserve).</li>
 * </ul>
 */
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

    /** Per sales-order line: quantity on non-cancelled delivery notes of any order header (drafts reserve it too). */
    public Map<Long, BigDecimal> deliveredBySalesOrderLine(Long salesOrderId, Long exceptNoteId, boolean deliveredOnly) {
        return sums("""
                SELECT l.sales_order_line_id, sum(l.quantity) FROM delivery_note_line l JOIN delivery_note n ON n.id = l.delivery_note_id
                JOIN sales_order_line sl ON sl.id = l.sales_order_line_id
                WHERE sl.sales_order_id = ? AND n.status <> 'CANCELLED' AND (NOT ? OR n.status = 'DELIVERED')
                  AND (?::bigint IS NULL OR n.id <> ?::bigint)
                GROUP BY l.sales_order_line_id""", salesOrderId, deliveredOnly, exceptNoteId, exceptNoteId);
    }

    /**
     * Per sales-order line: quantity on invoices made straight from the order. {@code issuedOnly}: only issued
     * (not draft, not rejected) invoices, plus {@code pendingInvoiceId} — the one being issued right now.
     */
    public Map<Long, BigDecimal> invoicedDirectBySalesOrderLine(Long salesOrderId, Long exceptInvoiceId, boolean issuedOnly, Long pendingInvoiceId) {
        return sums("""
                SELECT il.sales_order_line_id, sum(il.quantity) FROM invoice_line il JOIN invoice i ON i.id = il.invoice_id
                JOIN sales_order_line sl ON sl.id = il.sales_order_line_id
                WHERE sl.sales_order_id = ? AND i.doc_type = 'INVOICE' AND i.status <> 'REJECTED'
                  AND (NOT ? OR i.status <> 'DRAFT' OR i.id = ?::bigint)
                  AND (?::bigint IS NULL OR i.id <> ?::bigint) GROUP BY il.sales_order_line_id""",
                salesOrderId, issuedOnly, pendingInvoiceId, exceptInvoiceId, exceptInvoiceId);
    }

    /** Per sales-order line: everything invoiced for it, directly or through its deliveries (issued invoices only). */
    public Map<Long, BigDecimal> invoicedBySalesOrderLine(Long salesOrderId) {
        Map<Long, BigDecimal> m = sums("""
                SELECT dl.sales_order_line_id, sum(il.quantity) FROM invoice_line il JOIN invoice i ON i.id = il.invoice_id
                JOIN delivery_note_line dl ON dl.id = il.delivery_note_line_id JOIN sales_order_line sl ON sl.id = dl.sales_order_line_id
                WHERE sl.sales_order_id = ? AND i.doc_type = 'INVOICE' AND i.status NOT IN ('DRAFT','REJECTED')
                GROUP BY dl.sales_order_line_id""", salesOrderId);
        invoicedDirectBySalesOrderLine(salesOrderId, null, true, null).forEach((k, v) -> m.merge(k, v, BigDecimal::add));
        return m;
    }

    /** What new deliveries / direct invoices may still take from each line of an order. */
    public Map<Long, BigDecimal> openBySalesOrderLine(Long salesOrderId, Long exceptNoteId, Long exceptInvoiceId) {
        Map<Long, BigDecimal> taken = deliveredBySalesOrderLine(salesOrderId, exceptNoteId, false);
        invoicedDirectBySalesOrderLine(salesOrderId, exceptInvoiceId, false, null).forEach((k, v) -> taken.merge(k, v, BigDecimal::add));
        Map<Long, BigDecimal> open = new HashMap<>();
        jdbc.query("SELECT id, quantity FROM sales_order_line WHERE sales_order_id = ?",
                rs -> { open.put(rs.getLong(1), rs.getBigDecimal(2).subtract(taken.getOrDefault(rs.getLong(1), BigDecimal.ZERO))); }, salesOrderId);
        return open;
    }

    /**
     * Sales-order lines a new target document wants, grouped and checked: every order must be the organization's,
     * open (not closed or cancelled), for one customer, and no line may take more than is still open.
     * Returns the order ids involved.
     */
    public Set<Long> checkOrderLines(Long orgId, Map<Long, BigDecimal> askedByOrderLine, Long expectedCustomerId,
                                     Long exceptNoteId, Long exceptInvoiceId) {
        Set<Long> orders = new LinkedHashSet<>();
        Long customer = expectedCustomerId;
        boolean first = true;
        for (Long lineId : askedByOrderLine.keySet()) {
            List<Map<String, Object>> r = jdbc.queryForList("""
                    SELECT o.id, o.number, o.status, o.customer_id, o.organization_id FROM sales_order_line l
                    JOIN sales_order o ON o.id = l.sales_order_id WHERE l.id = ?""", lineId);
            if (r.isEmpty() || !orgId.equals(((Number) r.getFirst().get("organization_id")).longValue()))
                throw ApiException.badRequest("unknown_order_line", "A line does not belong to a sales order of this company");
            Map<String, Object> o = r.getFirst();
            String status = (String) o.get("status");
            if ("CANCELLED".equals(status) || "CLOSED".equals(status))
                throw ApiException.badRequest("order_closed", "Sales order " + o.get("number") + " is " + status.toLowerCase());
            Long c = (Long) o.get("customer_id");
            if (first && customer == null) customer = c;
            else if (!Objects.equals(customer, c))
                throw ApiException.badRequest("mixed_customers", "All sales orders must be for the same customer");
            first = false;
            orders.add(((Number) o.get("id")).longValue());
        }
        for (Long so : orders) {
            Map<Long, BigDecimal> open = openBySalesOrderLine(so, exceptNoteId, exceptInvoiceId);
            for (var e : askedByOrderLine.entrySet()) {
                if (!open.containsKey(e.getKey())) continue;
                if (e.getValue().compareTo(open.get(e.getKey())) > 0)
                    throw ApiException.badRequest("over_delivery", "A line takes more than is still open on the sales order ("
                            + open.get(e.getKey()).max(BigDecimal.ZERO).stripTrailingZeros().toPlainString() + ")");
            }
        }
        return orders;
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
     * Before an invoice is issued: every line copied from a delivery must point at a delivered note, every line copied
     * from an order at an open order, and nothing may be billed twice. Then the orders' statuses follow.
     */
    public void checkDeliveryLines(com.fatoora.api.invoice.Invoice inv) {
        Map<Long, BigDecimal> asked = new HashMap<>();
        Map<Long, BigDecimal> askedOrder = new LinkedHashMap<>();
        for (var l : inv.getLines()) {
            if (l.getDeliveryLineId() != null) asked.merge(l.getDeliveryLineId(), l.getQuantity(), BigDecimal::add);
            if (l.getSalesOrderLineId() != null && inv.getType() == com.fatoora.zatca.model.DocumentType.INVOICE)
                askedOrder.merge(l.getSalesOrderLineId(), l.getQuantity(), BigDecimal::add);
        }
        for (var e : asked.entrySet()) {
            var row = jdbc.queryForList("""
                    SELECT dl.quantity, dl.delivery_note_id, n.status, n.number, n.organization_id FROM delivery_note_line dl
                    JOIN delivery_note n ON n.id = dl.delivery_note_id WHERE dl.id = ?""", e.getKey());
            if (row.isEmpty() || !inv.getOrganizationId().equals(((Number) row.getFirst().get("organization_id")).longValue()))
                throw ApiException.notFound("Delivery line");
            var r = row.getFirst();
            if (!"DELIVERED".equals(r.get("status")))
                throw ApiException.badRequest("not_delivered", r.get("number") + " is not delivered");
            BigDecimal already = invoicedByDeliveryLine(((Number) r.get("delivery_note_id")).longValue(), inv.getId())
                    .getOrDefault(e.getKey(), BigDecimal.ZERO);
            BigDecimal left = ((BigDecimal) r.get("quantity")).subtract(already);
            if (e.getValue().compareTo(left) > 0)
                throw ApiException.badRequest("over_invoicing",
                        "A line bills more than is left to invoice on " + r.get("number") + " (" + left.stripTrailingZeros().toPlainString() + ")");
        }
        if (!askedOrder.isEmpty()) {
            Set<Long> orders = checkOrderLines(inv.getOrganizationId(), askedOrder, inv.getCustomerId(), null, inv.getId());
            for (Long so : orders) refreshSalesOrder(so, inv.getId());
        }
        // Orders fed through deliveries become "invoiced" as well.
        if (!asked.isEmpty())
            for (Long so : jdbc.queryForList("""
                    SELECT DISTINCT sl.sales_order_id FROM delivery_note_line dl JOIN sales_order_line sl ON sl.id = dl.sales_order_line_id
                    WHERE dl.id IN (%s)""".formatted(String.join(",", Collections.nCopies(asked.size(), "?"))), Long.class, asked.keySet().toArray()))
                refreshSalesOrder(so, inv.getId());
    }

    /** What a customer still owes: issued invoices ± their notes − payments, never below zero per invoice. */
    public BigDecimal outstanding(Long customerId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(GREATEST(open, 0)), 0) FROM (
                  SELECT i.total_incl_vat
                         + COALESCE((SELECT sum(CASE n.doc_type WHEN 'DEBIT_NOTE' THEN n.total_incl_vat ELSE -n.total_incl_vat END) FROM invoice n
                                     WHERE n.original_invoice_id = i.id AND n.status NOT IN ('DRAFT','REJECTED')), 0)
                         - COALESCE((SELECT sum(p.amount) FROM invoice_payment p WHERE p.invoice_id = i.id), 0) AS open
                  FROM invoice i WHERE i.customer_id = ? AND i.doc_type = 'INVOICE' AND i.status NOT IN ('DRAFT','REJECTED') AND i.closed_at IS NULL) x""",
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
            throw ApiException.badRequest("credit_limit", "%s would go over the credit limit of %s SAR (owes %s, this adds %s). Collect a payment or raise the limit."
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

    public void refreshSalesOrder(Long salesOrderId) {
        refreshSalesOrder(salesOrderId, null);
    }

    /**
     * OPEN → PARTIALLY_DELIVERED → CLOSED from fulfilled quantities (delivered + invoiced directly). CANCELLED and an order
     * closed by hand are left alone. {@code pendingInvoiceId}: an invoice being issued right now counts as issued.
     */
    public void refreshSalesOrder(Long salesOrderId, Long pendingInvoiceId) {
        if (salesOrderId == null) return;
        Map<String, Object> o = jdbc.queryForMap("SELECT status, closed_reason FROM sales_order WHERE id = ?", salesOrderId);
        if ("CANCELLED".equals(o.get("status")) || o.get("closed_reason") != null) return;
        Map<Long, BigDecimal> done = deliveredBySalesOrderLine(salesOrderId, null, true);
        invoicedDirectBySalesOrderLine(salesOrderId, null, true, pendingInvoiceId).forEach((k, v) -> done.merge(k, v, BigDecimal::add));
        boolean any = false, all = true;
        for (var row : jdbc.queryForList("SELECT id, quantity FROM sales_order_line WHERE sales_order_id = ?", salesOrderId)) {
            BigDecimal d = done.getOrDefault(((Number) row.get("id")).longValue(), BigDecimal.ZERO);
            if (d.signum() > 0) any = true;
            if (d.compareTo((BigDecimal) row.get("quantity")) < 0) all = false;
        }
        String next = all && any ? "CLOSED" : any ? "PARTIALLY_DELIVERED" : "OPEN";
        jdbc.update("UPDATE sales_order SET status = ?, updated_at = now() WHERE id = ? AND status <> ?", next, salesOrderId, next);
    }

    /** The orders a delivery note's lines come from. */
    public List<Long> ordersOfDelivery(Long deliveryNoteId) {
        return jdbc.queryForList("""
                SELECT DISTINCT sl.sales_order_id FROM delivery_note_line dl JOIN sales_order_line sl ON sl.id = dl.sales_order_line_id
                WHERE dl.delivery_note_id = ?""", Long.class, deliveryNoteId);
    }

    /** NONE / PARTIAL / FULL from a line map of done quantities against the order lines. */
    public static String level(Map<Long, BigDecimal> done, List<Map<String, Object>> lines) {
        boolean any = false, all = !lines.isEmpty();
        for (var row : lines) {
            BigDecimal d = done.getOrDefault(((Number) row.get("id")).longValue(), BigDecimal.ZERO);
            if (d.signum() > 0) any = true;
            if (d.compareTo((BigDecimal) row.get("quantity")) < 0) all = false;
        }
        return all ? "FULL" : any ? "PARTIAL" : "NONE";
    }

    private Map<Long, BigDecimal> sums(String sql, Object... args) {
        Map<Long, BigDecimal> m = new HashMap<>();
        jdbc.query(sql, rs -> { m.put(rs.getLong(1), rs.getBigDecimal(2)); }, args);
        return m;
    }
}
