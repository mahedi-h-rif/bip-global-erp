package com.fatoora.api.worker;

import com.fatoora.api.organization.Organization;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The worker ledger as an Excel workbook in the company's register layout (worker rows, WORK / PAID / DUE per
 * month), with live formulas for every due and total; and the reverse, reading that layout back in.
 */
final class WorkerExcel {

    static final String[] MONTHS = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
    private static final String LEDGER = "Annual Ledger";
    private static final int HEAD = 8;          // 0-based row of the month headings (row 9 in Excel)
    private static final int FIRST = HEAD + 2;  // first worker row (row 11)
    private static final String NUM = "#,##0.##;(#,##0.##);\"-\"";

    private WorkerExcel() {}

    // --- export -------------------------------------------------------------------------------------

    static byte[] write(WorkerLedger.Result r, Organization org) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            Sheet sh = wb.createSheet(LEDGER);
            int n = r.workers().size();
            int last = FIRST + Math.max(n, 1) - 1;   // last worker row, 0-based
            int total = last + 1;

            text(sh, 0, 2, nz(org.displayName()).toUpperCase(Locale.ROOT), st.company);
            text(sh, 1, 2, org.getLegalNameEn() == null ? "" : nz(org.getLegalNameAr()), st.companyAr);
            text(sh, 2, 2, "CR No. " + nz(org.getOtherId()) + "  |  VAT " + nz(org.getVatNumber()), st.muted);
            text(sh, 3, 2, nz(org.getWebsite()), st.muted);
            text(sh, 5, 0, "OUTSIDE WORKER — ANNUAL ACCOUNT LEDGER " + r.year(), st.title);
            text(sh, 6, 0, "MANAGEMENT REGISTER  •  ANNUAL WORK VALUE  •  PAYMENTS  •  OUTSTANDING BALANCES  •  CURRENCY: SAR", st.muted);

            String[] fixed = {"NO.", "WORKER NAME", "TOTAL WORK", "TOTAL PAID", "TOTAL DUE"};
            Row h1 = sh.createRow(HEAD), h2 = sh.createRow(HEAD + 1);
            for (int c = 0; c < fixed.length; c++) {
                cell(h1, c, fixed[c], st.head);
                cell(h2, c, null, st.head);
                sh.addMergedRegion(new CellRangeAddress(HEAD, HEAD + 1, c, c));
            }
            for (int m = 0; m < 12; m++) {
                int c = workCol(m);
                for (int k = 0; k < 3; k++) {
                    cell(h1, c + k, k == 0 ? MONTHS[m] : null, m % 2 == 0 ? st.month : st.monthAlt);
                    cell(h2, c + k, new String[]{"WORK", "PAID", "DUE"}[k], st.sub);
                }
                sh.addMergedRegion(new CellRangeAddress(HEAD, HEAD, c, c + 2));
            }

            for (int i = 0; i < Math.max(n, 1); i++) {
                Row row = sh.createRow(FIRST + i);
                int x = FIRST + i + 1;   // 1-based Excel row
                WorkerLedger.Row w = i < n ? r.workers().get(i) : null;
                Cell no = row.createCell(0);
                no.setCellValue(i + 1);
                no.setCellStyle(st.center);
                cell(row, 1, w == null ? null : w.name(), st.name);
                StringBuilder work = new StringBuilder(), paid = new StringBuilder();
                for (int m = 0; m < 12; m++) {
                    int c = workCol(m);
                    String wr = col(c) + x, pr = col(c + 1) + x;
                    WorkerLedger.Cell e = w == null ? null : w.months().get(m);
                    number(row, c, e == null ? null : e.work(), st.input);
                    number(row, c + 1, e == null ? null : e.paid(), st.input);
                    formula(row, c + 2, "IF(AND(" + wr + "=\"\"," + pr + "=\"\"),\"\",SUM(" + wr + ")-SUM(" + pr + "))", st.due);
                    work.append(m == 0 ? "" : ",").append(wr);
                    paid.append(m == 0 ? "" : ",").append(pr);
                }
                formula(row, 2, "SUM(" + work + ")", st.money);
                formula(row, 3, "SUM(" + paid + ")", st.money);
                formula(row, 4, "C" + x + "-D" + x, st.moneyBold);
            }

