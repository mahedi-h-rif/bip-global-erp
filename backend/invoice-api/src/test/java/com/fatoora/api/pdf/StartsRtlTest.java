package com.fatoora.api.pdf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Item names are laid out right-to-left in PDFs when they start with Arabic, like HTML dir="auto". */
class StartsRtlTest {

    @Test
    void arabicFirstIsRtl() {
        assertTrue(InvoicePdfService.startsRtl("حلق رويال سحاب ١٢سم ربلEPDM فرشه ٤٧٠﷼"));
        assertTrue(InvoicePdfService.startsRtl("12 سم كرتوال"));
        assertTrue(InvoicePdfService.startsRtl("➕ شبك EPDM"));
    }

    @Test
    void latinFirstOrNoLettersIsLtr() {
        assertFalse(InvoicePdfService.startsRtl("Diesel - ديزل"));
        assertFalse(InvoicePdfService.startsRtl("EPDM حلق"));
        assertFalse(InvoicePdfService.startsRtl("12345"));
        assertFalse(InvoicePdfService.startsRtl(""));
        assertFalse(InvoicePdfService.startsRtl(null));
    }
}
