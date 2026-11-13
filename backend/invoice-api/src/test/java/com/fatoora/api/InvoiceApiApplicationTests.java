package com.fatoora.api;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end through the real HTTP API (cookie session + CSRF, exactly like the portal) against
 * a local PostgreSQL and ZATCA's public developer sandbox.
 * Run with: ./gradlew :invoice-api:test -Dzatca.live=true
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "zatca.live", matches = "true")
class InvoiceApiApplicationTests {

    @TestConfiguration
    static class CleanDatabase {
        @Bean
        FlywayMigrationStrategy cleanMigrate() {
            return (Flyway flyway) -> {
                flyway.clean();
                flyway.migrate();
            };
        }
    }

    @Autowired
    Environment env;

    private final JsonMapper json = JsonMapper.builder().build();
    private final CookieManager cookies = new CookieManager();
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
    private String base;
    private long customerId;
    private long simplifiedId;
    private long standardId;

    @BeforeAll
    void login() throws Exception {
        base = "http://localhost:" + env.getProperty("local.server.port") + "/api/v1";
        assertEquals(401, call("GET", "/auth/me", null).statusCode(), "API must be closed without a session");
        call("GET", "/auth/csrf", null);
        // The e-invoicing provider performs ZATCA setup; it inherits all client-admin rights.
        var res = call("POST", "/auth/login", Map.of("email", "provider@test.local", "password", "provider-secret-123"));
        assertEquals(200, res.statusCode(), res.body());
    }

