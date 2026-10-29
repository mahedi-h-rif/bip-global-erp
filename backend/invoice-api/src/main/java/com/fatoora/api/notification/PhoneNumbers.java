package com.fatoora.api.notification;

/**
 * Mobile numbers in international format without "+" (as SMS gateways and wa.me expect).
 * Saudi numbers may be typed locally: 05XXXXXXXX or 5XXXXXXXX become 9665XXXXXXXX.
 * Other countries need their country code (+44…, 00971…).
 */
public final class PhoneNumbers {

    private PhoneNumbers() {}

    /** The normalised number, or null if it isn't a usable mobile number. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.strip().replaceAll("[\\s\\-().]", "");
        boolean international = false;
        if (s.startsWith("+")) {
            s = s.substring(1);
            international = true;
        } else if (s.startsWith("00")) {
            s = s.substring(2);
            international = true;
        }
        if (!s.matches("\\d+")) return null;

        if (!international) {
            if (s.matches("05\\d{8}")) return "966" + s.substring(1);
            if (s.matches("5\\d{8}")) return "966" + s;
        }
        // A trunk zero kept after the country code: +966 05X…
        if (s.matches("96605\\d{8}")) return "966" + s.substring(4);
        if (s.startsWith("966")) return s.matches("9665\\d{8}") ? s : null;
        if (international && s.length() >= 8 && s.length() <= 15 && s.charAt(0) != '0') return s;
        return null;
    }
}
