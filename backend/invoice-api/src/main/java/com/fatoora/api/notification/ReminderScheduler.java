package com.fatoora.api.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Looks for customers whose 15-day payment reminder is due (FATOORA_REMINDER_CHECK_INTERVAL, default 6 h). */
@Component
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ReminderService reminders;

    public ReminderScheduler(ReminderService reminders) {
        this.reminders = reminders;
    }

    @Scheduled(fixedDelayString = "${fatoora.notifications.check-interval:PT6H}", initialDelayString = "PT2M")
    public void run() {
        try {
            int n = reminders.sendDueReminders();
            if (n > 0) log.info("Sent automatic payment reminders to {} customer(s)", n);
        } catch (RuntimeException e) {
            log.error("Automatic payment reminders failed", e);
        }
    }
}
