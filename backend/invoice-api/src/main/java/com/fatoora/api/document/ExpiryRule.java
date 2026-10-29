package com.fatoora.api.document;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * When a document's expiry reminders go out. From two months before expiry, one every 15 days; in the last
 * 15 days, one every 2 days; after expiry, every 2 days until it is renewed, if the document asks for that.
 */
public final class ExpiryRule {

    public static final int WATCH_DAYS = 60;
    public static final int FINAL_DAYS = 15;
    public static final int EARLY_EVERY = 15;
    public static final int FINAL_EVERY = 2;

    public enum Stage { NOT_YET, EARLY, FINAL, EXPIRED }

    private ExpiryRule() {}

    public static long daysLeft(LocalDate expiry, LocalDate today) {
        return ChronoUnit.DAYS.between(today, expiry);
    }

    public static Stage stage(LocalDate expiry, LocalDate today) {
        long left = daysLeft(expiry, today);
        if (left > WATCH_DAYS) return Stage.NOT_YET;
        if (left > FINAL_DAYS) return Stage.EARLY;
        if (left >= 0) return Stage.FINAL;
        return Stage.EXPIRED;
    }

    /** Whether a reminder is due today, given the day the last one went out (null: none yet). */
    public static boolean due(LocalDate expiry, LocalDate today, LocalDate lastSent, boolean afterExpiry) {
        Stage s = stage(expiry, today);
        if (s == Stage.NOT_YET || s == Stage.EXPIRED && !afterExpiry) return false;
        if (lastSent == null) return true;
        // Entering the final 15 days always sends, even if the last early reminder was recent.
        if (s != Stage.EARLY && stage(expiry, lastSent) == Stage.EARLY) return true;
        long since = ChronoUnit.DAYS.between(lastSent, today);
        return since >= (s == Stage.EARLY ? EARLY_EVERY : FINAL_EVERY);
    }

    /** The next day a reminder will go out, or null when none will. */
    public static LocalDate next(LocalDate expiry, LocalDate today, LocalDate lastSent, boolean afterExpiry) {
        for (LocalDate d = today; d.isBefore(today.plusDays(400)); d = d.plusDays(1)) {
            if (due(expiry, d, lastSent, afterExpiry)) return d;
            if (stage(expiry, d) == Stage.EXPIRED && !afterExpiry) return null;
        }
        return null;
    }
}
