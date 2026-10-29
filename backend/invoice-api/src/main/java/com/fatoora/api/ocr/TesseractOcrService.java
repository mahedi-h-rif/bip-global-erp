package com.fatoora.api.ocr;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Shells out to the {@code tesseract} CLI rather than binding to it via JNA/Tess4J — the runtime
 * image is Alpine (musl libc) and Tess4J bundled natives target glibc Linux, so the CLI (backed
 * by the {@code tesseract-ocr} apk package) is the reliable integration point.
 *
 * <p>Two things make the difference between "reads the card" and "returns nothing", and both live
 * here rather than in the parsers:
 *
 * <ul>
 *   <li><b>Preprocessing</b> ({@link ImagePreprocessor}) — a raw phone photo is far below the
 *       ~300 DPI flat-and-contrasty input Tesseract is tuned for.
 *   <li><b>Page segmentation</b> — an ID card is a multi-column layout with a photo in it, so one
 *       fixed {@code --psm} guesses wrong regularly. Each mode has an ordered list of passes and
 *       stops as soon as one produces a usable amount of text; anything a pass does return is
 *       merged, because the field heuristics downstream only benefit from more candidate lines.
 * </ul>
 *
 * <p>Any failure to run tesseract (binary missing, disabled, unreadable upload, process error) is
 * treated as "no text extracted" rather than an error — extraction is a best-effort prefill, never
 * a hard requirement for onboarding a client.
 */
