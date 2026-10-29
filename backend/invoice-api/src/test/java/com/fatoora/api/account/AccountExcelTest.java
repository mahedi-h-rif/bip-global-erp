package com.fatoora.api.account;

import com.fatoora.api.organization.Organization;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AccountExcelTest {

    @Test
    void repairsSwappedAndDraggedDates() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            var row = wb.createSheet().createRow(0);
            var style = wb.createCellStyle();
            style.setDataFormat(wb.createDataFormat().getFormat("dd/mm/yyyy"));
            var swapped = row.createCell(0);
            swapped.setCellValue(LocalDateTime.of(2026, 5, 9, 0, 0));   // meant 5 September
            swapped.setCellStyle(style);
            row.createCell(1).setCellValue("13/09/2028");                // dragged: meant 2026
            row.createCell(2).setCellValue("26/10/2026");                // fine, but another month
            var f = new DataFormatter();
            assertEquals(LocalDate.of(2026, 9, 5), AccountExcel.fixDate(swapped, f, 2026, 9));
            assertEquals(LocalDate.of(2026, 9, 13), AccountExcel.fixDate(row.getCell(1), f, 2026, 9));
            assertNull(AccountExcel.fixDate(row.getCell(2), f, 2026, 9));
            assertNull(AccountExcel.fixDate(null, f, 2026, 9));
        }
    }

    @Test
    void guessesExpenseCategories() {
        assertEquals(ExpenseEntry.Category.VEHICLE, AccountExcel.guess("Zulfi Khabar Garir tel"));
        assertEquals(ExpenseEntry.Category.FOOD, AccountExcel.guess("khabar"));
        assertEquals(ExpenseEntry.Category.GOVERNMENT, AccountExcel.guess("Hridoy kafala"));
        assertEquals(ExpenseEntry.Category.GOVERNMENT, AccountExcel.guess("gosi company"));
        assertEquals(ExpenseEntry.Category.SALARY, AccountExcel.guess("company saudi Salry"));
        assertEquals(ExpenseEntry.Category.MATERIALS, AccountExcel.guess("Ducting Shit"));
        assertEquals(ExpenseEntry.Category.VEHICLE, AccountExcel.guess("gari bara"));
        assertEquals(ExpenseEntry.Category.OTHER, AccountExcel.guess("misc"));
    }

    @Test
    void monthAndYearFiguresAndExportsEvaluate() throws Exception {
        IncomeEntry a = income("Eng. Eyasin", "4600", "4600", 9);
        IncomeEntry b = income("ateek", "30000", "20000", 9);
        IncomeEntry c = income("Eng. Eyasin ", "1100", "1100", 9);
        ExpenseEntry x = new ExpenseEntry();
        x.setEntryDate(LocalDate.of(2026, 9, 6));
        x.setDescription("garir tel");
        x.setCategory(ExpenseEntry.Category.VEHICLE);
        x.setAmount(new BigDecimal("130"));
        var m = AccountReport.month(2026, 9, List.of(a, b, c), List.of(x), new BigDecimal("500"));
        assertEquals(0, new BigDecimal("35700").compareTo(m.totals().billed()));
        assertEquals(0, new BigDecimal("25700").compareTo(m.totals().received()));
        assertEquals(0, new BigDecimal("10000").compareTo(m.totals().due()));
        assertEquals(0, new BigDecimal("630").compareTo(m.totals().expense()));         // includes worker payments
        assertEquals(0, new BigDecimal("25070").compareTo(m.totals().cashAfterExpense()));
        assertEquals(2, m.customers().size());                                          // "Eng. Eyasin" twice
        assertEquals(2, m.customers().stream().filter(k -> k.name().startsWith("Eng")).findFirst().orElseThrow().jobs());

        Partner p = new Partner();
        p.setId(1L);
        p.setName("Shamas Uddin");
        PartnerWithdrawal w = new PartnerWithdrawal();
        w.setPartnerId(1L);
        w.setEntryDate(LocalDate.of(2026, 9, 1));
        w.setAmount(new BigDecimal("210"));
        BigDecimal[] wp = new BigDecimal[12];
        wp[8] = new BigDecimal("500");
        var y = AccountReport.year(2026, List.of(a, b, c), List.of(x), wp, List.of(p), Map.of(1L, new BigDecimal("1000")), List.of(w));
        assertEquals(0, new BigDecimal("25070").compareTo(y.totals().net()));
        assertEquals(0, new BigDecimal("24860").compareTo(y.totals().retained()));
        assertEquals(0, new BigDecimal("790").compareTo(y.partners().getFirst().balance()));

        Organization o = new Organization();
        o.setLegalNameEn("Astha Gulf Trading Company");
        for (byte[] file : List.of(AccountExcel.writeMonth(m, o), AccountExcel.writeYear(y, o))) {
            try (var wb = new XSSFWorkbook(new ByteArrayInputStream(file))) {
                var ev = wb.getCreationHelper().createFormulaEvaluator();
                for (var sheet : wb) for (var row : sheet) for (var cell : row)
                    if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA)
                        assertNotEquals(org.apache.poi.ss.usermodel.CellType.ERROR, ev.evaluate(cell).getCellType(),
                                sheet.getSheetName() + "!" + cell.getAddress());
            }
        }
        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(AccountExcel.writeMonth(m, o)))) {
            var ev = wb.getCreationHelper().createFormulaEvaluator();
            var sum = wb.getSheet("Summary");
            assertEquals(35700, ev.evaluate(sum.getRow(5).getCell(1)).getNumberValue(), 0.001);
            assertEquals(25070, ev.evaluate(sum.getRow(9).getCell(1)).getNumberValue(), 0.001);
        }
    }

    private static IncomeEntry income(String name, String amount, String paid, int month) {
        IncomeEntry e = new IncomeEntry();
        e.setEntryDate(LocalDate.of(2026, month, 9));
        e.setCustomerName(name);
        e.setAmount(new BigDecimal(amount));
        e.setPaid(new BigDecimal(paid));
        return e;
    }
}
