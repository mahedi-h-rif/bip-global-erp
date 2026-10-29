package com.fatoora.api.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class IqamaFieldExtractorTest {

    private final IqamaFieldExtractor extractor = new IqamaFieldExtractor(new DateTextParser());

    @Test
    void extractsIdNumberAndDatesWhenLabelsArePresent() {
        String ocrText = """
                KINGDOM OF SAUDI ARABIA
                RESIDENT IDENTITY
                Name: MOHAMMED AHMED
                ID Number 2451234567
                Issue Date 01/02/2020
                Expiry Date 15/03/2026
                Nationality: BANGLADESHI
                """;

        IqamaFieldExtractor.Result result = extractor.extract(ocrText);

        assertThat(result.iqamaNumber()).isEqualTo("2451234567");
        assertThat(result.issueDate()).isEqualTo(LocalDate.of(2020, 2, 1));
        assertThat(result.expireDate()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(result.name()).isEqualTo("MOHAMMED AHMED");
        assertThat(result.nationality()).isEqualTo("BANGLADESHI");
    }

    @Test
    void attributesEachDateToTheLabelItFollowsWhenColumnsAreMergedOntoOneLine() {
        // How a two-column card comes out of OCR. Matching labels against the whole line used to
        // file the issue date under "expiry" because that word also appeared on it.
        IqamaFieldExtractor.Result result =
                extractor.extract("Issue Date 01/01/2023 Expiry Date 01/01/2027");

        assertThat(result.issueDate()).isEqualTo(LocalDate.of(2023, 1, 1));
        assertThat(result.expireDate()).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void readsAValuePrintedUnderneathItsLabelRatherThanBesideIt() {
        String stacked = """
                Date of Issue
                01/01/2023
                Date of Expiry
                01/01/2027
                """;

        IqamaFieldExtractor.Result result = extractor.extract(stacked);

        assertThat(result.issueDate()).isEqualTo(LocalDate.of(2023, 1, 1));
        assertThat(result.expireDate()).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void readsDottedDatesAndArabicIndicDigits() {
        IqamaFieldExtractor.Result result =
                extractor.extract("Issue 01.02.2020\nExpiry ١٥.٠٣.٢٠٢٦");

        assertThat(result.issueDate()).isEqualTo(LocalDate.of(2020, 2, 1));
        assertThat(result.expireDate()).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    void convertsAHijriExpiryToGregorian() {
        // Saudi cards print the Hijri date, often as the only one the OCR manages to read.
        IqamaFieldExtractor.Result result = extractor.extract("Expiry Date 1445/03/12");

        assertThat(result.expireDate()).isNotNull();
        assertThat(result.expireDate().getYear()).isEqualTo(2023);
    }

    @Test
    void prefersTheLabelledIdOverOtherTenDigitNumbersOnTheCard() {
        String ocrText = """
                Contact 0551234567
                ID Number 2 451 234 567
                """;

        assertThat(extractor.extract(ocrText).iqamaNumber()).isEqualTo("2451234567");
    }

    @Test
    void fallsBackToChronologicalOrderWhenNoDateLabelIsLegible() {
        IqamaFieldExtractor.Result result = extractor.extract("2451234567\n01/01/2023\n01/01/2027");

        assertThat(result.issueDate()).isEqualTo(LocalDate.of(2023, 1, 1));
        assertThat(result.expireDate()).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void swapsDatesThatCameBackInTheWrongOrder() {
        IqamaFieldExtractor.Result result = extractor.extract("Issue 01/01/2027 Expiry 01/01/2023");

        assertThat(result.issueDate()).isEqualTo(LocalDate.of(2023, 1, 1));
        assertThat(result.expireDate()).isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    void leavesFieldsNullWhenNothingConfidentlyMatches() {
        IqamaFieldExtractor.Result result = extractor.extract("Blurry unreadable OCR noise with no useful data.");

        assertThat(result.iqamaNumber()).isNull();
        assertThat(result.issueDate()).isNull();
        assertThat(result.expireDate()).isNull();
    }

    @Test
    void handlesBlankInputGracefully() {
        IqamaFieldExtractor.Result result = extractor.extract(null);

        assertThat(result.iqamaNumber()).isNull();
        assertThat(result.issueDate()).isNull();
        assertThat(result.expireDate()).isNull();
    }
}
