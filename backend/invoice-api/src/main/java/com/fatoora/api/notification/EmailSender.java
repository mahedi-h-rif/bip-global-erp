package com.fatoora.api.notification;

import com.fatoora.api.config.FatooraProperties;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.Properties;

/** Reminder emails over SMTP ({@code fatoora.notifications.mail}). Without a host it only logs the message. */
@Component
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    private final FatooraProperties.Mail config;
    private final JavaMailSenderImpl mailer;

    public EmailSender(FatooraProperties props) {
        this.config = props.notifications().mail();
        this.mailer = configured() ? build(config) : null;
    }

    public boolean configured() {
        return config != null && config.host() != null && !config.host().isBlank();
    }

    public SendResult send(String to, String subject, String body) {
        if (mailer == null) {
            log.info("Email not configured; would send to {}: {}\n{}", to, subject, body);
            return SendResult.logged();
        }
        try {
            MimeMessage msg = mailer.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, false, "UTF-8");
            h.setFrom(config.from() == null || config.from().isBlank() ? config.username() : config.from());
            h.setTo(to);
            h.setSubject(subject);
            h.setText(body, false);
            mailer.send(msg);
            return SendResult.sent();
        } catch (Exception e) {
            log.warn("Email to {} failed", to, e);
            return SendResult.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static JavaMailSenderImpl build(FatooraProperties.Mail c) {
        JavaMailSenderImpl m = new JavaMailSenderImpl();
        m.setHost(c.host());
        m.setPort(c.port() == 0 ? 587 : c.port());
        m.setDefaultEncoding("UTF-8");
        boolean auth = c.username() != null && !c.username().isBlank();
        if (auth) {
            m.setUsername(c.username());
            m.setPassword(c.password());
        }
        Properties p = m.getJavaMailProperties();
        p.put("mail.smtp.auth", String.valueOf(auth));
        p.put("mail.smtp.starttls.enable", String.valueOf(c.starttls()));
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "15000");
        p.put("mail.smtp.writetimeout", "15000");
        return m;
    }
}
