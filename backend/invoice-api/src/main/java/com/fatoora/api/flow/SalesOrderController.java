package com.fatoora.api.flow;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.customer.Customer;
import com.fatoora.api.customer.CustomerRepository;
import com.fatoora.api.inventory.StockService;
import com.fatoora.api.organization.Organization;
import com.fatoora.api.organization.OrganizationRepository;
import com.fatoora.api.pdf.InvoicePdfService;
import com.fatoora.api.pdf.LetterheadPdf;
import com.fatoora.api.quotation.QuotationController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Sales orders (SO-YYYY-NNNNNN): made from a quotation (Copy To) or on their own, delivered in one or more
 * deliveries. The status follows the delivered quantities.
 */
@RestController
@RequestMapping("/api/v1/sales-orders")
public class SalesOrderController {

    private static final String WRITE = "hasAnyRole('ADMIN','ACCOUNTANT','BRANCH')";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public record Line(Long id, Long quotationLineId, Long productId, @NotBlank @Size(max = 1000) String name,
                       @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity, String unitCode,
                       @NotNull @DecimalMin("0") BigDecimal unitPrice, @DecimalMin("0") @DecimalMax("100") BigDecimal discountPct,
                       @DecimalMin("0") BigDecimal vatRate,
                       /** Read-only: delivered (DELIVERED notes) and still open to deliver. */
                       BigDecimal delivered, BigDecimal remaining) {}

    public record Request(Long quotationId, Long customerId, @Size(max = 500) String clientName,
                          @Size(max = 100) String customerRef, @Size(max = 200) String salesEmployee, Long warehouseId,
                          LocalDate orderDate, LocalDate deliveryDate, @Size(max = 4000) String remarks,
                          @NotEmpty List<@Valid Line> lines) {}

    public record Dto(Long id, String number, Long quotationId, String quotationNumber, Long customerId, String customerName,
                      String clientName, String customerRef, String salesEmployee, Long warehouseId, LocalDate orderDate,
                      LocalDate deliveryDate, String status, String remarks, BigDecimal totalExclVat, BigDecimal vatTotal,
                      BigDecimal totalInclVat, String createdBy, Instant createdAt, List<Line> lines) {}

    private final JdbcTemplate jdbc;
    private final FlowService flow;
    private final StockService stock;
    private final CustomerRepository customers;
    private final OrganizationRepository orgs;
    private final LetterheadPdf letterhead;
    private final AuditService audit;

    public SalesOrderController(JdbcTemplate jdbc, FlowService flow, StockService stock, CustomerRepository customers,
                                OrganizationRepository orgs, LetterheadPdf letterhead, AuditService audit) {
        this.jdbc = jdbc;
        this.flow = flow;
        this.stock = stock;
        this.customers = customers;
        this.orgs = orgs;
        this.letterhead = letterhead;
        this.audit = audit;
    }

