package com.fatoora.api.ocr;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Best-effort field extraction for Saudi Iqama (resident ID) cards from raw OCR text.
 *
 * <p>Unlike a passport there is no machine-readable zone and so no checksum to lean on — this is
 * heuristics all the way down. What makes them work on a real card rather than an idealised one:
 *
 * <ul>
 *   <li><b>Label proximity, not label equality.</b> A card is a two-column bilingual layout, and
 *       OCR routinely puts a label and its value on different lines. A date is attributed to the
 *       nearest label before it on its own line, then to a label on the line above or below.
 *   <li><b>The number is validated, not just matched.</b> A Saudi ID is ten digits beginning with 1
 *       (citizen) or 2 (resident), so a phone number or a border number elsewhere on the card no
 *       longer wins simply by appearing first.
 *   <li><b>Both calendars.</b> Dates are printed Hijri above Gregorian, in whichever digits the
 *       Arabic pass returned — {@link DateTextParser} handles both.
 *   <li><b>Ordering as a last resort.</b> Two dates and no usable labels still means issue then
 *       expiry, which is right far more often than leaving the form blank.
 * </ul>
 *
 * <p>Anything not confidently matched stays null so the admin fills it in by hand.
 */
@Component
@RequiredArgsConstructor
public class IqamaFieldExtractor {

    /** Ten digits, leading 1 or 2, tolerating the grouping separators OCR inserts into the print. */
    private static final Pattern ID_NUMBER = Pattern.compile("(?<!\\d)([12])[ .\\-]?(\\d{3})[ .\\-]?(\\d{3})[ .\\-]?(\\d{3})(?!\\d)");

    /** Fallback when the leading digit was misread: any bare ten-digit run. */
    private static final Pattern ANY_TEN_DIGITS = Pattern.compile("(?<!\\d)(\\d{10})(?!\\d)");

    private static final Pattern LATIN_NAME_NOISE = Pattern.compile("[^A-Za-z ]");

    private static final int MAX_NAME_LENGTH = 60;

    private static final Map<Label, List<String>> LABELS = labels();

    private final DateTextParser dateTextParser;

    private enum Label {
        ISSUE, EXPIRY, BIRTH, ID, NAME, NATIONALITY
    }

    public record Result(String iqamaNumber, LocalDate issueDate, LocalDate expireDate,
                         String name, String nationality, LocalDate dateOfBirth) {
    }

    public Result extract(String ocrText) {
        if (ocrText == null || ocrText.isBlank()) {
            return new Result(null, null, null, null, null, null);
        }

        String normalized = DateTextParser.normalizeDigits(ocrText);
        List<String> lines = normalized.lines().map(String::strip).filter(line -> !line.isBlank()).toList();

        Map<Label, LocalDate> dates = attributeDates(lines);
        List<LocalDate> unassigned = unassignedDates(lines);
        resolveIssueAndExpiry(dates, unassigned);

        return new Result(
                idNumber(lines),
                dates.get(Label.ISSUE),
                dates.get(Label.EXPIRY),
                labelledText(lines, Label.NAME),
                labelledText(lines, Label.NATIONALITY),
                dates.get(Label.BIRTH));
    }

    // ---------------------------------------------------------------- dates

