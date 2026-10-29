package com.fatoora.api.account;

import com.fatoora.api.pdf.LetterheadPdf;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The cash book of one month and of one year. Income is on a cash basis: what customers paid (received);
 * unpaid bills are customer dues. Worker payments from the Workers ledger count as an expense of their month.
 */
public final class AccountReport {

    public static final String WORKERS = "WORKERS";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public record Income(Long id, LocalDate date, String customerName, String mobile, String details, BigDecimal amount,
                         BigDecimal paid, BigDecimal due, AccountSource source) {}

    /** An expense row; the worker-payments row of a month has no id and category {@value #WORKERS}. */
    public record Expense(Long id, LocalDate date, String description, String category, BigDecimal amount,
                          AccountSource source) {}

    public record Customer(String name, String mobile, BigDecimal billed, BigDecimal paid, BigDecimal due, int jobs) {}

    public record Category(String category, BigDecimal amount) {}

    public record MonthTotals(BigDecimal billed, BigDecimal received, BigDecimal due, BigDecimal expense,
                              BigDecimal cashAfterExpense) {}

    public record MonthResult(int year, int month, List<Income> income, List<Expense> expenses, MonthTotals totals,
                              List<Customer> customers, List<Category> categories) {}

    public record YearMonth(int month, BigDecimal billed, BigDecimal received, BigDecimal expense, BigDecimal net,
                            Map<Long, BigDecimal> withdrawals) {}

    public record Withdrawal(Long id, Long partnerId, LocalDate date, BigDecimal amount, String reference,
                             String approvedBy, String notes, AccountSource source) {}

    public record PartnerRow(Long id, String name, boolean active, BigDecimal entitlement, BigDecimal withdrawn,
                             BigDecimal balance, List<Withdrawal> withdrawals) {}

    public record YearTotals(BigDecimal billed, BigDecimal received, BigDecimal due, BigDecimal expense, BigDecimal net,
                             BigDecimal drawings, BigDecimal retained) {}

    public record YearResult(int year, List<YearMonth> months, YearTotals totals, List<PartnerRow> partners,
                             List<Category> categories) {}

    private AccountReport() {}

    // --- month ------------------------------------------------------------------------------------

    public static MonthResult month(int year, int month, List<IncomeEntry> income, List<ExpenseEntry> expenses,
                                    BigDecimal workerPaid) {
        List<Income> in = income.stream().map(AccountReport::income).toList();
        List<Expense> out = new ArrayList<>(expenses.stream().map(AccountReport::expense).toList());
        if (workerPaid != null && workerPaid.signum() != 0)
            out.add(new Expense(null, monthEnd(year, month), "Worker payments (from Workers)", WORKERS, workerPaid, null));

        BigDecimal billed = sum(in.stream().map(Income::amount).toList());
        BigDecimal received = sum(in.stream().map(Income::paid).toList());
        BigDecimal due = sum(in.stream().map(Income::due).toList());
        BigDecimal spent = sum(out.stream().map(Expense::amount).toList());

        Map<String, Customer> byCustomer = new LinkedHashMap<>();
        for (Income i : in) {
            String key = i.customerName() == null ? "—" : i.customerName().strip().toLowerCase(Locale.ROOT);
            Customer c = byCustomer.get(key);
            byCustomer.put(key, c == null
                    ? new Customer(i.customerName() == null ? "—" : i.customerName().strip(), i.mobile(), i.amount(), i.paid(), i.due(), 1)
                    : new Customer(c.name(), c.mobile() != null ? c.mobile() : i.mobile(), c.billed().add(i.amount()),
                    c.paid().add(i.paid()), c.due().add(i.due()), c.jobs() + 1));
        }
        List<Customer> customers = byCustomer.values().stream()
                .sorted(Comparator.comparing(Customer::billed).reversed()).toList();
        return new MonthResult(year, month, in, out, new MonthTotals(billed, received, due, spent, received.subtract(spent)),
                customers, categories(out));
    }

    // --- year -------------------------------------------------------------------------------------

