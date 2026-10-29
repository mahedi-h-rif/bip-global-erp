package com.fatoora.api.worker;

import com.fatoora.api.organization.Organization;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkerExcelTest {

    static WorkerLedger.Result ledger(List<WorkerExcel.Imported> rows, int year) {
        List<Worker> ws = new ArrayList<>();
        List<WorkerMonth> ms = new ArrayList<>();
        long id = 0;
        for (WorkerExcel.Imported im : rows) {
            Worker w = new Worker();
            w.setId(++id);
            w.setName(im.name());
            ws.add(w);
            for (int m = 0; m < 12; m++) {
                if (im.work()[m] == null && im.paid()[m] == null) continue;
                WorkerMonth e = new WorkerMonth();
                e.setWorkerId(id);
                e.setYear(year);
                e.setMonth(m + 1);
                e.setWork(im.work()[m]);
                e.setPaid(im.paid()[m]);
                ms.add(e);
            }
        }
        return WorkerLedger.build(year, List.of(year), ws, ms);
    }

    @Test
    void dueIsWorkMinusPaidAndNegativeIsAdvance() {
        BigDecimal[] work = new BigDecimal[12], paid = new BigDecimal[12];
        work[8] = new BigDecimal("960");
        paid[8] = new BigDecimal("1900");
        work[5] = new BigDecimal("1850");
        paid[5] = new BigDecimal("1600");
        paid[7] = new BigDecimal("100");   // paid with no work entered still counts
        var r = ledger(List.of(new WorkerExcel.Imported("A", work, paid)), 2026);
        var w = r.workers().getFirst();
        assertEquals(0, new BigDecimal("-940").compareTo(w.months().get(8).due()));
        assertEquals(0, new BigDecimal("-100").compareTo(w.months().get(7).due()));
        assertNull(w.months().get(0).due());
        assertEquals(0, new BigDecimal("-790").compareTo(w.due()));
        assertEquals(0, new BigDecimal("790").compareTo(r.advances()));
        assertEquals(0, BigDecimal.ZERO.compareTo(r.outstanding()));
    }

    @Test
    void exportReadsBackTheSameFigures() throws Exception {
        BigDecimal[] work = new BigDecimal[12], paid = new BigDecimal[12];
        work[4] = new BigDecimal("3244");
        paid[4] = new BigDecimal("3244");
        work[6] = new BigDecimal("1920.50");
        paid[6] = new BigDecimal("1640");
        var r = ledger(List.of(new WorkerExcel.Imported("Sakat Khan", work, paid),
                new WorkerExcel.Imported("Izaz", new BigDecimal[12], new BigDecimal[12])), 2026);
        Organization o = new Organization();
        o.setLegalNameEn("Astha Gulf Trading Company");
        byte[] x = WorkerExcel.write(r, o);
        var back = WorkerExcel.read(new ByteArrayInputStream(x));
        assertEquals(2026, back.year());
        assertEquals(2, back.workers().size());
        assertEquals("Sakat Khan", back.workers().getFirst().name());
        assertEquals(0, new BigDecimal("1920.50").compareTo(back.workers().getFirst().work()[6]));
        assertEquals(0, new BigDecimal("1640").compareTo(back.workers().getFirst().paid()[6]));
        assertNull(back.workers().getFirst().work()[0]);

        // Every formula evaluates without an error, and the totals agree with the ledger.
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(x))) {
            var ev = wb.getCreationHelper().createFormulaEvaluator();
            int formulas = 0;
            for (var sheet : wb) for (var row : sheet) for (var c : row) {
                if (c.getCellType() != org.apache.poi.ss.usermodel.CellType.FORMULA) continue;
                formulas++;
                var v = ev.evaluate(c);
                assertNotEquals(org.apache.poi.ss.usermodel.CellType.ERROR, v.getCellType(), sheet.getSheetName() + "!" + c.getAddress());
            }
            assertTrue(formulas > 100);
            var ledger = wb.getSheet("Annual Ledger");
            assertEquals(5164.5, ev.evaluate(ledger.getRow(12).getCell(2)).getNumberValue(), 0.001);   // C13 company total work
            assertEquals(4884.0, ev.evaluate(ledger.getRow(12).getCell(3)).getNumberValue(), 0.001);
            assertEquals(280.5, ev.evaluate(ledger.getRow(12).getCell(4)).getNumberValue(), 0.001);
            assertEquals(280.5, ev.evaluate(ledger.getRow(10).getCell(4)).getNumberValue(), 0.001);    // E11 first worker
            assertEquals(2.0, ev.evaluate(ledger.getRow(16).getCell(12)).getNumberValue(), 0.001);     // worker count
            assertEquals(280.5, ev.evaluate(wb.getSheet("Summary").getRow(3).getCell(4)).getNumberValue(), 0.001);
        }
    }
}
