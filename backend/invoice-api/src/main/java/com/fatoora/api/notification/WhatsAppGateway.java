package com.fatoora.api.notification;

import com.fatoora.api.config.FatooraProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends WhatsApp messages from the number linked by QR, through the {@code whatsapp} service, and controls the
 * link (status, QR, connect, unlink). Without {@code fatoora.notifications.whatsapp.url} WhatsApp stays as
 * "open a prefilled chat" links that staff send from their own phone.
 */
@Component
public class WhatsAppGateway {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppGateway.class);

    /**
     * @param status idle, connecting, qr (waiting for a scan), connected, logged_out, error, or unavailable
     *               (the service is not set up or can't be reached)
     * @param qr     PNG data URL to scan while {@code status} is qr
     */
    public record Status(String status, String qr, String phone, String name, String lastError) {

        public boolean connected() {
            return "connected".equals(status);
        }
    }

    private final FatooraProperties.WhatsApp config;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public WhatsAppGateway(FatooraProperties props, JsonMapper json) {
        this.config = props.notifications() == null ? null : props.notifications().whatsapp();
        this.json = json;
    }

    public boolean configured() {
        return config != null && config.configured();
    }

    public Status status() {
        return control("GET", "/status");
    }

    /** True when messages can go out right now; false sends staff back to wa.me links. */
    public boolean connected() {
        return configured() && status().connected();
    }

    public Status connect() {
        return control("POST", "/connect");
    }

    public Status disconnect() {
        return control("POST", "/disconnect");
    }

    /** Unlinks the number; the next connect shows a new QR. */
    public Status logout() {
        return control("POST", "/logout");
    }

    /** @param to number in international format without "+", e.g. 9665XXXXXXXX */
    public SendResult sendText(String to, String text) {
        return send(Map.of("to", to, "text", text));
    }

    /** A file (e.g. the invoice PDF) with {@code caption} as its message. */
    public SendResult sendDocument(String to, String caption, byte[] file, String fileName, String mimetype) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", to);
        body.put("text", caption);
        body.put("document", Map.of("base64", Base64.getEncoder().encodeToString(file), "fileName", fileName, "mimetype", mimetype));
        return send(body);
    }

    private SendResult send(Map<String, ?> body) {
        if (!configured()) return SendResult.failed("WhatsApp is not set up");
        try {
            // Sends are queued a few seconds apart by the service, so allow for a short wait.
            HttpResponse<String> res = http.send(request("/send", Duration.ofSeconds(90))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 == 2) return SendResult.sent();
            return SendResult.failed(message(res.body(), "WhatsApp service returned HTTP " + res.statusCode()));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("WhatsApp send failed", e);
            return SendResult.failed("WhatsApp service can't be reached: " + e.getMessage());
        }
    }

    private Status control(String method, String path) {
        if (!configured()) return new Status("unavailable", null, null, null, "WhatsApp is not set up on this server");
        try {
            HttpRequest.Builder req = request(path, Duration.ofSeconds(20));
            req = "POST".equals(method) ? req.POST(HttpRequest.BodyPublishers.noBody()) : req.GET();
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2)
                return new Status("unavailable", null, null, null, message(res.body(), "WhatsApp service returned HTTP " + res.statusCode()));
            JsonNode n = json.readTree(res.body());
            return new Status(text(n, "status"), text(n, "qr"), text(n, "phone"), text(n, "name"), text(n, "lastError"));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Status("unavailable", null, null, null, "WhatsApp service can't be reached");
        }
    }

    private HttpRequest.Builder request(String path, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(config.url().replaceAll("/+$", "") + path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + config.token());
    }

    /** The service's error message, e.g. "This number is not on WhatsApp". */
    private String message(String body, String fallback) {
        try {
            String m = text(json.readTree(body), "message");
            return m == null ? fallback : m;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }
}
