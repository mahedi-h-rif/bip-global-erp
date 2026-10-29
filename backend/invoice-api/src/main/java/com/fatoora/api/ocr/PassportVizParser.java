package com.fatoora.api.ocr;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads the printed (visual inspection) zone of a passport photo page for the things the MRZ
 * cannot carry.
 *
 * <p>A TD3 machine-readable zone has no issue date and no parent name, so those two fields could
 * never be filled from {@link PassportMrzParser} no matter how good the OCR got — they have to come
 * off the printed page. Labels vary by issuing country and are frequently bilingual, so matching is
 * keyword-based and falls back to reasoning about the dates themselves: the issue date is the one
 * sitting between the holder date of birth and the expiry.
 */
@Component
@RequiredArgsConstructor
public class PassportVizParser {

    private static final List<String> ISSUE_LABELS = List.of(
            "DATE OF ISSUE", "ISSUE DATE", "DATEOFISSUE", "ISSUED ON", "ISSUED",
            "DATE D'EMISSION", "DATE DE DELIVRANCE", "تاريخ الاصدار", "تاريخ الإصدار");

    private static final List<String> EXPIRY_LABELS = List.of(
            "DATE OF EXPIRY", "EXPIRY DATE", "DATE OF EXPIRATION", "EXPIRES", "EXPIRY",
            "DATE D'EXPIRATION", "تاريخ الانتهاء");

    private static final List<String> FATHER_LABELS = List.of(
            "FATHER'S NAME", "FATHERS NAME", "FATHER NAME", "NAME OF FATHER", "FATHER",
            "اسم الاب", "اسم الأب");

    /** Passport name lines are Latin capitals; anything else on the line is OCR noise from the Arabic side. */
    private static final Pattern NAME_CHARS = Pattern.compile("[^A-Z ]");

    private static final int MAX_NAME_LENGTH = 60;

    private final DateTextParser dateTextParser;

    public record Result(LocalDate issueDate, String fatherName) {
    }

    /**
     * @param dateOfBirth from the MRZ, when it was readable — used to bracket the issue-date guess
     * @param expiryDate from the MRZ, when it was readable — likewise
     */
    public Result parse(String ocrText, LocalDate dateOfBirth, LocalDate expiryDate) {
        if (ocrText == null || ocrText.isBlank()) {
            return new Result(null, null);
        }
        List<String> lines = ocrText.lines().map(String::strip).filter(line -> !line.isBlank()).toList();

        LocalDate issueDate = labelledDate(lines, ISSUE_LABELS);
        if (issueDate == null) {
            issueDate = inferIssueDate(ocrText, dateOfBirth, expiryDate);
        }
        return new Result(issueDate, fatherName(lines));
    }

    /** A date on the same line as one of the labels, or on the line below it (stacked layouts). */
    private LocalDate labelledDate(List<String> lines, List<String> labels) {
        for (int i = 0; i < lines.size(); i++) {
            String upper = lines.get(i).toUpperCase(Locale.ROOT);
            if (labels.stream().noneMatch(upper::contains)) {
                continue;
            }
            LocalDate sameLine = dateTextParser.firstIn(lines.get(i));
            if (sameLine != null) {
                return sameLine;
            }
            if (i + 1 < lines.size()) {
                LocalDate nextLine = dateTextParser.firstIn(lines.get(i + 1));
                if (nextLine != null) {
                    return nextLine;
                }
            }
        }
        return null;
    }

    /**
     * With no usable label, fall back to the arithmetic: a passport page shows three dates, and the
     * issue date is the one between birth and expiry. Requires both MRZ dates so the bracket is real
     * — guessing from an unbracketed list would be worse than leaving the field blank.
     */
    private LocalDate inferIssueDate(String ocrText, LocalDate dateOfBirth, LocalDate expiryDate) {
        if (dateOfBirth == null || expiryDate == null) {
            return null;
        }
        LocalDate today = LocalDate.now();
        return dateTextParser.findAll(ocrText).stream()
                .map(DateTextParser.Found::date)
                .distinct()
                .filter(date -> date.isAfter(dateOfBirth) && date.isBefore(expiryDate) && !date.isAfter(today))
                .max(LocalDate::compareTo)
                .orElse(null);
    }

    private String fatherName(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String upper = lines.get(i).toUpperCase(Locale.ROOT);
            String matched = FATHER_LABELS.stream().filter(upper::contains).findFirst().orElse(null);
            if (matched == null) {
                continue;
            }
            String remainder = upper.substring(upper.indexOf(matched) + matched.length());
            String sameLine = tidy(remainder);
            if (sameLine != null) {
                return sameLine;
            }
            if (i + 1 < lines.size()) {
                String nextLine = tidy(lines.get(i + 1).toUpperCase(Locale.ROOT));
                if (nextLine != null) {
                    return nextLine;
                }
            }
        }
        return null;
    }

    private String tidy(String raw) {
        String cleaned = NAME_CHARS.matcher(raw.replace(':', ' ')).replaceAll(" ").replaceAll("\\s+", " ").trim();
        if (cleaned.length() < 3 || cleaned.length() > MAX_NAME_LENGTH) {
            return null;
        }
        // A single letter left over from a stripped label is not a name.
        return cleaned.contains(" ") || cleaned.length() >= 4 ? cleaned : null;
    }
}
