package com.fatoora.api.notification;

import com.fatoora.api.audit.AuditService;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.config.FatooraProperties;
import com.fatoora.api.customer.Customer;
import com.fatoora.api.customer.CustomerRepository;
import com.fatoora.api.due.DueService;
import com.fatoora.api.due.DueService.CustomerDue;
import com.fatoora.api.due.DueService.DueInvoice;
import com.fatoora.api.notification.NotificationLog.Channel;
import com.fatoora.api.notification.NotificationLog.Trigger;
import com.fatoora.api.organization.Organization;
import com.fatoora.api.organization.OrganizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Payment reminders to customers with a due balance: automatic every 15 days, or on demand from the Due pages. */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    public record LogEntry(Long id, Long customerId, String customerName, Channel channel, Trigger trigger,
                           String recipient, NotificationLog.Status status, BigDecimal dueAmount, String message,
                           String error, String createdBy, Instant createdAt) {}

    /**
     * The WhatsApp reminder: {@code sent} when it went out from the linked number; otherwise {@code url} opens
     * the chat with the message ready (not linked, or sending failed with {@code error}).
     */
    public record WhatsAppLink(String phone, String url, String message, boolean sent, String error) {}

    private final FatooraProperties.Notifications config;
    private final DueService dues;
    private final CustomerRepository customers;
    private final OrganizationRepository orgs;
    private final NotificationLogRepository logs;
    private final EmailSender email;
    private final SmsSender sms;
    private final WhatsAppGateway whatsapp;
    private final InvoiceLinks links;
    private final AuditService audit;

    public ReminderService(FatooraProperties props, DueService dues, CustomerRepository customers,
                           OrganizationRepository orgs, NotificationLogRepository logs, EmailSender email, SmsSender sms,
                           WhatsAppGateway whatsapp, InvoiceLinks links, AuditService audit) {
        this.config = props.notifications();
        this.dues = dues;
        this.customers = customers;
        this.orgs = orgs;
        this.logs = logs;
        this.email = email;
        this.sms = sms;
        this.whatsapp = whatsapp;
        this.links = links;
        this.audit = audit;
    }

    /** Sends email, SMS and (when a number is linked) WhatsApp now to one customer about all their due invoices. */
    public List<LogEntry> remindNow(Long orgId, Long customerId, String actor) {
        Customer c = load(orgId, customerId);
        List<DueInvoice> owed = dues.forCustomer(orgId, customerId);
        if (owed.isEmpty()) throw ApiException.conflict("nothing_due", "This customer has nothing due");
        if (isBlank(c.getEmail()) && PhoneNumbers.normalize(c.getPhone()) == null)
            throw ApiException.badRequest("no_contact", "Add an email address or mobile number to this customer first");
        List<NotificationLog> sent = send(orgs.findById(orgId).orElseThrow(), c, owed, Trigger.MANUAL, actor);
        audit.record(orgId, "REMINDER_SENT", "customer", customerId, sent.size() + " message(s)");
        return sent.stream().map(l -> entry(l, DueService.displayName(c))).toList();
    }

    /**
     * The WhatsApp reminder: sent straight away from the linked number, or, when none is linked, a wa.me link
     * with the message ready that the staff member sends from their own WhatsApp.
     */
    public WhatsAppLink whatsapp(Long orgId, Long customerId, String actor) {
        Customer c = load(orgId, customerId);
        String phone = PhoneNumbers.normalize(c.getPhone());
        if (phone == null) throw ApiException.badRequest("no_mobile", "Add a mobile number to this customer first");
        List<DueInvoice> owed = dues.forCustomer(orgId, customerId);
        if (owed.isEmpty()) throw ApiException.conflict("nothing_due", "This customer has nothing due");
        Organization org = orgs.findById(orgId).orElseThrow();
        String text = ReminderMessages.whatsapp(sellerName(org), DueService.displayName(c), owed, linkFor());
        String url = "https://wa.me/" + phone + "?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
        if (!whatsapp.connected()) {
            logs.save(log(orgId, c.getId(), Channel.WHATSAPP, Trigger.MANUAL, phone, SendResult.opened(),
                    ReminderMessages.totalDue(owed), text, actor));
            return new WhatsAppLink(phone, url, text, false, null);
        }
        SendResult r = whatsapp.sendText(phone, text);
        logs.save(log(orgId, c.getId(), Channel.WHATSAPP, Trigger.MANUAL, phone, r, ReminderMessages.totalDue(owed), text, actor));
        if (r.delivered()) {
            c.setLastReminderAt(Instant.now());
            customers.save(c);
            audit.record(orgId, "REMINDER_SENT", "customer", customerId, "WhatsApp");
        }
        return new WhatsAppLink(phone, r.delivered() ? null : url, text, r.delivered(), r.error());
    }

    /** Called by the scheduler: every customer whose 15-day reminder interval has passed. */
    public int sendDueReminders() {
        if (!config.enabled()) return 0;
        Duration interval = config.interval() == null ? Duration.ofDays(15) : config.interval();
        Instant now = Instant.now();
        int count = 0;
        for (Organization org : orgs.findAll()) {
            for (CustomerDue cd : dues.byCustomer(org.getId(), null)) {
                if (cd.customerId() == null || !cd.remindersEnabled()) continue;
                if (isBlank(cd.email()) && PhoneNumbers.normalize(cd.phone()) == null) continue;
                if (!ReminderPolicy.isDue(cd.lastReminderAt(), cd.oldestIssuedAt(), now, interval)) continue;
                try {
                    Customer c = load(org.getId(), cd.customerId());
                    send(org, c, dues.forCustomer(org.getId(), c.getId()), Trigger.AUTO, "system");
                    count++;
                } catch (RuntimeException e) {
                    log.error("Automatic reminder to customer {} failed", cd.customerId(), e);
                }
            }
        }
        return count;
    }

    public Page<LogEntry> history(Long orgId, Long customerId, int page, int size) {
        PageRequest p = PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<NotificationLog> result = customerId == null ? logs.findByOrganizationId(orgId, p)
                : logs.findByOrganizationIdAndCustomerId(orgId, customerId, p);
        var names = new java.util.HashMap<Long, String>();
        customers.findByOrganizationId(orgId, Sort.unsorted()).forEach(c -> names.put(c.getId(), DueService.displayName(c)));
        return result.map(l -> entry(l, l.getCustomerId() == null ? null : names.get(l.getCustomerId())));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private List<NotificationLog> send(Organization org, Customer c, List<DueInvoice> owed, Trigger trigger, String actor) {
        String seller = sellerName(org);
        String name = DueService.displayName(c);
        BigDecimal total = ReminderMessages.totalDue(owed);
        List<NotificationLog> result = new ArrayList<>();

        if (!isBlank(c.getEmail())) {
            var mail = ReminderMessages.email(seller, contact(org), name, owed, linkFor());
            result.add(logs.save(log(org.getId(), c.getId(), Channel.EMAIL, trigger, c.getEmail(),
                    email.send(c.getEmail(), mail.subject(), mail.body()), total, mail.body(), actor)));
        }
        String phone = PhoneNumbers.normalize(c.getPhone());
        if (phone != null) {
            DueInvoice first = owed.stream().filter(DueInvoice::shareable).findFirst().orElse(null);
            String text = ReminderMessages.sms(seller, name, owed, owed.size() == 1 && first != null ? links.pdfUrl(first.invoiceId()) : null);
            result.add(logs.save(log(org.getId(), c.getId(), Channel.SMS, trigger, phone, sms.send(phone, text), total, text, actor)));
            if (org.isWhatsappReminders() && whatsapp.connected()) {
                String wa = ReminderMessages.whatsapp(seller, name, owed, linkFor());
                result.add(logs.save(log(org.getId(), c.getId(), Channel.WHATSAPP, trigger, phone, whatsapp.sendText(phone, wa), total, wa, actor)));
            }
        }
        // The 15-day clock restarts once a message went out (or was logged because the channel isn't set up yet).
        if (result.stream().anyMatch(l -> l.getStatus() != NotificationLog.Status.FAILED)) {
            c.setLastReminderAt(Instant.now());
            customers.save(c);
        }
        return result;
    }

    private Function<DueInvoice, String> linkFor() {
        return d -> d.shareable() ? links.pdfUrl(d.invoiceId()) : null;
    }

    private static NotificationLog log(Long orgId, Long customerId, Channel channel, Trigger trigger, String recipient,
                                       SendResult r, BigDecimal due, String message, String actor) {
        NotificationLog l = new NotificationLog();
        l.setOrganizationId(orgId);
        l.setCustomerId(customerId);
        l.setChannel(channel);
        l.setTrigger(trigger);
        l.setRecipient(recipient);
        l.setStatus(r.status());
        l.setError(r.error());
        l.setDueAmount(due);
        l.setMessage(message);
        l.setCreatedBy(actor);
        return l;
    }

    private static LogEntry entry(NotificationLog l, String customerName) {
        return new LogEntry(l.getId(), l.getCustomerId(), customerName, l.getChannel(), l.getTrigger(), l.getRecipient(),
                l.getStatus(), l.getDueAmount(), l.getMessage(), l.getError(), l.getCreatedBy(), l.getCreatedAt());
    }

    private Customer load(Long orgId, Long customerId) {
        return customers.findByIdAndOrganizationId(customerId, orgId).orElseThrow(() -> ApiException.notFound("Customer"));
    }

    private static String sellerName(Organization org) {
        return isBlank(org.getLegalNameEn()) ? org.getLegalNameAr() : org.getLegalNameEn();
    }

    private static String contact(Organization org) {
        List<String> parts = new ArrayList<>();
        if (!isBlank(org.getPhone())) parts.add(org.getPhone());
        if (!isBlank(org.getEmail())) parts.add(org.getEmail());
        return String.join(" · ", parts);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