    /** Attributes each date on the card to whichever label is nearest to it, line by line. */
    private Map<Label, LocalDate> attributeDates(List<String> lines) {
        Map<Label, LocalDate> dates = new EnumMap<>(Label.class);

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            List<LabelHit> hits = labelHits(line);
            List<DateTextParser.Found> found = dateTextParser.findAll(line);

            if (!found.isEmpty() && !hits.isEmpty()) {
                for (DateTextParser.Found date : found) {
                    Label label = nearestLabel(hits, date.start());
                    if (label != null && isDateLabel(label)) {
                        dates.putIfAbsent(label, date.date());
                    }
                }
                continue;
            }

            // A label with no date of its own takes the first date from the line below it, which is
            // how a stacked (label-over-value) column comes out of OCR.
            if (found.isEmpty() && !hits.isEmpty() && i + 1 < lines.size()) {
                List<DateTextParser.Found> below = dateTextParser.findAll(lines.get(i + 1));
                if (!below.isEmpty() && labelHits(lines.get(i + 1)).isEmpty()) {
                    Label label = hits.get(0).label();
                    if (isDateLabel(label)) {
                        dates.putIfAbsent(label, below.get(0).date());
                    }
                }
            }
        }
        return dates;
    }

    /** Dates on lines carrying no label at all — the raw material for the ordering fallback. */
    private List<LocalDate> unassignedDates(List<String> lines) {
        List<LocalDate> unassigned = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!labelHits(lines.get(i)).isEmpty()) {
                continue;
            }
            // Already consumed as the value of a label sitting on the line above.
            boolean claimedByLineAbove = i > 0 && !labelHits(lines.get(i - 1)).isEmpty()
                    && dateTextParser.findAll(lines.get(i - 1)).isEmpty();
            if (claimedByLineAbove) {
                continue;
            }
            dateTextParser.findAll(lines.get(i)).forEach(found -> unassigned.add(found.date()));
        }
        return unassigned;
    }

    /**
     * Fills whichever of issue/expiry the labels did not, and sanity-checks the pair. An Iqama is
     * issued before it expires; if the two came out the other way round, the labels were misread and
     * swapping them is the better guess.
     */
    private void resolveIssueAndExpiry(Map<Label, LocalDate> dates, List<LocalDate> unassigned) {
        LocalDate birth = dates.get(Label.BIRTH);
        List<LocalDate> pool = unassigned.stream()
                .filter(date -> birth == null || !date.equals(birth))
                .distinct()
                .sorted()
                .toList();

        LocalDate issue = dates.get(Label.ISSUE);
        LocalDate expiry = dates.get(Label.EXPIRY);

        if (issue == null && expiry == null && pool.size() >= 2) {
            issue = pool.get(0);
            expiry = pool.get(pool.size() - 1);
        } else if (expiry == null && issue != null) {
            LocalDate knownIssue = issue;
            expiry = pool.stream().filter(date -> date.isAfter(knownIssue)).max(LocalDate::compareTo).orElse(null);
        } else if (issue == null && expiry != null) {
            LocalDate knownExpiry = expiry;
            issue = pool.stream().filter(date -> date.isBefore(knownExpiry)).max(LocalDate::compareTo).orElse(null);
        }

        if (issue != null && expiry != null && issue.isAfter(expiry)) {
            LocalDate swap = issue;
            issue = expiry;
            expiry = swap;
        }
        if (birth != null && birth.equals(issue)) {
            issue = null;
        }

        putOrRemove(dates, Label.ISSUE, issue);
        putOrRemove(dates, Label.EXPIRY, expiry);
    }

    private void putOrRemove(Map<Label, LocalDate> dates, Label label, LocalDate value) {
        if (value == null) {
            dates.remove(label);
        } else {
            dates.put(label, value);
        }
    }

    private boolean isDateLabel(Label label) {
        return label == Label.ISSUE || label == Label.EXPIRY || label == Label.BIRTH;
    }

    // ---------------------------------------------------------------- labels

    private record LabelHit(Label label, int start) {
    }

    private List<LabelHit> labelHits(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        List<LabelHit> hits = new ArrayList<>();
        LABELS.forEach((label, keywords) -> {
            for (String keyword : keywords) {
                int index = upper.indexOf(keyword);
                if (index >= 0) {
                    hits.add(new LabelHit(label, index));
                    break;
                }
            }
        });
        hits.sort(Comparator.comparingInt(LabelHit::start));
        return hits;
    }

    /**
     * The label a value belongs to is the last one that starts before it — which is what keeps a
     * merged "Issue 01/01/2023 Expiry 01/01/2027" line from filing the issue date under expiry, the
     * way naive whole-line matching did.
     */
    private Label nearestLabel(List<LabelHit> hits, int valueStart) {
        Label before = null;
        for (LabelHit hit : hits) {
            if (hit.start() < valueStart) {
                before = hit.label();
            }
        }
        // Nothing to the left: an RTL line can put the label after its value.
        return before != null ? before : hits.get(0).label();
    }

    private String labelledText(List<String> lines, Label label) {
        List<String> keywords = LABELS.get(label);
        for (int i = 0; i < lines.size(); i++) {
            String upper = lines.get(i).toUpperCase(Locale.ROOT);
            String matched = keywords.stream().filter(upper::contains).findFirst().orElse(null);
            if (matched == null) {
                continue;
            }
            // "Father's name" must not answer for "Name".
            if (label == Label.NAME && upper.contains("FATHER")) {
                continue;
            }
            String remainder = tidy(upper.substring(upper.indexOf(matched) + matched.length()));
            if (remainder != null) {
                return remainder;
            }
            if (i + 1 < lines.size()) {
                String below = tidy(lines.get(i + 1).toUpperCase(Locale.ROOT));
                if (below != null) {
                    return below;
                }
            }
        }
        return null;
    }

    private String tidy(String raw) {
        String cleaned = LATIN_NAME_NOISE.matcher(raw.replace(':', ' ')).replaceAll(" ")
                .replaceAll("\\s+", " ").trim();
        if (cleaned.length() < 3 || cleaned.length() > MAX_NAME_LENGTH) {
            return null;
        }
        return cleaned;
    }

    // ---------------------------------------------------------------- id number

    private String idNumber(List<String> lines) {
        String joined = String.join("\n", lines);

        String labelled = null;
        String anyValid = null;
        for (String line : lines) {
            Matcher matcher = ID_NUMBER.matcher(line);
            while (matcher.find()) {
                String candidate = matcher.group(1) + matcher.group(2) + matcher.group(3) + matcher.group(4);
                if (anyValid == null) {
                    anyValid = candidate;
                }
                if (labelled == null && labelHits(line).stream().anyMatch(hit -> hit.label() == Label.ID)) {
                    labelled = candidate;
                }
            }
        }
        if (labelled != null) {
            return labelled;
        }
        if (anyValid != null) {
            return anyValid;
        }

        // Last resort: the leading digit was misread badly enough to fail the 1/2 rule.
        Matcher fallback = ANY_TEN_DIGITS.matcher(joined);
        return fallback.find() ? fallback.group(1) : null;
    }

    private static Map<Label, List<String>> labels() {
        Map<Label, List<String>> labels = new EnumMap<>(Label.class);
        labels.put(Label.EXPIRY, List.of("EXPIRY", "EXPIRE", "EXPIRATION", "EXP.", "EXP ", "VALID UNTIL",
                "VALID TILL", "VALIDITY", "تاريخ الانتهاء", "الانتهاء", "انتهاء", "الصلاحية"));
        labels.put(Label.ISSUE, List.of("DATE OF ISSUE", "ISSUE DATE", "ISSUED", "ISSUE", "تاريخ الاصدار",
                "تاريخ الإصدار", "الاصدار", "الإصدار"));
        labels.put(Label.BIRTH, List.of("DATE OF BIRTH", "BIRTH DATE", "BIRTH", "D.O.B", "DOB",
                "تاريخ الميلاد", "الميلاد"));
        labels.put(Label.ID, List.of("ID NUMBER", "IDENTITY NUMBER", "ID NO", "IQAMA", "RESIDENT IDENTITY",
                "رقم الهوية", "الهوية", "الإقامة", "الاقامة"));
        labels.put(Label.NAME, List.of("NAME", "الاسم"));
        labels.put(Label.NATIONALITY, List.of("NATIONALITY", "الجنسية"));
        return Map.copyOf(labels);
    }
}
