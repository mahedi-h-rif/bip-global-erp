package com.fatoora.api.notification;

import com.fatoora.api.config.FatooraProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The gateway against a fake WhatsApp service: auth, payloads and how its answers map to send results. */
class WhatsAppGatewayTest {

    HttpServer server;
    final AtomicReference<String> lastBody = new AtomicReference<>();
    final AtomicReference<String> lastAuth = new AtomicReference<>();

    WhatsAppGateway gateway(String status, int sendCode, String sendBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/status", ex -> {
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] b = ("{\"status\":\"" + status + "\",\"qr\":null,\"phone\":\"966500000001\",\"name\":\"Astha\",\"lastError\":null}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/send", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = sendBody.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(sendCode, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        return new WhatsAppGateway(props(url, "secret"), JsonMapper.builder().build());
    }

    static FatooraProperties props(String url, String token) {
        return new FatooraProperties(null, null, null, null, null,
                new FatooraProperties.Notifications(true, Duration.ofDays(15), Duration.ofHours(6), null, null,
                        new FatooraProperties.WhatsApp(url, token)), null);
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void statusAndSendWithToken() throws Exception {
        var g = gateway("connected", 200, "{\"ok\":true}");
        assertTrue(g.connected());
        assertEquals("Bearer secret", lastAuth.get());
        assertEquals("966500000001", g.status().phone());

        assertTrue(g.sendDocument("966511111111", "Invoice INV-1", new byte[]{1, 2, 3}, "INV-1.pdf", "application/pdf").delivered());
        assertTrue(lastBody.get().contains("\"to\":\"966511111111\""));
        assertTrue(lastBody.get().contains("\"base64\":\"AQID\""));
        assertTrue(lastBody.get().contains("\"fileName\":\"INV-1.pdf\""));
    }

    @Test
    void serviceErrorsBecomeFailedResults() throws Exception {
        var g = gateway("connected", 422, "{\"error\":\"not_on_whatsapp\",\"message\":\"This number is not on WhatsApp\"}");
        SendResult r = g.sendText("966511111111", "hi");
        assertFalse(r.delivered());
        assertEquals("This number is not on WhatsApp", r.error());
    }

    @Test
    void waitingForScanIsNotConnected() throws Exception {
        assertFalse(gateway("qr", 200, "{}").connected());
    }

    @Test
    void notSetUpOrUnreachable() {
        var none = new WhatsAppGateway(props("", ""), JsonMapper.builder().build());
        assertFalse(none.configured());
        assertEquals("unavailable", none.status().status());
        assertFalse(none.sendText("966511111111", "hi").delivered());

        var down = new WhatsAppGateway(props("http://127.0.0.1:9", "secret"), JsonMapper.builder().build());
        assertEquals("unavailable", down.status().status());
        assertFalse(down.connected());
    }
}
