package com.fatoora.api.notification;

import com.fatoora.api.audit.AuditService;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.customer.Customer;
import com.fatoora.api.customer.CustomerRepository;
import com.fatoora.api.invoice.Invoice;
import com.fatoora.api.invoice.InvoiceService;
import com.fatoora.api.notification.NotificationLog.Channel;
import com.fatoora.api.notification.NotificationLog.Trigger;
import com.fatoora.api.organization.Organization;
import com.fatoora.api.organization.OrganizationRepository;
import com.fatoora.api.pdf.InvoicePdfService;
import com.fatoora.zatca.model.DocumentType;
import com.fatoora.zatca.model.InvoiceKind;
import org.springframework.stereotype.Service;

import java.util.Locale;

/** Sends an issued invoice's PDF to the customer on WhatsApp, from the linked number. */
@Service
public class InvoiceWhatsApp {

    private final InvoiceService invoices;
    private final InvoicePdfService pdf;
    private final CustomerRepository customers;
    private final OrganizationRepository orgs;
    private final NotificationLogRepository logs;
    private final WhatsAppGateway whatsapp;
    private final AuditService audit;

    public InvoiceWhatsApp(InvoiceService invoices, InvoicePdfService pdf, CustomerRepository customers, OrganizationRepository orgs,
                           NotificationLogRepository logs, WhatsAppGateway whatsapp, AuditService audit) {
        this.invoices = invoices;
        this.pdf = pdf;
        this.customers = customers;
        this.orgs = orgs;
        this.logs = logs;
        this.whatsapp = whatsapp;
        this.audit = audit;
    }

    /** @param phone number typed by staff (e.g. for a walk-in customer); null uses the customer's mobile */
    public SendResult send(Long orgId, Long invoiceId, String phone, String actor) {
        Invoice inv = invoices.load(orgId, invoiceId);
        if (!InvoiceService.downloadable(inv))
            throw ApiException.conflict("not_cleared", inv.getKind() == InvoiceKind.STANDARD
                    ? "A standard invoice can only be shared after ZATCA clears it" : "Issue the invoice first");
        Customer c = inv.getCustomerId() == null ? null : customers.findByIdAndOrganizationId(inv.getCustomerId(), orgId).orElse(null);
        String to = PhoneNumbers.normalize(phone != null && !phone.isBlank() ? phone : c == null ? null : c.getPhone());
        if (to == null) throw ApiException.badRequest("no_mobile", "Enter the customer's WhatsApp number");
        if (!whatsapp.connected())
            throw ApiException.conflict("whatsapp_not_connected", "WhatsApp is not connected. Link the number under Settings → WhatsApp.");

        Organization org = orgs.findById(orgId).orElseThrow();
        String caption = caption(org, inv, c);
        SendResult r = whatsapp.sendDocument(to, caption, pdf.render(inv), inv.getNumber() + ".pdf", "application/pdf");

        NotificationLog l = new NotificationLog();
        l.setOrganizationId(orgId);
        l.setCustomerId(inv.getCustomerId());
        l.setChannel(Channel.WHATSAPP);
        l.setTrigger(Trigger.MANUAL);
        l.setRecipient(to);
        l.setStatus(r.status());
        l.setError(r.error());
        l.setMessage(caption);
        l.setCreatedBy(actor);
        logs.save(l);
        if (r.delivered()) audit.record(orgId, "INVOICE_WHATSAPP_SENT", "invoice", inv.getId(), inv.getNumber() + " to " + to);
        return r;
    }

    /** Bilingual message under the PDF: who it is from, which document, the total. */
    static String caption(Organization org, Invoice inv, Customer c) {
        String seller = org.getLegalNameEn() == null || org.getLegalNameEn().isBlank() ? org.getLegalNameAr() : org.getLegalNameEn();
        String total = String.format(Locale.US, "%,.2f SAR", inv.getTotalInclVat());
        String[] what = inv.getType() == DocumentType.CREDIT_NOTE ? new String[]{"Credit note", "إشعار دائن"}
                : inv.getType() == DocumentType.DEBIT_NOTE ? new String[]{"Debit note", "إشعار مدين"}
                : new String[]{"Tax invoice", "فاتورة ضريبية"};
        String name = c == null ? null : c.getNameEn() != null && !c.getNameEn().isBlank() ? c.getNameEn() : c.getNameAr();
        return (name == null ? "" : "السلام عليكم " + name + "\n")
                + what[1] + " رقم " + inv.getNumber() + " بمبلغ " + total + " من *" + seller + "*.\n\n"
                + (name == null ? "" : "Dear " + name + ",\n")
                + what[0] + " " + inv.getNumber() + " for " + total + " from *" + seller + "*. Thank you.";
    }
}
