package com.fatoora.api.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PassportMrzParserTest {

    // The canonical ICAO Doc 9303 TD3 sample MRZ — check digits are known-valid.
    private static final String LINE_1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<";
    private static final String LINE_2 = "L898902C36UTO7408122F1204159ZE184226B<<<<<10";

    private final PassportMrzParser parser = new PassportMrzParser();

    @Test
    void parsesAllFieldsFromAValidMrz() {
        String ocrText = "SOME NOISE FROM THE VISUAL ZONE\n" + LINE_1 + "\n" + LINE_2 + "\ntrailing junk";

        Optional<PassportMrzParser.Result> result = parser.parse(ocrText);

        assertThat(result).isPresent();
        PassportMrzParser.Result value = result.get();
        assertThat(value.passportNumber()).isEqualTo("L898902C3");
        assertThat(value.surname()).isEqualTo("ERIKSSON");
        assertThat(value.givenNames()).isEqualTo("ANNA MARIA");
        assertThat(value.nationality()).isEqualTo("UTO");
        assertThat(value.issuingCountry()).isEqualTo("UTO");
        assertThat(value.sex()).isEqualTo("F");
        assertThat(value.dateOfBirth()).isEqualTo(LocalDate.of(1974, 8, 12));
        assertThat(value.expiryDate()).isEqualTo(LocalDate.of(2012, 4, 15));
        assertThat(value.unverifiedFields()).isEmpty();
    }

    @Test
    void coercesLettersBackToDigitsInsideTheNumericDateFields() {
        // Tesseract reading "0" as "O" in a date used to void that date outright. Those MRZ
        // positions can only hold digits, so the misread is corrected before the check digit is even
        // consulted — and the field still comes back verified.
        String datesWithLetterOh = "L898902C36UTO74O8122F12O4159ZE184226B<<<<<10";

        Optional<PassportMrzParser.Result> result = parser.parse(LINE_1 + "\n" + datesWithLetterOh);

        assertThat(result).isPresent();
        assertThat(result.get().dateOfBirth()).isEqualTo(LocalDate.of(1974, 8, 12));
        assertThat(result.get().expiryDate()).isEqualTo(LocalDate.of(2012, 4, 15));
        assertThat(result.get().unverifiedFields()).isEmpty();
    }

    @Test
    void returnsFieldsWhoseCheckDigitFailsButFlagsThemAsUnverified() {
        // Corrupt one digit of the passport number without touching its check digit, in a way no
        // single plausible OCR confusion explains — so there is no repair to be confident about.
        String corruptedLine2 = "L898902C46UTO7408122F1204159ZE184226B<<<<<10";
        String ocrText = LINE_1 + "\n" + corruptedLine2;

        Optional<PassportMrzParser.Result> result = parser.parse(ocrText);

        assertThat(result).isPresent();
        // Returned for the admin to correct rather than silently blanked.
        assertThat(result.get().passportNumber()).isEqualTo("L898902C4");
        assertThat(result.get().unverifiedFields()).contains(PassportMrzParser.FIELD_PASSPORT_NUMBER);
        // DOB and expiry check digits are untouched and still validate cleanly.
        assertThat(result.get().dateOfBirth()).isEqualTo(LocalDate.of(1974, 8, 12));
        assertThat(result.get().isVerified(PassportMrzParser.FIELD_DATE_OF_BIRTH)).isTrue();
    }

    @Test
    void readsAnMrzWhoseTwoLinesWereMergedOntoOneByOcr() {
        Optional<PassportMrzParser.Result> result = parser.parse("header\n" + LINE_1 + LINE_2);

        assertThat(result).isPresent();
        assertThat(result.get().passportNumber()).isEqualTo("L898902C3");
        assertThat(result.get().surname()).isEqualTo("ERIKSSON");
    }

    @Test
    void recoversTheChevronFillerFromItsCommonMisreads() {
        // The doubled filler between surname and given names is what Tesseract most often renders
        // as a guillemet; a single one it tends to turn into a stray bracket.
        String garbledFiller = "P<UTOERIKSSON«ANNA{MARIA<<<<<<<<<<<<<<<<<<<";

        Optional<PassportMrzParser.Result> result = parser.parse(garbledFiller + "\n" + LINE_2);

        assertThat(result).isPresent();
        assertThat(result.get().surname()).isEqualTo("ERIKSSON");
        assertThat(result.get().givenNames()).isEqualTo("ANNA MARIA");
    }

    @Test
    void stillReadsTheNumberAndDatesWhenOnlyTheSecondLineSurvived() {
        Optional<PassportMrzParser.Result> result = parser.parse("glare wiped out the name line\n" + LINE_2);

        assertThat(result).isPresent();
        assertThat(result.get().passportNumber()).isEqualTo("L898902C3");
        assertThat(result.get().dateOfBirth()).isEqualTo(LocalDate.of(1974, 8, 12));
        assertThat(result.get().surname()).isNull();
    }

    @Test
    void returnsEmptyWhenNoMrzIsFoundInTheText() {
        Optional<PassportMrzParser.Result> result = parser.parse("Just some random OCR text with no MRZ lines at all.");

        assertThat(result).isEmpty();
    }

    @Test
    void doesNotMistakeAStrippedProseLineForAnMrz() {
        String prose = """
                KINGDOM OF SAUDI ARABIA MINISTRY OF INTERIOR
                GENERAL DIRECTORATE OF PASSPORTS RESIDENT IDENTITY
                """;

        assertThat(parser.parse(prose)).isEmpty();
    }

    @Test
    void returnsEmptyForBlankInput() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("")).isEmpty();
    }
}