    public static YearResult year(int year, List<IncomeEntry> income, List<ExpenseEntry> expenses, BigDecimal[] workerPaid,
                                  List<Partner> partners, Map<Long, BigDecimal> entitlements, List<PartnerWithdrawal> withdrawals) {
        BigDecimal[] billed = zeros(), received = zeros(), spent = zeros();
        BigDecimal due = BigDecimal.ZERO;
        for (IncomeEntry i : income) {
            int m = i.getEntryDate().getMonthValue() - 1;
            billed[m] = billed[m].add(i.getAmount());
            received[m] = received[m].add(nz(i.getPaid()));
            due = due.add(dueOf(i.getAmount(), i.getPaid()));
        }
        List<Expense> all = new ArrayList<>();
        for (ExpenseEntry e : expenses) {
            int m = e.getEntryDate().getMonthValue() - 1;
            spent[m] = spent[m].add(e.getAmount());
            all.add(expense(e));
        }
        for (int m = 0; m < 12; m++) {
            BigDecimal w = workerPaid == null ? null : workerPaid[m];
            if (w != null && w.signum() != 0) {
                spent[m] = spent[m].add(w);
                all.add(new Expense(null, null, "Worker payments", WORKERS, w, null));
            }
        }

        List<YearMonth> months = new ArrayList<>(12);
        for (int m = 0; m < 12; m++) {
            Map<Long, BigDecimal> drawn = new LinkedHashMap<>();
            for (Partner p : partners) drawn.put(p.getId(), BigDecimal.ZERO);
            for (PartnerWithdrawal w : withdrawals)
                if (w.getEntryDate().getMonthValue() == m + 1) drawn.merge(w.getPartnerId(), w.getAmount(), BigDecimal::add);
            months.add(new YearMonth(m + 1, billed[m], received[m], spent[m], received[m].subtract(spent[m]), drawn));
        }

        List<PartnerRow> rows = new ArrayList<>();
        BigDecimal drawings = BigDecimal.ZERO;
        for (Partner p : partners) {
            List<Withdrawal> mine = withdrawals.stream().filter(w -> w.getPartnerId().equals(p.getId()))
                    .map(w -> new Withdrawal(w.getId(), w.getPartnerId(), w.getEntryDate(), w.getAmount(), w.getReference(),
                            w.getApprovedBy(), w.getNotes(), w.getSource())).toList();
            BigDecimal taken = sum(mine.stream().map(Withdrawal::amount).toList());
            BigDecimal entitled = entitlements.get(p.getId());
            drawings = drawings.add(taken);
            rows.add(new PartnerRow(p.getId(), p.getName(), p.isActive(), entitled, taken,
                    entitled == null ? null : entitled.subtract(taken), mine));
        }
        BigDecimal r = sum(List.of(received)), s = sum(List.of(spent));
        return new YearResult(year, months,
                new YearTotals(sum(List.of(billed)), r, due, s, r.subtract(s), drawings, r.subtract(s).subtract(drawings)),
                rows, categories(all));
    }

    // --- PDF --------------------------------------------------------------------------------------

    static String monthHtml(MonthResult r) {
        String title = Month.of(r.month()).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + r.year();
        MonthTotals t = r.totals();
        StringBuilder s = new StringBuilder();
        s.append("<h1 style=\"text-align:center\">Monthly Accounts · ").append(title).append("</h1>")
                .append("<p class=\"muted\" style=\"text-align:center\">Income, expenses and customer dues · Currency: SAR</p>")
                .append("<table style=\"margin:10px 0 4px 0\"><tr>")
                .append(kpi("Total bill / work", t.billed())).append(kpi("Received", t.received()))
                .append(kpi("Customer due", t.due())).append(kpi("Expenses", t.expense()))
                .append(kpi("Cash after expenses", t.cashAfterExpense())).append("</tr></table>");

        s.append("<h2>Income</h2><table style=\"font-size:8.5pt\">")
                .append(head(new String[]{"Date", "Customer", "Work / details", "Bill", "Paid", "Due"}, 3));
        for (Income i : r.income())
            s.append("<tr style=\"page-break-inside:avoid\">").append(td(date(i.date()), false))
                    .append(td(LetterheadPdf.esc(nz(i.customerName())) + (i.mobile() == null ? "" : "<br/><span class=\"muted\">" + LetterheadPdf.esc(i.mobile()) + "</span>"), false))
                    .append(td(LetterheadPdf.text(i.details()), false))
                    .append(td(LetterheadPdf.money(i.amount()), true)).append(td(LetterheadPdf.money(i.paid()), true))
                    .append(td(i.due().signum() == 0 ? "–" : "<b style=\"color:#b42318\">" + LetterheadPdf.money(i.due()) + "</b>", true)).append("</tr>");
        if (r.income().isEmpty()) s.append(empty(6));
        s.append(total(new String[]{"", "Month total", "", LetterheadPdf.money(t.billed()), LetterheadPdf.money(t.received()), LetterheadPdf.money(t.due())}, 3))
                .append("</table>");

        s.append("<h2>Expenses</h2><table style=\"font-size:8.5pt\">")
                .append(head(new String[]{"Date", "Expense / what was bought", "Category", "Amount"}, 3));
        for (Expense e : r.expenses())
            s.append("<tr style=\"page-break-inside:avoid\">").append(td(e.date() == null ? "" : date(e.date()), false))
                    .append(td(LetterheadPdf.text(e.description()), false)).append(td(categoryName(e.category()), false))
                    .append(td(LetterheadPdf.money(e.amount()), true)).append("</tr>");
        if (r.expenses().isEmpty()) s.append(empty(4));
        s.append(total(new String[]{"", "Month total", "", LetterheadPdf.money(t.expense())}, 3)).append("</table>");

        if (!r.customers().isEmpty()) {
            s.append("<div style=\"page-break-inside:avoid\"><h2>Customer-wise bill · paid · due</h2><table style=\"font-size:8.5pt\">")
                    .append(head(new String[]{"Customer", "Mobile", "Jobs", "Bill", "Paid", "Due", "Status"}, 2));
            for (Customer c : r.customers())
                s.append("<tr>").append(td(LetterheadPdf.esc(c.name()), false)).append(td(LetterheadPdf.esc(nz(c.mobile())), false))
                        .append(td(String.valueOf(c.jobs()), true)).append(td(LetterheadPdf.money(c.billed()), true))
                        .append(td(LetterheadPdf.money(c.paid()), true)).append(td(LetterheadPdf.money(c.due()), true))
                        .append(td(c.due().signum() > 0 ? "<b style=\"color:#b42318\">DUE</b>" : "<b style=\"color:#067647\">PAID</b>", true))
                        .append("</tr>");
            s.append("</table></div>");
        }
        return s.toString();
    }

