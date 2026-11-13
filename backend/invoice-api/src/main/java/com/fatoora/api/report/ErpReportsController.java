package com.fatoora.api.report;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.organization.OrganizationRepository;
import com.fatoora.api.pdf.LetterheadPdf;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * ERP reports (quotation "Reports and export"): every report is one table — JSON for the screen, {@code .xlsx} and
 * {@code .pdf} for export — plus the ERP dashboard and a quick search across the main records.
 */
@RestController
@RequestMapping("/api/v1")
public class ErpReportsController {

    /** A report: column headers, rows of cells (String or BigDecimal), and a totals row. */
    public record Table(String title, String subtitle, List<String> columns, List<List<Object>> rows, List<Object> totals) {}

    public record Tile(String key, String label, BigDecimal value, String unit, String href) {}

    /**
     * {@code monthly}: last 12 months of net sales, net purchases and gross profit; {@code topCustomers} / {@code topProducts}:
     * this year's five biggest by net sales; {@code receivablesAging} / {@code payablesAging}: open amounts per age bucket.
     */
    public record Dashboard(List<Tile> tiles, List<Map<String, Object>> salesTrend, List<Map<String, Object>> stockByWarehouse,
                            List<Map<String, Object>> lowStock, List<Map<String, Object>> expiringIds,
                            List<Map<String, Object>> expiringVehicles, List<Map<String, Object>> monthly,
                            List<Map<String, Object>> topCustomers, List<Map<String, Object>> topProducts,
                            List<Map<String, Object>> receivablesAging, List<Map<String, Object>> payablesAging) {}

    public record Hit(String type, String label, String detail, String href) {}

    private final JdbcTemplate jdbc;
    private final OrganizationRepository orgs;
    private final LetterheadPdf letterhead;

    public ErpReportsController(JdbcTemplate jdbc, OrganizationRepository orgs, LetterheadPdf letterhead) {
        this.jdbc = jdbc;
        this.orgs = orgs;
        this.letterhead = letterhead;
    }

    // --- reports ---------------------------------------------------------------------------------

    @GetMapping("/erp-reports/{name}")
    Table report(@PathVariable String name, @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                 @RequestParam(defaultValue = "day") String groupBy, @RequestParam(required = false) Long warehouseId,
                 @RequestParam(required = false) Integer year) {
        Long orgId = CurrentUser.orgId();
        LocalDate today = LocalDate.now(InvoiceApiApplication.KSA);
        LocalDate f = from != null ? from : today.withDayOfMonth(1), t = to != null ? to : today;
        Timestamp start = Timestamp.from(f.atStartOfDay(InvoiceApiApplication.KSA).toInstant());
        Timestamp end = Timestamp.from(t.plusDays(1).atStartOfDay(InvoiceApiApplication.KSA).toInstant());
        String period = f + " → " + t;
        return switch (name) {
            case "sales" -> sales(orgId, start, end, groupBy, period);
            case "stock" -> stock(orgId, warehouseId);
            case "purchases" -> purchases(orgId, f, t, groupBy, period);
            case "expenses" -> expenses(orgId, f, t, groupBy, period);
            case "vat" -> vat(orgId, start, end, f, t, period);
            case "pnl" -> pnl(orgId, year == null ? today.getYear() : year);
            case "receivables" -> aging(orgId, true, today);
            case "payables" -> aging(orgId, false, today);
            case "gross-profit" -> grossProfit(orgId, start, end, groupBy, period);
            default -> throw ApiException.notFound("Report");
        };
    }

