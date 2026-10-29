package com.fatoora.api.ocr;

/** An uploaded scan or photo handed to OCR: its bytes, content type and file name (for logs only). */
public record OcrInput(byte[] bytes, String contentType, String name) {}
