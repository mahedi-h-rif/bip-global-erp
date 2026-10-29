package com.fatoora.api.notification;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** One reminder attempt on one channel. */
@Entity
@Table(name = "notification_log")
@Getter
@Setter
public class NotificationLog {

    public enum Channel { EMAIL, SMS, WHATSAPP }

    public enum Trigger { AUTO, MANUAL }

    /** LOGGED: the channel is not configured, so the message was only written to the server log. */
    public enum Status { SENT, LOGGED, FAILED, OPENED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;
    private Long customerId;

    @Enumerated(EnumType.STRING)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type")
    private Trigger trigger;

    private String recipient;

    @Enumerated(EnumType.STRING)
    private Status status;

    private BigDecimal dueAmount;
    private String message;
    private String error;
    private String createdBy;
    private Instant createdAt = Instant.now();
}