    static String yearHtml(YearResult r) {
        YearTotals t = r.totals();
        StringBuilder s = new StringBuilder();
        s.append("<h1 style=\"text-align:center\">Yearly Cash Flow &amp; Partners' Account ").append(r.year()).append("</h1>")
                .append("<p class=\"muted\" style=\"text-align:center\">Income received, expenses and partner withdrawals · Currency: SAR</p>")
                .append("<table style=\"margin:10px 0 4px 0\"><tr>")
                .append(kpi("Income received", t.received())).append(kpi("Expenses", t.expense()))
                .append(kpi("Net profit", t.net())).append(kpi("Partner drawings", t.drawings()))
                .append(kpi("Kept in company", t.retained())).append("</tr></table>");

        List<String> cols = new ArrayList<>(List.of("Month", "Income", "Expenses", "Net"));
        for (PartnerRow p : r.partners()) cols.add(p.name());
        s.append("<h2>Month by month</h2><table style=\"font-size:8.5pt; page-break-inside:avoid\">")
                .append(head(cols.toArray(String[]::new), 1));
        for (YearMonth m : r.months()) {
            s.append("<tr>").append(td(Month.of(m.month()).getDisplayName(TextStyle.FULL, Locale.ENGLISH), false))
                    .append(td(dash(m.received()), true)).append(td(dash(m.expense()), true))
                    .append(td(m.received().signum() == 0 && m.expense().signum() == 0 ? "–" : signed(m.net()), true));
            for (PartnerRow p : r.partners()) s.append(td(dash(m.withdrawals().get(p.id())), true));
            s.append("</tr>");
        }
        List<String> tot = new ArrayList<>(List.of("Year total", LetterheadPdf.money(t.received()), LetterheadPdf.money(t.expense()), signed(t.net())));
        for (PartnerRow p : r.partners()) tot.add(LetterheadPdf.money(p.withdrawn()));
        s.append(total(tot.toArray(String[]::new), 1)).append("</table>");

        if (!r.partners().isEmpty()) {
            s.append("<div style=\"page-break-inside:avoid\"><h2>Partners' annual withdrawal account</h2><table style=\"font-size:8.5pt\">")
                    .append(head(new String[]{"Partner", "Annual entitlement", "Withdrawn", "Balance"}, 1));
            for (PartnerRow p : r.partners())
                s.append("<tr>").append(td(LetterheadPdf.esc(p.name()), false))
                        .append(td(p.entitlement() == null ? "–" : LetterheadPdf.money(p.entitlement()), true))
                        .append(td(LetterheadPdf.money(p.withdrawn()), true))
                        .append(td(p.balance() == null ? "–" : signed(p.balance()), true)).append("</tr>");
            s.append("</table></div>");
        }
        s.append("<p class=\"muted\" style=\"margin-top:10px; font-size:8pt\">Income is money received from customers. "
                + "Expenses include worker payments from the Workers ledger. Kept in company = net profit minus partner drawings.</p>");
        return s.toString();
    }

