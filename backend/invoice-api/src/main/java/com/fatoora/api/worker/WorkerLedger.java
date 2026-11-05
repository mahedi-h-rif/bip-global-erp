package com.fatoora.api.worker;

import com.fatoora.api.pdf.LetterheadPdf;

import java.math.BigDecimal;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One year of the outside-worker account: work done, paid and due per worker and month. A month's due is
 * work minus paid whenever either is entered; a negative due is money paid ahead of the work (an advance).
 */
public final class WorkerLedger {

    public record Cell(int month, BigDecimal work, BigDecimal paid, BigDecimal due, BigDecimal days, BigDecimal rate,
                       String paidMethod) {}

    public record Row(Long id, int no, String name, String phone, String iqamaNumber, String nationality, String notes,
                      boolean active, boolean daily, BigDecimal dailyRate, List<Cell> months, BigDecimal work,
                      BigDecimal paid, BigDecimal due) {}

    public record Totals(BigDecimal work, BigDecimal paid, BigDecimal due) {}

    public record Result(int year, List<Integer> years, List<Row> workers, List<Totals> months, Totals total,
                         BigDecimal outstanding, BigDecimal advances, int workerCount, int activeCount) {}

    private WorkerLedger() {}

    public static Result build(int year, List<Integer> years, List<Worker> workers, Collection<WorkerMonth> entries) {
        Map<Long, Map<Integer, WorkerMonth>> byWorker = entries.stream().collect(Collectors.groupingBy(
                WorkerMonth::getWorkerId, Collectors.toMap(WorkerMonth::getMonth, m -> m)));
        BigDecimal[] mw = zeros(), mp = zeros();
        List<Row> rows = new ArrayList<>();
        BigDecimal outstanding = BigDecimal.ZERO, advances = BigDecimal.ZERO;
        int no = 0;
        for (Worker w : workers) {
            Map<Integer, WorkerMonth> months = byWorker.getOrDefault(w.getId(), Map.of());
            List<Cell> cells = new ArrayList<>(12);
            BigDecimal work = BigDecimal.ZERO, paid = BigDecimal.ZERO;
            for (int m = 1; m <= 12; m++) {
                WorkerMonth e = months.get(m);
                BigDecimal cw = e == null ? null : e.getWork(), cp = e == null ? null : e.getPaid();
                cells.add(new Cell(m, cw, cp, cw == null && cp == null ? null : nz(cw).subtract(nz(cp)),
                        e == null ? null : e.getDays(), e == null ? null : e.getRate(),
                        e == null || e.getPaidMethod() == null ? null : e.getPaidMethod().name()));
                work = work.add(nz(cw));
                paid = paid.add(nz(cp));
                mw[m - 1] = mw[m - 1].add(nz(cw));
                mp[m - 1] = mp[m - 1].add(nz(cp));
            }
            BigDecimal due = work.subtract(paid);
            if (due.signum() > 0) outstanding = outstanding.add(due);
            else advances = advances.add(due.negate());
            rows.add(new Row(w.getId(), ++no, w.getName(), w.getPhone(), w.getIqamaNumber(), w.getNationality(),
                    w.getNotes(), w.isActive(), w.isDaily(), w.getDailyRate(), cells, work, paid, due));
        }
        List<Totals> monthTotals = new ArrayList<>(12);
        BigDecimal tw = BigDecimal.ZERO, tp = BigDecimal.ZERO;
        for (int i = 0; i < 12; i++) {
            monthTotals.add(new Totals(mw[i], mp[i], mw[i].subtract(mp[i])));
            tw = tw.add(mw[i]);
            tp = tp.add(mp[i]);
        }
        int active = (int) workers.stream().filter(Worker::isActive).count();
        return new Result(year, years, rows, monthTotals, new Totals(tw, tp, tw.subtract(tp)), outstanding, advances,
                workers.size(), active);
    }

    /** The ledger on the company letterhead: summary figures, the balance of each worker, and the month totals. */
    static String html(Result r) {
        StringBuilder s = new StringBuilder();
        s.append("<h1 style=\"text-align:center\">Outside Worker Ledger ").append(r.year()).append("</h1>")
                .append("<p class=\"muted\" style=\"text-align:center\">Annual work value, payments and balances · Currency: SAR</p>");

        s.append("<table style=\"margin:10px 0 4px 0\"><tr>")
                .append(kpi("Total work value", r.total().work()))
                .append(kpi("Total paid", r.total().paid()))
                .append(kpi("Outstanding to workers", r.outstanding()))
                .append(kpi("Paid in advance", r.advances()))
                .append("<td style=\"padding:4px\"><div style=\"border:1px solid #e3e7ef; background:#f6f8fb; padding:6px 8px\">")
                .append("<div class=\"muted\" style=\"font-size:7.5pt\">Workers</div><div style=\"font-size:12pt; font-weight:700\">")
                .append(r.workerCount()).append("</div></div></td></tr></table>");

        s.append("<h2>Balance by worker</h2><table style=\"font-size:8.5pt\">")
                .append(head("No.", "Worker", "Work value", "Paid", "Balance"));
        for (Row w : r.workers()) {
            s.append("<tr style=\"page-break-inside:avoid\">").append(td(String.valueOf(w.no()), false))
                    .append(td(LetterheadPdf.esc(w.name()) + (w.active() ? "" : " <span class=\"muted\">(inactive)</span>"), false))
                    .append(td(LetterheadPdf.money(w.work()), true)).append(td(LetterheadPdf.money(w.paid()), true))
                    .append(balance(w.due())).append("</tr>");
        }
        s.append(totalRow("Company total", r.total())).append("</table>");

        s.append("<div style=\"page-break-inside:avoid\"><h2>Month by month</h2><table style=\"font-size:8.5pt\">")
                .append(head("", "Month", "Work value", "Paid", "Balance"));
        for (int i = 0; i < 12; i++) {
            Totals t = r.months().get(i);
            boolean empty = t.work().signum() == 0 && t.paid().signum() == 0;
            s.append("<tr>").append(td("", false))
                    .append(td(Month.of(i + 1).getDisplayName(TextStyle.FULL, Locale.ENGLISH), false))
                    .append(td(empty ? "–" : LetterheadPdf.money(t.work()), true))
                    .append(td(empty ? "–" : LetterheadPdf.money(t.paid()), true))
                    .append(empty ? td("–", true) : balance(t.due())).append("</tr>");
        }
        s.append(totalRow("Year total", r.total())).append("</table></div>");
        s.append("<p class=\"muted\" style=\"margin-top:10px; font-size:8pt\">Work value = value of work completed · Paid = amount given"
                + " · Balance = work value minus paid; a negative balance (in brackets) is money paid in advance.</p>");
        return s.toString();
    }

