package com.fatoora.api.ocr;

import java.util.Optional;

/** Runs OCR against an uploaded image and returns the raw recognized text. */
public interface OcrService {

    /** Empty means OCR could not run (e.g. the tesseract binary is unavailable) — never throws for that case. */
    default Optional<String> extractText(OcrInput image) {
        return extractText(image, OcrMode.DOCUMENT);
    }

    /**
     * Same contract, but tuned for what the caller is looking for — see {@link OcrMode}. The MRZ
     * mode reads only the bottom strip of the image, so its text is useless for anything else.
     */
    Optional<String> extractText(OcrInput image, OcrMode mode);
}
