package com.fatoora.api.notification;

import com.fatoora.api.config.FatooraProperties;
import com.fatoora.api.due.DueService.DueInvoice;
import com.fatoora.api.payment.BalanceCalculator.PaymentStatus;
import com.fatoora.zatca.model.InvoiceKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NotificationTest {

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "0501234567, 966501234567",
            "050 123 4567, 966501234567",
            "050-123-4567, 966501234567",
            "501234567, 966501234567",
            "+966501234567, 966501234567",
            "00966501234567, 966501234567",
            "966501234567, 966501234567",
            "+966 0501234567, 966501234567",
            "+8801712345678, 8801712345678",
            "0097150123456, 97150123456",
            "0112345678, null",          // Riyadh landline: no SMS or WhatsApp
            "+96611234567, null",
            "12345, null",
            "abc, null",
            "'', null"})
    void phoneNormalization(String raw, String expected) {
        assertEquals(expected, PhoneNumbers.normalize(raw));
    }

    @Test
    void phoneNormalizationOfNull() {
        assertNull(PhoneNumbers.normalize(null));
    }

    static final Instant NOW = Instant.parse("2026-10-27T09:00:00Z");
    static final Duration EVERY_15_DAYS = Duration.ofDays(15);

    @Test
    void reminderIntervalStartsAtOldestDueInvoiceWhenNeverReminded() {
        assertFalse(ReminderPolicy.isDue(null, NOW.minus(Duration.ofDays(14)), NOW, EVERY_15_DAYS));
        assertTrue(ReminderPolicy.isDue(null, NOW.minus(Duration.ofDays(15)), NOW, EVERY_15_DAYS));
        assertFalse(ReminderPolicy.isDue(null, null, NOW, EVERY_15_DAYS), "nothing due, nothing to remind");
    }

    @Test
    void reminderRepeatsEvery15Days() {
        Instant oldDebt = NOW.minus(Duration.ofDays(90));
        assertFalse(ReminderPolicy.isDue(NOW.minus(Duration.ofDays(3)), oldDebt, NOW, EVERY_15_DAYS));
        assertFalse(ReminderPolicy.isDue(NOW.minus(Duration.ofDays(15)).plusSeconds(60), oldDebt, NOW, EVERY_15_DAYS));
        assertTrue(ReminderPolicy.isDue(NOW.minus(Duration.ofDays(15)), oldDebt, NOW, EVERY_15_DAYS));
        assertTrue(ReminderPolicy.isDue(NOW.minus(Duration.ofDays(40)), oldDebt, NOW, EVERY_15_DAYS));
    }

    @Test
    void oldReminderDoesNotTriggerForBrandNewDebt() {
        // Last reminder (for debt paid since) was months ago; the only open invoice is from yesterday.
        assertFalse(ReminderPolicy.isDue(NOW.minus(Duration.ofDays(120)), NOW.minus(Duration.ofDays(1)), NOW, EVERY_15_DAYS));
    }

    static DueInvoice due(long id, String number, String total, String due, boolean shareable) {
        return new DueInvoice(id, number, InvoiceKind.SIMPLIFIED, 1L, "Client Co", Instant.parse("2026-10-01T09:00:00Z"),
                LocalDate.of(2026, 10, 15), 26, 12L, new BigDecimal(total), new BigDecimal(total), BigDecimal.ZERO,
                new BigDecimal(due), PaymentStatus.UNPAID, shareable);
    }

    @Test
    void messagesAreBilingualAndListEveryDueInvoice() {
        var dues = List.of(due(1, "INV-2026-0001", "1150.00", "1150.00", true), due(2, "INV-2026-0002", "230.00", "30.00", false));
        var mail = ReminderMessages.email("Astha Gulf Trading Company", "+966 50 000 0000", "Client Co", dues,
                d -> d.shareable() ? "https://portal/x/" + d.invoiceId() : null);
        assertTrue(mail.subject().contains("1,180.00 SAR"), mail.subject());
        assertTrue(mail.body().contains("INV-2026-0001") && mail.body().contains("INV-2026-0002"));
        assertTrue(mail.body().contains("https://portal/x/1"));
        assertFalse(mail.body().contains("https://portal/x/2"), "uncleared invoices are not linked");
        assertTrue(mail.body().contains("عزيزنا") && mail.body().contains("Dear Client Co"));

        String wa = ReminderMessages.whatsapp("Astha Gulf Trading Company", "Client Co", dues, d -> null);
        assertTrue(wa.contains("1,180.00 SAR") && wa.contains("السلام عليكم") && wa.contains("INV-2026-0002"));

        String sms = ReminderMessages.sms("Astha", "Client Co", dues, null);
        assertTrue(sms.contains("1,180.00 SAR") && sms.contains("INV-2026-0001, INV-2026-0002"));
    }

    @Test
    void smsGatewayFormUsesConfiguredParameterNames() {
        var sms = new FatooraProperties.Sms("https://el.cloud.unifonic.com/rest/SMS/messages", null,
                "Recipient", "Body", "SenderID", "ASTHA", "AppSid=abc123", null);
        var props = new FatooraProperties(null, null, null, null, null,
                new FatooraProperties.Notifications(true, Duration.ofDays(15), Duration.ofHours(6), null, sms, null), null);
        String form = new HttpSmsSender(props).form("966501234567", "مرحبا & hi");
        assertEquals("AppSid=abc123&Recipient=966501234567&Body=%D9%85%D8%B1%D8%AD%D8%A8%D8%A7+%26+hi&SenderID=ASTHA", form);
    }

    @Test
    void smsGatewayJsonMatchesTaqnyat() {
        var sms = new FatooraProperties.Sms("https://api.taqnyat.sa/v1/messages", "Bearer t",
                "recipients", "body", "sender", "Taqnyat.sa", null, "json");
        var props = new FatooraProperties(null, null, null, null, null,
                new FatooraProperties.Notifications(true, Duration.ofDays(15), Duration.ofHours(6), null, sms, null), null);
        String json = new HttpSmsSender(props).json("966501234567", "مرحبا \"x\"\nhi");
        assertEquals("{\"recipients\":[\"966501234567\"],\"body\":\"مرحبا \\\"x\\\"\\nhi\",\"sender\":\"Taqnyat.sa\"}", json);
    }

    @Test
    void unconfiguredChannelsOnlyLog() {
        var props = new FatooraProperties(null, null, null, null, null,
                new FatooraProperties.Notifications(true, Duration.ofDays(15), Duration.ofHours(6),
                        new FatooraProperties.Mail("", 587, null, null, true, null),
                        new FatooraProperties.Sms("", null, null, null, null, null, null, null), null), null);
        assertEquals(NotificationLog.Status.LOGGED, new HttpSmsSender(props).send("966501234567", "x").status());
        assertEquals(NotificationLog.Status.LOGGED, new EmailSender(props).send("a@b.c", "s", "b").status());
    }

    @Test
    void invoiceLinkTokensAreStableAndCheckTheId() {
        var props = new FatooraProperties(null, new FatooraProperties.Jwt("secret-secret-secret-secret-secret", null, false),
                null, null, "https://invoice.example.com/", null, null);
        var links = new InvoiceLinks(props);
        String url = links.pdfUrl(42L);
        assertTrue(url.startsWith("https://invoice.example.com/api/v1/public/invoices/42/"), url);
        String token = url.substring(url.lastIndexOf('/') + 1);
        assertTrue(links.valid(42L, token));
        assertFalse(links.valid(43L, token), "a token only opens its own invoice");
        assertFalse(links.valid(42L, token.substring(1)));
    }
}