            Row t = sh.createRow(total);
            cell(t, 0, "COMPANY TOTAL", st.total);
            cell(t, 1, null, st.total);
            sh.addMergedRegion(new CellRangeAddress(total, total, 0, 1));
            for (int c = 2; c < workCol(12); c++) {
                String L = col(c);
                formula(t, c, "SUM(" + L + (FIRST + 1) + ":" + L + (last + 1) + ")", st.totalNum);
            }

            int sum = total + 2;
            text(sh, sum, 0, "ANNUAL MANAGEMENT SUMMARY", st.title);
            String[][] kpis = {
                    {"TOTAL WORK VALUE", "C" + (total + 1)},
                    {"TOTAL PAID", "D" + (total + 1)},
                    {"NET BALANCE DUE", "E" + (total + 1)},
                    {"WORKER COUNT", "COUNTA(B" + (FIRST + 1) + ":B" + (last + 1) + ")"}};
            Row kl = sh.createRow(sum + 1), kv = sh.createRow(sum + 2);
            for (int k = 0; k < kpis.length; k++) {
                int c = k * 4;
                for (int j = 0; j < 4; j++) {
                    cell(kl, c + j, j == 0 ? kpis[k][0] : null, st.kpiLabel);
                    cell(kv, c + j, null, st.kpiValue);
                }
                formula(kv, c, kpis[k][1], k == 3 ? st.kpiCount : st.kpiValue);
                sh.addMergedRegion(new CellRangeAddress(sum + 1, sum + 1, c, c + 3));
                sh.addMergedRegion(new CellRangeAddress(sum + 2, sum + 2, c, c + 3));
            }
            text(sh, sum + 4, 0, "WORK = value of work completed   |   PAID = amount given   |   DUE = work minus paid; "
                    + "a figure in brackets was paid in advance", st.muted);
            text(sh, sum + 5, 0, "Enter figures only in the blue WORK and PAID cells; every DUE and total is a formula.", st.muted);

            sh.setColumnWidth(0, 6 * 256);
            sh.setColumnWidth(1, 24 * 256);
            for (int c = 2; c <= 4; c++) sh.setColumnWidth(c, 13 * 256);
            for (int c = 5; c < workCol(12); c++) sh.setColumnWidth(c, 9 * 256);
            sh.createFreezePane(2, FIRST);
            sh.getPrintSetup().setLandscape(true);
            sh.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
            sh.setFitToPage(true);
            sh.getPrintSetup().setFitWidth((short) 1);
            sh.getPrintSetup().setFitHeight((short) 0);
            sh.setRepeatingRows(new CellRangeAddress(HEAD, HEAD + 1, -1, -1));

            summary(wb, st, r, n, total);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** Second sheet: each worker's year and each month's company total, read from the ledger by formula. */
    private static void summary(Workbook wb, Styles st, WorkerLedger.Result r, int n, int total) {
        Sheet sh = wb.createSheet("Summary");
        String L = "'" + LEDGER + "'!";
        text(sh, 0, 0, "WORKER BALANCES " + r.year(), st.title);
        Row h = sh.createRow(2);
        String[] heads = {"NO.", "WORKER NAME", "TOTAL WORK", "TOTAL PAID", "BALANCE DUE"};
        for (int c = 0; c < heads.length; c++) cell(h, c, heads[c], st.head);
        for (int i = 0; i < Math.max(n, 1); i++) {
            Row row = sh.createRow(3 + i);
            int x = FIRST + i + 1;
            formula(row, 0, L + "A" + x, st.center);
            formula(row, 1, L + "B" + x, st.name);
            formula(row, 2, L + "C" + x, st.money);
            formula(row, 3, L + "D" + x, st.money);
            formula(row, 4, L + "E" + x, st.moneyBold);
        }
        int tr = 3 + Math.max(n, 1);
        Row t = sh.createRow(tr);
        cell(t, 0, null, st.total);
        cell(t, 1, "COMPANY TOTAL", st.total);
        for (int c = 2; c <= 4; c++) formula(t, c, L + col(c) + (total + 1), st.totalNum);

        int mh = tr + 3;
        text(sh, mh - 1, 0, "MONTH BY MONTH " + r.year(), st.title);
        Row h2 = sh.createRow(mh);
        String[] mheads = {"", "MONTH", "WORK", "PAID", "DUE"};
        for (int c = 0; c < mheads.length; c++) cell(h2, c, mheads[c], st.head);
        for (int m = 0; m < 12; m++) {
            Row row = sh.createRow(mh + 1 + m);
            cell(row, 0, null, st.center);
            cell(row, 1, MONTHS[m], st.name);
            int c = workCol(m);
            formula(row, 2, L + col(c) + (total + 1), st.money);
            formula(row, 3, L + col(c + 1) + (total + 1), st.money);
            formula(row, 4, "C" + (mh + 2 + m) + "-D" + (mh + 2 + m), st.moneyBold);
        }
        sh.setColumnWidth(0, 6 * 256);
        sh.setColumnWidth(1, 24 * 256);
        for (int c = 2; c <= 4; c++) sh.setColumnWidth(c, 14 * 256);
    }

