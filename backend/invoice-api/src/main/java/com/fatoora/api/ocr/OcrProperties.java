package com.fatoora.api.ocr;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "fatoora.ocr")
public class OcrProperties {

    /** Path to (or bare name of, if on PATH) the tesseract binary. */
    private String tesseractPath = "tesseract";

    /**
     * Tesseract {@code -l} value. Blank means auto: {@code eng+ara} when the Arabic data file is
     * installed (it is in the runtime image), {@code eng} alone otherwise. Set it explicitly to pin
     * the language set — e.g. adding a script for a non-Arabic market.
     */
    private String languages = "";

    /** Kill switch — when false, extraction always returns blank fields with a warning, no process is spawned. */
    private boolean enabled = true;
}
