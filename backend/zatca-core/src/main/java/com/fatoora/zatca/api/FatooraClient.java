package com.fatoora.zatca.api;

import com.fatoora.zatca.ZatcaEnvironment;
import com.fatoora.zatca.sign.SignedInvoice;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin client for the Fatoora e-invoicing gateway (API version V2).
 * <pre>
 *  POST  /compliance                  CSR + OTP            → compliance CSID
 *  POST  /compliance/invoices         compliance CSID auth → validation of a sample document
 *  POST  /production/csids            compliance CSID auth → production CSID
 *  PATCH /production/csids            production auth + OTP + new CSR → renewed production CSID
 *  POST  /invoices/clearance/single   production auth, Clearance-Status: 1 → cleared XML
 *  POST  /invoices/reporting/single   production auth, Clearance-Status: 0 → reported
 * </pre>
 * Every call returns the HTTP status alongside the parsed body, because ZATCA encodes outcomes in both
 * (200 = accepted, 202 = accepted with warnings, 400 = rejected, 401 = bad credentials, 303 = clearance off).
 */
public final class FatooraClient {

    private final ZatcaEnvironment environment;
    private final HttpClient http;
    private final JsonMapper json;
    private final String language;

    public FatooraClient(ZatcaEnvironment environment) {
        this(environment, "en");
    }

    public FatooraClient(ZatcaEnvironment environment, String language) {
        this.environment = environment;
        this.language = language;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.json = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    public ZatcaEnvironment environment() {
        return environment;
    }

    /** Step 1 of onboarding: exchange CSR + OTP for a compliance CSID. */
    public FatooraResponse<CsidResponse> requestComplianceCsid(String csrBase64, String otp) {
        return send("POST", "/compliance", Map.of("csr", csrBase64), null, Map.of("OTP", otp), CsidResponse.class);
    }

    /** Step 2 of onboarding: submit a sample document for compliance checking. */
    public FatooraResponse<SubmissionResponse> checkCompliance(SignedInvoice invoice, Credentials compliance) {
        return send("POST", "/compliance/invoices", invoiceBody(invoice), compliance, Map.of(), SubmissionResponse.class);
    }

    /** Step 3 of onboarding: exchange the compliance request id for a production CSID. */
    public FatooraResponse<CsidResponse> requestProductionCsid(String complianceRequestId, Credentials compliance) {
        return send("POST", "/production/csids", Map.of("compliance_request_id", complianceRequestId), compliance,
                Map.of(), CsidResponse.class);
    }

    /** Renews a production CSID before it expires. Needs a fresh OTP and a new CSR. */
    public FatooraResponse<CsidResponse> renewProductionCsid(String csrBase64, String otp, Credentials production) {
        return send("PATCH", "/production/csids", Map.of("csr", csrBase64), production, Map.of("OTP", otp),
                CsidResponse.class);
    }

    /** Standard (B2B) documents: ZATCA validates and stamps the invoice; share only the returned XML. */
    public FatooraResponse<SubmissionResponse> clear(SignedInvoice invoice, Credentials production) {
        return send("POST", "/invoices/clearance/single", invoiceBody(invoice), production,
                Map.of("Clearance-Status", "1"), SubmissionResponse.class);
    }

    /** Simplified (B2C) documents: report within 24 hours of issuing. */
    public FatooraResponse<SubmissionResponse> report(SignedInvoice invoice, Credentials production) {
        return send("POST", "/invoices/reporting/single", invoiceBody(invoice), production,
                Map.of("Clearance-Status", "0"), SubmissionResponse.class);
    }

    private static Map<String, Object> invoiceBody(SignedInvoice invoice) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("invoiceHash", invoice.invoiceHash());
        body.put("uuid", invoice.uuid().toString());
        body.put("invoice", invoice.xmlBase64());
        return body;
    }

    private <T> FatooraResponse<T> send(String method, String path, Object body, Credentials credentials,
                                        Map<String, String> extraHeaders, Class<T> type) {
        String payload = json.writeValueAsString(body);
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(environment.baseUrl() + path))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Accept-Version", "V2")
                .header("Accept-Language", language)
                .method(method, HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        if (credentials != null) req.header("Authorization", credentials.basicAuth());
        extraHeaders.forEach(req::header);

        try {
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            T parsed = null;
            String raw = res.body();
            if (raw != null && raw.trim().startsWith("{")) {
                try {
                    parsed = json.readValue(raw, type);
                } catch (RuntimeException ignored) {
                    // keep raw body; caller inspects status + raw
                }
            }
            return new FatooraResponse<>(res.statusCode(), parsed, raw);
        } catch (IOException e) {
            throw new FatooraUnavailableException("Fatoora " + path + " unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FatooraUnavailableException("Interrupted calling Fatoora " + path, e);
        }
    }

    /** CSID credentials: the binarySecurityToken exactly as returned, plus its secret. */
    public record Credentials(String binarySecurityToken, String secret) {
        String basicAuth() {
            String raw = binarySecurityToken + ":" + secret;
            return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
    }
}
