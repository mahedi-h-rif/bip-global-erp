package com.fatoora.api.common;

import jakarta.validation.ConstraintViolation;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns bean-validation errors into sentences a user can act on:
 * {@code lines[1].name: must not be blank} becomes {@code Line 2: Name is required}.
 */
final class FriendlyValidation {

    private FriendlyValidation() {}

    private static final Pattern INDEXED = Pattern.compile("^(\\w*)\\[(\\d+)]$");
    private static final Set<String> ACRONYMS = Set.of(
            "vat", "cr", "iban", "otp", "id", "url", "sku", "po", "pdf", "sms", "zatca", "csr", "qr", "ip", "hs", "gl", "crn", "nin");

    /** Words read better as the singular when naming one row ("lines[1]" → "Line 2"). */
    private static String singular(String word) {
        if (word.endsWith("ies")) return word.substring(0, word.length() - 3) + "y";
        if (word.endsWith("sses") || word.endsWith("ches") || word.endsWith("shes")) return word.substring(0, word.length() - 2);
        if (word.endsWith("s") && !word.endsWith("ss")) return word.substring(0, word.length() - 1);
        return word;
    }

    /** "unitPrice" → "unit price", "vatNumber" → "VAT number", "customerId" → "customer". */
    static String words(String name) {
        String n = name.length() > 2 && name.endsWith("Id") ? name.substring(0, name.length() - 2) : name;
        n = n.replace('_', ' ');
        String[] parts = n.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|\\s+");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p.isBlank()) continue;
            String lower = p.toLowerCase(Locale.ROOT);
            out.add(ACRONYMS.contains(lower) ? lower.toUpperCase(Locale.ROOT) : lower);
        }
        return String.join(" ", out);
    }

    static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** "lines[1].name" → "Line 2: Name"; "customer.vatNumber" → "Customer VAT number". */
    static String label(String path) {
        List<String> rows = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String seg : path.split("\\.")) {
            Matcher m = INDEXED.matcher(seg);
            if (m.matches()) {
                String row = m.group(1).isEmpty() ? "Row" : capitalize(words(singular(m.group(1))));
                rows.add(row + " " + (Integer.parseInt(m.group(2)) + 1));
            } else if (!seg.isEmpty()) names.add(words(seg));
        }
        String field = capitalize(String.join(" ", names));
        if (rows.isEmpty()) return field;
        String where = String.join(", ", rows);
        return field.isEmpty() ? where : where + ": " + field;
    }

    /** One readable sentence for a field error. */
    static String message(FieldError f) {
        return message(f.getField(), f);
    }

    static String message(String path, FieldError f) {
        String label = label(path);
        String code = f.getCode() == null ? "" : f.getCode();
        Map<String, Object> attrs = attributes(f);
        String custom = customMessage(f);
        boolean many = f.getRejectedValue() instanceof Collection<?> || f.getRejectedValue() instanceof Object[];
        String rule = switch (code) {
            case "NotBlank", "NotNull" -> "is required";
            case "NotEmpty" -> many ? "needs at least one entry" : "is required";
            case "Size", "Length" -> size(attrs, many);
            case "Min", "DecimalMin" -> min(attrs);
            case "Max", "DecimalMax" -> max(attrs);
            case "Positive" -> "must be more than 0";
            case "PositiveOrZero" -> "can't be negative";
            case "Negative" -> "must be less than 0";
            case "Email" -> "must be a valid email address";
            case "Digits" -> "has too many digits";
            case "Past" -> "must be a date in the past";
            case "PastOrPresent" -> "can't be in the future";
            case "Future" -> "must be a date in the future";
            case "FutureOrPresent" -> "can't be in the past";
            case "Pattern" -> "has an invalid format";
            case "typeMismatch" -> "has an invalid value";
            default -> null;
        };
        if (custom != null) return label.isEmpty() ? capitalize(custom) : label + ": " + custom;
        if (rule == null) rule = f.getDefaultMessage() == null ? "is invalid" : f.getDefaultMessage();
        if (label.isEmpty()) return capitalize(rule);
        // "Line 2: Name is required"; a bare row ("Line 2") reads "Line 2 is required".
        return label + " " + rule;
    }

    static String message(ObjectError e) {
        String custom = e.getDefaultMessage();
        return custom == null || custom.isBlank() ? "Some details are invalid" : capitalize(custom);
    }

    private static String size(Map<String, Object> a, boolean many) {
        long min = num(a.get("min"), 0);
        long max = num(a.get("max"), Integer.MAX_VALUE);
        String unit = many ? "entries" : "characters";
        boolean noMax = max >= Integer.MAX_VALUE;
        if (min > 0 && noMax) return min == 1 && many ? "needs at least one entry" : "needs at least " + min + " " + unit;
        if (min <= 0 && !noMax) return "can be at most " + max + " " + unit;
        if (min == max) return "must be exactly " + max + " " + unit;
        return "must be between " + min + " and " + max + " " + unit;
    }

    private static String min(Map<String, Object> a) {
        Object v = a.get("value");
        boolean inclusive = !Boolean.FALSE.equals(a.get("inclusive"));
        String value = v == null ? "" : stripZeros(String.valueOf(v));
        if (!inclusive) return "0".equals(value) ? "must be more than 0" : "must be more than " + value;
        return "0".equals(value) ? "can't be negative" : "must be at least " + value;
    }

    private static String max(Map<String, Object> a) {
        Object v = a.get("value");
        boolean inclusive = !Boolean.FALSE.equals(a.get("inclusive"));
        String value = v == null ? "" : stripZeros(String.valueOf(v));
        return inclusive ? "can be at most " + value : "must be less than " + value;
    }

    private static String stripZeros(String s) {
        try {
            return new java.math.BigDecimal(s).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return s;
        }
    }

    private static long num(Object o, long fallback) {
        return o instanceof Number n ? n.longValue() : fallback;
    }

    private static Map<String, Object> attributes(FieldError f) {
        try {
            if (f.contains(ConstraintViolation.class)) {
                return f.unwrap(ConstraintViolation.class).getConstraintDescriptor().getAttributes();
            }
        } catch (RuntimeException ignored) {
            // fall through: no constraint details
        }
        return Map.of();
    }

    /** The message written on the annotation itself (e.g. message = "4 digits"), or null for the built-in default. */
    private static String customMessage(FieldError f) {
        try {
            if (f.contains(ConstraintViolation.class)) {
                String template = f.unwrap(ConstraintViolation.class).getMessageTemplate();
                if (template != null && !template.startsWith("{")) return f.getDefaultMessage();
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return null;
    }
}
