package com.fatoora.api.notification;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.due.DueService.DueInvoice;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Bilingual (Arabic / English) payment reminder texts, generated from the customer's due invoices. */
public final class ReminderMessages {

    public record Email(String subject, String body) {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);

    private ReminderMessages() {}

    /**
     * @param seller   the seller's name as it appears to the customer
     * @param contact  seller phone / email to reply to, may be null
     * @param links    public PDF link for a shareable invoice, or null to leave it out
     */
    public static Email email(String seller, String contact, String customer, List<DueInvoice> dues,
                              Function<DueInvoice, String> links) {
        String total = money(totalDue(dues));
        StringBuilder b = new StringBuilder();
        b.append("عزيزنا ").append(customer).append("،\n\n")
                .append("نود تذكيركم بوجود مبالغ مستحقة لدى ").append(seller).append(" بإجمالي ").append(total).append(".\n\n")
                .append("Dear ").append(customer).append(",\n\n")
                .append("This is a friendly reminder from ").append(seller).append(" that ").append(total)
                .append(" is outstanding on the following invoice(s):\n\n");
        for (DueInvoice d : dues) {
            b.append("• ").append(d.number()).append(" — ").append(date(d)).append(" — total / الإجمالي ")
                    .append(money(d.adjustedTotal())).append(" — due / المستحق ").append(money(d.due()));
            if (d.dueDate() != null) b.append(" — due date / تاريخ الاستحقاق ").append(DATE.format(d.dueDate()));
            String link = links.apply(d);
            if (link != null) b.append("\n  ").append(link);
            b.append('\n');
        }
        b.append("\nTotal due / إجمالي المستحق: ").append(total).append("\n\n")
                .append("If you have already paid, please ignore this message. / إذا تم السداد، يرجى تجاهل هذه الرسالة.\n");
        if (contact != null && !contact.isBlank()) b.append("Contact / للتواصل: ").append(contact).append('\n');
        b.append("\n").append(seller);
        return new Email("Payment reminder / تذكير بالسداد — " + seller + " — " + total, b.toString());
    }

    /** Short text for SMS (Arabic SMS segments hold 70 characters, so invoices are summarised, not listed). */
    public static String sms(String seller, String customer, List<DueInvoice> dues, String link) {
        String total = money(totalDue(dues));
        String numbers = dues.size() <= 3 ? String.join(", ", dues.stream().map(DueInvoice::number).toList())
                : dues.size() + " invoices";
        return seller + ": عزيزنا " + customer + "، المبلغ المستحق " + total + ".\n"
                + "Dear " + customer + ", amount due " + total + " (" + numbers + ")."
                + (link == null ? "" : "\n" + link);
    }

    /** WhatsApp text, opened by staff from the portal with wa.me and sent from their own WhatsApp. */
    public static String whatsapp(String seller, String customer, List<DueInvoice> dues, Function<DueInvoice, String> links) {
        String total = money(totalDue(dues));
        StringBuilder b = new StringBuilder();
        b.append("السلام عليكم ").append(customer).append("،\n")
                .append("تذكير ودي من *").append(seller).append("* بوجود مبلغ مستحق قدره *").append(total).append("*.\n\n")
                .append("Dear ").append(customer).append(",\n")
                .append("A friendly reminder from *").append(seller).append("*: *").append(total).append("* is due.\n\n");
        for (DueInvoice d : dues) {
            b.append("🧾 ").append(d.number()).append(" (").append(date(d)).append(")\n")
                    .append("   Total / الإجمالي: ").append(money(d.adjustedTotal())).append('\n')
                    .append("   Due / المستحق: ").append(money(d.due())).append('\n');
            String link = links.apply(d);
            if (link != null) b.append("   ").append(link).append('\n');
        }
        b.append("\nThank you / شكراً لكم");
        return b.toString();
    }

    public static BigDecimal totalDue(List<DueInvoice> dues) {
        return dues.stream().map(DueInvoice::due).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static String money(BigDecimal v) {
        return String.format(Locale.US, "%,.2f SAR", v);
    }

    private static String date(DueInvoice d) {
        return d.issuedAt() == null ? "" : DATE.format(d.issuedAt().atZone(InvoiceApiApplication.KSA));
    }
}
