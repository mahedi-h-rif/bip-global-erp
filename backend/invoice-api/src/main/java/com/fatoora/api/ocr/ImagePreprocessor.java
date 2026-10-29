package com.fatoora.api.ocr;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

/**
 * Turns whatever the admin uploaded into something Tesseract can actually read.
 *
 * <p>Tesseract expects flat, high-contrast text at roughly 300 DPI. What arrives here is usually a
 * phone photo of a card held at an angle under office lighting — a few hundred pixels of text with
 * glare on one half. Feeding that in raw is the main reason fields come back empty, so every pass
 * goes through here first: optional crop to the region of interest, greyscale, upscale to a
 * workable character height, and (for the MRZ) Sauvola adaptive thresholding, which — unlike the
 * global Otsu threshold Tesseract applies internally — survives a photo that is brighter on one
 * side than the other.
 *
 * <p>PDF uploads are rendered to an image here too, so a scanned-to-PDF passport works the same as
 * a photo of one.
 */
@Component
public class ImagePreprocessor {

    /** Tesseract wants ~300 DPI; upscaling a small photo to this width gets character heights into that range. */
    private static final int TARGET_MIN_WIDTH = 2000;

    /** Beyond this the OCR gets slower without getting better, and memory use stops being trivial. */
    private static final int MAX_WIDTH = 4000;

    /** Sauvola window is a fraction of image height: big enough to cover a character, small enough to track lighting. */
    private static final double SAUVOLA_WINDOW_FRACTION = 0.06;
    private static final int SAUVOLA_MIN_WINDOW = 15;
    private static final double SAUVOLA_K = 0.2;
    private static final double SAUVOLA_R = 128.0;

    /** PDFs render at this DPI before going down the same path as an uploaded photo. */
    private static final int PDF_RENDER_DPI = 300;

    /**
     * Decodes an upload to a raster image. Handles PDFs (first page) as well as anything ImageIO
     * knows, and returns null rather than throwing when the bytes are not a usable image.
     */
    public BufferedImage decode(byte[] bytes, String contentType) throws IOException {
        if (isPdf(bytes, contentType)) {
            try (PDDocument document = Loader.loadPDF(bytes)) {
                if (document.getNumberOfPages() == 0) {
                    return null;
                }
                return new PDFRenderer(document).renderImageWithDPI(0, PDF_RENDER_DPI, ImageType.RGB);
            }
        }
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    /**
     * Crops to a horizontal band of the source, expressed as fractions of total height — used to
     * isolate the MRZ strip at the bottom of a passport photo page. {@code 0..1} returns the whole
     * image.
     */
    public BufferedImage cropBand(BufferedImage source, double topFraction, double bottomFraction) {
        int height = source.getHeight();
        int top = (int) Math.floor(Math.max(0, Math.min(1, topFraction)) * height);
        int bottom = (int) Math.ceil(Math.max(0, Math.min(1, bottomFraction)) * height);
        if (bottom - top < 8) {
            return source;
        }
        return source.getSubimage(0, top, source.getWidth(), bottom - top);
    }

    /** Greyscale + upscale. The general-purpose path: leaves binarization to Tesseract own Otsu. */
    public BufferedImage normalize(BufferedImage source) {
        return upscale(toGreyscale(source));
    }

    /**
     * Greyscale + upscale + Sauvola adaptive threshold. Use where the text is known to be a dense
     * uniform block (the MRZ) — on a full card the aggressive thresholding can eat faint print.
     */
    public BufferedImage binarize(BufferedImage source) {
        return sauvola(upscale(toGreyscale(source)));
    }

    /** PNG bytes, because it is lossless — JPEG artifacts around glyph edges cost real accuracy. */
    public byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private boolean isPdf(byte[] bytes, String contentType) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("pdf")) {
            return true;
        }
        // Sniff the magic bytes too: browsers sometimes send application/octet-stream for a PDF.
        return bytes.length >= 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
    }

    private BufferedImage toGreyscale(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            return source;
        }
        BufferedImage grey = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = grey.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return grey;
    }

    private BufferedImage upscale(BufferedImage source) {
        int width = source.getWidth();
        if (width >= TARGET_MIN_WIDTH) {
            return width > MAX_WIDTH ? scaleTo(source, MAX_WIDTH) : copyGrey(source);
        }
        return scaleTo(source, Math.min(TARGET_MIN_WIDTH, MAX_WIDTH));
    }

    /**
     * Sauvola below writes through the backing byte array, so it needs a contiguous TYPE_BYTE_GRAY
     * raster it owns — a sub-image from {@link #cropBand} shares its parent buffer and would have a
     * row stride wider than its own width.
     */
    private BufferedImage copyGrey(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }

    private BufferedImage scaleTo(BufferedImage source, int targetWidth) {
        int targetHeight = Math.max(1, (int) Math.round(source.getHeight() * (targetWidth / (double) source.getWidth())));
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    /**
     * Sauvola adaptive thresholding over integral images (so it stays O(pixels) regardless of the
     * window size): each pixel is compared against a threshold derived from the local mean and
     * standard deviation rather than one global cutoff.
     */
    private BufferedImage sauvola(BufferedImage grey) {
        int width = grey.getWidth();
        int height = grey.getHeight();
        byte[] pixels = ((DataBufferByte) grey.getRaster().getDataBuffer()).getData();

        // +1 row/column of zeroes so the area lookup below needs no bounds special-casing.
        int stride = width + 1;
        long[] sum = new long[stride * (height + 1)];
        long[] sumSquares = new long[stride * (height + 1)];
        for (int y = 0; y < height; y++) {
            long rowSum = 0;
            long rowSumSquares = 0;
            for (int x = 0; x < width; x++) {
                int value = pixels[y * width + x] & 0xFF;
                rowSum += value;
                rowSumSquares += (long) value * value;
                int idx = (y + 1) * stride + (x + 1);
                sum[idx] = sum[idx - stride] + rowSum;
                sumSquares[idx] = sumSquares[idx - stride] + rowSumSquares;
            }
        }

        int window = Math.max(SAUVOLA_MIN_WINDOW, (int) (height * SAUVOLA_WINDOW_FRACTION));
        if (window % 2 == 0) {
            window++;
        }
        int radius = window / 2;

        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] outPixels = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < height; y++) {
            int y0 = Math.max(0, y - radius);
            int y1 = Math.min(height - 1, y + radius);
            for (int x = 0; x < width; x++) {
                int x0 = Math.max(0, x - radius);
                int x1 = Math.min(width - 1, x + radius);
                long area = (long) (x1 - x0 + 1) * (y1 - y0 + 1);

                long areaSum = areaOf(sum, stride, x0, y0, x1, y1);
                long areaSumSquares = areaOf(sumSquares, stride, x0, y0, x1, y1);
                double mean = areaSum / (double) area;
                double variance = Math.max(0, (areaSumSquares / (double) area) - (mean * mean));
                double threshold = mean * (1 + SAUVOLA_K * ((Math.sqrt(variance) / SAUVOLA_R) - 1));

                int value = pixels[y * width + x] & 0xFF;
                outPixels[y * width + x] = (byte) (value > threshold ? 0xFF : 0x00);
            }
        }
        return out;
    }

    private long areaOf(long[] integral, int stride, int x0, int y0, int x1, int y1) {
        return integral[(y1 + 1) * stride + (x1 + 1)]
                - integral[y0 * stride + (x1 + 1)]
                - integral[(y1 + 1) * stride + x0]
                + integral[y0 * stride + x0];
    }
}
