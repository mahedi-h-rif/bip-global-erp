package com.fatoora.api.worker;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DayWorkImportTest {

    private static List<Worker> saved(String... names) {
        List<Worker> out = new ArrayList<>();
        long id = 0;
        for (String n : names) {
            Worker w = new Worker();
            w.setId(++id);
            w.setName(n);
            out.add(w);
        }
        return out;
    }

    @Test
    void suggestsTheWorkerTheCompanySheetsMean() {
        List<Worker> ws = saved("Sakat Khan", "Rakat Khan", "Izaz", "Sharif Khan", "Asraful Islam", "Ladden", "Ridoy",
                "Baitulllah", "Ridoy Duct Moyalem", "Ladaner Bhai", "Soboj Hadhat", "Bairer Lok");
        assertEquals(2L, WorkerMatcher.suggest("Rakat Khan ", ws));
        assertEquals(1L, WorkerMatcher.suggest("Sakat ", ws));
        assertEquals(5L, WorkerMatcher.suggest("Asraful ", ws));
        assertEquals(9L, WorkerMatcher.suggest("Hridoy Duct ", ws));
        assertEquals(6L, WorkerMatcher.suggest("Laden ar Bhai ", ws));
        assertEquals(11L, WorkerMatcher.suggest("Soboj", ws));
        assertEquals(12L, WorkerMatcher.suggest("bhairer Lok ", ws));
        assertEquals(8L, WorkerMatcher.suggest("Baitullah", ws));
        assertNull(WorkerMatcher.suggest("Mohammed Karim", ws));
    }

    @Test
    void readsEachSheetWithTheMonthFromItsName() throws Exception {
        byte[] file;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String name : new String[]{"sep26", "oct 26"}) {
                var sh = wb.createSheet(name);
                var r = sh.createRow(9);
                r.createCell(0).setCellValue("MONTH");
                r.createCell(1).setCellValue(9);   // copied from the September sheet: wrong on the October one
                r.createCell(2).setCellValue("YEAR");
                r.createCell(3).setCellValue(2026);
                var h = sh.createRow(11);
                String[] heads = {"NO.", "WORKER NAME", "DAYS", "DAILY RATE", "AMOUNT DUE", "PAID", "BALANCE", "SIGNATURE"};
                for (int i = 0; i < heads.length; i++) h.createCell(i).setCellValue(heads[i]);
                var l = sh.createRow(12);
                l.createCell(0).setCellValue(1);
                l.createCell(1).setCellValue("Rakat Khan ");
                l.createCell(2).setCellValue(name.startsWith("sep") ? 8 : 7);
                l.createCell(3).setCellValue(120);
                l.createCell(4).setCellFormula("C13*D13");
                if (name.startsWith("sep")) l.createCell(5).setCellValue(1900);
                var empty = sh.createRow(13);
                empty.createCell(0).setCellValue(2);
                sh.createRow(32).createCell(0).setCellValue("MONTH TOTAL");
            }
            wb.write(out);
            file = out.toByteArray();
        }
        var sheets = WorkerExcel.readDayWork(new ByteArrayInputStream(file), 2025);
        assertEquals(2, sheets.size());
        assertEquals(9, sheets.get(0).month());
        assertEquals(10, sheets.get(1).month());
        assertEquals(2026, sheets.get(1).year());
        var sep = sheets.get(0).lines();
        assertEquals(1, sep.size());
        assertEquals("Rakat Khan", sep.getFirst().name());
        assertEquals(0, new BigDecimal("960").compareTo(sep.getFirst().work()));
        assertEquals(0, new BigDecimal("1900").compareTo(sep.getFirst().paid()));
        assertNull(sheets.get(1).lines().getFirst().paid());

        // The ledger import refuses a day-work file instead of creating workers with no months.
        var e = assertThrows(IllegalArgumentException.class, () -> WorkerExcel.read(new ByteArrayInputStream(file)));
        assertTrue(e.getMessage().contains("Import day work"));
    }
}
