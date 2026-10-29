package com.fatoora.api.notification;

import com.fatoora.api.config.FatooraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generic SMS gateway: an HTTP form POST, which Unifonic, Twilio, Msegat, Taqnyat and most others accept.
 * Parameter names, sender, credentials and extra parameters come from {@code fatoora.notifications.sms}.
 * Without a URL it only logs the message.
 */
@Component
public class HttpSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(HttpSmsSender.class);

    private final FatooraProperties.Sms config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public HttpSmsSender(FatooraProperties props) {
        this.config = props.notifications().sms();
    }

    public boolean configured() {
        return config != null && config.url() != null && !config.url().isBlank();
    }

    @Override
    public SendResult send(String to, String text) {
        if (!configured()) {
            log.info("SMS not configured; would send to {}:\n{}", to, text);
            return SendResult.logged();
        }
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(config.url()))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", config.json() ? "application/json; charset=UTF-8"
                            : "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(config.json() ? json(to, text) : form(to, text)));
            if (config.authHeader() != null && !config.authHeader().isBlank()) req.header("Authorization", config.authHeader());
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 == 2) return SendResult.sent();
            return SendResult.failed("SMS gateway returned HTTP " + res.statusCode() + ": " + res.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("SMS to {} failed", to, e);
            return SendResult.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    String form(String to, String text) {
        Map<String, String> params = new LinkedHashMap<>();
        if (config.extraParams() != null && !config.extraParams().isBlank()) {
            for (String pair : config.extraParams().split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) params.put(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip());
            }
        }
        params.put(name(config.toParam(), "to"), to);
        params.put(name(config.textParam(), "text"), text);
        if (config.sender() != null && !config.sender().isBlank()) params.put(name(config.senderParam(), "sender"), config.sender());
        return params.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    /** JSON body: extra parameters and sender as strings, the recipient as a one-element array. */
    String json(String to, String text) {
        Map<String, String> params = new LinkedHashMap<>();
        if (config.extraParams() != null && !config.extraParams().isBlank()) {
            for (String pair : config.extraParams().split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) params.put(pair.substring(0, eq).strip(), quote(pair.substring(eq + 1).strip()));
            }
        }
        params.put(name(config.toParam(), "to"), "[" + quote(to) + "]");
        params.put(name(config.textParam(), "text"), quote(text));
        if (config.sender() != null && !config.sender().isBlank()) params.put(name(config.senderParam(), "sender"), quote(config.sender()));
        return params.entrySet().stream()
                .map(e -> quote(e.getKey()) + ":" + e.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    private static String name(String configured, String fallback) {
        return configured == null || configured.isBlank() ? fallback : configured;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
