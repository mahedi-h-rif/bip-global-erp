package com.fatoora.api.report;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.invoice.InvoiceFigures;
import com.fatoora.api.payment.BalanceCalculator.Balance;
import com.fatoora.zatca.model.DocumentType;
import com.fatoora.zatca.model.InvoiceKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Sales figures for issued invoices, grouped by month, day and customer. Pure function, unit tested.
 *
 * <p>Each invoice counts in the period it was issued, at its value after credit and debit notes, so
 * net sales = collected + due for every row.
 */
public final class SalesReport {

    /** Which invoices to include. Hours are KSA local time; {@code hourFrom > hourTo} wraps past midnight. */
    public record Filter(LocalDate from, LocalDate to, Long customerId, InvoiceKind kind, Integer hourFrom, Integer hourTo,
                         Predicate<Long> customerMatch) {}

    /**
     * @param gross      invoice totals as issued (incl. VAT)
     * @param notes      debit notes minus credit notes raised against those invoices
     * @param net        gross + notes
     * @param vat        VAT on the invoices as issued
     * @param collected  payments received against them (capped at what they are worth)
     */
    public record Row(String key, String label, long invoices, BigDecimal gross, BigDecimal notes, BigDecimal net,
                      BigDecimal vat, BigDecimal collected, BigDecimal due) {}

    public record Result(LocalDate from, LocalDate to, Row totals, List<Row> months, List<Row> days, List<Row> customers) {}

    private SalesReport() {}

    public static Result build(Collection<InvoiceFigures> documents, Map<Long, Balance> balances, Filter f,
                               Function<Long, String> customerName) {
        List<InvoiceFigures> picked = documents.stream()
                .filter(d -> d.type() == DocumentType.INVOICE && d.counts() && d.issuedAt() != null && balances.containsKey(d.id()))
                .filter(d -> matches(d, f))
                .sorted(Comparator.comparing(InvoiceFigures::issuedAt))
                .toList();

        Map<String, Acc> months = new TreeMap<>(), days = new TreeMap<>(), customers = new TreeMap<>();
        Acc total = new Acc("total", "Total");
        for (InvoiceFigures d : picked) {
            Balance b = balances.get(d.id());
            ZonedDateTime at = d.issuedAt().atZone(InvoiceApiApplication.KSA);
            YearMonth ym = YearMonth.from(at);
            LocalDate day = at.toLocalDate();
            String ck = d.customerId() == null ? "walk-in" : String.valueOf(d.customerId());
            total.add(d, b);
            months.computeIfAbsent(ym.toString(), k -> new Acc(k, ym.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + " " + ym.getYear())).add(d, b);
            days.computeIfAbsent(day.toString(), k -> new Acc(k, k)).add(d, b);
            customers.computeIfAbsent(ck, k -> new Acc(k, d.customerId() == null ? "Walk-in customers" : customerName.apply(d.customerId()))).add(d, b);
        }
        List<Row> byCustomer = new ArrayList<>(customers.values().stream().map(Acc::row).toList());
        byCustomer.sort(Comparator.comparing(Row::net).reversed());
        return new Result(f.from(), f.to(), total.row(), months.values().stream().map(Acc::row).toList(),
                days.values().stream().map(Acc::row).toList(), byCustomer);
    }

    static boolean matches(InvoiceFigures d, Filter f) {
        ZonedDateTime at = d.issuedAt().atZone(InvoiceApiApplication.KSA);
        LocalDate day = at.toLocalDate();
        if (f.from() != null && day.isBefore(f.from())) return false;
        if (f.to() != null && day.isAfter(f.to())) return false;
        if (f.kind() != null && d.kind() != f.kind()) return false;
        if (f.customerId() != null && !f.customerId().equals(d.customerId())) return false;
        if (f.customerMatch() != null && !f.customerMatch().test(d.customerId())) return false;
        if (f.hourFrom() != null && f.hourTo() != null && !f.hourFrom().equals(f.hourTo())) {
            int h = at.getHour();
            boolean in = f.hourFrom() < f.hourTo() ? h >= f.hourFrom() && h < f.hourTo() : h >= f.hourFrom() || h < f.hourTo();
            if (!in) return false;
        }
        return true;
    }

    private static final class Acc {
        final String key, label;
        long invoices;
        BigDecimal gross = BigDecimal.ZERO, notes = BigDecimal.ZERO, vat = BigDecimal.ZERO,
                collected = BigDecimal.ZERO, due = BigDecimal.ZERO;

        Acc(String key, String label) {
            this.key = key;
            this.label = label;
        }

        void add(InvoiceFigures d, Balance b) {
            invoices++;
            gross = gross.add(b.total());
            notes = notes.add(b.notes());
            vat = vat.add(d.vatTotal());
            collected = collected.add(b.paid().min(b.adjusted()));
            due = due.add(b.due());
        }

        Row row() {
            return new Row(key, label, invoices, gross, notes, gross.add(notes).max(BigDecimal.ZERO), vat, collected, due);
        }
    }
}