    // --- import -------------------------------------------------------------------------------------

    record Imported(String name, BigDecimal[] work, BigDecimal[] paid) {}

    record Parsed(Integer year, List<Imported> workers) {}

    private static final Pattern YEAR = Pattern.compile("\\b(20\\d\\d)\\b");

    /** Reads a ledger in the register layout: a "WORKER NAME" heading, month headings above WORK / PAID columns. */
    static Parsed read(InputStream in) throws IOException {
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sh = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.ENGLISH);
            Integer year = null;
            int head = -1, nameCol = -1;
            for (int r = 0; r <= Math.min(sh.getLastRowNum(), 30) && head < 0; r++) {
                Row row = sh.getRow(r);
                if (row == null) continue;
                for (Cell c : row) {
                    String v = fmt.formatCellValue(c).strip();
                    if (year == null && r < HEAD) {
                        Matcher m = YEAR.matcher(v);
                        if (m.find() && v.toUpperCase(Locale.ROOT).contains("LEDGER")) year = Integer.parseInt(m.group(1));
                    }
                    if (v.equalsIgnoreCase("WORKER NAME")) {
                        head = r;
                        nameCol = c.getColumnIndex();
                    }
                }
            }
            if (head < 0) throw new IllegalArgumentException("This file has no \"WORKER NAME\" heading");

            int[] monthCol = new int[12];
            java.util.Arrays.fill(monthCol, -1);
            for (Cell c : sh.getRow(head)) {
                String v = fmt.formatCellValue(c).strip().toUpperCase(Locale.ROOT);
                for (int m = 0; m < 12; m++) if (v.startsWith(MONTHS[m])) monthCol[m] = c.getColumnIndex();
            }
            if (java.util.Arrays.stream(monthCol).allMatch(c -> c < 0))
                throw new IllegalArgumentException(isDayWork(sh, head, fmt)
                        ? "This is a day-work statement; use \"Import day work\" instead"
                        : "No month columns (JAN, FEB …) found in this file");

