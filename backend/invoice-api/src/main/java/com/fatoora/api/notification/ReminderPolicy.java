package com.fatoora.api.notification;

import java.time.Duration;
import java.time.Instant;

/** When an automatic payment reminder is due for a customer. */
public final class ReminderPolicy {

    private ReminderPolicy() {}

    /**
     * The clock starts at the later of the last reminder and the oldest unpaid invoice, so a customer is not
     * reminded about an invoice issued yesterday just because their last reminder (for older debt) is months old.
     *
     * @param lastReminderAt   last reminder sent to this customer, or null if never
     * @param oldestDueIssued  issue time of the customer's oldest invoice that still has money due, or null if none
     */
    public static boolean isDue(Instant lastReminderAt, Instant oldestDueIssued, Instant now, Duration interval) {
        if (oldestDueIssued == null) return false;
        Instant from = lastReminderAt != null && lastReminderAt.isAfter(oldestDueIssued) ? lastReminderAt : oldestDueIssued;
        return !from.plus(interval).isAfter(now);
    }
}
