package com.fatoora.api.account;

import com.fatoora.api.ocr.ImagePreprocessor;
import com.fatoora.api.ocr.OcrInput;
import com.fatoora.api.ocr.OcrService;
import com.fatoora.zatca.qr.QrCodeTlv;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what it can off an uploaded invoice so the income/expense form can be prefilled. It never
 * throws for a file it cannot read — a scan is a best-effort convenience, so a failure just comes
 * back as {@code source = NONE} and the user types the figures in by hand.
 *
 * <p>Two readers, tried in order:
 * <ol>
 *   <li><b>ZATCA QR</b> — every Saudi tax invoice carries a QR with the seller, total, VAT and
 *       timestamp as a base64 TLV payload. This is exact, so it is tried first.
 *   <li><b>OCR fallback</b> — when there is no QR, the invoice text is read with Tesseract and the
 *       amount, date, invoice number and VAT number are picked out heuristically.
 * </ol>
 */
@Component
public class InvoiceScanService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceScanService.class);

    /** Labels that sit next to the grand total on an invoice, English and Arabic. */
    private static final List<String> TOTAL_LABELS = List.of(
            "grand total", "total due", "amount due", "total amount", "total", "net",
            "الإجمالي", "الاجمالي", "اجمالي", "إجمالي", "المجموع", "المبلغ", "الاجمالى");

    private static final Pattern MONEY = Pattern.compile("\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?");
    private static final Pattern DATE_ISO = Pattern.compile("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})");
    private static final Pattern DATE_DMY = Pattern.compile("(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})");
    private static final Pattern VAT_NO = Pattern.compile("\\b(3\\d{14})\\b");
    private static final Pattern INVOICE_NO = Pattern.compile(
            "(?i)(?:tax\\s+invoice|invoice|inv|فاتورة|رقم)[^0-9A-Za-z\\u0660-\\u0669]{0,12}([A-Za-z0-9][A-Za-z0-9\\-/]{2,})");

    /** The fields a scan could recover; {@code source} is QR, OCR or NONE. */
    public record ScanResult(String source, LocalDate date, BigDecimal amount, String vendor,
                             String vatNumber, String invoiceNumber) {}

    private final ImagePreprocessor preprocessor;
    private final OcrService ocr;

    public InvoiceScanService(ImagePreprocessor preprocessor, OcrService ocr) {
        this.preprocessor = preprocessor;
        this.ocr = ocr;
    }

    public ScanResult scan(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) return none();

        BufferedImage image = null;
        try {
            image = preprocessor.decode(bytes, contentType);
        } catch (Exception ex) {
            log.warn("invoice_scan_decode_failed reason={}", ex.toString());
        }

        if (image != null) {
            ScanResult qr = readQr(image);
            if (qr != null) return qr;
        }

        Optional<String> text = ocr.extractText(new OcrInput(bytes, contentType, "invoice"));
        if (text.isPresent()) return fromText(text.get());
        return none();
    }

    // --- QR ---------------------------------------------------------------------------------------

    private ScanResult readQr(BufferedImage image) {
        String payload = decodeQr(image);
        if (payload == null) return null;
        Map<Integer, byte[]> tags;
        try {
            tags = QrCodeTlv.decode(payload);
        } catch (RuntimeException ex) {
            return null; // Some other QR (e.g. a vCard) — not a ZATCA payload.
        }
        // A ZATCA payload has at least the seller name (1) and total (4).
        if (!tags.containsKey(1) && !tags.containsKey(4)) return null;

        String vendor = utf8(tags.get(1));
        String vatNumber = utf8(tags.get(2));
        LocalDate date = parseIsoDate(utf8(tags.get(3)));
        BigDecimal amount = parseAmount(utf8(tags.get(4)));
        return new ScanResult("QR", date, amount, blankToNull(vendor), blankToNull(vatNumber), null);
    }

    private String decodeQr(BufferedImage image) {
        try {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            Result result = new MultiFormatReader().decode(bitmap, hints);
            return result.getText();
        } catch (Exception ex) {
            return null; // No readable QR in the image.
        }
    }

    // --- OCR --------------------------------------------------------------------------------------

    private ScanResult fromText(String text) {
        List<String> lines = text.lines().map(String::strip).filter(l -> !l.isBlank()).toList();

        BigDecimal amount = amountFromLabels(lines);
        if (amount == null) amount = largestAmount(lines);

        return new ScanResult("OCR", dateFrom(text), amount, null, firstMatch(VAT_NO, text), invoiceNumber(text));
    }

    /** The largest number on any line that carries a total-style label. */
    private BigDecimal amountFromLabels(List<String> lines) {
        BigDecimal best = null;
        for (String line : lines) {
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (TOTAL_LABELS.stream().noneMatch(lower::contains)) continue;
            BigDecimal onLine = largestAmount(List.of(line));
            if (onLine != null && (best == null || onLine.compareTo(best) > 0)) best = onLine;
        }
        return best;
    }

    private BigDecimal largestAmount(List<String> lines) {
        BigDecimal best = null;
        for (String line : lines) {
            Matcher m = MONEY.matcher(line);
            while (m.find()) {
                BigDecimal v = parseAmount(m.group());
                if (v != null && (best == null || v.compareTo(best) > 0)) best = v;
            }
        }
        return best;
    }

    private LocalDate dateFrom(String text) {
        Matcher iso = DATE_ISO.matcher(text);
        if (iso.find()) {
            LocalDate d = toDate(iso.group(1), iso.group(2), iso.group(3));
            if (d != null) return d;
        }
        Matcher dmy = DATE_DMY.matcher(text);
        if (dmy.find()) {
            LocalDate d = toDate(dmy.group(3), dmy.group(2), dmy.group(1));
            if (d != null) return d;
        }
        return null;
    }

    private String invoiceNumber(String text) {
        Matcher m = INVOICE_NO.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static ScanResult none() {
        return new ScanResult("NONE", null, null, null, null, null);
    }

    private static String utf8(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8).strip();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            BigDecimal v = new BigDecimal(raw.replace(",", "").replace(" ", "").strip());
            return v.signum() < 0 ? null : v;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** First 10 characters of a ZATCA timestamp are the issue date. */
    private static LocalDate parseIsoDate(String raw) {
        if (raw == null || raw.length() < 10) return null;
        Matcher m = DATE_ISO.matcher(raw);
        return m.find() ? toDate(m.group(1), m.group(2), m.group(3)) : null;
    }

    private static LocalDate toDate(String year, String month, String day) {
        try {
            int y = Integer.parseInt(year), mo = Integer.parseInt(month), d = Integer.parseInt(day);
            if (y < 2000 || y > 2100 || mo < 1 || mo > 12 || d < 1 || d > 31) return null;
            return LocalDate.of(y, mo, d);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String firstMatch(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
