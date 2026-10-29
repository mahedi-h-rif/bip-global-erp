package com.fatoora.api.ocr;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.chrono.HijrahChronology;
import java.time.chrono.HijrahEra;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Finds every date in a line of OCR text, in the shapes Saudi and foreign identity documents
 * actually print them.
 *
 * <p>The previous single {@code d/m/y} pattern missed most real cards. An Iqama prints its dates
 * twice — Hijri above Gregorian — often with dots or Arabic-Indic digits; a passport prints
 * {@code 12 MAR 2020}; a scan-to-PDF can come back ISO-ordered. Each of those is a shape here.
 *
 * <p>Hijri dates are converted with {@link HijrahChronology}, which is the Umm al-Qura calendar —
 * the one Saudi documents are issued against — so a card showing only Hijri still yields a usable
 * Gregorian value.
 */
@Component
public class DateTextParser {

    /** Year range treated as Hijri rather than Gregorian. Umm al-Qura 1300 AH is 1882 CE. */
    private static final int HIJRI_MIN_YEAR = 1300;
    private static final int HIJRI_MAX_YEAR = 1600;

    private static final Pattern NUMERIC_DATE =
            Pattern.compile("(?<!\\d)(\\d{1,4})\\s?[/.\\-]\\s?(\\d{1,2})\\s?[/.\\-]\\s?(\\d{1,4})(?!\\d)");

    /** Spaced numeric (e.g. "12 03 2027") — only with an unambiguous 4-digit year, or it matches noise. */
    private static final Pattern SPACED_DATE = Pattern.compile("(?<!\\d)(\\d{1,2})\\s+(\\d{1,2})\\s+(\\d{4})(?!\\d)");

    private static final Pattern DAY_MONTH_NAME_YEAR =
            Pattern.compile("(?<!\\d)(\\d{1,2})[\\s.\\-/]+([\\p{L}]{3,12})\\.?[\\s.\\-/]+(\\d{2,4})(?!\\d)");

    private static final Pattern MONTH_NAME_DAY_YEAR =
            Pattern.compile("([\\p{L}]{3,12})\\.?[\\s.\\-/]+(\\d{1,2})(?:ST|ND|RD|TH)?,?[\\s.\\-/]+(\\d{2,4})(?!\\d)",
                    Pattern.CASE_INSENSITIVE);

    private static final Map<String, Integer> MONTH_NAMES = monthNames();

    /** A date found in the text, with where it sat so callers can reason about nearby labels. */
    public record Found(LocalDate date, int start, int end) {
    }

    /**
     * Replaces Arabic-Indic ({@code ٠١٢}) and Extended Arabic-Indic ({@code ۰۱۲}) digits with ASCII
     * ones. Tesseract returns these verbatim from an Arabic-language pass, and every numeric
     * pattern downstream is ASCII-only.
     */
    public static String normalizeDigits(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c >= '٠' && c <= '٩') {
                sb.append((char) ('0' + (c - '٠')));
            } else if (c >= '۰' && c <= '۹') {
                sb.append((char) ('0' + (c - '۰')));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Every date in the text, in the order they appear, deduplicated by (value, position). */
    public List<Found> findAll(String text) {
        List<Found> found = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return found;
        }
        String normalized = normalizeDigits(text);

        collect(found, NUMERIC_DATE.matcher(normalized), this::fromNumericParts);
        collect(found, SPACED_DATE.matcher(normalized), this::fromNumericParts);
        collect(found, DAY_MONTH_NAME_YEAR.matcher(normalized), this::fromDayMonthNameYear);
        collect(found, MONTH_NAME_DAY_YEAR.matcher(normalized), this::fromMonthNameDayYear);

        found.sort(java.util.Comparator.comparingInt(Found::start));
        return found;
    }

    /** The first date in the text, or null. */
    public LocalDate firstIn(String text) {
        List<Found> all = findAll(text);
        return all.isEmpty() ? null : all.get(0).date();
    }

    private void collect(List<Found> target, Matcher matcher, PartsParser parser) {
        while (matcher.find()) {
            LocalDate date = parser.parse(matcher.group(1), matcher.group(2), matcher.group(3));
            if (date == null) {
                continue;
            }
            int start = matcher.start();
            boolean duplicate = target.stream()
                    .anyMatch(existing -> existing.date().equals(date) && Math.abs(existing.start() - start) < 4);
            if (!duplicate) {
                target.add(new Found(date, start, matcher.end()));
            }
        }
    }

