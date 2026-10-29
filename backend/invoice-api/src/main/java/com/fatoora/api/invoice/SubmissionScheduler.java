package com.fatoora.api.invoice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Sends queued documents: simplified invoices issued "for later" (before the 24-hour limit) and
 * anything waiting for a retry after a transient failure.
 */
@Component
public class SubmissionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SubmissionScheduler.class);

    private final InvoiceRepository invoices;
    private final InvoiceSubmissionService submitter;

    public SubmissionScheduler(InvoiceRepository invoices, InvoiceSubmissionService submitter) {
        this.invoices = invoices;
        this.submitter = submitter;
    }

    @Scheduled(fixedDelayString = "${fatoora.scheduler-interval:PT5M}", initialDelayString = "PT30S")
    public void run() {
        for (Long id : invoices.findDueForSubmission(Instant.now())) {
            try {
                submitter.submit(id);
            } catch (RuntimeException e) {
                log.error("Scheduled submission of invoice {} failed", id, e);
            }
        }
    }
}
