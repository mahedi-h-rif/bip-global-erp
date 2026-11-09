package com.fatoora.api.product;

import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Units of measure (SAP B1 UoM groups, Odoo UoM categories). An item has one base unit — its {@code unit_code}, the
 * unit stock is counted in — plus alternative units, each holding {@code factor} base units (dozen = 12, carton = 24).
 * <p>Every document line stores the unit it was entered in and the factor at that moment; stock always moves
 * {@code quantity × factor} base units. The factor is resolved here on the server, never taken from the client.
 */
@RestController
@RequestMapping("/api/v1/products")
public class UomService {

    public record Uom(@NotBlank @Size(max = 10) @Pattern(regexp = "[A-Za-z0-9]{1,10}") String unitCode,
                      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal factor,
                      @DecimalMin("0") BigDecimal price, @Size(max = 60) String barcode,
                      Boolean salesDefault, Boolean purchaseDefault) {}

    /** One row per (item, unit) for the line editors; the base unit is included with factor 1. */
    public record ItemUnit(Long productId, String unitCode, BigDecimal factor, BigDecimal price, String barcode,
                           boolean base, boolean salesDefault, boolean purchaseDefault) {}

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public UomService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** All units of all items (base + alternatives), for the document line editors and the POS. */
    @GetMapping("/units")
    public List<ItemUnit> all() {
        Long orgId = CurrentUser.orgId();
        List<ItemUnit> out = new ArrayList<>(jdbc.query("""
                SELECT p.id, p.unit_code, p.unit_price, p.barcode,
                       NOT EXISTS (SELECT 1 FROM product_uom u WHERE u.product_id = p.id AND u.is_sales_default) AS sd,
                       NOT EXISTS (SELECT 1 FROM product_uom u WHERE u.product_id = p.id AND u.is_purchase_default) AS pd
                FROM product p WHERE p.organization_id = ?""",
                (rs, i) -> new ItemUnit(rs.getLong(1), rs.getString(2), BigDecimal.ONE, rs.getBigDecimal(3), rs.getString(4), true,
                        rs.getBoolean(5), rs.getBoolean(6)), orgId));
        out.addAll(jdbc.query("""
                SELECT u.product_id, u.unit_code, u.factor, COALESCE(u.price, round(p.unit_price * u.factor, 2)), u.barcode,
                       u.is_sales_default, u.is_purchase_default
                FROM product_uom u JOIN product p ON p.id = u.product_id WHERE p.organization_id = ? ORDER BY u.product_id, u.factor""",
                (rs, i) -> new ItemUnit(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getString(5), false,
                        rs.getBoolean(6), rs.getBoolean(7)), orgId));
        return out;
    }

    @GetMapping("/{id}/units")
    public List<Uom> list(@PathVariable Long id) {
        requireProduct(CurrentUser.orgId(), id);
        return jdbc.query("SELECT * FROM product_uom WHERE product_id = ? ORDER BY factor", (rs, i) -> new Uom(rs.getString("unit_code"),
                rs.getBigDecimal("factor"), rs.getBigDecimal("price"), rs.getString("barcode"), rs.getBoolean("is_sales_default"),
                rs.getBoolean("is_purchase_default")), id);
    }