    /** One month's payment statement: each worker with figures that month, a signature space, and the totals. */
    static String statementHtml(Result r, int month) {
        String name = Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        StringBuilder s = new StringBuilder();
        s.append("<h1 style=\"text-align:center\">Outside Worker Payment Statement</h1>")
                .append("<p class=\"muted\" style=\"text-align:center\">").append(name).append(" ").append(r.year())
                .append(" · Currency: SAR</p>");
        s.append("<table style=\"font-size:9pt; margin-top:10px\">")
                .append(head("No.", "Worker", "Work value", "Paid", "Balance")
                        .replace("</tr>", "<th style=\"background:#e9edf4; padding:4px 6px; width:22%\">Signature</th></tr>"));
        BigDecimal tw = BigDecimal.ZERO, tp = BigDecimal.ZERO;
        int no = 0;
        for (Row w : r.workers()) {
            Cell c = w.months().get(month - 1);
            if (c.work() == null && c.paid() == null) continue;
            tw = tw.add(nz(c.work()));
            tp = tp.add(nz(c.paid()));
            s.append("<tr style=\"page-break-inside:avoid\">").append(td(String.valueOf(++no), false))
                    .append(td(LetterheadPdf.esc(w.name()), false))
                    .append(td(LetterheadPdf.money(nz(c.work())), true)).append(td(LetterheadPdf.money(nz(c.paid())), true))
                    .append(balance(c.due()))
                    .append("<td style=\"padding:3px 6px; border-bottom:1px solid #c9d0dc; height:22px\"></td></tr>");
        }
        if (no == 0) s.append("<tr><td colspan=\"6\" style=\"padding:10px; text-align:center\" class=\"muted\">No work or payments entered for this month.</td></tr>");
        s.append("<tr style=\"font-weight:700; background:#f6f8fb\">").append(td("", false)).append(td("Month total", false))
                .append(td(LetterheadPdf.money(tw), true)).append(td(LetterheadPdf.money(tp), true))
                .append(balance(tw.subtract(tp))).append(td("", false)).append("</tr></table>");
        s.append("<table style=\"margin-top:28px; page-break-inside:avoid\"><tr>")
                .append("<td style=\"width:50%\"></td><td style=\"text-align:center\">")
                .append("<div style=\"border-top:1px solid #1b2233; margin:0 20px; padding-top:4px\">Authorized signature · توقيع المعتمد</div>")
                .append("</td></tr></table>");
        s.append("<p class=\"muted\" style=\"margin-top:10px; font-size:8pt\">Balance = work value minus paid; a figure in brackets was paid in advance.</p>");
        return s.toString();
    }

    private static String kpi(String label, BigDecimal v) {
        return "<td style=\"padding:4px\"><div style=\"border:1px solid #e3e7ef; background:#f6f8fb; padding:6px 8px\">"
                + "<div class=\"muted\" style=\"font-size:7.5pt\">" + label + "</div>"
                + "<div style=\"font-size:12pt; font-weight:700\">" + LetterheadPdf.money(v) + "</div></div></td>";
    }

    private static String head(String... cols) {
        StringBuilder h = new StringBuilder("<tr>");
        for (int i = 0; i < cols.length; i++)
            h.append("<th style=\"background:#e9edf4; padding:4px 6px; text-align:").append(i >= 2 ? "right" : "left")
                    .append(i == 0 ? "; width:8%" : "").append("\">").append(cols[i]).append("</th>");
        return h.append("</tr>").toString();
    }

    private static String td(String html, boolean right) {
        return "<td style=\"padding:3px 6px; border-bottom:1px solid #eef1f6" + (right ? "; text-align:right" : "") + "\">" + html + "</td>";
    }

    private static String balance(BigDecimal v) {
        if (v == null) return td("", true);
        String text = v.signum() < 0 ? "(" + LetterheadPdf.money(v.negate()) + ")" : LetterheadPdf.money(v);
        return "<td style=\"padding:3px 6px; border-bottom:1px solid #eef1f6; text-align:right; font-weight:700; color:"
                + (v.signum() > 0 ? "#b42318" : v.signum() < 0 ? "#067647" : "#1b2233") + "\">" + text + "</td>";
    }

    private static String totalRow(String label, Totals t) {
        return "<tr style=\"font-weight:700; background:#f6f8fb\">" + td("", false) + td(label, false)
                + td(LetterheadPdf.money(t.work()), true) + td(LetterheadPdf.money(t.paid()), true) + balance(t.due()) + "</tr>";
    }

    private static BigDecimal[] zeros() {
        BigDecimal[] a = new BigDecimal[12];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
