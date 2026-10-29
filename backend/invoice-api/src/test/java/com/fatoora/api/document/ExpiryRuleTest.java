package com.fatoora.api.document;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExpiryRuleTest {

    private static final LocalDate EXPIRY = LocalDate.of(2027, 3, 31);

    /** Days-left on each day a reminder goes out, walking day by day from 90 days before to `until` days after. */
    private static List<Long> schedule(boolean afterExpiry, int until) {
        List<Long> sent = new ArrayList<>();
        LocalDate last = null;
        for (LocalDate d = EXPIRY.minusDays(90); !d.isAfter(EXPIRY.plusDays(until)); d = d.plusDays(1)) {
            if (ExpiryRule.due(EXPIRY, d, last, afterExpiry)) {
                sent.add(ExpiryRule.daysLeft(EXPIRY, d));
                last = d;
            }
        }
        return sent;
    }

    @Test
    void everyFifteenDaysFromTwoMonthsThenEveryTwoDaysInTheLastFifteen() {
        assertEquals(List.of(60L, 45L, 30L, 15L, 13L, 11L, 9L, 7L, 5L, 3L, 1L), schedule(false, 10));
    }

    @Test
    void keepsRemindingEveryTwoDaysAfterExpiryWhenAsked() {
        List<Long> s = schedule(true, 6);
        assertEquals(List.of(60L, 45L, 30L, 15L, 13L, 11L, 9L, 7L, 5L, 3L, 1L, -1L, -3L, -5L), s);
    }

    @Test
    void nothingBeforeTwoMonthsAndNothingWhenSwitchedOffAfterExpiry() {
        assertFalse(ExpiryRule.due(EXPIRY, EXPIRY.minusDays(61), null, true));
        assertTrue(ExpiryRule.due(EXPIRY, EXPIRY.minusDays(60), null, true));
        assertFalse(ExpiryRule.due(EXPIRY, EXPIRY.plusDays(3), EXPIRY.minusDays(1), false));
    }

    @Test
    void documentAddedLateIsRemindedAtOnce() {
        assertTrue(ExpiryRule.due(EXPIRY, EXPIRY.minusDays(20), null, true));
        assertEquals(EXPIRY.minusDays(15), ExpiryRule.next(EXPIRY, EXPIRY.minusDays(19), EXPIRY.minusDays(20), true));
        assertNull(ExpiryRule.next(EXPIRY, EXPIRY.plusDays(1), EXPIRY.minusDays(1), false));
    }
}