    /**
     * Resolves a numeric triple. Day-first is assumed (every market this serves writes d/m/y), with
     * two escape hatches: a 4-digit leading group is ISO year-first, and a first group above 12
     * with a plausible second group is US month-first.
     */
    private LocalDate fromNumericParts(String first, String second, String third) {
        Integer a = toInt(first);
        Integer b = toInt(second);
        Integer c = toInt(third);
        if (a == null || b == null || c == null) {
            return null;
        }
        // Neither end is a recognisable year, so this is something like a "1/2/3" list marker.
        if (first.length() < 3 && third.length() < 2) {
            return null;
        }

        if (first.length() >= 3) {
            return build(a, b, c);
        }
        if (third.length() >= 3 || c > 31) {
            if (a > 12 && b <= 12) {
                return build(c, b, a);
            }
            if (b > 12 && a <= 12) {
                return build(c, a, b);
            }
            return build(c, b, a);
        }
        // All three are 1-2 digits: a two-digit year in last position, still day-first.
        return build(c, b, a);
    }

    private LocalDate fromDayMonthNameYear(String day, String monthName, String year) {
        Integer month = MONTH_NAMES.get(monthKey(monthName));
        Integer d = toInt(day);
        Integer y = toInt(year);
        return month == null || d == null || y == null ? null : build(y, month, d);
    }

    private LocalDate fromMonthNameDayYear(String monthName, String day, String year) {
        Integer month = MONTH_NAMES.get(monthKey(monthName));
        Integer d = toInt(day);
        Integer y = toInt(year);
        return month == null || d == null || y == null ? null : build(y, month, d);
    }

    /** Builds a Gregorian date, converting from Umm al-Qura first when the year is clearly Hijri. */
    private LocalDate build(int year, int month, int day) {
        int resolvedYear = year;
        if (year < 100) {
            // A 2-digit year on an identity document is within a few decades either side of now:
            // 00-49 is this century, 50-99 the last one (same rule the MRZ spec uses).
            resolvedYear = year <= 49 ? 2000 + year : 1900 + year;
        }

        if (resolvedYear >= HIJRI_MIN_YEAR && resolvedYear <= HIJRI_MAX_YEAR) {
            try {
                return LocalDate.from(HijrahChronology.INSTANCE.date(HijrahEra.AH, resolvedYear, month, day));
            } catch (DateTimeException ex) {
                return null;
            }
        }

        try {
            return LocalDate.of(resolvedYear, month, day);
        } catch (DateTimeException ex) {
            return null;
        }
    }

    private Integer toInt(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String monthKey(String raw) {
        return raw.toUpperCase(Locale.ROOT).replaceAll("[^\\p{L}]", "");
    }

    private static Map<String, Integer> monthNames() {
        Map<String, Integer> names = new LinkedHashMap<>();
        String[] english = {"JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE",
                "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER"};
        for (int i = 0; i < english.length; i++) {
            names.put(english[i], i + 1);
            names.put(english[i].substring(0, 3), i + 1);
        }
        // Bilingual passports (common across the Gulf and francophone Africa) print the French form
        // alongside the English one, and the OCR can land on either side of the slash.
        String[] french = {"JANVIER", "FEVRIER", "MARS", "AVRIL", "MAI", "JUIN",
                "JUILLET", "AOUT", "SEPTEMBRE", "OCTOBRE", "NOVEMBRE", "DECEMBRE"};
        for (int i = 0; i < french.length; i++) {
            names.putIfAbsent(french[i], i + 1);
        }
        // Arabic (Levantine/Gulf Gregorian month names, as printed on Saudi-issued documents).
        String[] arabic = {"يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
                "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"};
        for (int i = 0; i < arabic.length; i++) {
            names.putIfAbsent(arabic[i].toUpperCase(Locale.ROOT), i + 1);
        }
        return Map.copyOf(names);
    }

    @FunctionalInterface
    private interface PartsParser {
        LocalDate parse(String first, String second, String third);
    }
}
