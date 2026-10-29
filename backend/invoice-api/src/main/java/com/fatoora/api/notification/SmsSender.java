package com.fatoora.api.notification;

/**
 * Sends one SMS. The default is {@link HttpSmsSender}; to use a provider it can't be configured for,
 * add another implementation as a {@code @Primary} bean.
 */
public interface SmsSender {

    /** @param to mobile number in international format without "+", e.g. 9665XXXXXXXX */
    SendResult send(String to, String text);
}