            List<Imported> out = new ArrayList<>();
            for (int r = head + 2; r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                if (row == null) continue;
                String first = fmt.formatCellValue(row.getCell(0)).strip().toUpperCase(Locale.ROOT);
                if (first.contains("TOTAL") || first.contains("SUMMARY")) break;
                String name = fmt.formatCellValue(row.getCell(nameCol)).strip();
                if (name.isEmpty()) continue;
                BigDecimal[] work = new BigDecimal[12], paid = new BigDecimal[12];
                for (int m = 0; m < 12; m++) {
                    if (monthCol[m] < 0) continue;
                    work[m] = number(row.getCell(monthCol[m]));
                    paid[m] = number(row.getCell(monthCol[m] + 1));
                }
                out.add(new Imported(name.replaceAll("\\s+", " "), work, paid));
            }
            return new Parsed(year, out);
        }
    }

    // --- day-work statements --------------------------------------------------------------------------

    /** One worker's line on a monthly day-work statement: days × daily rate, and what was paid. */
    record DayLine(String name, BigDecimal days, BigDecimal rate, BigDecimal paid) {
        BigDecimal work() {
            return days == null || rate == null ? null : days.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        }
    }

    record DaySheet(String sheet, int year, int month, List<DayLine> lines) {}

    private static final Pattern SHEET_MONTH = Pattern.compile("(?i)^(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[\\s._-]*'?(\\d{4}|\\d{2})?");

    private static boolean isDayWork(Sheet sh, int head, DataFormatter fmt) {
        for (Cell c : sh.getRow(head)) if (fmt.formatCellValue(c).strip().equalsIgnoreCase("DAYS")) return true;
        return false;
    }

    /**
     * Reads every sheet of a day-work workbook (one sheet per month: NO., WORKER NAME, DAYS, DAILY RATE, PAID).
     * The month comes from the sheet name ("sep26", "Oct 26") when it has one, because the MONTH cell inside is
     * often left over from the sheet it was copied from; otherwise from the MONTH / YEAR cells.
     */
    static List<DaySheet> readDayWork(InputStream in, int defaultYear) throws IOException {
        try (Workbook wb = WorkbookFactory.create(in)) {
            DataFormatter fmt = new DataFormatter(Locale.ENGLISH);
            List<DaySheet> out = new ArrayList<>();
            for (Sheet sh : wb) {
                int head = -1, name = -1, days = -1, rate = -1, paid = -1;
                Integer cellMonth = null, cellYear = null;
                for (int r = 0; r <= Math.min(sh.getLastRowNum(), 30) && head < 0; r++) {
                    Row row = sh.getRow(r);
                    if (row == null) continue;
                    for (Cell c : row) {
                        String v = fmt.formatCellValue(c).strip().toUpperCase(Locale.ROOT);
                        int i = c.getColumnIndex();
                        if (v.equals("MONTH")) cellMonth = integer(row.getCell(i + 1));
                        if (v.equals("YEAR")) cellYear = integer(row.getCell(i + 1));
                        if (v.equals("WORKER NAME")) { head = r; name = i; }
                        if (v.equals("DAYS")) days = i;
                        if (v.equals("DAILY RATE") || v.equals("RATE")) rate = i;
                        if (v.equals("PAID")) paid = i;
                    }
                }
                if (head < 0 || days < 0) continue;
                int month, year;
                Matcher m = SHEET_MONTH.matcher(sh.getSheetName().strip());
                if (m.find()) {
                    month = List.of(MONTHS).indexOf(m.group(1).toUpperCase(Locale.ROOT)) + 1;
                    String y = m.group(2);
                    year = y == null ? (cellYear != null ? cellYear : defaultYear) : y.length() == 2 ? 2000 + Integer.parseInt(y) : Integer.parseInt(y);
                } else if (cellMonth != null && cellMonth >= 1 && cellMonth <= 12) {
                    month = cellMonth;
                    year = cellYear != null ? cellYear : defaultYear;
                } else continue;

                List<DayLine> lines = new ArrayList<>();
                for (int r = head + 1; r <= sh.getLastRowNum(); r++) {
                    Row row = sh.getRow(r);
                    if (row == null) continue;
                    String first = fmt.formatCellValue(row.getCell(0)).strip().toUpperCase(Locale.ROOT);
                    if (first.contains("TOTAL") || first.contains("SUMMARY")) break;
                    String n = fmt.formatCellValue(row.getCell(name)).strip().replaceAll("\\s+", " ");
                    if (n.isEmpty()) continue;
                    DayLine l = new DayLine(n, number(row.getCell(days)), rate < 0 ? null : number(row.getCell(rate)),
                            paid < 0 ? null : number(row.getCell(paid)));
                    if (l.days() != null || l.paid() != null) lines.add(l);
                }
                out.add(new DaySheet(sh.getSheetName(), year, month, lines));
            }
            return out;
        }
    }

    private static Integer integer(Cell c) {
        BigDecimal v = number(c);
        return v == null ? null : v.intValue();
    }

    private static BigDecimal number(Cell c) {
        if (c == null) return null;
        CellType type = c.getCellType() == CellType.FORMULA ? c.getCachedFormulaResultType() : c.getCellType();
        if (type == CellType.NUMERIC) return BigDecimal.valueOf(c.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP);
        if (type == CellType.STRING) {
            String s = c.getStringCellValue().replace(",", "").strip();
            if (s.matches("-?\\d+(\\.\\d+)?")) return new BigDecimal(s).setScale(2, RoundingMode.HALF_UP);
        }
        return null;
    }

    // --- helpers ------------------------------------------------------------------------------------

    static int workCol(int monthIndex) {
        return 5 + monthIndex * 3;
    }

    private static String col(int c) {
        return CellReference.convertNumToColString(c);
    }

    private static void text(Sheet sh, int r, int c, String v, CellStyle s) {
        Row row = sh.getRow(r) == null ? sh.createRow(r) : sh.getRow(r);
        cell(row, c, v, s);
    }

    private static void cell(Row row, int c, String v, CellStyle s) {
        Cell cell = row.createCell(c);
        if (v != null) cell.setCellValue(v);
        cell.setCellStyle(s);
    }

    private static void number(Row row, int c, BigDecimal v, CellStyle s) {
        Cell cell = row.createCell(c);
        if (v != null) cell.setCellValue(v.doubleValue());
        cell.setCellStyle(s);
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
        final CellStyle company, companyAr, muted, title, head, month, monthAlt, sub, center, name, input, due, money,
                moneyBold, total, totalNum, kpiLabel, kpiValue, kpiCount;

        Styles(XSSFWorkbook wb) {
            XSSFColor navy = rgb(0x1F, 0x3A, 0x5F), navy2 = rgb(0x2C, 0x4F, 0x7C), pale = rgb(0xE9, 0xED, 0xF4),
                    soft = rgb(0xF6, 0xF8, 0xFB), white = rgb(0xFF, 0xFF, 0xFF), blue = rgb(0x00, 0x00, 0xFF),
                    grey = rgb(0x5A, 0x62, 0x75);
            short num = wb.createDataFormat().getFormat(NUM);
            company = style(wb, font(wb, 16, true, navy), null, false);
            companyAr = style(wb, font(wb, 13, true, navy), null, false);
            muted = style(wb, font(wb, 9, false, grey), null, false);
            title = style(wb, font(wb, 13, true, navy), null, false);
            head = boxed(wb, font(wb, 10, true, white), navy, HorizontalAlignment.CENTER);
            month = boxed(wb, font(wb, 10, true, white), navy2, HorizontalAlignment.CENTER);
            monthAlt = boxed(wb, font(wb, 10, true, white), navy, HorizontalAlignment.CENTER);
            sub = boxed(wb, font(wb, 8, true, navy), pale, HorizontalAlignment.CENTER);
            center = boxed(wb, font(wb, 10, false, null), null, HorizontalAlignment.CENTER);
            name = boxed(wb, font(wb, 10, true, null), null, HorizontalAlignment.LEFT);
            input = boxed(wb, font(wb, 10, false, blue), null, HorizontalAlignment.RIGHT);
            input.setDataFormat(num);
            due = boxed(wb, font(wb, 10, false, null), soft, HorizontalAlignment.RIGHT);
            due.setDataFormat(num);
            money = boxed(wb, font(wb, 10, false, null), soft, HorizontalAlignment.RIGHT);
            money.setDataFormat(num);
            moneyBold = boxed(wb, font(wb, 10, true, null), soft, HorizontalAlignment.RIGHT);
            moneyBold.setDataFormat(num);
            total = boxed(wb, font(wb, 10, true, white), navy, HorizontalAlignment.LEFT);
            totalNum = boxed(wb, font(wb, 10, true, white), navy, HorizontalAlignment.RIGHT);
            totalNum.setDataFormat(num);
            kpiLabel = boxed(wb, font(wb, 9, true, white), navy2, HorizontalAlignment.CENTER);
            kpiValue = boxed(wb, font(wb, 14, true, navy), pale, HorizontalAlignment.CENTER);
            kpiValue.setDataFormat(num);
            kpiCount = boxed(wb, font(wb, 14, true, navy), pale, HorizontalAlignment.CENTER);
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

        private static XSSFCellStyle style(XSSFWorkbook wb, XSSFFont f, XSSFColor fill, boolean border) {
            XSSFCellStyle s = wb.createCellStyle();
            s.setFont(f);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            if (fill != null) {
                s.setFillForegroundColor(fill);
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            if (border) {
                XSSFColor line = rgb(0xD5, 0xDB, 0xE5);
                for (var side : new org.apache.poi.xssf.usermodel.extensions.XSSFCellBorder.BorderSide[]{
                        org.apache.poi.xssf.usermodel.extensions.XSSFCellBorder.BorderSide.TOP,
                        org.apache.poi.xssf.usermodel.extensions.XSSFCellBorder.BorderSide.BOTTOM,
                        org.apache.poi.xssf.usermodel.extensions.XSSFCellBorder.BorderSide.LEFT,
                        org.apache.poi.xssf.usermodel.extensions.XSSFCellBorder.BorderSide.RIGHT}) {
                    s.setBorderColor(side, line);
                }
                s.setBorderTop(BorderStyle.THIN);
                s.setBorderBottom(BorderStyle.THIN);
                s.setBorderLeft(BorderStyle.THIN);
                s.setBorderRight(BorderStyle.THIN);
            }
            return s;
        }

        private static XSSFCellStyle boxed(XSSFWorkbook wb, XSSFFont f, XSSFColor fill, HorizontalAlignment align) {
            XSSFCellStyle s = style(wb, f, fill, true);
            s.setAlignment(align);
            return s;
        }
    }
}