    @GetMapping
    List<Dto> list(@RequestParam(required = false) String status, @RequestParam(required = false) Long customerId) {
        Long orgId = CurrentUser.orgId();
        String owner = CurrentUser.owner();
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM sales_order WHERE organization_id = ? AND (?::text IS NULL OR status = ?::text)
                  AND (?::bigint IS NULL OR customer_id = ?::bigint) AND (?::text IS NULL OR lower(created_by) = lower(?::text))
                ORDER BY id DESC LIMIT 500""", Long.class, orgId, status, status, customerId, customerId, owner, owner);
        List<Dto> out = new ArrayList<>();
        for (Long id : ids) out.add(dto(orgId, id));
        return out;
    }

    @GetMapping("/{id}")
    Dto get(@PathVariable Long id) {
        return load(id);
    }

    /** Copy To: a new order's contents from a quotation, with only the quantities not yet ordered. */
    @GetMapping("/from-quotation/{quotationId}")
    Request prefill(@PathVariable Long quotationId) {
        Long orgId = CurrentUser.orgId();
        Map<String, Object> q = quotation(orgId, quotationId);
        Map<Long, BigDecimal> ordered = flow.orderedByQuotationLine(quotationId, null);
        List<Line> lines = jdbc.query("SELECT * FROM quotation_line WHERE quotation_id = ? ORDER BY position", (rs, i) -> {
            BigDecimal left = rs.getBigDecimal("quantity").subtract(ordered.getOrDefault(rs.getLong("id"), BigDecimal.ZERO));
            return new Line(null, rs.getLong("id"), (Long) rs.getObject("product_id"), rs.getString("name"), left,
                    rs.getString("unit_code"), rs.getBigDecimal("unit_price"), BigDecimal.ZERO, rs.getBigDecimal("vat_rate"), null, left);
        }, quotationId).stream().filter(l -> l.quantity().signum() > 0).toList();
        String remarks = joinLines((String) q.get("terms"), "Based on Quotation " + q.get("number") + ".");
        return new Request(quotationId, (Long) q.get("customer_id"), (String) q.get("client_name"), null, null, null,
                LocalDate.now(InvoiceApiApplication.KSA), null, remarks, lines);
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Dto create(@Valid @RequestBody Request req) {
        Long orgId = CurrentUser.orgId();
        validate(orgId, req, null);
        String number = stock.nextNumber(orgId, "SALES_ORDER", "SO");
        Long id = jdbc.queryForObject("""
                INSERT INTO sales_order (organization_id, number, order_date, created_by) VALUES (?, ?, ?, ?) RETURNING id""",
                Long.class, orgId, number, LocalDate.now(InvoiceApiApplication.KSA), CurrentUser.get().email());
        write(id, req);
        if (req.quotationId() != null)
            jdbc.update("UPDATE quotation SET status = 'ACCEPTED', updated_at = now() WHERE id = ? AND status IN ('DRAFT','SENT')", req.quotationId());
        audit.record(orgId, "SALES_ORDER_CREATED", "sales_order", id, number);
        return dto(orgId, id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    Dto update(@PathVariable Long id, @Valid @RequestBody Request req) {
        Dto d = load(id);
        if (!"OPEN".equals(d.status()) || hasDeliveries(id))
            throw ApiException.conflict("order_in_progress", "An order with deliveries can't be edited; cancel the deliveries first");
        Long orgId = CurrentUser.orgId();
        validate(orgId, req, id);
        write(id, req);
        audit.record(orgId, "SALES_ORDER_UPDATED", "sales_order", id, d.number());
        return dto(orgId, id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(WRITE)
    @Transactional
    Dto cancel(@PathVariable Long id) {
        Dto d = load(id);
        if (hasDeliveries(id))
            throw ApiException.conflict("order_delivered", "This order has deliveries. Cancel them first, or leave the order to close.");
        jdbc.update("UPDATE sales_order SET status = 'CANCELLED', updated_at = now() WHERE id = ?", id);
        audit.record(CurrentUser.orgId(), "SALES_ORDER_CANCELLED", "sales_order", id, d.number());
        return dto(CurrentUser.orgId(), id);
    }

    @GetMapping("/{id}/pdf")
    @Transactional(readOnly = true)
    ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        Dto d = load(id);
        Organization org = orgs.findById(CurrentUser.orgId()).orElseThrow();
        byte[] pdf = letterhead.render(org, "Sales order " + d.number(), html(d, org));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(d.number() + ".pdf").build().toString())
                .body(pdf);
    }

    // --- helpers -----------------------------------------------------------------------------------

    private void validate(Long orgId, Request req, Long exceptOrderId) {
        if (req.customerId() != null && customers.findByIdAndOrganizationId(req.customerId(), orgId).isEmpty())
            throw ApiException.notFound("Customer");
        if (req.customerId() == null && (req.clientName() == null || req.clientName().isBlank()))
            throw new ApiException.Validation(Map.of("clientName", "Choose a customer or enter the client's name"));
        if (req.warehouseId() != null) stock.requireWarehouse(orgId, req.warehouseId());
        for (Line l : req.lines())
            if (l.productId() != null && jdbc.queryForObject("SELECT count(*) FROM product WHERE id = ? AND organization_id = ?",
                    Integer.class, l.productId(), orgId) == 0) throw ApiException.notFound("Product");
        if (req.quotationId() == null) {
            for (Line l : req.lines()) if (l.quotationLineId() != null)
                throw ApiException.badRequest("quotation_line_without_quotation", "Quotation lines need the quotation");
            return;
        }
        quotation(orgId, req.quotationId());
        Map<Long, BigDecimal> quoted = new HashMap<>();
        jdbc.query("SELECT id, quantity FROM quotation_line WHERE quotation_id = ?",
                rs -> { quoted.put(rs.getLong(1), rs.getBigDecimal(2)); }, req.quotationId());
        Map<Long, BigDecimal> ordered = flow.orderedByQuotationLine(req.quotationId(), exceptOrderId);
        Map<Long, BigDecimal> asked = new HashMap<>();
        for (Line l : req.lines()) {
            if (l.quotationLineId() == null) continue; // extra lines (e.g. freight) may be added to an order from a quotation
            if (!quoted.containsKey(l.quotationLineId()))
                throw ApiException.badRequest("unknown_quotation_line", "A line does not belong to this quotation");
            asked.merge(l.quotationLineId(), l.quantity(), BigDecimal::add);
        }
        for (var e : asked.entrySet()) {
            BigDecimal left = quoted.get(e.getKey()).subtract(ordered.getOrDefault(e.getKey(), BigDecimal.ZERO));
            if (e.getValue().compareTo(left) > 0)
                throw ApiException.badRequest("over_order", "A line exceeds the quoted quantity still open (" + StockServicePlain.plain(left) + ")");
        }
    }

    private void write(Long id, Request req) {
        BigDecimal net = BigDecimal.ZERO, vat = BigDecimal.ZERO;
        jdbc.update("DELETE FROM sales_order_line WHERE sales_order_id = ?", id);
        int pos = 1;
        for (Line l : req.lines()) {
            BigDecimal disc = l.discountPct() == null ? BigDecimal.ZERO : l.discountPct();
            BigDecimal rate = l.vatRate() == null ? new BigDecimal("15") : l.vatRate();
            BigDecimal lineNet = lineNet(l.quantity(), l.unitPrice(), disc);
            net = net.add(lineNet);
            vat = vat.add(lineNet.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP));
            jdbc.update("""
                    INSERT INTO sales_order_line (sales_order_id, position, quotation_line_id, product_id, name, quantity, unit_code,
                                                  unit_price, discount_pct, vat_rate) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    id, pos++, l.quotationLineId(), l.productId(), l.name().strip(), l.quantity(),
                    l.unitCode() == null || l.unitCode().isBlank() ? "PCE" : l.unitCode(), l.unitPrice(), disc, rate);
        }
        jdbc.update("""
                UPDATE sales_order SET quotation_id = ?, customer_id = ?, client_name = ?, customer_ref = ?, sales_employee = ?,
                  warehouse_id = ?, order_date = COALESCE(?, order_date), delivery_date = ?, remarks = ?, total_excl_vat = ?,
                  vat_total = ?, total_incl_vat = ?, updated_at = now() WHERE id = ?""",
                req.quotationId(), req.customerId(), blank(req.clientName()), blank(req.customerRef()), blank(req.salesEmployee()),
                req.warehouseId(), req.orderDate(), req.deliveryDate(), blank(req.remarks()), net, vat, net.add(vat), id);
    }

    static BigDecimal lineNet(BigDecimal qty, BigDecimal price, BigDecimal discountPct) {
        BigDecimal gross = qty.multiply(price);
        return gross.subtract(gross.multiply(discountPct).divide(HUNDRED, 6, RoundingMode.HALF_UP)).setScale(2, RoundingMode.HALF_UP);
    }

    private boolean hasDeliveries(Long id) {
        return jdbc.queryForObject("SELECT count(*) FROM delivery_note WHERE sales_order_id = ? AND status <> 'CANCELLED'", Integer.class, id) > 0;
    }

    private Map<String, Object> quotation(Long orgId, Long quotationId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, number, status, customer_id, client_name, terms FROM quotation WHERE id = ? AND organization_id = ?", quotationId, orgId);
        if (rows.isEmpty()) throw ApiException.notFound("Quotation");
        String status = (String) rows.getFirst().get("status");
        if ("DECLINED".equals(status))
            throw ApiException.badRequest("quotation_declined", "A declined quotation can't become an order");
        return rows.getFirst();
    }

    private Dto load(Long id) {
        Dto d = dto(CurrentUser.orgId(), id);
        if (!CurrentUser.owns(d.createdBy())) throw ApiException.notFound("Sales order");
        return d;
    }

    Dto dto(Long orgId, Long id) {
        Map<Long, BigDecimal> delivered = flow.deliveredBySalesOrderLine(id, null, true);
        Map<Long, BigDecimal> reserved = flow.deliveredBySalesOrderLine(id, null, false);
        List<Line> lines = jdbc.query("SELECT * FROM sales_order_line WHERE sales_order_id = ? ORDER BY position", (rs, i) -> {
            long lid = rs.getLong("id");
            BigDecimal qty = rs.getBigDecimal("quantity");
            return new Line(lid, (Long) rs.getObject("quotation_line_id"), (Long) rs.getObject("product_id"), rs.getString("name"), qty,
                    rs.getString("unit_code"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("discount_pct"), rs.getBigDecimal("vat_rate"),
                    delivered.getOrDefault(lid, BigDecimal.ZERO), qty.subtract(reserved.getOrDefault(lid, BigDecimal.ZERO)));
        }, id);
        return jdbc.query("""
                SELECT s.*, q.number AS qnumber, COALESCE(c.name_en, c.name_ar) AS cname FROM sales_order s
                LEFT JOIN quotation q ON q.id = s.quotation_id LEFT JOIN customer c ON c.id = s.customer_id
                WHERE s.id = ? AND s.organization_id = ?""",
                (rs, i) -> new Dto(rs.getLong("id"), rs.getString("number"), (Long) rs.getObject("quotation_id"), rs.getString("qnumber"),
                        (Long) rs.getObject("customer_id"), rs.getString("cname"), rs.getString("client_name"), rs.getString("customer_ref"),
                        rs.getString("sales_employee"), (Long) rs.getObject("warehouse_id"), rs.getObject("order_date", LocalDate.class),
                        rs.getObject("delivery_date", LocalDate.class), rs.getString("status"), rs.getString("remarks"),
                        rs.getBigDecimal("total_excl_vat"), rs.getBigDecimal("vat_total"), rs.getBigDecimal("total_incl_vat"),
                        rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(), lines), id, orgId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Sales order"));
    }

    private String html(Dto d, Organization org) {
        Customer c = d.customerId() == null ? null : customers.findById(d.customerId()).orElse(null);
        String clientName = c != null ? QuotationController.displayName(c) : d.clientName();
        String brand = org.getBrandColor() == null ? "#0B2A6F" : org.getBrandColor();
        String client = LetterheadPdf.box("Customer", "العميل", new String[][]{
                {"Client", clientName}, {"VAT number", c == null ? null : c.getVatNumber()},
                {"Address", c == null ? null : QuotationController.address(c)}, {"Mobile", c == null ? null : c.getPhone()}});
        String meta = LetterheadPdf.box("Order details", "تفاصيل الطلب", new String[][]{
                {"Order no.", d.number()}, {"Date", DATE.format(d.orderDate())},
                {"Delivery date", d.deliveryDate() == null ? null : DATE.format(d.deliveryDate())},
                {"Customer PO", d.customerRef()}, {"Quotation", d.quotationNumber()}, {"Sales employee", d.salesEmployee()}});
        String th = "background:%s; color:#fff; font-size:8pt; padding:5px 4px; text-align:center".formatted(brand);
        StringBuilder rows = new StringBuilder();
        int n = 1;
        for (Line l : d.lines()) {
            BigDecimal net = lineNet(l.quantity(), l.unitPrice(), l.discountPct());
            BigDecimal vat = net.multiply(l.vatRate()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            rows.append("<tr>").append(td(Integer.toString(n++), "center"))
                    .append(td(LetterheadPdf.esc(l.name()), InvoicePdfService.startsRtl(l.name()) ? "right; direction:rtl" : "left"))
                    .append(td(StockServicePlain.plain(l.quantity()) + " " + LetterheadPdf.esc(l.unitCode()), "right"))
                    .append(td(LetterheadPdf.money(l.unitPrice()), "right"))
                    .append(td(l.discountPct().signum() == 0 ? "" : StockServicePlain.plain(l.discountPct()) + "%", "right"))
                    .append(td(LetterheadPdf.money(vat), "right"))
                    .append(td(LetterheadPdf.money(net.add(vat)), "right")).append("</tr>");
        }
        return """
                <table><tr><td><h1>Sales Order</h1></td><td class="rtl"><h1>أمر بيع</h1></td></tr></table>
                <table style="margin-top:6px"><tr>
                  <td style="width:55%%; vertical-align:top; padding-right:6px">%s</td><td style="width:45%%; vertical-align:top">%s</td>
                </tr></table>
                <table style="margin-top:12px">
                  <tr><th style="%s">#</th><th style="%s">Item<br/>الصنف</th><th style="%s">Qty<br/>الكمية</th><th style="%s">Unit price<br/>سعر الوحدة</th>
                  <th style="%s">Disc.<br/>خصم</th><th style="%s">VAT<br/>الضريبة</th><th style="%s">Total<br/>الإجمالي</th></tr>
                  %s
                </table>
                <table style="margin-top:10px; page-break-inside:avoid"><tr><td style="width:50%%"></td><td style="width:50%%"><table>
                  <tr><td style="padding:4px 8px">Total excl. VAT · الإجمالي غير شامل الضريبة</td><td style="padding:4px 8px; text-align:right; font-weight:700">%s SAR</td></tr>
                  <tr><td style="padding:4px 8px">VAT · ضريبة القيمة المضافة</td><td style="padding:4px 8px; text-align:right; font-weight:700">%s SAR</td></tr>
                  <tr><td style="padding:6px 8px; background:%s; color:#fff">Total incl. VAT · الإجمالي شامل الضريبة</td><td style="padding:6px 8px; background:%s; color:#fff; text-align:right; font-weight:700">%s SAR</td></tr>
                </table></td></tr></table>
                %s
                %s
                """.formatted(client, meta, th, th, th, th, th, th, th, rows,
                LetterheadPdf.money(d.totalExclVat()), LetterheadPdf.money(d.vatTotal()), brand, brand, LetterheadPdf.money(d.totalInclVat()),
                d.remarks() == null ? "" : "<h2>Remarks · ملاحظات</h2><p>" + LetterheadPdf.text(d.remarks()) + "</p>",
                LetterheadPdf.signatures(org, clientName == null ? "" : clientName));
    }

    private static String td(String content, String align) {
        return "<td style=\"border-bottom:1px solid #e3e7ef; padding:6px 4px; text-align:" + align + "\">" + content + "</td>";
    }

    static String joinLines(String a, String b) {
        if (a == null || a.isBlank()) return b;
        return a.strip() + "\n" + b;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Plain decimal formatting shared by the flow documents. */
    static final class StockServicePlain {
        static String plain(BigDecimal v) {
            return v.stripTrailingZeros().toPlainString();
        }
    }
}
