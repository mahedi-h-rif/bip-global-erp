package com.fatoora.api.ocr;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Parses the two-line TD3 machine-readable zone (MRZ) printed on a passport photo page.
 *
 * <p>Every field carries its own check digit (ICAO Doc 9303: weights 7-3-1 repeating, {@code <} = 0,
 * letters = 10-35). This parser uses them three ways rather than one:
 *
 * <ol>
 *   <li>as validation — a field that checks out is returned as <b>verified</b>;
 *   <li>as a <b>repair oracle</b> — when a field fails, each single-character OCR confusion
 *       ({@code O}/{@code 0}, {@code I}/{@code 1}, {@code S}/{@code 5}…) is tried against the check
 *       digit, and a unique fix is accepted. This recovers the common case where one bad glyph used
 *       to void a whole field;
 *   <li>as a <b>confidence signal</b> — a field that still fails is returned anyway, listed in
 *       {@link Result#unverifiedFields()}, so the form can prefill it and flag it for review. A
 *       value the admin corrects beats a blank they have to type from scratch.
 * </ol>
 *
 * <p>Line detection is likewise tolerant: lines are matched on structure rather than on an exact
 * length, a pair merged onto one OCR line is split back apart, and a page where only the second
 * line survived still yields the number and dates (just no name).
 */
@Component
public class PassportMrzParser {

    private static final int[] WEIGHTS = {7, 3, 1};
    private static final int TD3_LENGTH = 44;

    /** Anything in this length range is a plausible MRZ line once non-MRZ characters are stripped. */
    private static final int MIN_LINE_LENGTH = 28;
    private static final int MAX_LINE_LENGTH = 50;

    /** Both TD3 lines merged onto one OCR line lands in this range. */
    private static final int MIN_MERGED_LENGTH = 80;
    private static final int MAX_MERGED_LENGTH = 96;

    private static final Pattern MRZ_CHARS = Pattern.compile("^[A-Z0-9<]+$");

    /**
     * Stripping punctuation and spaces turns any long-enough sentence into something that looks like
     * an MRZ line, so a pair has to actually fit the TD3 shape before it is believed — a structural
     * match on line 2 alone ({@code 4}) is not enough without corroboration from line 1.
     */
    private static final int MIN_PAIR_SCORE = 5;

    /** Line 1: document type P, then the issuing state, then the name field with its {@code <<} split. */
    private static final Pattern LINE_1_SHAPE = Pattern.compile("^P[A-Z0-9<][A-Z<]{3}[A-Z<]*<<[A-Z<]*");

    /** Line 2: number+check, nationality, birth date+check, sex, expiry+check. */
    private static final Pattern LINE_2_SHAPE =
            Pattern.compile("^[A-Z0-9<]{9}[0-9<][A-Z<]{3}[0-9<]{6}[0-9<][MFX<][0-9<]{6}[0-9<].*");

    public static final String FIELD_PASSPORT_NUMBER = "passportNumber";
    public static final String FIELD_DATE_OF_BIRTH = "dateOfBirth";
    public static final String FIELD_EXPIRY_DATE = "expiryDate";

    /** Glyphs Tesseract swaps for one another. Used only to propose repairs the check digit then judges. */
    private static final Map<Character, String> CONFUSIONS = confusions();

    private static final Map<Character, Character> LETTER_TO_DIGIT = Map.ofEntries(
            Map.entry('O', '0'), Map.entry('D', '0'), Map.entry('Q', '0'), Map.entry('U', '0'),
            Map.entry('I', '1'), Map.entry('L', '1'), Map.entry('J', '1'),
            Map.entry('Z', '2'), Map.entry('A', '4'), Map.entry('S', '5'),
            Map.entry('G', '6'), Map.entry('T', '7'), Map.entry('B', '8'), Map.entry('C', '0'));

    private static final Map<Character, Character> DIGIT_TO_LETTER = Map.of(
            '0', 'O', '1', 'I', '2', 'Z', '4', 'A', '5', 'S', '6', 'G', '8', 'B');

    /**
     * @param unverifiedFields names of the fields whose check digit never validated — present in the
     *     result on a best-effort basis and worth flagging in the UI rather than trusting silently.
     */
    public record Result(String passportNumber, String surname, String givenNames, String nationality,
                         String issuingCountry, String sex, LocalDate dateOfBirth, LocalDate expiryDate,
                         Set<String> unverifiedFields) {

        public boolean isVerified(String field) {
            return !unverifiedFields.contains(field);
        }
    }

    public Optional<Result> parse(String ocrText) {
        if (ocrText == null || ocrText.isBlank()) {
            return Optional.empty();
        }

        List<String> candidates = candidateLines(ocrText);
        String[] pair = bestPair(candidates);
        if (pair == null) {
            return Optional.empty();
        }

        String line1 = pair[0];
        String line2 = pair[1];
        Set<String> unverified = new LinkedHashSet<>();

        String passportNumber = null;
        String nationality = null;
        String sex = null;
        LocalDate dob = null;
        LocalDate expiry = null;

        if (line2 != null) {
            Checked number = checkedField(line2.substring(0, 9), line2.charAt(9), CharClass.ALPHANUMERIC);
            passportNumber = blankToNull(number.value().replace("<", ""));
            if (passportNumber != null && !number.verified()) {
                unverified.add(FIELD_PASSPORT_NUMBER);
            }

            nationality = blankToNull(coerce(line2.substring(10, 13), CharClass.ALPHABETIC).replace("<", ""));
            char sexChar = line2.charAt(20);
            sex = sexChar == 'M' || sexChar == 'F' ? String.valueOf(sexChar) : null;

            CheckedDate birth = checkedDate(line2.substring(13, 19), line2.charAt(19));
            dob = birth.value();
            if (dob != null && !birth.verified()) {
                unverified.add(FIELD_DATE_OF_BIRTH);
            }

            CheckedDate expires = checkedDate(line2.substring(21, 27), line2.charAt(27));
            expiry = expires.value();
            if (expiry != null && !expires.verified()) {
                unverified.add(FIELD_EXPIRY_DATE);
            }
        }

        String issuingCountry = null;
        String surname = null;
        String givenNames = null;
        if (line1 != null) {
            issuingCountry = blankToNull(coerce(line1.substring(2, 5), CharClass.ALPHABETIC).replace("<", ""));
            String[] names = names(line1);
            surname = names[0];
            givenNames = names[1];
        }

        if (passportNumber == null && surname == null && dob == null && expiry == null) {
            return Optional.empty();
        }

        return Optional.of(new Result(passportNumber, surname, givenNames, nationality, issuingCountry,
                sex, dob, expiry, Set.copyOf(unverified)));
    }

    // ---------------------------------------------------------------- line detection

    private List<String> candidateLines(String ocrText) {
        List<String> candidates = new ArrayList<>();
        for (String rawLine : ocrText.lines().toList()) {
            String cleaned = clean(rawLine);
            if (cleaned.length() >= MIN_MERGED_LENGTH && cleaned.length() <= MAX_MERGED_LENGTH) {
                // Both lines ran together into one OCR line — split at the halfway point.
                int half = cleaned.length() / 2;
                candidates.add(cleaned.substring(0, half));
                candidates.add(cleaned.substring(half));
                continue;
            }
            if (cleaned.length() >= MIN_LINE_LENGTH && cleaned.length() <= MAX_LINE_LENGTH
                    && MRZ_CHARS.matcher(cleaned).matches()) {
                candidates.add(cleaned);
            }
        }
        return candidates;
    }

    /**
     * Picks the two lines most likely to be the MRZ, scoring each adjacent pair on how well it fits
     * the TD3 shape. Handles the pair arriving in either order, and a page where only one of the two
     * lines was legible.
     */
    private String[] bestPair(List<String> candidates) {
        String[] best = null;
        int bestScore = 0;

        for (int i = 0; i + 1 < candidates.size(); i++) {
            String first = candidates.get(i);
            String second = candidates.get(i + 1);

            int forward = score(first, second);
            if (forward > bestScore) {
                bestScore = forward;
                best = new String[]{pad(first), pad(second)};
            }
            // Stripping non-MRZ characters can reorder nothing, but a two-column scan or a rotated
            // page can hand the lines over in the opposite order.
            int reversed = score(second, first);
            if (reversed > bestScore) {
                bestScore = reversed;
                best = new String[]{pad(second), pad(first)};
            }
        }
        if (bestScore >= MIN_PAIR_SCORE) {
            return best;
        }

        // No convincing pair. A lone line is still worth having — line 2 alone carries the number,
        // the nationality and both dates, which is most of what the form wants.
        for (String candidate : candidates) {
            String padded = pad(candidate);
            if (LINE_2_SHAPE.matcher(padded).matches()) {
                return new String[]{null, padded};
            }
        }
        return null;
    }

    private int score(String first, String second) {
        String line1 = pad(first);
        String line2 = pad(second);
        int score = 0;
        if (line1.startsWith("P")) {
            score += 3;
        }
        if (LINE_1_SHAPE.matcher(line1).find()) {
            score += 3;
        }
        if (line1.contains("<<")) {
            score += 1;
        }
        if (LINE_2_SHAPE.matcher(line2).matches()) {
            score += 4;
        }
        if (Character.isDigit(line2.charAt(9))) {
            score += 1;
        }
        // Both lines of a TD3 zone are 44 characters; being close to that is corroboration.
        if (Math.abs(first.length() - TD3_LENGTH) <= 3 && Math.abs(second.length() - TD3_LENGTH) <= 3) {
            score += 1;
        }
        return score;
    }

    /**
     * Normalises one OCR line to the MRZ alphabet. The filler character is the one Tesseract gets
     * wrong most (it has no counterpart in ordinary text), so its common stand-ins are mapped back
     * before anything else is dropped.
     */
    private String clean(String rawLine) {
        String upper = rawLine.toUpperCase(Locale.ROOT)
                .replace("«", "<<")
                .replace("»", "<<")
                .replace("‹", "<")
                .replace("〈", "<")
                .replace("≤", "<")
                .replace("{", "<")
                .replace("(", "<");
        return upper.replaceAll("[^A-Z0-9<]", "");
    }

    private String pad(String line) {
        if (line == null) {
            return null;
        }
        if (line.length() >= TD3_LENGTH) {
            return line.substring(0, TD3_LENGTH);
        }
        return line + "<".repeat(TD3_LENGTH - line.length());
    }

    // ---------------------------------------------------------------- fields

    private String[] names(String line1) {
        String namesField;
        if (LINE_1_SHAPE.matcher(line1).find()) {
            namesField = line1.substring(5);
        } else if (line1.contains("<<")) {
            // The "P<XXX" prefix did not survive OCR; the name field starts the line.
            namesField = line1;
        } else {
            return new String[]{null, null};
        }

        namesField = namesField.replaceAll("<+$", "");
        String[] parts = namesField.split("<<", 2);
        String surname = parts.length > 0 ? tidyName(parts[0]) : null;
        String givenNames = parts.length > 1 ? tidyName(parts[1]) : null;
        return new String[]{surname, givenNames};
    }

    private String tidyName(String raw) {
        String coerced = coerce(raw, CharClass.ALPHABETIC).replace('<', ' ').replaceAll("\\s+", " ").trim();
        return coerced.isBlank() ? null : coerced;
    }

    private record Checked(String value, boolean verified) {
    }

    private record CheckedDate(LocalDate value, boolean verified) {
    }

    /**
     * Validates a field against its check digit, repairing it first if that is what it takes. A
     * repair is accepted only when exactly one plausible single-character fix satisfies the check
     * digit — more than one and there is no basis to choose, so the field comes back unverified.
     */
    private Checked checkedField(String raw, char rawCheckDigit, CharClass charClass) {
        String value = coerce(raw, charClass);
        Character checkDigit = asDigit(rawCheckDigit);
        if (checkDigit == null) {
            return new Checked(value, false);
        }
        if (checksum(value) == checkDigit - '0') {
            return new Checked(value, true);
        }

        Set<String> repairs = new LinkedHashSet<>();
        for (int i = 0; i < value.length(); i++) {
            for (char replacement : CONFUSIONS.getOrDefault(value.charAt(i), "").toCharArray()) {
                if (!charClass.allows(replacement)) {
                    continue;
                }
                String candidate = value.substring(0, i) + replacement + value.substring(i + 1);
                if (checksum(candidate) == checkDigit - '0') {
                    repairs.add(candidate);
                }
            }
        }
        if (repairs.size() == 1) {
            return new Checked(repairs.iterator().next(), true);
        }
        return new Checked(value, false);
    }

    private CheckedDate checkedDate(String raw, char rawCheckDigit) {
        Checked checked = checkedField(raw, rawCheckDigit, CharClass.NUMERIC);
        LocalDate date = toDate(checked.value());
        if (date != null) {
            return new CheckedDate(date, checked.verified());
        }
        // The repaired form is not a real date, so fall back to reading the raw digits: a wrong
        // check digit should not cost the admin a date they can see on the page.
        return new CheckedDate(toDate(coerce(raw, CharClass.NUMERIC)), false);
    }

    private LocalDate toDate(String yymmdd) {
        if (yymmdd == null || yymmdd.length() != 6 || !yymmdd.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            int yy = Integer.parseInt(yymmdd.substring(0, 2));
            int mm = Integer.parseInt(yymmdd.substring(2, 4));
            int dd = Integer.parseInt(yymmdd.substring(4, 6));
            // ICAO convention for a 2-digit MRZ year.
            int year = yy <= 49 ? 2000 + yy : 1900 + yy;
            return LocalDate.of(year, mm, dd);
        } catch (DateTimeException | NumberFormatException ex) {
            return null;
        }
    }

    /** Forces a field into the alphabet its MRZ position allows, which fixes most misreads outright. */
    private String coerce(String value, CharClass charClass) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            sb.append(charClass.coerce(c));
        }
        return sb.toString();
    }

    private Character asDigit(char c) {
        if (Character.isDigit(c)) {
            return c;
        }
        return LETTER_TO_DIGIT.get(c);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private int checksum(String value) {
        int sum = 0;
        for (int i = 0; i < value.length(); i++) {
            sum += charValue(value.charAt(i)) * WEIGHTS[i % 3];
        }
        return sum % 10;
    }

    private int charValue(char c) {
        if (c == '<') {
            return 0;
        }
        if (Character.isDigit(c)) {
            return c - '0';
        }
        return (c - 'A') + 10;
    }

    /** What an MRZ position is allowed to hold, and how to push a misread character into it. */
    private enum CharClass {
        NUMERIC {
            @Override
            char coerce(char c) {
                if (Character.isDigit(c)) {
                    return c;
                }
                Character digit = LETTER_TO_DIGIT.get(c);
                return digit != null ? digit : c;
            }

            @Override
            boolean allows(char c) {
                return Character.isDigit(c);
            }
        },
        ALPHABETIC {
            @Override
            char coerce(char c) {
                if (c == '<' || (c >= 'A' && c <= 'Z')) {
                    return c;
                }
                Character letter = DIGIT_TO_LETTER.get(c);
                return letter != null ? letter : c;
            }

            @Override
            boolean allows(char c) {
                return c == '<' || (c >= 'A' && c <= 'Z');
            }
        },
        ALPHANUMERIC {
            @Override
            char coerce(char c) {
                return c;
            }

            @Override
            boolean allows(char c) {
                return c == '<' || Character.isLetterOrDigit(c);
            }
        };

        abstract char coerce(char c);

        abstract boolean allows(char c);
    }

    private static Map<Character, String> confusions() {
        Map<String, String> pairs = new LinkedHashMap<>();
        pairs.put("0", "ODQU8");
        pairs.put("1", "ILJT7");
        pairs.put("2", "Z7");
        pairs.put("3", "89");
        pairs.put("4", "A9");
        pairs.put("5", "S6");
        pairs.put("6", "G5C8");
        pairs.put("7", "T12");
        pairs.put("8", "B036");
        pairs.put("9", "G43");
        pairs.put("O", "0DQ");
        pairs.put("D", "0O");
        pairs.put("Q", "0O");
        pairs.put("U", "0V");
        pairs.put("I", "1LT");
        pairs.put("L", "1I");
        pairs.put("J", "1");
        pairs.put("T", "17I");
        pairs.put("Z", "2");
        pairs.put("A", "4");
        pairs.put("S", "5");
        pairs.put("G", "69");
        pairs.put("C", "6G0");
        pairs.put("B", "8");
        pairs.put("V", "UY");
        pairs.put("Y", "V");
        pairs.put("K", "X");
        pairs.put("X", "K");

        Map<Character, String> confusions = new LinkedHashMap<>();
        pairs.forEach((from, to) -> confusions.put(from.charAt(0), to));
        return Map.copyOf(confusions);
    }
}
