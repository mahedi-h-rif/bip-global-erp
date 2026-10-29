package com.fatoora.zatca.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VAT and monetary totals computed the way ZATCA's business rules check them:
 * <ul>
 *   <li>line net = round2(quantity × unit price)</li>
 *   <li>line VAT = round2(line net × rate)</li>
 *   <li>per-category VAT = round2(sum of line nets × rate) (BR-CO-17)</li>
 *   <li>document VAT = sum of per-category VAT</li>
 * </ul>
 */
public record InvoiceTotals(
        List<LineAmounts> lines,
        List<VatBreakdown> breakdown,
        BigDecimal lineExtension,
        BigDecimal taxExclusive,
        BigDecimal vatTotal,
        BigDecimal taxInclusive,
        BigDecimal payable) {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public record LineAmounts(InvoiceLine line, BigDecimal net, BigDecimal vat, BigDecimal gross) {}

    public record VatBreakdown(VatCategory category, BigDecimal rate, String exemptionCode, String exemptionReason,
                               BigDecimal taxable, BigDecimal vat) {}

    public static InvoiceTotals of(List<InvoiceLine> invoiceLines) {
        List<LineAmounts> lines = new ArrayList<>();
        Map<String, List<LineAmounts>> groups = new LinkedHashMap<>();

        for (InvoiceLine line : invoiceLines) {
            BigDecimal net = round(line.quantity().multiply(line.unitPrice()));
            BigDecimal vat = round(net.multiply(line.vatRate()).divide(HUNDRED));
            LineAmounts amounts = new LineAmounts(line, net, vat, net.add(vat));
            lines.add(amounts);
            String key = line.vatCategory() + "|" + line.vatRate().stripTrailingZeros().toPlainString()
                    + "|" + line.exemptionCode();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(amounts);
        }

        List<VatBreakdown> breakdown = new ArrayList<>();
        for (List<LineAmounts> group : groups.values()) {
            InvoiceLine first = group.getFirst().line();
            BigDecimal taxable = group.stream().map(LineAmounts::net).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal vat = round(taxable.multiply(first.vatRate()).divide(HUNDRED));
            breakdown.add(new VatBreakdown(first.vatCategory(), first.vatRate(), first.exemptionCode(),
                    first.exemptionReason(), round(taxable), vat));
        }

        BigDecimal lineExtension = round(lines.stream().map(LineAmounts::net).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal vatTotal = round(breakdown.stream().map(VatBreakdown::vat).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal taxInclusive = lineExtension.add(vatTotal);
        return new InvoiceTotals(List.copyOf(lines), List.copyOf(breakdown), lineExtension, lineExtension,
                vatTotal, taxInclusive, taxInclusive);
    }

    public static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
