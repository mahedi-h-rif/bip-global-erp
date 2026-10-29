package com.fatoora.api.agreement;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgreementTextTest {

    @Test
    void headingsBulletsAndParagraphs() {
        String html = AgreementController.body("""
                Intro line one
                line two

                ## 1. Scope
                - first
                - second
                Closing""", Map.of());
        assertEquals("<p>Intro line one<br/>line two</p><h2>1. Scope</h2>"
                + "<p style=\"margin:0 0 3px 0; padding-left:14px\">• first</p>"
                + "<p style=\"margin:0 0 3px 0; padding-left:14px\">• second</p><p>Closing</p>", html);
    }

    @Test
    void placeholdersAreFilledAndEverythingIsEscaped() {
        String html = AgreementController.body("Between {{company_name}} & {{client_name}}\n{{client_details}}",
                Map.of("company_name", "Astha <Gulf>", "client_name", "A & B", "client_details", "VAT 3\nMobile 05"));
        assertEquals("<p>Between Astha &lt;Gulf&gt; &amp; A &amp; B<br/>VAT 3<br/>Mobile 05</p>", html);
    }

    @Test
    void standardAgreementUsesOnlyKnownPlaceholders() {
        var known = java.util.Set.of("company_name", "company_name_ar", "company_vat", "company_cr", "company_address",
                "client_name", "client_details", "date", "agreement_number");
        var m = java.util.regex.Pattern.compile("\\{\\{(\\w+)}}").matcher(StandardAgreement.BODY);
        while (m.find()) assertTrue(known.contains(m.group(1)), "unknown placeholder " + m.group(1));
    }

    /** Amounts and payment terms belong on quotations and invoices, never in the agreement. */
    @Test
    void standardAgreementHasNoMoney() {
        String text = StandardAgreement.BODY.toLowerCase();
        for (String word : new String[] {"sar", "riyal", "ريال", "price", "payment", "pay ", "paid", "fee", "amount",
                "cost", "%", "invoice", "bank", "iban", "cash", "credit"})
            assertFalse(text.contains(word), "standard agreement mentions " + word);
    }
}
