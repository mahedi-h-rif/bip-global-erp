package com.fatoora.api.ocr;

/**
 * Which kind of text a given OCR pass is after. The two want opposite preprocessing: a whole ID
 * card needs its layout analysed and its greys left alone, while an MRZ band is a single block of
 * a known 37-character alphabet that benefits from hard binarization and a character whitelist.
 */
public enum OcrMode {

    /** The whole document — layout analysis on, no character restrictions. */
    DOCUMENT,

    /** Just the machine-readable zone strip at the bottom of a passport. */
    MRZ
}
