package com.fatoora.api.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PassportVizParserTest {

    private final PassportVizParser parser = new PassportVizParser(new DateTextParser());

    @Test
    void readsTheIssueDateTheMrzCannotCarry() {
        String viz = """
                Date of birth / Date de naissance   12 AUG 1974
                Date of issue / Date de delivrance  05 MAY 2018
                Date of expiry / Date d'expiration  04 MAY 2028
                """;

        PassportVizParser.Result result = parser.parse(viz, LocalDate.of(1974, 8, 12), LocalDate.of(2028, 5, 4));

        assertThat(result.issueDate()).isEqualTo(LocalDate.of(2018, 5, 5));
    }

    @Test
    void readsAnIssueDatePrintedBelowItsLabel() {
        String viz = """
                DATE OF ISSUE
                05/05/2018
                """;

        assertThat(parser.parse(viz, null, null).issueDate()).isEqualTo(LocalDate.of(2018, 5, 5));
    }

    @Test
    void infersTheIssueDateFromTheDateBracketWhenTheLabelIsUnreadable() {
        // Arabic-only labels, or a label smeared by glare: the issue date is still the one sitting
        // between birth and expiry.
        String viz = "12 AUG 1974\n05 MAY 2018\n04 MAY 2028";

        PassportVizParser.Result result = parser.parse(viz, LocalDate.of(1974, 8, 12), LocalDate.of(2028, 5, 4));

        assertThat(result.issueDate()).isEqualTo(LocalDate.of(2018, 5, 5));
    }

    @Test
    void doesNotGuessAnIssueDateWithoutBothMrzDatesToBracketIt() {
        String viz = "12 AUG 1974\n05 MAY 2018\n04 MAY 2028";

        assertThat(parser.parse(viz, null, null).issueDate()).isNull();
    }

    @Test
    void readsTheFatherNameOffThePrintedPage() {
        String viz = """
                Given names: ANNA MARIA
                Father's Name: ABDUL KARIM
                Place of birth: DHAKA
                """;

        assertThat(parser.parse(viz, null, null).fatherName()).isEqualTo("ABDUL KARIM");
    }

    @Test
    void readsAFatherNamePrintedBelowItsLabel() {
        assertThat(parser.parse("NAME OF FATHER\nABDUL KARIM", null, null).fatherName()).isEqualTo("ABDUL KARIM");
    }

    @Test
    void handlesBlankInput() {
        PassportVizParser.Result result = parser.parse(null, null, null);

        assertThat(result.issueDate()).isNull();
        assertThat(result.fatherName()).isNull();
    }
}
