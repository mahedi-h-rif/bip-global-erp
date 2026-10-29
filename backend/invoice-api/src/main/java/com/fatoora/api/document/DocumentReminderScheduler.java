package com.fatoora.api.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Checks for document expiry reminders that are due (same interval as payment reminders, default 6 h). */
@Component
public class DocumentReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(DocumentReminderScheduler.class);

    private final DocumentService documents;

    public DocumentReminderScheduler(DocumentService documents) {
        this.documents = documents;
    }

    @Scheduled(fixedDelayString = "${fatoora.notifications.check-interval:PT6H}", initialDelayString = "PT3M")
    public void run() {
        try {
            int n = documents.sendDueReminders();
            if (n > 0) log.info("Sent {} document expiry reminder(s)", n);
        } catch (RuntimeException e) {
            log.error("Document expiry reminders failed", e);
        }
    }
}
