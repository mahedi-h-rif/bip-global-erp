package com.fatoora.api.due;

import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.due.DueService.CustomerDue;
import com.fatoora.api.due.DueService.DueInvoice;
import com.fatoora.api.notification.ReminderService;
import com.fatoora.api.notification.ReminderService.LogEntry;
import com.fatoora.api.notification.ReminderService.WhatsAppLink;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class DueController {

    private static final String WRITE = "hasAnyRole('ADMIN','ACCOUNTANT')";

    private final DueService dues;
    private final ReminderService reminders;

    public DueController(DueService dues, ReminderService reminders) {
        this.dues = dues;
        this.reminders = reminders;
    }

    /** Every issued invoice with money still due, oldest first. */
    @GetMapping("/dues")
    List<DueInvoice> dues(@RequestParam(required = false) String q,
                          @RequestParam(required = false) Long customerId,
                          @RequestParam(defaultValue = "false") boolean overdueOnly) {
        return dues.dues(CurrentUser.orgId(), q, customerId, overdueOnly);
    }

    @GetMapping("/dues/customers")
    List<CustomerDue> byCustomer(@RequestParam(required = false) String q) {
        return dues.byCustomer(CurrentUser.orgId(), q);
    }

    @PostMapping("/dues/customers/{customerId}/remind")
    @PreAuthorize(WRITE)
    List<LogEntry> remind(@PathVariable Long customerId) {
        return reminders.remindNow(CurrentUser.orgId(), customerId, CurrentUser.get().email());
    }

    @PostMapping("/dues/customers/{customerId}/whatsapp")
    @PreAuthorize(WRITE)
    WhatsAppLink whatsapp(@PathVariable Long customerId) {
        return reminders.whatsapp(CurrentUser.orgId(), customerId, CurrentUser.get().email());
    }

    @GetMapping("/notifications")
    Page<LogEntry> notifications(@RequestParam(required = false) Long customerId,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "50") int size) {
        return reminders.history(CurrentUser.orgId(), customerId, page, size);
    }
}
