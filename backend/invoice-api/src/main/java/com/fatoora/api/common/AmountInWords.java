package com.fatoora.api.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** "Eight hundred ninety-six and 50/100 SAR" — the amount-in-words line on purchase orders, quotations and invoices. */
public final class AmountInWords {

    private static final String[] ONES = {"", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
    private static final String[] AR_ONES = {"", "واحد", "اثنان", "ثلاثة", "أربعة", "خمسة", "ستة", "سبعة", "ثمانية", "تسعة", "عشرة",
            "أحد عشر", "اثنا عشر", "ثلاثة عشر", "أربعة عشر", "خمسة عشر", "ستة عشر", "سبعة عشر", "ثمانية عشر", "تسعة عشر"};
    private static final String[] AR_TENS = {"", "", "عشرون", "ثلاثون", "أربعون", "خمسون", "ستون", "سبعون", "ثمانون", "تسعون"};
    private static final String[] AR_HUNDREDS = {"", "مائة", "مائتان", "ثلاثمائة", "أربعمائة", "خمسمائة", "ستمائة", "سبعمائة", "ثمانمائة", "تسعمائة"};

    private AmountInWords() {}

    public static String english(BigDecimal amount, String currency) {
        BigDecimal a = amount.setScale(2, RoundingMode.HALF_UP).abs();
        long whole = a.longValue();
        int cents = a.remainder(BigDecimal.ONE).movePointRight(2).intValue();
        String words = whole == 0 ? "zero" : en(whole);
        String s = Character.toUpperCase(words.charAt(0)) + words.substring(1);
        return s + (cents > 0 ? " and %02d/100".formatted(cents) : "") + " " + currency + " only";
    }

    /** Arabic, e.g. "فقط ثمانمائة وستة وتسعون ريال و 50 هللة لا غير". */
    public static String arabic(BigDecimal amount) {
        BigDecimal a = amount.setScale(2, RoundingMode.HALF_UP).abs();
        long whole = a.longValue();
        int cents = a.remainder(BigDecimal.ONE).movePointRight(2).intValue();
        String words = whole == 0 ? "صفر" : ar(whole);
        return "فقط " + words + " ريال" + (cents > 0 ? " و " + cents + " هللة" : "") + " لا غير";
    }

    private static String en(long n) {
        if (n < 20) return ONES[(int) n];
        if (n < 100) return TENS[(int) (n / 10)] + (n % 10 == 0 ? "" : "-" + ONES[(int) (n % 10)]);
        if (n < 1000) return ONES[(int) (n / 100)] + " hundred" + (n % 100 == 0 ? "" : " " + en(n % 100));
        if (n < 1_000_000) return en(n / 1000) + " thousand" + (n % 1000 == 0 ? "" : " " + en(n % 1000));
        if (n < 1_000_000_000) return en(n / 1_000_000) + " million" + (n % 1_000_000 == 0 ? "" : " " + en(n % 1_000_000));
        return en(n / 1_000_000_000) + " billion" + (n % 1_000_000_000 == 0 ? "" : " " + en(n % 1_000_000_000));
    }

    private static String ar(long n) {
        if (n < 20) return AR_ONES[(int) n];
        if (n < 100) {
            int o = (int) (n % 10);
            return o == 0 ? AR_TENS[(int) (n / 10)] : AR_ONES[o] + " و" + AR_TENS[(int) (n / 10)];
        }
        if (n < 1000) return AR_HUNDREDS[(int) (n / 100)] + (n % 100 == 0 ? "" : " و" + ar(n % 100));
        if (n < 1_000_000) {
            long t = n / 1000;
            String th = t == 1 ? "ألف" : t == 2 ? "ألفان" : t <= 10 ? ar(t) + " آلاف" : ar(t) + " ألف";
            return th + (n % 1000 == 0 ? "" : " و" + ar(n % 1000));
        }
        long m = n / 1_000_000;
        String mm = m == 1 ? "مليون" : m == 2 ? "مليونان" : m <= 10 ? ar(m) + " ملايين" : ar(m) + " مليون";
        return mm + (n % 1_000_000 == 0 ? "" : " و" + ar(n % 1_000_000));
    }
}