@Component
@RequiredArgsConstructor
public class TesseractOcrService implements OcrService {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcrService.class);
    private static final Duration PASS_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration LIST_LANGS_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Wall-clock budget for one document. No further pass is started once it is spent, which bounds
     * a call to roughly this plus one pass timeout. Onboarding makes three of these calls (the
     * passport twice, the Iqama once), and the whole request still has to come back inside the
     * 120s {@code proxy_read_timeout} the API vhost sets.
     */
    private static final Duration DOCUMENT_BUDGET = Duration.ofSeconds(20);

    /** Below this many characters a pass is treated as a miss and the next segmentation mode is tried. */
    private static final int USABLE_TEXT_LENGTH = 80;

    /** The MRZ alphabet, per ICAO Doc 9303 — restricting it removes most of the chevron misreads. */
    private static final String MRZ_WHITELIST = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<";

    /** The MRZ occupies the bottom ~15% of a TD3 page; the wider band leaves room for a skewed photo. */
    private static final double MRZ_BAND_TOP = 0.62;

    private final OcrProperties properties;
    private final ImagePreprocessor preprocessor;

    /** Resolved once from {@code tesseract --list-langs} — null until the first call needs it. */
    private volatile Set<String> availableLanguages;

    @Override
    public Optional<String> extractText(OcrInput image, OcrMode mode) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }

        BufferedImage decoded;
        try {
            decoded = preprocessor.decode(image.bytes(), image.contentType());
        } catch (IOException | RuntimeException ex) {
            log.warn("ocr_decode_failed image={} reason={}", image.name(), ex.toString());
            return Optional.empty();
        }
        if (decoded == null) {
            log.warn("ocr_decode_empty image={}", image.name());
            return Optional.empty();
        }

        long deadline = System.nanoTime() + DOCUMENT_BUDGET.toNanos();
        return mode == OcrMode.MRZ ? runMrzPasses(decoded, deadline) : runDocumentPasses(decoded, deadline);
    }

    private boolean budgetSpent(long deadline) {
        if (System.nanoTime() < deadline) {
            return false;
        }
        log.warn("ocr_budget_exhausted budget_seconds={}", DOCUMENT_BUDGET.toSeconds());
        return true;
    }

    /**
     * Whole-card text. Starts with orientation detection when the OSD data file is installed (phone
     * photos of cards arrive rotated more often than not), then falls back through progressively
     * more rigid layout assumptions.
     */
    private Optional<String> runDocumentPasses(BufferedImage source, long deadline) {
        byte[] png = encode(preprocessor.normalize(source));
        if (png == null) {
            return Optional.empty();
        }

        List<Integer> segmentationModes = new ArrayList<>();
        if (hasLanguage("osd")) {
            segmentationModes.add(1);
        }
        segmentationModes.add(3);
        segmentationModes.add(4);
        segmentationModes.add(6);

        List<String> collected = new ArrayList<>();
        for (int psm : segmentationModes) {
            if (!collected.isEmpty() && budgetSpent(deadline)) {
                break;
            }
            Optional<String> text = runTesseract(png, psm, null);
            text.ifPresent(collected::add);
            if (text.filter(value -> countAlphanumeric(value) >= USABLE_TEXT_LENGTH).isPresent()) {
                break;
            }
        }

        String merged = mergeDistinctLines(collected);
        return merged.isBlank() ? Optional.empty() : Optional.of(merged);
    }

    /**
     * Just the machine-readable strip. Cropping to it first matters twice over: the adaptive
     * threshold adapts to the strip lighting rather than the whole page, and Tesseract stops trying
     * to fit the MRZ into the page layout it inferred from the photo and headings above it.
     */
    private Optional<String> runMrzPasses(BufferedImage source, long deadline) {
        List<String> collected = new ArrayList<>();

        byte[] band = encode(preprocessor.binarize(preprocessor.cropBand(source, MRZ_BAND_TOP, 1.0)));
        if (band != null) {
            // PSM 6 (uniform block) is right for the MRZ specifically: it is two lines of one font
            // at one size, which is exactly the case the other modes over-analyse.
            runTesseract(band, 6, MRZ_WHITELIST).ifPresent(collected::add);
        }

        // A cropped-tight upload (someone photographed only the MRZ) has no bottom band to speak of,
        // and a portrait-rotated page puts the MRZ somewhere other than the bottom — so if the band
        // gave us nothing MRZ-shaped, fall back to whitelisting the whole page.
        if (collected.stream().noneMatch(this::looksLikeMrz) && !budgetSpent(deadline)) {
            byte[] full = encode(preprocessor.binarize(source));
            if (full != null) {
                runTesseract(full, 6, MRZ_WHITELIST).ifPresent(collected::add);
            }
        }

        String merged = mergeDistinctLines(collected);
        return merged.isBlank() ? Optional.empty() : Optional.of(merged);
    }

    private Optional<String> runTesseract(byte[] png, int psm, String whitelist) {
        Path tempImage = null;
        try {
            tempImage = Files.createTempFile("ocr-", ".png");
            Files.write(tempImage, png);

            List<String> command = new ArrayList<>(List.of(
                    properties.getTesseractPath(), tempImage.toString(), "stdout",
                    "-l", languages(),
                    "--psm", String.valueOf(psm),
                    // The preprocessor upscales to roughly this, and stating it stops Tesseract
                    // guessing a DPI from the (meaningless) PNG metadata.
                    "--dpi", "300"));
            if (whitelist != null) {
                command.add("-c");
                command.add("tessedit_char_whitelist=" + whitelist);
            }

            return runProcess(command, PASS_TIMEOUT).filter(text -> !text.isBlank());
        } catch (IOException ex) {
            log.warn("ocr_unavailable psm={} reason={}", psm, ex.getMessage());
            return Optional.empty();
        } finally {
            deleteQuietly(tempImage);
        }
    }

    /**
     * Arabic is half of what is printed on a Saudi Iqama, and leaving it out does not merely lose
     * those fields — the unrecognised script comes back as noise that splits the Latin lines the
     * field heuristics rely on. Falls back to English alone when the data file is not installed.
     */
    private String languages() {
        String configured = properties.getLanguages();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return hasLanguage("ara") ? "eng+ara" : "eng";
    }

    private boolean hasLanguage(String language) {
        Set<String> languages = availableLanguages;
        if (languages == null) {
            synchronized (this) {
                if (availableLanguages == null) {
                    availableLanguages = discoverLanguages();
                }
                languages = availableLanguages;
            }
        }
        return languages.contains(language);
    }

    private Set<String> discoverLanguages() {
        Optional<String> output = runProcess(List.of(properties.getTesseractPath(), "--list-langs"), LIST_LANGS_TIMEOUT);
        if (output.isEmpty()) {
            log.warn("ocr_list_langs_failed path={}", properties.getTesseractPath());
            return Set.of();
        }
        Set<String> languages = output.get().lines()
                .map(String::trim)
                // The first line is a "List of available languages" header, not a language.
                .filter(line -> !line.isBlank() && !line.contains(" "))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        log.info("ocr_languages_available languages={}", languages);
        return languages;
    }

    private Optional<String> runProcess(List<String> command, Duration timeout) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(false).start();

            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("ocr_timeout command={}", command.get(0));
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                log.warn("ocr_nonzero_exit exit={} args={}", process.exitValue(), command.subList(1, command.size()));
                return Optional.empty();
            }
            return Optional.of(output);
        } catch (IOException ex) {
            log.warn("ocr_unavailable reason={}", ex.getMessage());
            return Optional.empty();
        } catch (InterruptedException ex) {
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private byte[] encode(BufferedImage image) {
        try {
            return preprocessor.toPng(image);
        } catch (IOException | RuntimeException ex) {
            log.warn("ocr_encode_failed reason={}", ex.toString());
            return null;
        }
    }

    /**
     * Passes see the same image, so they repeat most lines. Keeping one copy of each keeps the
     * "first match wins" heuristics downstream from being skewed by a duplicated line.
     */
    private String mergeDistinctLines(List<String> texts) {
        Set<String> lines = new LinkedHashSet<>();
        for (String text : texts) {
            text.lines().map(String::strip).filter(line -> !line.isBlank()).forEach(lines::add);
        }
        return String.join("\n", lines);
    }

    private boolean looksLikeMrz(String text) {
        return text.lines()
                .map(line -> line.replaceAll("[^A-Za-z0-9<]", ""))
                .anyMatch(line -> line.length() >= 28 && line.contains("<"));
    }

    private int countAlphanumeric(String text) {
        return (int) text.chars().filter(Character::isLetterOrDigit).count();
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup of a scratch temp file — leaving one stray file behind is not worth failing the request.
        }
    }
}
