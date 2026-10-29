package com.fatoora.api.notification;

/** Outcome of handing one message to a channel. */
public record SendResult(NotificationLog.Status status, String error) {

    public static SendResult sent() {
        return new SendResult(NotificationLog.Status.SENT, null);
    }

    /** The channel isn't configured: the message was written to the server log instead. */
    public static SendResult logged() {
        return new SendResult(NotificationLog.Status.LOGGED, null);
    }

    /** WhatsApp: the prefilled chat was opened for a staff member, who sends it themselves. */
    public static SendResult opened() {
        return new SendResult(NotificationLog.Status.OPENED, null);
    }

    public static SendResult failed(String error) {
        return new SendResult(NotificationLog.Status.FAILED, error == null ? "Unknown error"
                : error.length() > 2000 ? error.substring(0, 2000) : error);
    }

    public boolean delivered() {
        return status != NotificationLog.Status.FAILED;
    }
}
