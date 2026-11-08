package com.fatoora.api.flow;

import com.fatoora.api.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * The relationship map (SAP B1 style): every business document connected to a starting document, walked over the
 * foreign keys each document already carries, so the map can never drift from the data.
 */
@Service
public class DocumentGraph {

    public enum Type {
        QUOTATION, SALES_ORDER, DELIVERY_NOTE, INVOICE, RECEIPT,
        PURCHASE_ORDER, GOODS_RECEIPT, SUPPLIER_INVOICE, SUPPLIER_PAYMENT
    }

    /** {@code state}: OPEN, PARTIAL, CLOSED, PAID, UNPAID, DRAFT, CANCELLED … for colouring the node. */
    public record Node(Type type, Long id, String number, String label, LocalDate date, String party, String ref,
                       BigDecimal total, String status, boolean open) {}

    public record Edge(Type fromType, Long fromId, Type toType, Long toId) {}

    public record Graph(Type rootType, Long rootId, List<Node> nodes, List<Edge> edges) {}

    private static final int MAX_NODES = 300;

    private final JdbcTemplate jdbc;

    public DocumentGraph(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Graph walk(Long orgId, Type type, Long id) {
        if (node(orgId, type, id) == null) throw ApiException.notFound("Document");
        record Key(Type t, Long id) {}
        Map<Key, Node> nodes = new LinkedHashMap<>();
        Set<Edge> edges = new LinkedHashSet<>();
        Deque<Key> queue = new ArrayDeque<>(List.of(new Key(type, id)));
        while (!queue.isEmpty() && nodes.size() < MAX_NODES) {
            Key k = queue.poll();
            if (nodes.containsKey(k)) continue;
            Node n = node(orgId, k.t(), k.id());
            if (n == null) continue;
            nodes.put(k, n);
            for (Edge e : neighbours(orgId, k.t(), k.id())) {
                edges.add(e);
                Key other = e.fromType() == k.t() && e.fromId().equals(k.id()) ? new Key(e.toType(), e.toId()) : new Key(e.fromType(), e.fromId());
                if (!nodes.containsKey(other)) queue.add(other);
            }
        }
        List<Edge> kept = edges.stream().filter(e -> nodes.containsKey(new Key(e.fromType(), e.fromId()))
                && nodes.containsKey(new Key(e.toType(), e.toId()))).toList();
        return new Graph(type, id, new ArrayList<>(nodes.values()), kept);
    }

    // --- edges, derived from the documents' own foreign keys plus manual doc_link rows ------------------------

    private List<Edge> neighbours(Long orgId, Type t, Long id) {
        List<Edge> out = new ArrayList<>();
        switch (t) {
            case QUOTATION -> {
                ids("SELECT id FROM sales_order WHERE quotation_id = ?", id).forEach(x -> out.add(e(Type.QUOTATION, id, Type.SALES_ORDER, x)));
                ids("SELECT invoice_id FROM quotation WHERE id = ? AND invoice_id IS NOT NULL", id).forEach(x -> out.add(e(Type.QUOTATION, id, Type.INVOICE, x)));
            }
            case SALES_ORDER -> {
                ids("SELECT quotation_id FROM sales_order WHERE id = ? AND quotation_id IS NOT NULL", id).forEach(x -> out.add(e(Type.QUOTATION, x, Type.SALES_ORDER, id)));
                ids("SELECT id FROM delivery_note WHERE sales_order_id = ?", id).forEach(x -> out.add(e(Type.SALES_ORDER, id, Type.DELIVERY_NOTE, x)));
            }
            case DELIVERY_NOTE -> {
                ids("SELECT sales_order_id FROM delivery_note WHERE id = ? AND sales_order_id IS NOT NULL", id).forEach(x -> out.add(e(Type.SALES_ORDER, x, Type.DELIVERY_NOTE, id)));
                // Older flow: the invoice came first and the delivery note was made from it.
                ids("SELECT invoice_id FROM delivery_note WHERE id = ? AND invoice_id IS NOT NULL", id).forEach(x -> out.add(e(Type.INVOICE, x, Type.DELIVERY_NOTE, id)));
                ids("""
                        SELECT DISTINCT il.invoice_id FROM invoice_line il JOIN delivery_note_line dl ON dl.id = il.delivery_note_line_id
                        WHERE dl.delivery_note_id = ?""", id).forEach(x -> out.add(e(Type.DELIVERY_NOTE, id, Type.INVOICE, x)));
            }
            case INVOICE -> {
                ids("SELECT id FROM quotation WHERE invoice_id = ?", id).forEach(x -> out.add(e(Type.QUOTATION, x, Type.INVOICE, id)));
                ids("SELECT id FROM delivery_note WHERE invoice_id = ?", id).forEach(x -> out.add(e(Type.INVOICE, id, Type.DELIVERY_NOTE, x)));
                ids("""
                        SELECT DISTINCT dl.delivery_note_id FROM invoice_line il JOIN delivery_note_line dl ON dl.id = il.delivery_note_line_id
                        WHERE il.invoice_id = ?""", id).forEach(x -> out.add(e(Type.DELIVERY_NOTE, x, Type.INVOICE, id)));
                ids("SELECT id FROM invoice WHERE original_invoice_id = ?", id).forEach(x -> out.add(e(Type.INVOICE, id, Type.INVOICE, x)));
                ids("SELECT original_invoice_id FROM invoice WHERE id = ? AND original_invoice_id IS NOT NULL", id).forEach(x -> out.add(e(Type.INVOICE, x, Type.INVOICE, id)));
                ids("SELECT DISTINCT receipt_id FROM invoice_payment WHERE invoice_id = ? AND receipt_id IS NOT NULL", id).forEach(x -> out.add(e(Type.INVOICE, id, Type.RECEIPT, x)));
                // Cancelled receipts stay on the map, linked through the allocations they had.
                jdbc.queryForList("SELECT id FROM receipt WHERE status = 'CANCELLED' AND (';' || cancelled_allocations) LIKE ?",
                        Long.class, "%;" + id + ":%").forEach(x -> out.add(e(Type.INVOICE, id, Type.RECEIPT, x)));
            }
            case RECEIPT -> {
                ids("SELECT DISTINCT invoice_id FROM invoice_payment WHERE receipt_id = ?", id).forEach(x -> out.add(e(Type.INVOICE, x, Type.RECEIPT, id)));
                String snap = jdbc.queryForList("SELECT cancelled_allocations FROM receipt WHERE id = ?", String.class, id).stream()
                        .filter(Objects::nonNull).findFirst().orElse("");
                for (String part : snap.split(";"))
                    if (!part.isBlank()) out.add(e(Type.INVOICE, Long.parseLong(part.split(":")[0]), Type.RECEIPT, id));
            }
            default -> out.addAll(purchaseNeighbours(t, id));
        }
        jdbc.query("SELECT from_type, from_id, to_type, to_id FROM doc_link WHERE organization_id = ? AND ((from_type = ? AND from_id = ?) OR (to_type = ? AND to_id = ?))",
                rs -> { out.add(new Edge(Type.valueOf(rs.getString(1)), rs.getLong(2), Type.valueOf(rs.getString(3)), rs.getLong(4))); },
                orgId, t.name(), id, t.name(), id);
        return out;
    }

    /** Purchasing edges; empty until the purchasing tables exist (Phase B adds them). */
    protected List<Edge> purchaseNeighbours(Type t, Long id) {
        if (!tableExists("purchase_order")) return List.of();
        List<Edge> out = new ArrayList<>();
        switch (t) {
            case PURCHASE_ORDER -> ids("SELECT id FROM goods_receipt WHERE purchase_order_id = ?", id).forEach(x -> out.add(e(Type.PURCHASE_ORDER, id, Type.GOODS_RECEIPT, x)));
            case GOODS_RECEIPT -> {
                ids("SELECT purchase_order_id FROM goods_receipt WHERE id = ? AND purchase_order_id IS NOT NULL", id).forEach(x -> out.add(e(Type.PURCHASE_ORDER, x, Type.GOODS_RECEIPT, id)));
                ids("""
                        SELECT DISTINCT sl.supplier_invoice_id FROM supplier_invoice_line sl JOIN goods_receipt_line gl ON gl.id = sl.goods_receipt_line_id
                        WHERE gl.goods_receipt_id = ?""", id).forEach(x -> out.add(e(Type.GOODS_RECEIPT, id, Type.SUPPLIER_INVOICE, x)));
            }
            case SUPPLIER_INVOICE -> {
                ids("""
                        SELECT DISTINCT gl.goods_receipt_id FROM supplier_invoice_line sl JOIN goods_receipt_line gl ON gl.id = sl.goods_receipt_line_id
                        WHERE sl.supplier_invoice_id = ?""", id).forEach(x -> out.add(e(Type.GOODS_RECEIPT, x, Type.SUPPLIER_INVOICE, id)));
                ids("SELECT DISTINCT supplier_payment_id FROM supplier_payment_allocation WHERE supplier_invoice_id = ?", id).forEach(x -> out.add(e(Type.SUPPLIER_INVOICE, id, Type.SUPPLIER_PAYMENT, x)));
            }
            case SUPPLIER_PAYMENT -> ids("SELECT DISTINCT supplier_invoice_id FROM supplier_payment_allocation WHERE supplier_payment_id = ?", id).forEach(x -> out.add(e(Type.SUPPLIER_INVOICE, x, Type.SUPPLIER_PAYMENT, id)));
            default -> { }
        }
        return out;
    }

    // --- nodes ---------------------------------------------------------------------------------------------

    public Node node(Long orgId, Type t, Long id) {
        String sql = switch (t) {
            case QUOTATION -> """
                    SELECT q.number, q.issue_date AS d, COALESCE(c.name_en, c.name_ar, q.client_name) AS party, NULL AS ref,
                           q.total_incl_vat AS total, q.status, q.status IN ('DRAFT','SENT','ACCEPTED') AS open
                    FROM quotation q LEFT JOIN customer c ON c.id = q.customer_id WHERE q.id = ? AND q.organization_id = ?""";
            case SALES_ORDER -> """
                    SELECT s.number, s.order_date AS d, COALESCE(c.name_en, c.name_ar, s.client_name) AS party, s.customer_ref AS ref,
                           s.total_incl_vat AS total, s.status, s.status IN ('OPEN','PARTIALLY_DELIVERED') AS open
                    FROM sales_order s LEFT JOIN customer c ON c.id = s.customer_id WHERE s.id = ? AND s.organization_id = ?""";
            case DELIVERY_NOTE -> """
                    SELECT n.number, n.delivery_date AS d, COALESCE(c.name_en, c.name_ar, n.client_name) AS party, n.customer_ref AS ref,
                           (SELECT sum(l.quantity * COALESCE(l.unit_price, 0) * (1 + COALESCE(l.vat_rate, 0) / 100)) FROM delivery_note_line l
                            WHERE l.delivery_note_id = n.id) AS total, n.status, n.status = 'DRAFT' AS open
                    FROM delivery_note n LEFT JOIN customer c ON c.id = n.customer_id WHERE n.id = ? AND n.organization_id = ?""";
            case INVOICE -> """
                    SELECT COALESCE(i.number, 'Draft') AS number, COALESCE((i.issued_at AT TIME ZONE 'Asia/Riyadh')::date, i.created_at::date) AS d,
                           COALESCE(c.name_en, c.name_ar) AS party, i.customer_ref AS ref, i.total_incl_vat AS total,
                           i.doc_type || ':' || i.status AS status,
                           i.doc_type = 'INVOICE' AND i.status NOT IN ('DRAFT','REJECTED')
                             AND i.total_incl_vat > COALESCE((SELECT sum(p.amount) FROM invoice_payment p WHERE p.invoice_id = i.id), 0)
                             - COALESCE((SELECT sum(n.total_incl_vat) FROM invoice n WHERE n.original_invoice_id = i.id AND n.doc_type = 'CREDIT_NOTE'
                                         AND n.status NOT IN ('DRAFT','REJECTED')), 0) AS open
                    FROM invoice i LEFT JOIN customer c ON c.id = i.customer_id WHERE i.id = ? AND i.organization_id = ?""";
            case RECEIPT -> """
                    SELECT r.number, r.receipt_date AS d, COALESCE(c.name_en, c.name_ar) AS party, r.reference AS ref,
                           r.amount AS total, CASE WHEN r.status = 'CANCELLED' THEN 'CANCELLED' ELSE r.method END AS status, FALSE AS open
                    FROM receipt r JOIN customer c ON c.id = r.customer_id WHERE r.id = ? AND r.organization_id = ?""";
            default -> purchaseNodeSql(t);
        };
        if (sql == null) return null;
        List<Node> found = jdbc.query(sql, (rs, i) -> new Node(t, id, rs.getString("number"), label(t, rs.getString("status")),
                rs.getObject("d", LocalDate.class), rs.getString("party"), rs.getString("ref"), rs.getBigDecimal("total"),
                rs.getString("status"), rs.getBoolean("open")), id, orgId);
        return found.isEmpty() ? null : found.getFirst();
    }

    protected String purchaseNodeSql(Type t) {
        if (!tableExists("purchase_order")) return null;
        return switch (t) {
            case PURCHASE_ORDER -> """
                    SELECT p.number, p.order_date AS d, s.name AS party, p.supplier_ref AS ref, p.total_incl_vat AS total, p.status,
                           p.status IN ('PENDING','APPROVED','PARTIALLY_RECEIVED') AS open
                    FROM purchase_order p JOIN supplier s ON s.id = p.supplier_id WHERE p.id = ? AND p.organization_id = ?""";
            case GOODS_RECEIPT -> """
                    SELECT g.number, g.receipt_date AS d, s.name AS party, g.supplier_ref AS ref, g.total_incl_vat AS total, g.status,
                           FALSE AS open
                    FROM goods_receipt g JOIN supplier s ON s.id = g.supplier_id WHERE g.id = ? AND g.organization_id = ?""";
            case SUPPLIER_INVOICE -> """
                    SELECT v.number, v.invoice_date AS d, s.name AS party, v.supplier_invoice_no AS ref, v.total_incl_vat AS total, v.status,
                           v.status = 'OPEN' AND v.total_sar > COALESCE((SELECT sum(a.amount) FROM supplier_payment_allocation a JOIN supplier_payment sp ON sp.id = a.supplier_payment_id WHERE a.supplier_invoice_id = v.id AND sp.status = 'POSTED'), 0) AS open
                    FROM supplier_invoice v JOIN supplier s ON s.id = v.supplier_id WHERE v.id = ? AND v.organization_id = ?""";
            case SUPPLIER_PAYMENT -> """
                    SELECT p.number, p.payment_date AS d, s.name AS party, p.reference AS ref, p.amount AS total, p.method AS status,
                           FALSE AS open
                    FROM supplier_payment p JOIN supplier s ON s.id = p.supplier_id WHERE p.id = ? AND p.organization_id = ?""";
            default -> null;
        };
    }

    private static String label(Type t, String status) {
        if (t == Type.INVOICE && status != null) {
            if (status.startsWith("CREDIT_NOTE")) return "Credit note";
            if (status.startsWith("DEBIT_NOTE")) return "Debit note";
            return "Invoice";
        }
        return switch (t) {
            case QUOTATION -> "Quotation";
            case SALES_ORDER -> "Sales order";
            case DELIVERY_NOTE -> "Delivery";
            case RECEIPT -> "Incoming payment";
            case PURCHASE_ORDER -> "Purchase order";
            case GOODS_RECEIPT -> "Goods receipt";
            case SUPPLIER_INVOICE -> "Supplier invoice";
            case SUPPLIER_PAYMENT -> "Outgoing payment";
            default -> t.name();
        };
    }

    private List<Long> ids(String sql, Long id) {
        return jdbc.queryForList(sql, Long.class, id);
    }

    private static Edge e(Type ft, Long fid, Type tt, Long tid) {
        return new Edge(ft, fid, tt, tid);
    }

    private Boolean purchasing;

    private boolean tableExists(String name) {
        if (purchasing == null)
            purchasing = Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, name));
        return purchasing;
    }
}