    // --- helpers ----------------------------------------------------------------------------------

    static String categoryName(String c) {
        return switch (c) {
            case WORKERS -> "Worker payments";
            case "MATERIALS" -> "Materials";
            case "VEHICLE" -> "Vehicle & fuel";
            case "FOOD" -> "Food";
            case "GOVERNMENT" -> "Government & fees";
            case "SALARY" -> "Salaries";
            case "RENT_UTILITIES" -> "Rent & utilities";
            case "OFFICE" -> "Office";
            default -> "Other";
        };
    }

    private static List<Category> categories(List<Expense> all) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (Expense e : all) m.merge(e.category(), e.amount(), BigDecimal::add);
        return m.entrySet().stream().map(e -> new Category(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(Category::amount).reversed()).toList();
    }

    private static Income income(IncomeEntry e) {
        BigDecimal paid = nz(e.getPaid());
        return new Income(e.getId(), e.getEntryDate(), e.getCustomerName(), e.getMobile(), e.getDetails(), e.getAmount(), paid,
                dueOf(e.getAmount(), paid), e.getSource());
    }

    private static Expense expense(ExpenseEntry e) {
        return new Expense(e.getId(), e.getEntryDate(), e.getDescription(), e.getCategory().name(), e.getAmount(), e.getSource());
    }

    /** What the customer still owes; paying more than the bill does not make it negative. */
    static BigDecimal dueOf(BigDecimal amount, BigDecimal paid) {
        return nz(amount).subtract(nz(paid)).max(BigDecimal.ZERO);
    }

    private static LocalDate monthEnd(int year, int month) {
        return java.time.YearMonth.of(year, month).atEndOfMonth();
    }

    private static String kpi(String label, BigDecimal v) {
        return "<td style=\"padding:3px\"><div style=\"border:1px solid #e3e7ef; background:#f6f8fb; padding:6px 8px\">"
                + "<div class=\"muted\" style=\"font-size:7pt\">" + label + "</div>"
                + "<div style=\"font-size:11pt; font-weight:700\">" + LetterheadPdf.money(v) + "</div></div></td>";
    }

    /** Header row; columns from {@code firstNumeric} on are right-aligned. */
    private static String head(String[] cols, int firstNumeric) {
        StringBuilder h = new StringBuilder("<tr>");
        for (int i = 0; i < cols.length; i++)
            h.append("<th style=\"background:#e9edf4; padding:4px 6px; text-align:").append(i >= firstNumeric ? "right" : "left")
                    .append("\">").append(LetterheadPdf.esc(cols[i])).append("</th>");
        return h.append("</tr>").toString();
    }

    private static String total(String[] cells, int firstNumeric) {
        StringBuilder h = new StringBuilder("<tr style=\"font-weight:700; background:#f6f8fb\">");
        for (int i = 0; i < cells.length; i++) h.append(td(cells[i], i >= firstNumeric));
        return h.append("</tr>").toString();
    }

    private static String td(String html, boolean right) {
        return "<td style=\"padding:3px 6px; border-bottom:1px solid #eef1f6; vertical-align:top" + (right ? "; text-align:right" : "") + "\">" + html + "</td>";
    }

    private static String empty(int cols) {
        return "<tr><td colspan=\"" + cols + "\" class=\"muted\" style=\"padding:8px; text-align:center\">Nothing entered.</td></tr>";
    }

    private static String dash(BigDecimal v) {
        return v == null || v.signum() == 0 ? "–" : LetterheadPdf.money(v);
    }

    private static String signed(BigDecimal v) {
        return v.signum() < 0 ? "<span style=\"color:#b42318\">(" + LetterheadPdf.money(v.negate()) + ")</span>" : LetterheadPdf.money(v);
    }

    private static String date(LocalDate d) {
        return d == null ? "" : DATE.format(d);
    }

    static BigDecimal sum(List<BigDecimal> values) {
        BigDecimal s = BigDecimal.ZERO;
        for (BigDecimal v : values) s = s.add(nz(v));
        return s;
    }

    private static BigDecimal[] zeros() {
        BigDecimal[] a = new BigDecimal[12];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