    /** Replaces an item's alternative units. Lines already entered keep their own factor, so history never changes. */
    @PutMapping("/{id}/units")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    @Transactional
    public List<Uom> replace(@PathVariable Long id, @RequestBody List<@Valid Uom> units) {
        Long orgId = CurrentUser.orgId();
        String base = requireProduct(orgId, id);
        Set<String> seen = new HashSet<>();
        int sd = 0, pd = 0;
        for (Uom u : units) {
            String code = u.unitCode().trim().toUpperCase();
            if (code.equals(base)) throw ApiException.badRequest("uom_is_base", code + " is the item's base unit; it always has factor 1");
            if (!seen.add(code)) throw ApiException.badRequest("uom_duplicate", code + " is listed twice");
            if (Boolean.TRUE.equals(u.salesDefault())) sd++;
            if (Boolean.TRUE.equals(u.purchaseDefault())) pd++;
            if (u.barcode() != null && !u.barcode().isBlank() && barcodeTaken(orgId, u.barcode().trim(), id, code))
                throw ApiException.badRequest("barcode_taken", "Barcode " + u.barcode().trim() + " is already used by another item or unit");
        }
        if (sd > 1 || pd > 1) throw ApiException.badRequest("uom_default", "Choose at most one default sales unit and one default purchase unit");
        jdbc.update("DELETE FROM product_uom WHERE product_id = ?", id);
        for (Uom u : units)
            jdbc.update("""
                    INSERT INTO product_uom (product_id, unit_code, factor, price, barcode, is_sales_default, is_purchase_default)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", id, u.unitCode().trim().toUpperCase(), u.factor(), u.price(),
                    u.barcode() == null || u.barcode().isBlank() ? null : u.barcode().trim(),
                    Boolean.TRUE.equals(u.salesDefault()), Boolean.TRUE.equals(u.purchaseDefault()));
        audit.record(orgId, "PRODUCT_UNITS_UPDATED", "product", id,
                units.stream().map(u -> u.unitCode().toUpperCase() + "=" + u.factor().stripTrailingZeros().toPlainString()).reduce((a, b) -> a + ", " + b).orElse("base only"));
        return list(id);
    }

    // --- used by every document --------------------------------------------------------------------

    /**
     * The factor of {@code unitCode} for an item: 1 for its base unit (or a free-text line), the configured factor for
     * an alternative unit. An item line in a unit the item doesn't have is refused, so stock can never be miscounted.
     */
    public BigDecimal factor(Long productId, String unitCode) {
        if (productId == null) return BigDecimal.ONE;
        String code = unitCode == null || unitCode.isBlank() ? null : unitCode.trim().toUpperCase();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT unit_code, name FROM product WHERE id = ?", productId);
        if (rows.isEmpty()) throw ApiException.notFound("Product");
        Map<String, Object> p = rows.getFirst();
        if (code == null || code.equals(p.get("unit_code"))) return BigDecimal.ONE;
        List<BigDecimal> f = jdbc.queryForList("SELECT factor FROM product_uom WHERE product_id = ? AND unit_code = ?", BigDecimal.class, productId, code);
        if (f.isEmpty())
            throw ApiException.badRequest("unknown_uom", "\"%s\" is not sold or bought in %s. Its units: %s".formatted(p.get("name"), code, units(productId)));
        return f.getFirst();
    }

    /** Base-unit quantity of a line. */
    public static BigDecimal base(BigDecimal quantity, BigDecimal factor) {
        return factor == null ? quantity : quantity.multiply(factor).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private String units(Long productId) {
        List<String> u = new ArrayList<>(jdbc.queryForList("SELECT unit_code FROM product WHERE id = ?", String.class, productId));
        u.addAll(jdbc.queryForList("SELECT unit_code || ' (' || trim(trailing '.' from trim(trailing '0' from factor::text)) || ')' FROM product_uom WHERE product_id = ? ORDER BY factor",
                String.class, productId));
        return String.join(", ", u);
    }

    private boolean barcodeTaken(Long orgId, String barcode, Long productId, String unit) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM product WHERE organization_id = ? AND barcode = ? AND ?::bigint IS NOT NULL)
                    OR EXISTS (SELECT 1 FROM product_uom u JOIN product p ON p.id = u.product_id
                               WHERE p.organization_id = ? AND u.barcode = ? AND NOT (u.product_id = ? AND u.unit_code = ?))""",
                Boolean.class, orgId, barcode, productId, orgId, barcode, productId, unit));
    }

    private String requireProduct(Long orgId, Long id) {
        List<String> base = jdbc.queryForList("SELECT unit_code FROM product WHERE id = ? AND organization_id = ?", String.class, id, orgId);
        if (base.isEmpty()) throw ApiException.notFound("Product");
        return base.getFirst();
    }
}