    @GetMapping("/erp-reports/{name}.xlsx")
    ResponseEntity<byte[]> excel(@PathVariable String name, @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                 @RequestParam(defaultValue = "day") String groupBy, @RequestParam(required = false) Long warehouseId,
                                 @RequestParam(required = false) Integer year) throws java.io.IOException {
        Table r = report(name, from, to, groupBy, warehouseId, year);
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out = new java.io.ByteArrayOutputStream()) {
            var sh = wb.createSheet(name);
            var bold = wb.createCellStyle();
            var font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);
            sh.createRow(0).createCell(0).setCellValue(r.title() + (r.subtitle() == null ? "" : " · " + r.subtitle()));
            var h = sh.createRow(2);
            for (int i = 0; i < r.columns().size(); i++) { h.createCell(i).setCellValue(r.columns().get(i)); h.getCell(i).setCellStyle(bold); }
            int n = 3;
            for (List<Object> row : r.rows()) put(sh.createRow(n++), row, null);
            if (r.totals() != null) put(sh.createRow(n), r.totals(), bold);
            for (int i = 0; i < r.columns().size(); i++) sh.autoSizeColumn(i);
            wb.write(out);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + name + "-report.xlsx").body(out.toByteArray());
        }
    }

    @GetMapping("/erp-reports/{name}.pdf")
    ResponseEntity<byte[]> pdf(@PathVariable String name, @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                               @RequestParam(defaultValue = "day") String groupBy, @RequestParam(required = false) Long warehouseId,
                               @RequestParam(required = false) Integer year) {
        Table r = report(name, from, to, groupBy, warehouseId, year);
        var org = orgs.findById(CurrentUser.orgId()).orElseThrow();
        String brand = org.getBrandColor() == null ? "#0B2A6F" : org.getBrandColor();
        StringBuilder html = new StringBuilder("<h1>").append(LetterheadPdf.esc(r.title())).append("</h1>");
        if (r.subtitle() != null) html.append("<p class=\"muted\">").append(LetterheadPdf.esc(r.subtitle())).append("</p>");
        html.append("<table style=\"margin-top:8px; font-size:8pt\"><tr>");
        for (String c : r.columns()) html.append("<th style=\"background:").append(brand).append("; color:#fff; padding:4px\">").append(LetterheadPdf.esc(c)).append("</th>");
        html.append("</tr>");
        for (List<Object> row : r.rows()) html.append(tr(row, false));
        if (r.totals() != null) html.append(tr(r.totals(), true));
        html.append("</table>");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name + "-report.pdf").build().toString())
                .body(letterhead.render(org, r.title(), html.toString()));
    }

    private Table sales(Long orgId, Timestamp start, Timestamp end, String groupBy, String period) {
        // Credit notes count negative, debit notes positive; drafts and rejected documents never count.
        String sign = "CASE i.doc_type WHEN 'CREDIT_NOTE' THEN -1 ELSE 1 END";
        String base = " FROM invoice i LEFT JOIN customer c ON c.id = i.customer_id WHERE i.organization_id = ? AND i.status NOT IN ('DRAFT','REJECTED') AND i.issued_at >= ? AND i.issued_at < ?";
        List<List<Object>> rows;
        String keyCol;
        if ("product".equals(groupBy)) {
            keyCol = "Product";
            rows = jdbc.query("SELECT l.name AS k, sum(" + sign + " * l.quantity) AS q, sum(" + sign + " * round(l.quantity * l.unit_price, 2)) AS net, count(DISTINCT i.id) AS n"
                    + " FROM invoice_line l JOIN invoice i ON i.id = l.invoice_id WHERE i.organization_id = ? AND i.status NOT IN ('DRAFT','REJECTED')"
                    + " AND i.issued_at >= ? AND i.issued_at < ? GROUP BY l.name ORDER BY net DESC",
                    (rs, x) -> row(rs.getString("k"), rs.getBigDecimal("q"), rs.getLong("n"), rs.getBigDecimal("net"),
                            rs.getBigDecimal("net").multiply(new BigDecimal("0.15")).setScale(2, RoundingMode.HALF_UP),
                            rs.getBigDecimal("net").multiply(new BigDecimal("1.15")).setScale(2, RoundingMode.HALF_UP)), orgId, start, end);
            return table("Sales by product", period, List.of("Product", "Quantity", "Invoices", "Net", "VAT (est.)", "Total"), rows, 3);
        }
        String expr = switch (groupBy) {
            case "month" -> "to_char(i.issued_at AT TIME ZONE 'Asia/Riyadh', 'YYYY-MM')";
            case "customer" -> "COALESCE(c.name_en, c.name_ar, 'Walk-in customer')";
            case "user" -> "COALESCE(i.created_by, '—')";
            default -> "to_char(i.issued_at AT TIME ZONE 'Asia/Riyadh', 'YYYY-MM-DD')";
        };
        keyCol = switch (groupBy) { case "month" -> "Month"; case "customer" -> "Customer"; case "user" -> "User"; default -> "Day"; };
        rows = jdbc.query("SELECT " + expr + " AS k, count(*) AS n, sum(" + sign + " * i.total_excl_vat) AS net, sum(" + sign + " * i.vat_total) AS vat,"
                        + " sum(" + sign + " * i.total_incl_vat) AS tot" + base + " GROUP BY 1 ORDER BY " + ("customer".equals(groupBy) || "user".equals(groupBy) ? "tot DESC" : "1"),
                (rs, x) -> row(rs.getString("k"), rs.getLong("n"), rs.getBigDecimal("net"), rs.getBigDecimal("vat"), rs.getBigDecimal("tot")), orgId, start, end);
        return table("Sales by " + keyCol.toLowerCase(), period, List.of(keyCol, "Documents", "Net", "VAT", "Total"), rows, 1);
    }

    private Table stock(Long orgId, Long warehouseId) {
        List<List<Object>> rows = jdbc.query("""
                SELECT w.name AS wname, p.number, p.name, p.unit_code, s.quantity, COALESCE(p.avg_cost, p.cost_price, 0) AS cost,
                       round(s.quantity * COALESCE(p.avg_cost, p.cost_price, 0), 2) AS value, p.low_stock_threshold
                FROM stock_level s JOIN product p ON p.id = s.product_id JOIN warehouse w ON w.id = s.warehouse_id
                WHERE w.organization_id = ? AND p.track_stock AND (?::bigint IS NULL OR w.id = ?::bigint) AND s.quantity <> 0
                ORDER BY w.name, lower(p.name)""",
                (rs, x) -> row(rs.getString("wname"), rs.getString("number"), rs.getString("name"), rs.getBigDecimal("quantity"),
                        rs.getString("unit_code"), rs.getBigDecimal("cost"), rs.getBigDecimal("value"),
                        rs.getBigDecimal("low_stock_threshold") != null && rs.getBigDecimal("quantity").compareTo(rs.getBigDecimal("low_stock_threshold")) <= 0 ? "LOW" : ""),
                orgId, warehouseId, warehouseId);
        return table("Stock valuation (moving average)", null, List.of("Warehouse", "Code", "Item", "On hand", "Unit", "Avg cost", "Value", "Alert"), rows, 6);
    }

    private Table purchases(Long orgId, LocalDate f, LocalDate t, String groupBy, String period) {
        String expr = switch (groupBy) { case "month" -> "to_char(v.invoice_date, 'YYYY-MM')"; case "product" -> "l.name"; default -> "s.name"; };
        String key = switch (groupBy) { case "month" -> "Month"; case "product" -> "Item"; default -> "Supplier"; };
        List<List<Object>> rows = "product".equals(groupBy)
                ? jdbc.query("""
                        SELECT l.name AS k, sum(l.quantity) AS q, count(DISTINCT v.id) AS n, sum(round(l.quantity * l.unit_cost * v.exchange_rate, 2)) AS net
                        FROM supplier_invoice_line l JOIN supplier_invoice v ON v.id = l.supplier_invoice_id
                        WHERE v.organization_id = ? AND v.status = 'OPEN' AND v.invoice_date BETWEEN ? AND ? GROUP BY l.name ORDER BY net DESC""",
                        (rs, x) -> row(rs.getString("k"), rs.getLong("n"), rs.getBigDecimal("net"), rs.getBigDecimal("q")), orgId, f, t)
                : jdbc.query("SELECT " + expr + " AS k, count(DISTINCT v.id) AS n, sum(v.total_sar - v.vat_sar) AS net, sum(v.vat_sar) AS vat, sum(v.total_sar) AS tot"
                        // A/P credit memos count negative, dated on their own date.
                        + " FROM (SELECT id, supplier_id, organization_id, status, invoice_date, total_sar, vat_sar FROM supplier_invoice"
                        + " UNION ALL SELECT -id, supplier_id, organization_id, CASE status WHEN 'POSTED' THEN 'OPEN' ELSE status END, credit_date, -total_sar, -vat_sar"
                        + " FROM supplier_credit_note) v JOIN supplier s ON s.id = v.supplier_id WHERE v.organization_id = ? AND v.status = 'OPEN'"
                        + " AND v.invoice_date BETWEEN ? AND ? GROUP BY 1 ORDER BY 1",
                        (rs, x) -> row(rs.getString("k"), rs.getLong("n"), rs.getBigDecimal("net"), rs.getBigDecimal("vat"), rs.getBigDecimal("tot")), orgId, f, t);
        return "product".equals(groupBy)
                ? table("Purchases by item", period, List.of(key, "Invoices", "Net (SAR)", "Quantity"), rows, 2)
                : table("Purchases by " + key.toLowerCase(), period, List.of(key, "Invoices", "Net (SAR)", "Input VAT", "Total (SAR)"), rows, 2);
    }

    private Table expenses(Long orgId, LocalDate f, LocalDate t, String groupBy, String period) {
        String expr = "month".equals(groupBy) ? "to_char(entry_date, 'YYYY-MM')" : "category";
        List<List<Object>> rows = jdbc.query("SELECT " + expr + " AS k, count(*) AS n, sum(amount) AS a FROM account_expense"
                        + " WHERE organization_id = ? AND entry_date BETWEEN ? AND ? GROUP BY 1 ORDER BY " + ("month".equals(groupBy) ? "1" : "a DESC"),
                (rs, x) -> row(rs.getString("k"), rs.getLong("n"), rs.getBigDecimal("a")), orgId, f, t);
        return table("Expenses by " + ("month".equals(groupBy) ? "month" : "category"), period, List.of("month".equals(groupBy) ? "Month" : "Category", "Entries", "Amount"), rows, 2);
    }

    private Table vat(Long orgId, Timestamp start, Timestamp end, LocalDate f, LocalDate t, String period) {
        Map<String, Object> out = jdbc.queryForMap("""
                SELECT COALESCE(sum(CASE doc_type WHEN 'CREDIT_NOTE' THEN -total_excl_vat ELSE total_excl_vat END), 0) AS net,
                       COALESCE(sum(CASE doc_type WHEN 'CREDIT_NOTE' THEN -vat_total ELSE vat_total END), 0) AS vat
                FROM invoice WHERE organization_id = ? AND status NOT IN ('DRAFT','REJECTED') AND issued_at >= ? AND issued_at < ?""", orgId, start, end);
        Map<String, Object> in = jdbc.queryForMap("""
                SELECT COALESCE(sum(total_sar - vat_sar), 0) AS net, COALESCE(sum(vat_sar), 0) AS vat FROM supplier_invoice
                WHERE organization_id = ? AND status = 'OPEN' AND invoice_date BETWEEN ? AND ?""", orgId, f, t);
        // A/P credit memos reverse input VAT in the period they are dated.
        Map<String, Object> cr = jdbc.queryForMap("""
                SELECT COALESCE(sum(total_sar - vat_sar), 0) AS net, COALESCE(sum(vat_sar), 0) AS vat FROM supplier_credit_note
                WHERE organization_id = ? AND status = 'POSTED' AND credit_date BETWEEN ? AND ?""", orgId, f, t);
        in = Map.of("net", ((BigDecimal) in.get("net")).subtract((BigDecimal) cr.get("net")), "vat", ((BigDecimal) in.get("vat")).subtract((BigDecimal) cr.get("vat")));
        BigDecimal outVat = (BigDecimal) out.get("vat"), inVat = (BigDecimal) in.get("vat");
        List<List<Object>> rows = List.of(
                row("Standard-rated sales (output VAT)", out.get("net"), outVat),
                row("Purchases (input VAT)", in.get("net"), inVat));
        return new Table("VAT summary", period, List.of("Line", "Amount excl. VAT", "VAT"), rows,
                row("Net VAT payable", "", outVat.subtract(inVat)));
    }

    private Table pnl(Long orgId, int year) {
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal[] tot = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (int m = 1; m <= 12; m++) {
            LocalDate a = LocalDate.of(year, m, 1), b = a.plusMonths(1);
            Timestamp s = Timestamp.from(a.atStartOfDay(InvoiceApiApplication.KSA).toInstant()), e = Timestamp.from(b.atStartOfDay(InvoiceApiApplication.KSA).toInstant());
            BigDecimal sales = jdbc.queryForObject("""
                    SELECT COALESCE(sum(CASE doc_type WHEN 'CREDIT_NOTE' THEN -total_excl_vat ELSE total_excl_vat END), 0) FROM invoice
                    WHERE organization_id = ? AND status NOT IN ('DRAFT','REJECTED') AND issued_at >= ? AND issued_at < ?""", BigDecimal.class, orgId, s, e);
            // Cost of goods sold: the moving-average value of stock that left on sales and deliveries, net of returns.
            BigDecimal cogs = jdbc.queryForObject("""
                    SELECT COALESCE(-sum(value), 0) FROM stock_movement WHERE organization_id = ? AND kind IN ('SALE','DELIVERY','RETURN')
                      AND created_at >= ? AND created_at < ?""", BigDecimal.class, orgId, s, e);
            BigDecimal exp = jdbc.queryForObject("SELECT COALESCE(sum(amount), 0) FROM account_expense WHERE organization_id = ? AND entry_date >= ? AND entry_date < ?",
                    BigDecimal.class, orgId, a, b);
            BigDecimal gp = sales.subtract(cogs), np = gp.subtract(exp);
            if (sales.signum() == 0 && cogs.signum() == 0 && exp.signum() == 0) continue;
            rows.add(row("%d-%02d".formatted(year, m), sales, cogs, gp, exp, np));
            BigDecimal[] v = {sales, cogs, gp, exp, np};
            for (int i = 0; i < 5; i++) tot[i] = tot[i].add(v[i]);
        }
        return new Table("Profit & loss " + year, "Sales excl. VAT − cost of goods sold (moving average) − expenses (incl. salaries)",
                List.of("Month", "Sales", "Cost of goods sold", "Gross profit", "Expenses", "Net profit"), rows,
                row("Total", tot[0], tot[1], tot[2], tot[3], tot[4]));
    }

    /** Gross profit by invoice, customer, product or month: revenue excl. VAT − cost of the stock that left. */
    private Table grossProfit(Long orgId, Timestamp start, Timestamp end, String groupBy, String period) {
        String key = switch (groupBy) {
            case "customer" -> "customer";
            case "product" -> "product";
            case "month" -> "to_char(issued_at AT TIME ZONE 'Asia/Riyadh', 'YYYY-MM')";
            default -> "number";
        };
        String col = switch (groupBy) { case "customer" -> "Customer"; case "product" -> "Item"; case "month" -> "Month"; default -> "Invoice"; };
        List<List<Object>> rows = new ArrayList<>();
        BigDecimal[] tot = {BigDecimal.ZERO, BigDecimal.ZERO};
        jdbc.query("SELECT " + key + " AS k, sum(revenue) AS rev, sum(cost) AS cost FROM (" + GrossProfitController.LINES_SQL
                        + " WHERE i.organization_id = ? AND i.status NOT IN ('DRAFT','REJECTED') AND i.issued_at >= ? AND i.issued_at < ?) x"
                        + " GROUP BY 1 ORDER BY " + ("month".equals(groupBy) || "invoice".equals(groupBy) || "day".equals(groupBy) ? "1" : "sum(revenue) - sum(cost) DESC"),
                rs -> {
                    BigDecimal rev = rs.getBigDecimal("rev"), cost = rs.getBigDecimal("cost"), gp = rev.subtract(cost);
                    rows.add(row(rs.getString("k"), rev, cost, gp, GrossProfitController.pct(gp, rev).setScale(2, RoundingMode.HALF_UP) + " %"));
                    tot[0] = tot[0].add(rev);
                    tot[1] = tot[1].add(cost);
                }, orgId, start, end);
        BigDecimal gp = tot[0].subtract(tot[1]);
        return new Table("Gross profit by " + col.toLowerCase(), period, List.of(col, "Revenue", "Cost", "Gross profit", "GP %"), rows,
                row("Total", tot[0], tot[1], gp, GrossProfitController.pct(gp, tot[0]) + " %"));
    }

    private Table aging(Long orgId, boolean receivables, LocalDate today) {
        List<Map<String, Object>> docs = receivables ? jdbc.queryForList("""
                SELECT COALESCE(c.name_en, c.name_ar, 'Walk-in') AS party, i.number, COALESCE(i.due_date, (i.issued_at AT TIME ZONE 'Asia/Riyadh')::date) AS due,
                       i.total_incl_vat
                       + COALESCE((SELECT sum(CASE n.doc_type WHEN 'DEBIT_NOTE' THEN n.total_incl_vat ELSE -n.total_incl_vat END) FROM invoice n
                                   WHERE n.original_invoice_id = i.id AND n.status NOT IN ('DRAFT','REJECTED')), 0)
                       - COALESCE((SELECT sum(p.amount) FROM invoice_payment p WHERE p.invoice_id = i.id), 0) AS open
                FROM invoice i LEFT JOIN customer c ON c.id = i.customer_id
                WHERE i.organization_id = ? AND i.doc_type = 'INVOICE' AND i.status NOT IN ('DRAFT','REJECTED') AND i.closed_at IS NULL""", orgId)
                : jdbc.queryForList("""
                SELECT s.name AS party, v.number, COALESCE(v.due_date, v.invoice_date) AS due,
                       v.total_sar - COALESCE((SELECT sum(a.amount) FROM supplier_payment_allocation a JOIN supplier_payment p ON p.id = a.supplier_payment_id
                                               WHERE a.supplier_invoice_id = v.id AND p.status = 'POSTED'), 0)
                       - COALESCE((SELECT sum(cn.total_sar) FROM supplier_credit_note cn WHERE cn.supplier_invoice_id = v.id AND cn.status = 'POSTED'), 0) AS open
                FROM supplier_invoice v JOIN supplier s ON s.id = v.supplier_id WHERE v.organization_id = ? AND v.status = 'OPEN'""", orgId);
        Map<String, BigDecimal[]> by = new TreeMap<>();
        BigDecimal[] tot = new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (var d : docs) {
            BigDecimal open = (BigDecimal) d.get("open");
            if (open == null || open.compareTo(new BigDecimal("0.01")) < 0) continue;
            long days = ChronoUnit.DAYS.between(((java.sql.Date) d.get("due")).toLocalDate(), today);
            int bucket = days <= 0 ? 0 : days <= 30 ? 1 : days <= 60 ? 2 : days <= 90 ? 3 : 4;
            BigDecimal[] a = by.computeIfAbsent((String) d.get("party"), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            a[bucket] = a[bucket].add(open);
            a[5] = a[5].add(open);
            tot[bucket] = tot[bucket].add(open);
            tot[5] = tot[5].add(open);
        }
        List<List<Object>> rows = new ArrayList<>();
        by.forEach((k, a) -> rows.add(row(k, a[0], a[1], a[2], a[3], a[4], a[5])));
        return new Table(receivables ? "Receivables aging" : "Payables aging", "As of " + today,
                List.of(receivables ? "Customer" : "Supplier", "Not due", "1–30 days", "31–60", "61–90", "90+", "Total"), rows,
                row("Total", tot[0], tot[1], tot[2], tot[3], tot[4], tot[5]));
    }

    // --- dashboard -------------------------------------------------------------------------------

    @GetMapping("/erp-dashboard")
    Dashboard dashboard() {
        Long orgId = CurrentUser.orgId();
        LocalDate today = LocalDate.now(InvoiceApiApplication.KSA);
        Timestamp dayStart = Timestamp.from(today.atStartOfDay(InvoiceApiApplication.KSA).toInstant());
        Timestamp monthStart = Timestamp.from(today.withDayOfMonth(1).atStartOfDay(InvoiceApiApplication.KSA).toInstant());
        String salesSql = "SELECT COALESCE(sum(CASE doc_type WHEN 'CREDIT_NOTE' THEN -total_incl_vat ELSE total_incl_vat END), 0) FROM invoice"
                + " WHERE organization_id = ? AND status NOT IN ('DRAFT','REJECTED') AND issued_at >= ?";
        List<Tile> tiles = new ArrayList<>();
        tiles.add(new Tile("salesToday", "Sales today", q(salesSql, orgId, dayStart), "SAR", "/invoices"));
        tiles.add(new Tile("salesMonth", "Sales this month", q(salesSql, orgId, monthStart), "SAR", "/erp-reports?r=sales"));
        tiles.add(new Tile("invoicesMonth", "Invoices this month", q("SELECT count(*) FROM invoice WHERE organization_id = ? AND doc_type = 'INVOICE' AND status NOT IN ('DRAFT','REJECTED') AND issued_at >= ?", orgId, monthStart), null, "/invoices"));
        tiles.add(new Tile("receivables", "Receivables", total(aging(orgId, true, today)), "SAR", "/erp-reports?r=receivables"));
        tiles.add(new Tile("payables", "Payables", total(aging(orgId, false, today)), "SAR", "/erp-reports?r=payables"));
        tiles.add(new Tile("stockValue", "Stock value", q("SELECT COALESCE(sum(s.quantity * COALESCE(p.avg_cost, p.cost_price, 0)), 0) FROM stock_level s JOIN product p ON p.id = s.product_id WHERE p.organization_id = ?", orgId), "SAR", "/inventory"));
        tiles.add(new Tile("zatcaPending", "Waiting for ZATCA", q("SELECT count(*) FROM invoice WHERE organization_id = ? AND status = 'SIGNED'", orgId), null, "/invoices"));
        tiles.add(new Tile("zatcaRejected", "Rejected by ZATCA", q("SELECT count(*) FROM invoice WHERE organization_id = ? AND status = 'REJECTED'", orgId), null, "/invoices"));
        tiles.add(new Tile("openOrders", "Open sales orders", q("SELECT count(*) FROM sales_order WHERE organization_id = ? AND status IN ('OPEN','PARTIALLY_DELIVERED')", orgId), null, "/sales-orders"));
        tiles.add(new Tile("pendingPo", "POs awaiting approval", q("SELECT count(*) FROM purchase_order WHERE organization_id = ? AND status = 'PENDING'", orgId), null, "/purchase-orders"));
        tiles.add(new Tile("employees", "Employees", q("SELECT count(*) FROM employee WHERE organization_id = ? AND status = 'ACTIVE'", orgId), null, "/hr"));
        tiles.add(new Tile("payroll", "Last payroll (net)", q("""
                SELECT COALESCE((SELECT sum(l.net) FROM payroll_line l WHERE l.payroll_run_id = r.id), 0) FROM payroll_run r
                WHERE r.organization_id = ? ORDER BY r.year DESC, r.month DESC LIMIT 1""", orgId), "SAR", "/payroll"));
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            Timestamp a = Timestamp.from(d.atStartOfDay(InvoiceApiApplication.KSA).toInstant()), b = Timestamp.from(d.plusDays(1).atStartOfDay(InvoiceApiApplication.KSA).toInstant());
            trend.add(Map.of("date", d.toString(), "total", q(salesSql + " AND issued_at < ?", orgId, a, b)));
        }
        List<Map<String, Object>> byWh = jdbc.queryForList("""
                SELECT w.name, COALESCE(sum(s.quantity * COALESCE(p.avg_cost, p.cost_price, 0)), 0) AS value FROM warehouse w
                LEFT JOIN stock_level s ON s.warehouse_id = w.id LEFT JOIN product p ON p.id = s.product_id
                WHERE w.organization_id = ? AND w.active GROUP BY w.id, w.name ORDER BY value DESC""", orgId);
        List<Map<String, Object>> low = jdbc.queryForList("""
                SELECT p.id, p.number, p.name, w.name AS warehouse, s.quantity, p.low_stock_threshold FROM stock_level s
                JOIN product p ON p.id = s.product_id JOIN warehouse w ON w.id = s.warehouse_id
                WHERE p.organization_id = ? AND p.track_stock AND p.active AND p.low_stock_threshold IS NOT NULL AND s.quantity <= p.low_stock_threshold
                ORDER BY s.quantity LIMIT 15""", orgId);
        List<Map<String, Object>> ids = jdbc.queryForList("""
                SELECT id, name, code, id_number, id_expiry, passport_expiry FROM employee WHERE organization_id = ? AND status = 'ACTIVE'
                  AND (id_expiry <= ? OR passport_expiry <= ?) ORDER BY LEAST(COALESCE(id_expiry, '9999-12-31'), COALESCE(passport_expiry, '9999-12-31')) LIMIT 15""",
                orgId, today.plusDays(30), today.plusDays(30));
        List<Map<String, Object>> vehicles = jdbc.queryForList("""
                SELECT id, plate, name, istimara_expiry, insurance_expiry, mvpi_expiry FROM vehicle WHERE organization_id = ? AND active
                  AND (istimara_expiry <= ? OR insurance_expiry <= ? OR mvpi_expiry <= ?) ORDER BY plate LIMIT 15""",
                orgId, today.plusDays(30), today.plusDays(30), today.plusDays(30));
        // Charts: 12-month sales / purchases / gross profit, top customers and products, aging buckets.
        List<Map<String, Object>> monthly = new ArrayList<>();
        LocalDate first = today.withDayOfMonth(1).minusMonths(11);
        for (int i = 0; i < 12; i++) {
            LocalDate a = first.plusMonths(i), b = a.plusMonths(1);
            Timestamp s = Timestamp.from(a.atStartOfDay(InvoiceApiApplication.KSA).toInstant()), e = Timestamp.from(b.atStartOfDay(InvoiceApiApplication.KSA).toInstant());
            Map<String, Object> gp = jdbc.queryForMap("SELECT COALESCE(sum(revenue), 0) AS rev, COALESCE(sum(cost), 0) AS cost FROM (" + GrossProfitController.LINES_SQL
                    + " WHERE i.organization_id = ? AND i.status NOT IN ('DRAFT','REJECTED') AND i.issued_at >= ? AND i.issued_at < ?) x", orgId, s, e);
            BigDecimal purchases = q("""
                    SELECT COALESCE((SELECT sum(total_sar - vat_sar) FROM supplier_invoice WHERE organization_id = ? AND status = 'OPEN' AND invoice_date >= ? AND invoice_date < ?), 0)
                         - COALESCE((SELECT sum(total_sar - vat_sar) FROM supplier_credit_note WHERE organization_id = ? AND status = 'POSTED' AND credit_date >= ? AND credit_date < ?), 0)""",
                    orgId, a, b, orgId, a, b);
            BigDecimal rev = (BigDecimal) gp.get("rev"), cost = (BigDecimal) gp.get("cost");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", a.toString().substring(0, 7));
            m.put("sales", rev);
            m.put("purchases", purchases);
            m.put("grossProfit", rev.subtract(cost));
            monthly.add(m);
        }
        Timestamp yearStart = Timestamp.from(today.withDayOfYear(1).atStartOfDay(InvoiceApiApplication.KSA).toInstant());
        List<Map<String, Object>> topCustomers = jdbc.queryForList("SELECT customer AS name, sum(revenue) AS value FROM (" + GrossProfitController.LINES_SQL
                + " WHERE i.organization_id = ? AND i.status NOT IN ('DRAFT','REJECTED') AND i.issued_at >= ?) x GROUP BY customer HAVING sum(revenue) > 0"
                + " ORDER BY value DESC LIMIT 5", orgId, yearStart);
        List<Map<String, Object>> topProducts = jdbc.queryForList("SELECT product AS name, sum(revenue) AS value, sum(revenue) - sum(cost) AS gp FROM ("
                + GrossProfitController.LINES_SQL + " WHERE i.organization_id = ? AND i.status NOT IN ('DRAFT','REJECTED') AND i.issued_at >= ?) x"
                + " GROUP BY product HAVING sum(revenue) > 0 ORDER BY value DESC LIMIT 5", orgId, yearStart);
        return new Dashboard(tiles, trend, byWh, low, ids, vehicles, monthly, topCustomers, topProducts,
                buckets(aging(orgId, true, today)), buckets(aging(orgId, false, today)));
    }

    private static List<Map<String, Object>> buckets(Table aging) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int c = 1; c < aging.columns().size() - 1; c++)
            out.add(Map.of("bucket", aging.columns().get(c), "value", aging.totals().get(c)));
        return out;
    }

    // --- quick search ----------------------------------------------------------------------------

    @GetMapping("/search")
    List<Hit> search(@RequestParam String q) {
        Long orgId = CurrentUser.orgId();
        if (q == null || q.trim().length() < 2) return List.of();
        String like = "%" + q.trim().toLowerCase() + "%";
        List<Hit> out = new ArrayList<>();
        out.addAll(jdbc.query("SELECT id, COALESCE(name_en, name_ar) AS n, vat_number, phone FROM customer WHERE organization_id = ? AND (lower(COALESCE(name_en,'')) LIKE ? OR lower(name_ar) LIKE ? OR vat_number LIKE ? OR phone LIKE ?) LIMIT 5",
                (rs, i) -> new Hit("Customer", rs.getString("n"), Objects.toString(rs.getString("vat_number"), Objects.toString(rs.getString("phone"), "")), "/customers?q=" + rs.getString("n")),
                orgId, like, like, like, like));
        out.addAll(jdbc.query("SELECT id, name, number, barcode FROM product WHERE organization_id = ? AND (lower(name) LIKE ? OR lower(number) LIKE ? OR barcode LIKE ?) LIMIT 5",
                (rs, i) -> new Hit("Product", rs.getString("name"), Objects.toString(rs.getString("number"), ""), "/products"), orgId, like, like, like));
        out.addAll(jdbc.query("SELECT id, number, total_incl_vat FROM invoice WHERE organization_id = ? AND lower(COALESCE(number,'')) LIKE ? ORDER BY id DESC LIMIT 5",
                (rs, i) -> new Hit("Invoice", rs.getString("number"), rs.getBigDecimal("total_incl_vat") + " SAR", "/invoices/" + rs.getLong("id")), orgId, like));
        for (String[] d : new String[][]{{"quotation", "Quotation", "/quotations/"}, {"sales_order", "Sales order", "/sales-orders/"},
                {"delivery_note", "Delivery note", "/delivery-notes/"}, {"purchase_order", "Purchase order", "/purchase-orders/"},
                {"goods_receipt", "Goods receipt", "/goods-receipts/"}, {"supplier_invoice", "Supplier invoice", "/supplier-invoices/"}})
            out.addAll(jdbc.query("SELECT id, number FROM " + d[0] + " WHERE organization_id = ? AND lower(number) LIKE ? ORDER BY id DESC LIMIT 3",
                    (rs, i) -> new Hit(d[1], rs.getString("number"), "", d[2] + rs.getLong("id")), orgId, like));
        out.addAll(jdbc.query("SELECT id, name, code FROM supplier WHERE organization_id = ? AND (lower(name) LIKE ? OR lower(code) LIKE ?) LIMIT 5",
                (rs, i) -> new Hit("Supplier", rs.getString("name"), rs.getString("code"), "/suppliers"), orgId, like, like));
        out.addAll(jdbc.query("SELECT id, name, code, id_number FROM employee WHERE organization_id = ? AND (lower(name) LIKE ? OR lower(code) LIKE ? OR id_number LIKE ?) LIMIT 5",
                (rs, i) -> new Hit("Employee", rs.getString("name"), rs.getString("code"), "/hr"), orgId, like, like, like));
        return out;
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** A single number; no row (e.g. no payroll run yet on a fresh database) counts as zero. */
    private BigDecimal q(String sql, Object... args) {
        List<Object> v = jdbc.queryForList(sql, Object.class, args);
        return v.isEmpty() || v.getFirst() == null ? BigDecimal.ZERO : new BigDecimal(v.getFirst().toString());
    }

    private static BigDecimal total(Table t) {
        return t.totals() == null ? BigDecimal.ZERO : (BigDecimal) t.totals().getLast();
    }

    private static List<Object> row(Object... cells) {
        List<Object> r = new ArrayList<>();
        for (Object c : cells) r.add(c instanceof Long l ? BigDecimal.valueOf(l) : c);
        return r;
    }

    /** A table whose numeric columns from {@code firstSum} on are summed into the totals row. */
    private static Table table(String title, String subtitle, List<String> cols, List<List<Object>> rows, int firstSum) {
        List<Object> tot = new ArrayList<>();
        tot.add("Total");
        for (int c = 1; c < cols.size(); c++) {
            if (c < firstSum) { tot.add(""); continue; }
            BigDecimal s = BigDecimal.ZERO;
            boolean num = false;
            for (List<Object> r : rows) if (c < r.size() && r.get(c) instanceof BigDecimal b) { s = s.add(b); num = true; }
            tot.add(num ? s : "");
        }
        return new Table(title, subtitle, cols, rows, tot);
    }

    private static void put(org.apache.poi.ss.usermodel.Row r, List<Object> cells, org.apache.poi.ss.usermodel.CellStyle style) {
        for (int i = 0; i < cells.size(); i++) {
            var c = r.createCell(i);
            if (cells.get(i) instanceof BigDecimal b) c.setCellValue(b.doubleValue());
            else c.setCellValue(cells.get(i) == null ? "" : cells.get(i).toString());
            if (style != null) c.setCellStyle(style);
        }
    }

    private static String tr(List<Object> row, boolean bold) {
        StringBuilder b = new StringBuilder("<tr>");
        for (Object c : row)
            b.append("<td style=\"border-bottom:1px solid #e3e7ef; padding:3px 4px;").append(c instanceof BigDecimal ? " text-align:right;" : "")
                    .append(bold ? " font-weight:700;" : "").append("\">")
                    .append(c instanceof BigDecimal d ? LetterheadPdf.money(d).replace(" SAR", "") : LetterheadPdf.esc(c == null ? "" : c.toString())).append("</td>");
        return b.append("</tr>").toString();
    }
}
