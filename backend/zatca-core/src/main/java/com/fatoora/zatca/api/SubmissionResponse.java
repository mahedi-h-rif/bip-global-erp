package com.fatoora.zatca.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Response of compliance checks, clearance and reporting.
 *
 * @param reportingStatus "REPORTED" / "NOT_REPORTED" (simplified documents)
 * @param clearanceStatus "CLEARED" / "NOT_CLEARED" (standard documents)
 * @param clearedInvoice  base64 of ZATCA's stamped XML (clearance only); this is the legal copy
 */
public record SubmissionResponse(
        ValidationResults validationResults,
        String reportingStatus,
        String clearanceStatus,
        String clearedInvoice,
        String qrSellertStatus,
        String qrBuyertStatus) {

    public record ValidationResults(
            List<Message> infoMessages,
            List<Message> warningMessages,
            List<Message> errorMessages,
            String status) {}

    public record Message(String type, String code, String category, String message, String status) {}

    public String clearedInvoiceXml() {
        return clearedInvoice == null ? null
                : new String(Base64.getDecoder().decode(clearedInvoice), StandardCharsets.UTF_8);
    }

    public List<Message> errors() {
        return validationResults == null || validationResults.errorMessages() == null
                ? List.of() : validationResults.errorMessages();
    }

    public List<Message> warnings() {
        return validationResults == null || validationResults.warningMessages() == null
                ? List.of() : validationResults.warningMessages();
    }
}
