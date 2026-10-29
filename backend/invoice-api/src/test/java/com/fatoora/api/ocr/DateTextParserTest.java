package com.fatoora.api.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DateTextParserTest {

    private final DateTextParser parser = new DateTextParser();

    @Test
    void readsDayFirstNumericDates() {
        assertThat(parser.firstIn("15/03/2026")).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(parser.firstIn("15-03-2026")).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(parser.firstIn("15.03.2026")).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(parser.firstIn("15 03 2026")).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    void readsIsoOrderedDates() {
        assertThat(parser.firstIn("2026-03-15")).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    void readsMonthNamesInTheLanguagesPassportsPrintThem() {
        assertThat(parser.firstIn("12 MAR 2020")).isEqualTo(LocalDate.of(2020, 3, 12));
        assertThat(parser.firstIn("12 MARCH 2020")).isEqualTo(LocalDate.of(2020, 3, 12));
        assertThat(parser.firstIn("MAR 12, 2020")).isEqualTo(LocalDate.of(2020, 3, 12));
        assertThat(parser.firstIn("12 MARS 2020")).isEqualTo(LocalDate.of(2020, 3, 12));
    }

    @Test
    void switchesToMonthFirstOnlyWhenDayFirstIsImpossible() {
        assertThat(parser.firstIn("03/15/2026")).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    void normalizesArabicIndicDigits() {
        assertThat(DateTextParser.normalizeDigits("١٥/٠٣/٢٠٢٦")).isEqualTo("15/03/2026");
        assertThat(parser.firstIn("١٥/٠٣/٢٠٢٦")).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    void convertsHijriYearsThroughUmmAlQura() {
        LocalDate converted = parser.firstIn("1445/03/12");

        assertThat(converted).isNotNull();
        assertThat(converted.getYear()).isEqualTo(2023);
    }

    @Test
    void ignoresNumbersThatAreNotDates() {
        assertThat(parser.firstIn("item 1/2/3 of the list")).isNull();
        assertThat(parser.firstIn("no digits here at all")).isNull();
        assertThat(parser.firstIn("32/13/2020")).isNull();
    }

    @Test
    void findsEveryDateInReadingOrder() {
        var found = parser.findAll("Issue 01/01/2023 Expiry 01/01/2027");

        assertThat(found).hasSize(2);
        assertThat(found.get(0).date()).isEqualTo(LocalDate.of(2023, 1, 1));
        assertThat(found.get(1).date()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(found.get(0).start()).isLessThan(found.get(1).start());
    }

    @Test
    void handlesBlankInput() {
        assertThat(parser.firstIn(null)).isNull();
        assertThat(parser.findAll("")).isEmpty();
    }
}
