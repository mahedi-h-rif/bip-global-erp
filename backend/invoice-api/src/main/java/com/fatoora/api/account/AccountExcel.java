package com.fatoora.api.account;

import com.fatoora.api.organization.Organization;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.extensions.XSSFCellBorder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The cash book as Excel workbooks with live formulas, and reading the company's own monthly and yearly sheets. */
final class AccountExcel {

    private static final String NUM = "#,##0.##;(#,##0.##);\"-\"";
    private static final String[] MONTHS = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};

    private AccountExcel() {}

    // --- export: month ------------------------------------------------------------------------------

    static byte[] writeMonth(AccountReport.MonthResult r, Organization org) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            String period = Month.of(r.month()).getDisplayName(TextStyle.FULL, Locale.ENGLISH).toUpperCase(Locale.ROOT) + " " + r.year();

            Sheet sum = wb.createSheet("Summary");
            Sheet inc = wb.createSheet("Income");
            Sheet exp = wb.createSheet("Expenses");
            Sheet cus = wb.createSheet("Customers");

            // Income: SL, DATE, CUSTOMER, MOBILE, DETAILS, TOTAL, PAID, DUE
            letterhead(inc, org, "MONTHLY INCOME — " + period, st);
            String[] ih = {"SL", "DATE", "CUSTOMER NAME", "MOBILE", "WORK / INVOICE DETAILS", "TOTAL AMOUNT", "PAID", "DUE"};
            header(inc, 5, ih, st);
            int n = Math.max(r.income().size(), 1), first = 7, last = first + n - 1;   // Excel rows
            for (int i = 0; i < n; i++) {
                Row row = inc.createRow(first - 1 + i);
                AccountReport.Income e = i < r.income().size() ? r.income().get(i) : null;
                num(row, 0, BigDecimal.valueOf(i + 1), st.center);
                date(row, 1, e == null ? null : e.date(), st);
                text(row, 2, e == null ? null : e.customerName(), st.input);
                text(row, 3, e == null ? null : e.mobile(), st.input);
                text(row, 4, e == null ? null : e.details(), st.wrap);
                num(row, 5, e == null ? null : e.amount(), st.inputNum);
                num(row, 6, e == null ? null : e.paid(), st.inputNum);
                int x = first + i;
                formula(row, 7, "IF(F" + x + "=\"\",\"\",MAX(0,F" + x + "-G" + x + "))", st.money);
            }
            Row it = inc.createRow(last);
            text(it, 0, "MONTH TOTAL", st.total);
            for (int c = 1; c <= 4; c++) text(it, c, null, st.total);
            inc.addMergedRegion(new CellRangeAddress(last, last, 0, 4));
            for (int c = 5; c <= 7; c++) formula(it, c, "SUM(" + col(c) + first + ":" + col(c) + last + ")", st.totalNum);
            widths(inc, 6, 12, 24, 14, 36, 14, 14, 14);
            inc.createFreezePane(0, 6);

            // Expenses: SL, DATE, DETAILS, CATEGORY, AMOUNT
            letterhead(exp, org, "MONTHLY EXPENDITURE — " + period, st);
            header(exp, 5, new String[]{"SL", "DATE", "EXPENSE DETAILS / WHAT BOUGHT", "CATEGORY", "AMOUNT"}, st);
            int m = Math.max(r.expenses().size(), 1), elast = first + m - 1;
            for (int i = 0; i < m; i++) {
                Row row = exp.createRow(first - 1 + i);
                AccountReport.Expense e = i < r.expenses().size() ? r.expenses().get(i) : null;
                num(row, 0, BigDecimal.valueOf(i + 1), st.center);
                date(row, 1, e == null ? null : e.date(), st);
                text(row, 2, e == null ? null : e.description(), st.wrap);
                text(row, 3, e == null ? null : AccountReport.categoryName(e.category()), st.input);
                num(row, 4, e == null ? null : e.amount(), st.inputNum);
            }
            Row et = exp.createRow(elast);
            text(et, 0, "MONTH TOTAL", st.total);
            for (int c = 1; c <= 3; c++) text(et, c, null, st.total);
            exp.addMergedRegion(new CellRangeAddress(elast, elast, 0, 3));
            formula(et, 4, "SUM(E" + first + ":E" + elast + ")", st.totalNum);
            widths(exp, 6, 12, 44, 20, 14);
            exp.createFreezePane(0, 6);

            // Customers: names from the income, figures by SUMIF so they follow edits.
            letterhead(cus, org, "CUSTOMER-WISE BILL • PAID • DUE — " + period, st);
            header(cus, 5, new String[]{"CUSTOMER NAME", "MOBILE", "TOTAL BILL", "TOTAL PAID", "TOTAL DUE", "JOBS", "STATUS"}, st);
            String range = "Income!$C$" + first + ":$C$" + last;
            int cn = Math.max(r.customers().size(), 1);
            for (int i = 0; i < cn; i++) {
                Row row = cus.createRow(first - 1 + i);
                AccountReport.Customer c = i < r.customers().size() ? r.customers().get(i) : null;
                int x = first + i;
                text(row, 0, c == null ? null : c.name(), st.name);
                text(row, 1, c == null ? null : c.mobile(), st.input);
                formula(row, 2, "IF(A" + x + "=\"\",\"\",SUMIF(" + range + ",A" + x + ",Income!$F$" + first + ":$F$" + last + "))", st.money);
                formula(row, 3, "IF(A" + x + "=\"\",\"\",SUMIF(" + range + ",A" + x + ",Income!$G$" + first + ":$G$" + last + "))", st.money);
                formula(row, 4, "IF(A" + x + "=\"\",\"\",SUMIF(" + range + ",A" + x + ",Income!$H$" + first + ":$H$" + last + "))", st.moneyBold);
                formula(row, 5, "IF(A" + x + "=\"\",\"\",COUNTIF(" + range + ",A" + x + "))", st.center);
                formula(row, 6, "IF(A" + x + "=\"\",\"\",IF(E" + x + ">0,\"DUE\",\"PAID\"))", st.center);
            }
            widths(cus, 26, 14, 14, 14, 14, 8, 10);

            // Summary: the month's figures, all formulas.
            letterhead(sum, org, "MONTHLY ACCOUNTS — " + period, st);
            String[][] kpis = {
                    {"TOTAL BILL / WORK", "Income!F" + (last + 1)},
                    {"TOTAL RECEIVED", "Income!G" + (last + 1)},
                    {"CUSTOMER DUE", "Income!H" + (last + 1)},
                    {"TOTAL EXPENSE", "Expenses!E" + (elast + 1)},
                    {"CASH AFTER EXPENSE", "Income!G" + (last + 1) + "-Expenses!E" + (elast + 1)}};
            for (int k = 0; k < kpis.length; k++) {
                Row row = sum.createRow(5 + k);
                text(row, 0, kpis[k][0], st.kpiLabel);
                formula(row, 1, kpis[k][1], st.kpiValue);
            }
            text(sum.createRow(11), 0, "Income counts money received. Enter figures in the blue cells on Income and Expenses; "
                    + "dues, totals and the customer list are formulas.", st.muted);
            widths(sum, 28, 20);
            wb.setActiveSheet(0);
            wb.write(out);
            return out.toByteArray();
        }
    }

    // --- export: year -------------------------------------------------------------------------------

    static byte[] writeYear(AccountReport.YearResult r, Organization org) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            Sheet sh = wb.createSheet("Yearly Company Account");
            letterhead(sh, org, "YEARLY COMPANY CASH FLOW & PARTNERS' ACCOUNT " + r.year(), st);
            List<String> heads = new ArrayList<>(List.of("MONTH", "TOTAL INCOME", "TOTAL EXPENSE", "NET / PROFIT"));
            for (AccountReport.PartnerRow p : r.partners()) heads.add("PARTNER - " + p.name().toUpperCase(Locale.ROOT));
            header(sh, 5, heads.toArray(String[]::new), st);
            int first = 7, last = first + 11, cols = heads.size();
            for (int i = 0; i < 12; i++) {
                AccountReport.YearMonth m = r.months().get(i);
                Row row = sh.createRow(first - 1 + i);
                int x = first + i;
                text(row, 0, Month.of(i + 1).getDisplayName(TextStyle.FULL, Locale.ENGLISH), st.name);
                num(row, 1, zeroNull(m.received()), st.inputNum);
                num(row, 2, zeroNull(m.expense()), st.inputNum);
                formula(row, 3, "IF(AND(B" + x + "=\"\",C" + x + "=\"\"),\"\",SUM(B" + x + ")-SUM(C" + x + "))", st.moneyBold);
                int c = 4;
                for (AccountReport.PartnerRow p : r.partners()) num(row, c++, zeroNull(m.withdrawals().get(p.id())), st.inputNum);
            }
            Row t = sh.createRow(last);
            text(t, 0, "YEAR TOTAL", st.total);
            for (int c = 1; c < cols; c++) formula(t, c, "SUM(" + col(c) + first + ":" + col(c) + last + ")", st.totalNum);

            int pr = last + 2;
            text(sh.createRow(pr), 0, "PARTNERS' ANNUAL WITHDRAWAL / DRAWING ACCOUNT", st.title);
            header(sh, pr + 1, new String[]{"PARTNER", "ANNUAL ENTITLEMENT", "AMOUNT WITHDRAWN", "BALANCE / DUE"}, st);
            int i = 0;
            for (AccountReport.PartnerRow p : r.partners()) {
                Row row = sh.createRow(pr + 2 + i);
                int x = pr + 3 + i;
                text(row, 0, p.name(), st.name);
                num(row, 1, p.entitlement(), st.inputNum);
                formula(row, 2, col(4 + i) + (last + 1), st.money);
                formula(row, 3, "IF(B" + x + "=\"\",\"\",B" + x + "-C" + x + ")", st.moneyBold);
                i++;
            }

            int sr = pr + 3 + Math.max(i, 1);
            text(sh.createRow(sr), 0, "MANAGEMENT SUMMARY", st.title);
            String drawings = r.partners().isEmpty() ? "0" : "SUM(" + col(4) + (last + 1) + ":" + col(3 + r.partners().size()) + (last + 1) + ")";
            String[][] kpis = {{"TOTAL INCOME", "B" + (last + 1)}, {"TOTAL EXPENSE", "C" + (last + 1)},
                    {"NET PROFIT / CASH RESULT", "D" + (last + 1)}, {"PARTNER DRAWINGS", drawings},
                    {"KEPT IN COMPANY", "D" + (last + 1) + "-" + drawings}};
            for (int k = 0; k < kpis.length; k++) {
                Row row = sh.createRow(sr + 1 + k);
                text(row, 0, kpis[k][0], st.kpiLabel);
                formula(row, 1, kpis[k][1], st.kpiValue);
            }
            text(sh.createRow(sr + 7), 0, "Income is money received; expenses include worker payments. "
                    + "Enter only monthly income, expenses and partner payments; net and totals are formulas.", st.muted);
            widths(sh, 22, 16, 16, 16);
            for (int c = 4; c < cols; c++) sh.setColumnWidth(c, 20 * 256);
            sh.createFreezePane(1, 6);
            wb.write(out);
            return out.toByteArray();
        }
    }

    // --- import -------------------------------------------------------------------------------------

    record ImportedIncome(LocalDate date, boolean dateGuessed, String customer, String mobile, String details,
                          BigDecimal amount, BigDecimal paid) {}

    record ImportedExpense(LocalDate date, boolean dateGuessed, String description, ExpenseEntry.Category category,
                           BigDecimal amount) {}

    record MonthSheet(String sheet, int year, int month, List<ImportedIncome> income, List<ImportedExpense> expenses,
                      List<String> memos) {}

    record YearSheet(List<BigDecimal> income, List<BigDecimal> expense, Map<String, List<BigDecimal>> withdrawals,
                     Map<String, BigDecimal> entitlements) {}

    record Parsed(List<MonthSheet> months, YearSheet year) {}

    private static final Pattern SHEET_MONTH = Pattern.compile("(?i)^(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[\\s._-]*'?(\\d{4}|\\d{2})?");
    private static final Pattern TEXT_DATE = Pattern.compile("(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{2,4})");

    /** Reads either kind of company workbook: monthly accounts (a sheet per month) or the yearly cash flow. */
    static Parsed read(InputStream in, int defaultYear) throws IOException {
        try (Workbook wb = WorkbookFactory.create(in)) {
            DataFormatter fmt = new DataFormatter(Locale.ENGLISH);
            List<MonthSheet> months = new ArrayList<>();
            YearSheet year = null;
            for (Sheet sh : wb) {
                Map<String, Integer> h = new LinkedHashMap<>();
                int head = -1;
                for (int r = 0; r <= Math.min(sh.getLastRowNum(), 30) && head < 0; r++) {
                    Row row = sh.getRow(r);
                    if (row == null) continue;
                    for (Cell c : row) {
                        String v = fmt.formatCellValue(c).strip().toUpperCase(Locale.ROOT);
                        if (v.equals("CUSTOMER NAME") || v.equals("TOTAL INCOME")) head = r;
                    }
                    if (head >= 0) for (Cell c : row) {
                        String v = fmt.formatCellValue(c).strip().toUpperCase(Locale.ROOT);
                        if (!v.isEmpty()) h.putIfAbsent(v, c.getColumnIndex());
                        if (v.equals("DATE") && h.containsKey("DATE") && h.get("DATE") != c.getColumnIndex()) h.put("DATE#2", c.getColumnIndex());
                    }
                }
                if (head < 0) continue;
                if (h.containsKey("CUSTOMER NAME")) months.add(monthSheet(sh, h, head, fmt, defaultYear));
                else if (year == null) year = yearSheet(sh, h, head, fmt);
            }
            if (months.isEmpty() && year == null)
                throw new IllegalArgumentException("This file has no monthly accounts (CUSTOMER NAME) or yearly cash flow (TOTAL INCOME) table");
            return new Parsed(months, year);
        }
    }

    private static MonthSheet monthSheet(Sheet sh, Map<String, Integer> h, int head, DataFormatter fmt, int defaultYear) {
        // The month: a date near the top (the sheet's month heading), else the sheet name.
        LocalDate base = null;
        for (int r = 0; r < head && base == null; r++) {
            Row row = sh.getRow(r);
            if (row == null) continue;
            for (Cell c : row) if (c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
                base = c.getLocalDateTimeCellValue().toLocalDate();
                break;
            }
        }
        if (base == null) {
            Matcher m = SHEET_MONTH.matcher(sh.getSheetName().strip());
            if (!m.find()) throw new IllegalArgumentException("Cannot tell which month sheet \"" + sh.getSheetName() + "\" is");
            int month = List.of(MONTHS).indexOf(m.group(1).toUpperCase(Locale.ROOT)) + 1;
            String y = m.group(2);
            base = LocalDate.of(y == null ? defaultYear : y.length() == 2 ? 2000 + Integer.parseInt(y) : Integer.parseInt(y), month, 1);
        }
        int year = base.getYear(), month = base.getMonthValue();

        int cDate = h.getOrDefault("DATE", -1), cName = h.get("CUSTOMER NAME"), cMobile = h.getOrDefault("MOBILE", -1),
                cDetails = find(h, "WORK", "DETAILS"), cTotal = find(h, "TOTAL AMOUNT"), cPaid = h.getOrDefault("PAID", -1),
                cExpense = find(h, "EXPENSE DETAILS"), cAmount = h.getOrDefault("AMOUNT", -1),
                cExpDate = h.getOrDefault("DATE#2", cExpense > 0 ? cExpense - 1 : -1);

        List<ImportedIncome> income = new ArrayList<>();
        List<ImportedExpense> expenses = new ArrayList<>();
        List<String> memos = new ArrayList<>();
        for (int r = head + 1; r <= sh.getLastRowNum(); r++) {
            Row row = sh.getRow(r);
            if (row == null) continue;
            String first = fmt.formatCellValue(row.getCell(0)).strip().toUpperCase(Locale.ROOT);
            if (first.contains("TOTAL") || first.contains("SUMMARY")) break;

            String customer = str(row, cName, fmt), details = str(row, cDetails, fmt);
            BigDecimal amount = number(cell(row, cTotal)), paid = number(cell(row, cPaid));
            if (amount != null || paid != null) {
                LocalDate d = fixDate(cell(row, cDate), fmt, year, month);
                income.add(new ImportedIncome(d == null ? base.withDayOfMonth(1) : d, d == null, customer,
                        mobile(cell(row, cMobile), fmt), details, amount != null ? amount : paid, paid == null ? BigDecimal.ZERO : paid));
            } else if (customer != null || details != null) {
                memos.add(String.join(" · ", java.util.stream.Stream.of(customer, details).filter(s -> s != null).toList()));
            }

            String what = str(row, cExpense, fmt);
            BigDecimal spent = number(cell(row, cAmount));
            if (spent != null && spent.signum() != 0) {
                LocalDate d = fixDate(cell(row, cExpDate), fmt, year, month);
                expenses.add(new ImportedExpense(d == null ? base.withDayOfMonth(1) : d, d == null,
                        what == null ? "Expense" : what, guess(what), spent));
            } else if (what != null) {
                memos.add(what);
            }
        }
        return new MonthSheet(sh.getSheetName(), year, month, income, expenses, memos);
    }

    private static YearSheet yearSheet(Sheet sh, Map<String, Integer> h, int head, DataFormatter fmt) {
        int cMonth = h.getOrDefault("MONTH", 0), cIn = h.get("TOTAL INCOME"), cOut = find(h, "TOTAL EXPENSE");
        Map<String, Integer> partnerCols = new LinkedHashMap<>();
        for (var e : h.entrySet())
            if (e.getKey().startsWith("PARTNER")) {
                String name = e.getKey().replaceFirst("^PARTNER\\s*-?\\s*", "").strip();
                if (!name.isEmpty()) partnerCols.put(title(name), e.getValue());
            }
        List<BigDecimal> in = new ArrayList<>(), out = new ArrayList<>();
        Map<String, List<BigDecimal>> drawn = new LinkedHashMap<>();
        partnerCols.keySet().forEach(p -> drawn.put(p, new ArrayList<>()));
        for (int i = 0; i < 12; i++) {
            in.add(null);
            out.add(null);
            drawn.values().forEach(l -> l.add(null));
        }
        Map<String, BigDecimal> entitlements = new LinkedHashMap<>();
        for (int r = head + 1; r <= sh.getLastRowNum(); r++) {
            Row row = sh.getRow(r);
            if (row == null) continue;
            String label = str(row, cMonth, fmt);
            if (label == null) continue;
            int m = List.of(MONTHS).indexOf(label.length() >= 3 ? label.substring(0, 3).toUpperCase(Locale.ROOT) : "");
            if (m >= 0 && r <= head + 13) {
                in.set(m, number(cell(row, cIn)));
                out.set(m, number(cell(row, cOut)));
                for (var p : partnerCols.entrySet()) drawn.get(p.getKey()).set(m, number(cell(row, p.getValue())));
            } else if (label.toUpperCase(Locale.ROOT).startsWith("PARTNER")) {
                // "PARTNER 1 / 2" rows of the drawing-account table: an entitlement in the next column.
                BigDecimal ent = number(cell(row, cMonth + 1));
                if (ent != null) entitlements.put(label, ent);
            }
        }
        return new YearSheet(in, out, drawn, entitlements);
    }

    /**
     * Repairs the sheets' dates: Excel often stored "9/5" as 9 May instead of 5 September (day and month swapped),
     * and dates dragged down a column gained a year each row (13/09/2027, 13/09/2028). Null when no usable date.
     */
    static LocalDate fixDate(Cell c, DataFormatter fmt, int year, int month) {
        if (c == null) return null;
        Integer d = null, m = null, y = null;
        if (c.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(c)) {
            LocalDate v = c.getLocalDateTimeCellValue().toLocalDate();
            d = v.getDayOfMonth();
            m = v.getMonthValue();
            y = v.getYear();
        } else {
            Matcher t = TEXT_DATE.matcher(fmt.formatCellValue(c));
            if (t.find()) {
                d = Integer.parseInt(t.group(1));
                m = Integer.parseInt(t.group(2));
                y = Integer.parseInt(t.group(3));
            }
        }
        if (d == null) return null;
        try {
            if (m == month) return LocalDate.of(year, month, d);
            if (d == month) return LocalDate.of(year, month, m);   // day and month swapped
        } catch (DateTimeException e) {
            return null;
        }
        return null;
    }

    /** Category from the words in an expense, in the Bangla-English the sheets use; "Other" when unsure. */
    static ExpenseEntry.Category guess(String text) {
        if (text == null) return ExpenseEntry.Category.OTHER;
        String t = text.toLowerCase(Locale.ROOT);
        if (t.matches(".*\\b(tel|petrol|diesel|fuel|gari|garir|car|fahas|tyre|oil change)\\b.*")) return ExpenseEntry.Category.VEHICLE;
        if (t.matches(".*\\b(khabar|khana|food|lunch|dinner|nasta)\\b.*")) return ExpenseEntry.Category.FOOD;
        if (t.matches(".*\\b(kafala|gosi|iqama|muktob|tartrib|baladi|balady|fee|fees|visa|paper|exdreck)\\b.*")) return ExpenseEntry.Category.GOVERNMENT;
        if (t.matches(".*\\b(salary|salry|beton|tolob)\\b.*")) return ExpenseEntry.Category.SALARY;
        if (t.matches(".*\\b(rent|bara|bill|electric|water|internet)\\b.*") && !t.contains("gari")) return ExpenseEntry.Category.RENT_UTILITIES;
        if (t.matches(".*\\b(mal|shit|sheet|duct|pipe|material|singbord|signboard|sikala|tools?)\\b.*")) return ExpenseEntry.Category.MATERIALS;
        return ExpenseEntry.Category.OTHER;
    }

    // --- helpers ------------------------------------------------------------------------------------

    private static int find(Map<String, Integer> h, String... words) {
        for (var e : h.entrySet()) {
            boolean all = true;
            for (String w : words) all &= e.getKey().contains(w);
            if (all) return e.getValue();
        }
        return -1;
    }

    private static Cell cell(Row row, int c) {
        return c < 0 ? null : row.getCell(c);
    }

    private static String str(Row row, int c, DataFormatter fmt) {
        if (c < 0) return null;
        String s = fmt.formatCellValue(row.getCell(c)).strip().replaceAll("\\s+", " ");
        return s.isEmpty() ? null : s;
    }

    /** Mobile numbers lose their leading 0 when Excel stores them as numbers: 510053671 → 0510053671. */
    private static String mobile(Cell c, DataFormatter fmt) {
        if (c == null) return null;
        String s = fmt.formatCellValue(c).replaceAll("[^0-9+]", "");
        if (s.isEmpty()) return null;
        return s.length() == 9 && s.startsWith("5") ? "0" + s : s;
    }

    private static BigDecimal number(Cell c) {
        if (c == null) return null;
        CellType type = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        if (type == CellType.NUMERIC && !DateUtil.isCellDateFormatted(c))
            return BigDecimal.valueOf(c.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP);
        if (type == CellType.STRING) {
            String s = c.getStringCellValue().replace(",", "").strip();
            if (s.matches("-?\\d+(\\.\\d+)?")) return new BigDecimal(s).setScale(2, RoundingMode.HALF_UP);
        }
        return null;
    }

    private static String title(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.toLowerCase(Locale.ROOT).split("\\s+"))
            if (!w.isEmpty()) b.append(b.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return b.toString();
    }

    private static BigDecimal zeroNull(BigDecimal v) {
        return v == null || v.signum() == 0 ? null : v;
    }

    private static String col(int c) {
        return CellReference.convertNumToColString(c);
    }

    private static void letterhead(Sheet sh, Organization org, String title, Styles st) {
        text(sh.createRow(0), 0, nz(org.displayName()).toUpperCase(Locale.ROOT), st.company);
        text(sh.createRow(1), 0, org.getLegalNameEn() == null ? "" : nz(org.getLegalNameAr()), st.companyAr);
        text(sh.createRow(2), 0, "CR No. " + nz(org.getOtherId()) + "  |  VAT " + nz(org.getVatNumber())
                + (org.getWebsite() == null ? "" : "  |  " + org.getWebsite()), st.muted);
        text(sh.createRow(3), 0, title, st.title);
    }

    private static void header(Sheet sh, int r, String[] cols, Styles st) {
        Row row = sh.createRow(r);
        for (int c = 0; c < cols.length; c++) text(row, c, cols[c], st.head);
    }

    private static void widths(Sheet sh, int... w) {
        for (int c = 0; c < w.length; c++) sh.setColumnWidth(c, w[c] * 256);
    }

    private static void text(Row row, int c, String v, CellStyle s) {
        Cell cell = row.createCell(c);
        if (v != null) cell.setCellValue(v);
        cell.setCellStyle(s);
    }

    private static void num(Row row, int c, BigDecimal v, CellStyle s) {
        Cell cell = row.createCell(c);
        if (v != null) cell.setCellValue(v.doubleValue());
        cell.setCellStyle(s);
    }

    private static void date(Row row, int c, LocalDate d, Styles st) {
        Cell cell = row.createCell(c);
        if (d != null) cell.setCellValue(d);
        cell.setCellStyle(st.date);
    }

    private static void formula(Row row, int c, String f, CellStyle s) {
        Cell cell = row.createCell(c);
        cell.setCellFormula(f);
        cell.setCellStyle(s);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static final class Styles {
        final CellStyle company, companyAr, muted, title, head, center, name, input, wrap, inputNum, date, money, moneyBold,
                total, totalNum, kpiLabel, kpiValue;

        Styles(XSSFWorkbook wb) {
            XSSFColor navy = rgb(0x1F, 0x3A, 0x5F), pale = rgb(0xE9, 0xED, 0xF4), soft = rgb(0xF6, 0xF8, 0xFB),
                    white = rgb(0xFF, 0xFF, 0xFF), blue = rgb(0x00, 0x00, 0xFF), grey = rgb(0x5A, 0x62, 0x75);
            short num = wb.createDataFormat().getFormat(NUM);
            company = style(wb, font(wb, 16, true, navy), null, false, null);
            companyAr = style(wb, font(wb, 13, true, navy), null, false, null);
            muted = style(wb, font(wb, 9, false, grey), null, false, null);
            title = style(wb, font(wb, 12, true, navy), null, false, null);
            head = style(wb, font(wb, 10, true, white), navy, true, HorizontalAlignment.CENTER);
            head.setWrapText(true);
            center = style(wb, font(wb, 10, false, null), null, true, HorizontalAlignment.CENTER);
            name = style(wb, font(wb, 10, true, null), null, true, HorizontalAlignment.LEFT);
            input = style(wb, font(wb, 10, false, blue), null, true, HorizontalAlignment.LEFT);
            wrap = style(wb, font(wb, 10, false, blue), null, true, HorizontalAlignment.LEFT);
            wrap.setWrapText(true);
            inputNum = style(wb, font(wb, 10, false, blue), null, true, HorizontalAlignment.RIGHT);
            inputNum.setDataFormat(num);
            date = style(wb, font(wb, 10, false, blue), null, true, HorizontalAlignment.CENTER);
            date.setDataFormat(wb.createDataFormat().getFormat("dd/mm/yyyy"));
            money = style(wb, font(wb, 10, false, null), soft, true, HorizontalAlignment.RIGHT);
            money.setDataFormat(num);
            moneyBold = style(wb, font(wb, 10, true, null), soft, true, HorizontalAlignment.RIGHT);
            moneyBold.setDataFormat(num);
            total = style(wb, font(wb, 10, true, white), navy, true, HorizontalAlignment.LEFT);
            totalNum = style(wb, font(wb, 10, true, white), navy, true, HorizontalAlignment.RIGHT);
            totalNum.setDataFormat(num);
            kpiLabel = style(wb, font(wb, 10, true, navy), pale, true, HorizontalAlignment.LEFT);
            kpiValue = style(wb, font(wb, 12, true, navy), pale, true, HorizontalAlignment.RIGHT);
            kpiValue.setDataFormat(num);
        }

        private static XSSFColor rgb(int r, int g, int b) {
            return new XSSFColor(new byte[]{(byte) r, (byte) g, (byte) b}, null);
        }

        private static XSSFFont font(XSSFWorkbook wb, int size, boolean bold, XSSFColor color) {
            XSSFFont f = wb.createFont();
            f.setFontName("Arial");
            f.setFontHeightInPoints((short) size);
            f.setBold(bold);
            if (color != null) f.setColor(color);
            return f;
        }

        private static XSSFCellStyle style(XSSFWorkbook wb, XSSFFont f, XSSFColor fill, boolean border, HorizontalAlignment align) {
            XSSFCellStyle s = wb.createCellStyle();
            s.setFont(f);
            s.setVerticalAlignment(VerticalAlignment.TOP);
            if (align != null) s.setAlignment(align);
            if (fill != null) {
                s.setFillForegroundColor(fill);
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            if (border) {
                XSSFColor line = rgb(0xD5, 0xDB, 0xE5);
                for (var side : XSSFCellBorder.BorderSide.values()) {
                    if (side == XSSFCellBorder.BorderSide.DIAGONAL || side.name().equals("VERTICAL") || side.name().equals("HORIZONTAL")) continue;
                    s.setBorderColor(side, line);
                }
                s.setBorderTop(BorderStyle.THIN);
                s.setBorderBottom(BorderStyle.THIN);
                s.setBorderLeft(BorderStyle.THIN);
                s.setBorderRight(BorderStyle.THIN);
            }
            return s;
        }
    }
}
