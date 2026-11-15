package com.fatoora.api.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnitNamesTest {

    @Test
    void codesPrintInFull() {
        assertEquals("Kilogram", UnitNames.of("KGM"));
        assertEquals("Roll", UnitNames.of("RO"));
        assertEquals("Piece", UnitNames.of("pce"));
        assertEquals("Abc", UnitNames.of("ABC"));
        assertEquals("", UnitNames.of(null));
    }
}
