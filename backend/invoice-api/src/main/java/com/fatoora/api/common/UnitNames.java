package com.fatoora.api.common;

import java.util.Locale;
import java.util.Map;

/** Full unit names for printed documents ("Piece", not "PCE"); the UN/ECE code itself stays in the data and ZATCA XML. */
public final class UnitNames {

    private UnitNames() {}

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("PCE", "Piece"), Map.entry("LTR", "Litre"), Map.entry("KGM", "Kilogram"), Map.entry("MTR", "Metre"),
            Map.entry("HUR", "Hour"), Map.entry("DAY", "Day"), Map.entry("BX", "Box"), Map.entry("DZN", "Dozen"),
            Map.entry("CT", "Carton"), Map.entry("PK", "Pack"), Map.entry("RO", "Roll"), Map.entry("BG", "Bag"),
            Map.entry("BE", "Bundle"), Map.entry("PR", "Pair"), Map.entry("TNE", "Tonne"), Map.entry("SET", "Set"));

    /** "KGM" → "Kilogram"; an unknown code → first letter capital, the rest small ("ABC" → "Abc"). */
    public static String of(String code) {
        if (code == null || code.isBlank()) return "";
        String c = code.trim();
        String known = NAMES.get(c.toUpperCase(Locale.ROOT));
        if (known != null) return known;
        return c.substring(0, 1).toUpperCase(Locale.ROOT) + c.substring(1).toLowerCase(Locale.ROOT);
    }
}