    @Test
    @Order(8)
    void clientAdminCannotReachZatcaSetupOrLegalDetails() throws Exception {
        var jar = new CookieManager();
        var client = HttpClient.newBuilder().cookieHandler(jar).build();
        client.send(HttpRequest.newBuilder(URI.create(base + "/auth/csrf")).build(), HttpResponse.BodyHandlers.ofString());
        java.util.function.Supplier<String> token = () -> jar.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow();
        java.util.function.BiFunction<String, Object[], HttpResponse<String>> as = (spec, args) -> {
            try {
                String[] parts = spec.split(" ", 2);
                var req = HttpRequest.newBuilder(URI.create(base + parts[1])).header("Content-Type", "application/json")
                        .header("X-XSRF-TOKEN", token.get())
                        .method(parts[0], args.length == 0 ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(args[0])));
                return client.send(req.build(), HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        assertEquals(200, as.apply("POST /auth/login",
                new Object[]{Map.of("email", "admin@test.local", "password", "correct-horse-battery")}).statusCode());
        assertEquals("ADMIN", json.readTree(as.apply("GET /auth/me", new Object[0]).body()).get("role").asString());

        assertEquals(200, as.apply("GET /egs-units", new Object[0]).statusCode(), "admins may see the devices (read-only)");
        assertEquals(403, as.apply("POST /egs-units", new Object[]{Map.of("name", "X", "branch", "B",
                "location", "L", "industry", "I", "invoiceTypes", "1100")}).statusCode(), "onboarding is provider-only");

        var org = (tools.jackson.databind.node.ObjectNode) json.readTree(as.apply("GET /organization", new Object[0]).body());
        var changedVat = org.deepCopy().put("vatNumber", "311111111111113");
        var refused = as.apply("PUT /organization", new Object[]{changedVat});
        assertEquals(403, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("provider_only"));

        var branding = org.deepCopy().put("phone", "+966 11 000 0000").put("brandColor", "#1B6B05");
        var ok = as.apply("PUT /organization", new Object[]{branding});
        assertEquals(200, ok.statusCode(), ok.body());
        assertEquals("#1B6B05", json.readTree(ok.body()).get("brandColor").asString());

        var dash = json.readTree(as.apply("GET /dashboard", new Object[0]).body());
        assertEquals(0, dash.get("certificates").size(), "certificate warnings are for the provider only");
    }

    @Test
    @Order(1)
    void wrongPasswordIsRejected() throws Exception {
        var jar = new CookieManager();
        var anon = HttpClient.newBuilder().cookieHandler(jar).build();
        anon.send(HttpRequest.newBuilder(URI.create(base + "/auth/csrf")).build(), HttpResponse.BodyHandlers.ofString());
        String token = jar.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow();
        var res = anon.send(HttpRequest.newBuilder(URI.create(base + "/auth/login"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"admin@test.local\",\"password\":\"wrong-password\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, res.statusCode(), res.body());

        var noCsrf = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + "/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"admin@test.local\",\"password\":\"correct-horse-battery\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        // Correct password but no CSRF token: refused (Spring reports it as 401 for anonymous callers), no session.
        assertTrue(noCsrf.statusCode() == 401 || noCsrf.statusCode() == 403, "status " + noCsrf.statusCode());
        assertTrue(noCsrf.headers().allValues("Set-Cookie").stream().noneMatch(c -> c.startsWith("fatoora_session=")),
                "login without the CSRF token must not create a session");
    }

    @Test
    @Order(2)
    void onboardSandboxDevice() throws Exception {
        var created = body(call("POST", "/egs-units", Map.of("name", "NG-PORTAL-1", "branch", "Head Office",
                "location", "QFDA3287", "industry", "Fuel retail", "invoiceTypes", "1100")), 201);
        long id = created.get("id").asLong();
        var result = body(call("POST", "/egs-units/" + id + "/onboard", Map.of("otp", "123345")), 200);
        System.out.println("ONBOARDING " + result);
        assertEquals("ACTIVE", result.get("status").asString());
        assertEquals(9, result.get("steps").size(), "CSR + compliance CSID + 6 compliance checks + production CSID");
    }

    @Test
    @Order(3)
    void createCustomer() throws Exception {
        var c = body(call("POST", "/customers", Map.ofEntries(
                Map.entry("nameAr", "شركة العميل التجارية"), Map.entry("nameEn", "Client Trading Co."),
                Map.entry("vatNumber", "399999999800003"), Map.entry("street", "طريق الملك عبدالعزيز"),
                Map.entry("buildingNumber", "1234"), Map.entry("additionalNumber", "5678"),
                Map.entry("district", "حي الريان"), Map.entry("city", "بريدة"), Map.entry("postalCode", "52365"),
                Map.entry("phone", "0501234567"))), 201);
        customerId = c.get("id").asLong();
    }

    @Test
    @Order(4)
    void standardDraftIsNotDownloadableThenClears() throws Exception {
        var draft = body(call("POST", "/invoices", Map.of("kind", "STANDARD", "customerId", customerId,
                "lines", new Object[]{Map.of("name", "Diesel - ديزل", "quantity", 1000, "unitCode", "LTR", "unitPrice", 1.66)})), 201);
        standardId = draft.get("id").asLong();
        assertEquals("DRAFT", draft.get("status").asString());
        assertEquals(409, call("GET", "/invoices/" + standardId + "/pdf", null).statusCode(), "drafts must not be shareable");
        assertEquals(400, call("POST", "/invoices/" + standardId + "/issue", Map.of("sendMode", "LATER")).statusCode(),
                "standard invoices cannot be queued");

        var issued = body(call("POST", "/invoices/" + standardId + "/issue", Map.of("sendMode", "NOW")), 200);
        System.out.println("STANDARD " + issued.get("status") + " " + issued.get("number") + " errors=" + issued.get("zatcaErrors"));
        assertEquals("CLEARED", issued.get("status").asString());
        assertTrue(issued.get("downloadable").asBoolean());
        // 1000 L × 1.66 = 1,660.00 + 15% VAT 249.00
        assertEquals(0, new java.math.BigDecimal("1909.00").compareTo(issued.get("totalInclVat").decimalValue()));
    }

    @Test
    @Order(5)
    void simplifiedQueuedForLaterIsReportedByScheduler() throws Exception {
        var draft = body(call("POST", "/invoices", Map.of("kind", "SIMPLIFIED",
                "lines", new Object[]{Map.of("name", "Petrol 91 - بنزين 91", "quantity", 40, "unitCode", "LTR", "unitPrice", 2.18)})), 201);
        simplifiedId = draft.get("id").asLong();
        var issued = body(call("POST", "/invoices/" + simplifiedId + "/issue", Map.of("sendMode", "LATER")), 200);
        assertEquals("SIGNED", issued.get("status").asString(), "queued, not yet reported");
        assertTrue(issued.get("downloadable").asBoolean(), "simplified invoices can be given to the customer immediately");
        assertEquals(2, issued.get("icv").asLong(), "second document of this device");

        var sent = body(call("POST", "/invoices/" + simplifiedId + "/submit", null), 200);
        assertEquals("REPORTED", sent.get("status").asString(), sent.toString());
    }

    @Test
    @Order(6)
    void pdfAndXmlDownloadAndCreditNote() throws Exception {
        var pdf = http.send(HttpRequest.newBuilder(URI.create(base + "/invoices/" + standardId + "/pdf")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, pdf.statusCode());
        assertEquals("%PDF", new String(pdf.body(), 0, 4));
        java.nio.file.Files.write(java.nio.file.Path.of("build/sample-standard-invoice.pdf"), pdf.body());

        var xml = call("GET", "/invoices/" + standardId + "/xml", null);
        assertEquals(200, xml.statusCode());
        assertTrue(xml.body().contains("<cbc:ID>QR</cbc:ID>"));

        var note = body(call("POST", "/invoices/" + standardId + "/notes", Map.of("type", "CREDIT_NOTE", "reason", "Returned 100 L")), 201);
        long noteId = note.get("id").asLong();
        var issued = body(call("POST", "/invoices/" + noteId + "/issue", Map.of("sendMode", "NOW")), 200);
        System.out.println("CREDIT NOTE " + issued.get("status") + " " + issued.get("number"));
        assertEquals("CLEARED", issued.get("status").asString(), issued.toString());
        assertTrue(issued.get("number").asString().startsWith("CN-"));

        var dash = body(call("GET", "/dashboard", null), 200);
        assertEquals(0, dash.get("awaitingSubmission").asLong());
    }

    @Test
    @Order(7)
    void companyVatDifferentFromCertificateIsRefusedBeforeNumbering() throws Exception {
        // Nazmul's real VAT, while the sandbox device holds ZATCA's shared test certificate (399999999900003).
        var org = (tools.jackson.databind.node.ObjectNode) body(call("GET", "/organization", null), 200);
        String originalVat = org.get("vatNumber").asString();
        org.put("vatNumber", "314920207600003");
        body(call("PUT", "/organization", org), 200);
        try {
            var draft = body(call("POST", "/invoices", Map.of("kind", "SIMPLIFIED",
                    "lines", new Object[]{Map.of("name", "Petrol 95", "quantity", 10, "unitCode", "LTR", "unitPrice", 2.33)})), 201);
            long id = draft.get("id").asLong();
            var refused = call("POST", "/invoices/" + id + "/issue", Map.of("sendMode", "NOW"));
            assertEquals(409, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("certificate_vat_mismatch"), refused.body());

            var still = body(call("GET", "/invoices/" + id, null), 200);
            assertEquals("DRAFT", still.get("status").asString(), "no number, ICV or ZATCA call may be spent");
            assertTrue(still.get("number").isNull());
        } finally {
            org.put("vatNumber", originalVat);
            body(call("PUT", "/organization", org), 200);
        }
    }

    @Test
    @Order(20)
    void customAccessUserGetsOnlyTickedFeaturesAndActions() throws Exception {
        String email = "quotes-" + System.nanoTime() + "@test.local";
        body(call("POST", "/users", Map.of("email", email, "fullName", "Quotes Only", "role", "ACCOUNTANT",
                "password", "quotes-only-secret-1", "features", java.util.List.of("quotations"),
                "actions", java.util.List.of("view", "create"), "ownDataOnly", true)), 200);

        var jar = new CookieManager();
        var client = HttpClient.newBuilder().cookieHandler(jar).build();
        client.send(HttpRequest.newBuilder(URI.create(base + "/auth/csrf")).build(), HttpResponse.BodyHandlers.ofString());
        java.util.function.BiFunction<String, Object, HttpResponse<String>> as = (spec, payload) -> {
            try {
                String[] parts = spec.split(" ", 2);
                String token = jar.getCookieStore().getCookies().stream()
                        .filter(c -> c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow();
                var req = HttpRequest.newBuilder(URI.create(base + parts[1])).header("Content-Type", "application/json")
                        .header("X-XSRF-TOKEN", token)
                        .method(parts[0], payload == null ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
                return client.send(req.build(), HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        assertEquals(200, as.apply("POST /auth/login", Map.of("email", email, "password", "quotes-only-secret-1")).statusCode());

        var perms = json.readTree(as.apply("GET /settings/my-permissions", null).body());
        assertTrue(perms.get("custom").asBoolean());
        assertTrue(perms.get("pages").toString().contains("/quotations"));

        assertEquals(200, as.apply("GET /quotations", null).statusCode(), "ticked feature");
        assertEquals(200, as.apply("GET /customers", null).statusCode(), "pickers stay readable");
        var created = as.apply("POST /quotations", Map.of("clientName", "Walk-in buyer",
                "lines", new Object[]{Map.of("name", "Carton tape", "quantity", 1, "unitCode", "PCE", "unitPrice", 5, "vatRate", 15)}));
        assertTrue(created.statusCode() == 200 || created.statusCode() == 201, created.body());
        long qid = json.readTree(created.body()).get("id").asLong();

        assertEquals(403, as.apply("DELETE /quotations/" + qid, null).statusCode(), "delete not ticked");
        assertEquals(403, as.apply("GET /invoices", null).statusCode(), "invoices not ticked");
        assertEquals(403, as.apply("GET /users", null).statusCode(), "users not ticked");
        assertEquals(403, as.apply("POST /customers", Map.of("nameEn", "X")).statusCode(), "creating a customer needs Customers");

        // Own data only: the quotations list holds only what this login created.
        var list = json.readTree(as.apply("GET /quotations", null).body());
        for (var q : list) assertEquals(qid, q.get("id").asLong(), "only own quotations");

        var names = json.readTree(as.apply("GET /suggest?field=party&q=walk", null).body());
        assertTrue(names.toString().contains("Walk-in buyer"), "suggests names already entered: " + names);
    }

    @Test
    @Order(21)
    void cancelIssuesFullCreditNoteAndRefundsWhenTicked() throws Exception {
        var draft = body(call("POST", "/invoices", Map.of("kind", "SIMPLIFIED",
                "lines", new Object[]{Map.of("name", "Engine oil", "quantity", 2, "unitCode", "PCE", "unitPrice", 50)})), 201);
        long id = draft.get("id").asLong();
        body(call("POST", "/invoices/" + id + "/issue", Map.of("sendMode", "NOW")), 200);
        body(call("POST", "/invoices/" + id + "/payments", Map.of("amount", 40, "paidOn", java.time.LocalDate.now().toString(), "method", "CASH")), 201);

        var cancelled = body(call("POST", "/invoices/" + id + "/cancel-invoice",
                Map.of("reason", "Customer called off the order", "goodsReturned", false, "paymentRefunded", true)), 200);
        assertEquals("CANCELLED", cancelled.get("lifecycle").asString(), cancelled.toString());
        assertFalse(cancelled.get("cancelNoteNumber").isNull(), "the full credit note is issued");
        assertEquals(0, cancelled.get("paidAmount").decimalValue().signum(), "refund nets out what was paid: " + cancelled);

        var note = body(call("GET", "/invoices/" + cancelled.get("cancelNoteId").asLong(), null), 200);
        assertEquals("CREDIT_NOTE", note.get("type").asString());
        assertEquals(0, new java.math.BigDecimal("115.00").compareTo(note.get("totalInclVat").decimalValue()), "full amount credited");

        assertEquals(409, call("POST", "/invoices/" + id + "/cancel-invoice",
                Map.of("reason", "again", "goodsReturned", false, "paymentRefunded", false)).statusCode(), "cancel only once");
        assertEquals(409, call("POST", "/invoices/" + id + "/payments",
                Map.of("amount", 1, "paidOn", java.time.LocalDate.now().toString(), "method", "CASH")).statusCode(), "no payments after cancel");
    }

    @Test
    @Order(22)
    void closeWritesOffTheRestAndReopenUndoesIt() throws Exception {
        var draft = body(call("POST", "/invoices", Map.of("kind", "SIMPLIFIED",
                "lines", new Object[]{Map.of("name", "Filter", "quantity", 1, "unitCode", "PCE", "unitPrice", 100)})), 201);
        long id = draft.get("id").asLong();
        body(call("POST", "/invoices/" + id + "/issue", Map.of("sendMode", "NOW")), 200);

        var closed = body(call("POST", "/invoices/" + id + "/close", Map.of("reason", "Customer will not pay the rest")), 200);
        assertEquals("CLOSED", closed.get("lifecycle").asString());
        assertEquals(0, closed.get("dueAmount").decimalValue().signum(), "nothing due on a closed invoice");
        assertEquals("CLOSED", closed.get("paymentStatus").asString());
        assertEquals(409, call("POST", "/invoices/" + id + "/payments",
                Map.of("amount", 10, "paidOn", java.time.LocalDate.now().toString(), "method", "CASH")).statusCode());

        var open = body(call("POST", "/invoices/" + id + "/reopen", null), 200);
        assertTrue(open.get("lifecycle").isNull());
        assertEquals(0, new java.math.BigDecimal("115.00").compareTo(open.get("dueAmount").decimalValue()));
    }

    @Test
    @Order(23)
    void customerOverviewSumsTheirInvoices() throws Exception {
        var o = body(call("GET", "/customers/" + customerId + "/overview", null), 200);
        assertEquals(customerId, o.get("customer").get("id").asLong());
        assertTrue(o.get("invoiceCount").asLong() >= 1, o.toString());
        // "invoiced" is after credit notes (an earlier test credits this customer's invoice), so check the list instead.
        assertTrue(o.get("recent").get(0).get("total").decimalValue().signum() > 0, o.toString());
        assertTrue(o.get("recent").size() >= 1);
    }

    @Test
    @Order(24)
    void paymentProofCanBeAttachedAndDownloaded() throws Exception {
        var draft = body(call("POST", "/invoices", Map.of("kind", "SIMPLIFIED",
                "lines", new Object[]{Map.of("name", "Brake pads", "quantity", 1, "unitCode", "PCE", "unitPrice", 80)})), 201);
        long id = draft.get("id").asLong();
        body(call("POST", "/invoices/" + id + "/issue", Map.of("sendMode", "NOW")), 200);
        long paymentId = body(call("POST", "/invoices/" + id + "/payments",
                Map.of("amount", 92, "paidOn", java.time.LocalDate.now().toString(), "method", "BANK_TRANSFER")), 201).get("id").asLong();

        byte[] pdf = "%PDF-1.4 test slip".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var req = HttpRequest.newBuilder(URI.create(base + "/payments/" + paymentId + "/proof?name=slip.pdf"))
                .header("Content-Type", "application/pdf").header("X-XSRF-TOKEN", xsrf().orElseThrow())
                .PUT(HttpRequest.BodyPublishers.ofByteArray(pdf)).build();
        assertEquals(200, http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());

        var list = body(call("GET", "/invoices/" + id + "/payment-proofs", null), 200);
        assertEquals("slip.pdf", list.get(0).get("fileName").asString());
        var file = http.send(HttpRequest.newBuilder(URI.create(base + "/payments/" + paymentId + "/proof")).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, file.statusCode());
        assertEquals(new String(pdf, java.nio.charset.StandardCharsets.UTF_8), new String(file.body(), java.nio.charset.StandardCharsets.UTF_8));

        var exe = HttpRequest.newBuilder(URI.create(base + "/payments/" + paymentId + "/proof?name=x.exe"))
                .header("Content-Type", "application/x-msdownload").header("X-XSRF-TOKEN", xsrf().orElseThrow())
                .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1, 2})).build();
        assertEquals(400, http.send(exe, HttpResponse.BodyHandlers.ofString()).statusCode(), "only photos and PDFs");
    }

    @Test
    @Order(25)
    void agreementPrintsInEnglishAndArabic() throws Exception {
        long id = body(call("POST", "/agreements", Map.of("clientName", "Al Noor Trading Est.")), 201).get("id").asLong();
        for (String lang : new String[]{"en", "ar"}) {
            var pdf = http.send(HttpRequest.newBuilder(URI.create(base + "/agreements/" + id + "/pdf?lang=" + lang)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, pdf.statusCode());
            try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf.body())) {
                String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
                assertTrue(lang.equals("en") ? text.contains("General Supply Agreement") : text.contains("Al Noor Trading Est."), lang);
                if (lang.equals("ar")) assertTrue(text.codePoints().filter(c -> c >= 0x0600 && c <= 0x06FF).count() > 500, "Arabic text");
            }
        }
    }

    @Test
    @Order(26)
    void actionsAreSetPerFeature() throws Exception {
        String email = "per-feature-" + System.nanoTime() + "@test.local";
        var created = body(call("POST", "/users", Map.of("email", email, "fullName", "Per Feature", "role", "ACCOUNTANT",
                "password", "per-feature-secret-1", "ownDataOnly", false,
                "permissions", Map.of("invoices", java.util.List.of("view", "create"), "quotations", java.util.List.of("view")))), 200);
        assertEquals("[create, view]", created.get("permissions").get("invoices").toString().replace("\"", "").replace(",", ", "));

        var jar = new CookieManager();
        var client = HttpClient.newBuilder().cookieHandler(jar).build();
        client.send(HttpRequest.newBuilder(URI.create(base + "/auth/csrf")).build(), HttpResponse.BodyHandlers.ofString());
        java.util.function.BiFunction<String, Object, HttpResponse<String>> as = (spec, payload) -> {
            try {
                String[] parts = spec.split(" ", 2);
                String token = jar.getCookieStore().getCookies().stream()
                        .filter(c -> c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow();
                var req = HttpRequest.newBuilder(URI.create(base + parts[1])).header("Content-Type", "application/json")
                        .header("X-XSRF-TOKEN", token).method(parts[0], payload == null ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
                return client.send(req.build(), HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        assertEquals(200, as.apply("POST /auth/login", Map.of("email", email, "password", "per-feature-secret-1")).statusCode());
        var line = new Object[]{Map.of("name", "Tape", "quantity", 1, "unitCode", "PCE", "unitPrice", 5)};
        assertEquals(201, as.apply("POST /invoices", Map.of("kind", "SIMPLIFIED", "lines", line)).statusCode(), "create on invoices");
        assertEquals(200, as.apply("GET /quotations", null).statusCode(), "view on quotations");
        assertEquals(403, as.apply("POST /quotations", Map.of("clientName", "X", "lines", line)).statusCode(), "quotations are view-only");
    }

    @Test
    @Order(27)
    void bankQrUploadAndQuotationWithoutBankDetails() throws Exception {
        body(call("PUT", "/settings/bank", Map.of("bankName", "AL RAJHI BANK", "bankAccountName", "TEST CO",
                "bankAccountNo", "123", "bankIban", "SA5680000450608010141252")), 200);
        var img = new java.awt.image.BufferedImage(40, 40, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", png);
        var put = HttpRequest.newBuilder(URI.create(base + "/settings/bank/qr")).header("Content-Type", "image/png")
                .header("X-XSRF-TOKEN", xsrf().orElseThrow()).PUT(HttpRequest.BodyPublishers.ofByteArray(png.toByteArray())).build();
        var saved = http.send(put, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, saved.statusCode(), saved.body());
        assertTrue(json.readTree(saved.body()).get("hasQr").asBoolean());
        assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(base + "/settings/bank/qr")).build(), HttpResponse.BodyHandlers.ofByteArray()).statusCode());

        long q = body(call("POST", "/quotations", Map.of("clientName", "QR Client",
                "lines", new Object[]{Map.of("name", "Tape", "quantity", 1, "unitCode", "PCE", "unitPrice", 5, "vatRate", 15)})), 201).get("id").asLong();
        for (boolean proforma : new boolean[]{false, true}) {
            var pdf = http.send(HttpRequest.newBuilder(URI.create(base + "/quotations/" + q + "/pdf?proforma=" + proforma)).build(), HttpResponse.BodyHandlers.ofByteArray());
            try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf.body())) {
                String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
                assertEquals(proforma, text.contains("SA56 8000 0450 6080 1014 1252"), (proforma ? "proforma shows" : "quotation hides") + " the bank details");
            }
        }
        var removed = body(call("DELETE", "/settings/bank/qr", null), 200);
        assertFalse(removed.get("hasQr").asBoolean(), "QR removed");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private HttpResponse<String> call(String method, String path, Object body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        xsrf().ifPresent(t -> req.header("X-XSRF-TOKEN", t));
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private java.util.Optional<String> xsrf() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst();
    }

    private JsonNode body(HttpResponse<String> res, int expected) {
        assertEquals(expected, res.statusCode(), res.body());
        return json.readTree(res.body());
    }
}
