package com.fatoora.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "fatoora")
public record FatooraProperties(
        String masterKey,
        Jwt jwt,
        Duration autoReportAfter,
        Duration schedulerInterval,
        String portalUrl,
        Notifications notifications,
        Bootstrap bootstrap) {

    public record Jwt(String secret, Duration ttl, boolean cookieSecure) {}

    public record Bootstrap(String adminEmail, String adminPassword, String adminName,
                            String operatorEmail, String operatorPassword, String operatorName,
                            String userEmail, String userPassword, String userName, String branchUsers,
                            Org organization) {}

    /**
     * Payment reminders. {@code interval} is the gap between two reminders to the same customer;
     * {@code checkInterval} is how often the scheduler looks for customers that are due one.
     */
    public record Notifications(boolean enabled, Duration interval, Duration checkInterval, Mail mail, Sms sms, WhatsApp whatsapp) {}

    /**
     * The WhatsApp service (the {@code whatsapp} container) that sends from a number linked by QR.
     * Leave {@code url} empty to keep WhatsApp as "open a prefilled chat" links only.
     */
    public record WhatsApp(String url, String token) {

        public boolean configured() {
            return url != null && !url.isBlank() && token != null && !token.isBlank();
        }
    }

    /** SMTP server for reminder emails. Leave {@code host} empty to only log messages. */
    public record Mail(String host, int port, String username, String password, boolean starttls, String from) {}

    /**
     * Generic HTTP SMS gateway: a form POST to {@code url} with the recipient in {@code toParam}, the text in
     * {@code textParam} and optional sender / extra parameters (e.g. {@code AppSid=...} for Unifonic).
     * Leave {@code url} empty to only log messages. {@code format} {@code json} sends a JSON body instead, with the
     * recipient as a one-element array, as Taqnyat expects ({@code {"recipients":[...],"body":...,"sender":...}}).
     */
    public record Sms(String url, String authHeader, String toParam, String textParam, String senderParam,
                      String sender, String extraParams, String format) {

        public boolean json() {
            return "json".equalsIgnoreCase(format);
        }
    }

    public record Org(
            String legalNameAr,
            String legalNameEn,
            String vatNumber,
            String otherId,
            String street,
            String buildingNumber,
            String additionalNumber,
            String district,
            String city,
            String postalCode,
            String email,
            String phone,
            String zatcaEnvironment) {}
}
