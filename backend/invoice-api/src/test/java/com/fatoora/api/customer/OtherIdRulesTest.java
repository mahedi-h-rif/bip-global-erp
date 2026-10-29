package com.fatoora.api.customer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class OtherIdRulesTest {

    @Test
    void acceptsWellFormedIds() {
        assertNull(OtherIdRules.check("CRN", "7054854679"));
        assertNull(OtherIdRules.check("700", "7054854679"));
        assertNull(OtherIdRules.check("NAT", "1012345678"));
        assertNull(OtherIdRules.check("IQA", "2412345678"));
        assertNull(OtherIdRules.check("CRN", ""));
        assertNull(OtherIdRules.check("PAS", "A1234567"));
    }

    @Test
    void rejectsIdsZatcaFlagsWithBrKsaF08() {
        assertNotNull(OtherIdRules.check("CRN", "11111111111111111111111111111111111"));
        assertNotNull(OtherIdRules.check("CRN", "12345"));
        assertNotNull(OtherIdRules.check("700", "1054854679"));
        assertNotNull(OtherIdRules.check("NAT", "2012345678"));
    }
}
