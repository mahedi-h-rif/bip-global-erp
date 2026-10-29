package com.fatoora.api.pad;

import com.fatoora.api.organization.Organization;
import org.apache.poi.util.Units;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The letterhead pad as a Word document: logo, names and contacts in the page header, address and
 * registration in the footer, so anyone can type on it in Word, WPS or Google Docs and keep the letterhead.
 */
public final class PadWord {

    private static final String FONT = "Arial";
    private static final Pattern COLOR = Pattern.compile("color:\\s*#([0-9a-f]{6})");
    private static final Pattern RGB = Pattern.compile("color:\\s*rgb\\((\\d+),\\s*(\\d+),\\s*(\\d+)\\)");
    private static final Pattern SIZE_PT = Pattern.compile("font-size:\\s*([0-9.]+)pt");
    private static final Pattern SIZE_PX = Pattern.compile("font-size:\\s*([0-9.]+)px");
    private static final Pattern ALIGN = Pattern.compile("text-align:\\s*(left|right|center|justify|start|end)");
    private static final int[] FONT_SIZE = {7, 8, 10, 11, 14, 18, 24};

    private PadWord() {
    }

    /** A pad with {@code bodyHtml} (editor HTML, may be blank) under an optional title. */
    public static byte[] build(Organization org, String title, String bodyHtml) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            page(doc);
            String color = (org.getBrandColor() == null ? "#0B2A6F" : org.getBrandColor()).replace("#", "");
            header(doc, org, color);
            footer(doc, org, color);

            if (title != null && !title.isBlank()) {
                XWPFParagraph p = doc.createParagraph();
                p.setAlignment(ParagraphAlignment.CENTER);
                p.setSpacingAfter(160);
                XWPFRun r = run(p, 16, color);
                r.setBold(true);
                r.setText(title.strip());
            }
            if (bodyHtml != null && !PadHtml.isEmpty(bodyHtml)) {
                new Body(doc).blocks(Jsoup.parseBodyFragment(PadHtml.clean(bodyHtml)).body());
            } else {
                // An empty first line to start typing on.
                run(doc.createParagraph(), 11, null).setText("");
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- page, header and footer ------------------------------------------------------------------

    /** A4 with room for the header and footer. */
    private static void page(XWPFDocument doc) {
        CTSectPr sect = doc.getDocument().getBody().isSetSectPr() ? doc.getDocument().getBody().getSectPr()
                : doc.getDocument().getBody().addNewSectPr();
        CTPageSz size = sect.isSetPgSz() ? sect.getPgSz() : sect.addNewPgSz();
        size.setW(BigInteger.valueOf(11906));
        size.setH(BigInteger.valueOf(16838));
        CTPageMar mar = sect.isSetPgMar() ? sect.getPgMar() : sect.addNewPgMar();
        mar.setTop(BigInteger.valueOf(2400));
        mar.setBottom(BigInteger.valueOf(1600));
        mar.setLeft(BigInteger.valueOf(907));
        mar.setRight(BigInteger.valueOf(907));
        mar.setHeader(BigInteger.valueOf(400));
        mar.setFooter(BigInteger.valueOf(400));
    }

    private static void header(XWPFDocument doc, Organization org, String color) {
        XWPFHeader h = doc.createHeader(HeaderFooterType.DEFAULT);
        XWPFTable t = h.createTable(1, 3);
        noBorders(t);
        t.setWidth("100%");
        XWPFTableRow row = t.getRow(0);
        width(row.getCell(0), 1400);
        width(row.getCell(1), 5200);
        width(row.getCell(2), 3500);

        XWPFParagraph logo = row.getCell(0).getParagraphs().get(0);
        picture(logo, org);

        XWPFTableCell en = row.getCell(1);
        en.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
        XWPFRun name = run(en.getParagraphs().get(0), 14, color);
        name.setBold(true);
        name.setText(nz(org.getLegalNameEn()));
        line(en, joined(" · ", org.getEmail(), org.getWebsite()));
        line(en, org.getPhone() == null || org.getPhone().isBlank() ? null : "Mobile " + org.getPhone());

        XWPFTableCell ar = row.getCell(2);
        ar.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
        XWPFParagraph arP = ar.getParagraphs().get(0);
        arP.setAlignment(ParagraphAlignment.RIGHT);
        rtl(arP);
        XWPFRun arName = run(arP, 14, color);
        arName.setBold(true);
        arName.setText(nz(org.getLegalNameAr()));
        if (org.getCity() != null && !org.getCity().isBlank()) {
            XWPFParagraph city = ar.addParagraph();
            city.setAlignment(ParagraphAlignment.RIGHT);
            rtl(city);
            run(city, 8, "5A6275").setText(org.getCity());
        }

        // The coloured rule under the header.
        XWPFParagraph rule = h.createParagraph();
        rule.setSpacingAfter(0);
        CTPBdr bdr = rule.getCTP().addNewPPr().addNewPBdr();
        CTBorder bottom = bdr.addNewBottom();
        bottom.setVal(STBorder.SINGLE);
        bottom.setSz(BigInteger.valueOf(18));
        bottom.setColor(color);
    }

    private static void footer(XWPFDocument doc, Organization org, String color) {
        XWPFFooter f = doc.createFooter(HeaderFooterType.DEFAULT);
        XWPFParagraph p = f.createParagraph();
        CTBorder top = p.getCTP().addNewPPr().addNewPBdr().addNewTop();
        top.setVal(STBorder.SINGLE);
        top.setSz(BigInteger.valueOf(12));
        top.setColor(color);
        String address = joined("، ", org.getStreet(), org.getDistrict(), org.getCity());
        if (!address.isEmpty()) {
            XWPFRun r = run(p, 7.5, "5A6275");
            r.setText(address);
            r.getCTR().addNewRPr().addNewRtl();
            r.addBreak();
        }
        run(p, 7.5, "5A6275").setText(joined(" · ",
                org.getBuildingNumber() == null ? null : "Bldg " + org.getBuildingNumber(),
                org.getPostalCode() == null ? null : "Postal " + org.getPostalCode(),
                "VAT " + nz(org.getVatNumber()), "CR " + nz(org.getOtherId())));
    }

    private static void picture(XWPFParagraph p, Organization org) {
        if (org.getLogo() == null) return;
        byte[] bytes = Base64.getDecoder().decode(org.getLogo());
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) return;
            double h = 52, w = h * img.getWidth() / img.getHeight();
            if (w > 110) {
                h = h * 110 / w;
                w = 110;
            }
            int type = "image/png".equals(org.getLogoContentType()) ? Document.PICTURE_TYPE_PNG : Document.PICTURE_TYPE_JPEG;
            p.createRun().addPicture(new ByteArrayInputStream(bytes), type, "logo", Units.toEMU(w), Units.toEMU(h));
        } catch (Exception e) {
            // No logo rather than no pad.
        }
    }

    // --- body: editor HTML to Word paragraphs ------------------------------------------------------

    /** Walks the cleaned HTML and writes paragraphs, headings, lists and tables with their formatting. */
    private static final class Body {
        private final XWPFDocument doc;

        Body(XWPFDocument doc) {
            this.doc = doc;
        }

        void blocks(Element parent) {
            XWPFParagraph loose = null;
            for (Node n : parent.childNodes()) {
                if (n instanceof Element e && isBlock(e)) {
                    loose = null;
                    block(e);
                } else {
                    // Text sitting directly in the body (no <p>) collects into one paragraph.
                    if (n instanceof TextNode t && t.isBlank()) continue;
                    if (loose == null) loose = paragraph(parent);
                    inline(loose, n, new Fmt());
                }
            }
        }

        private void block(Element e) {
            switch (e.tagName()) {
                case "h1", "h2", "h3" -> {
                    XWPFParagraph p = paragraph(e);
                    p.setSpacingBefore(160);
                    Fmt f = new Fmt();
                    f.bold = true;
                    f.size = e.tagName().equals("h1") ? 16 : e.tagName().equals("h2") ? 13 : 11.5;
                    inlineChildren(p, e, f);
                }
                case "ul", "ol" -> {
                    int i = 1;
                    for (Element li : e.children()) {
                        if (!li.tagName().equals("li")) continue;
                        XWPFParagraph p = paragraph(li);
                        p.setIndentationLeft(360);
                        p.setIndentationHanging(260);
                        run(p, 11, null).setText(e.tagName().equals("ul") ? "•\t" : (i++) + ".\t");
                        inlineChildren(p, li, new Fmt());
                    }
                }
                case "table" -> table(e);
                case "hr" -> {
                    CTBorder b = doc.createParagraph().getCTP().addNewPPr().addNewPBdr().addNewBottom();
                    b.setVal(STBorder.SINGLE);
                    b.setSz(BigInteger.valueOf(6));
                    b.setColor("9AA3B5");
                }
                case "blockquote" -> {
                    XWPFParagraph p = paragraph(e);
                    p.setIndentationLeft(567);
                    inlineChildren(p, e, new Fmt());
                }
                default -> {
                    // <p>/<div>: a div holding blocks is just a wrapper.
                    if (e.children().stream().anyMatch(PadWord::isBlock)) {
                        blocks(e);
                    } else {
                        inlineChildren(paragraph(e), e, new Fmt());
                    }
                }
            }
        }

        private void table(Element table) {
            var rows = table.select("tr");
            if (rows.isEmpty()) return;
            int cols = rows.stream().mapToInt(r -> r.select("> td, > th").size()).max().orElse(1);
            XWPFTable t = doc.createTable(rows.size(), Math.max(1, cols));
            t.setWidth("100%");
            for (int r = 0; r < rows.size(); r++) {
                var cells = rows.get(r).select("> td, > th");
                for (int c = 0; c < cells.size() && c < cols; c++) {
                    XWPFTableCell cell = t.getRow(r).getCell(c);
                    Fmt f = new Fmt();
                    f.bold = cells.get(c).tagName().equals("th");
                    inlineChildren(cell.getParagraphs().get(0), cells.get(c), f);
                }
            }
            doc.createParagraph();
        }

        private XWPFParagraph paragraph(Element e) {
            XWPFParagraph p = doc.createParagraph();
            p.setSpacingAfter(120);
            String style = e.attr("style").toLowerCase(Locale.ROOT);
            Matcher m = ALIGN.matcher(style);
            String align = m.find() ? m.group(1) : e.attr("align").toLowerCase(Locale.ROOT);
            boolean rtl = "rtl".equals(e.attr("dir")) || style.contains("direction:rtl");
            if (rtl) rtl(p);
            switch (align) {
                case "center" -> p.setAlignment(ParagraphAlignment.CENTER);
                case "right" -> p.setAlignment(ParagraphAlignment.RIGHT);
                case "justify" -> p.setAlignment(ParagraphAlignment.BOTH);
                case "end" -> p.setAlignment(rtl ? ParagraphAlignment.LEFT : ParagraphAlignment.RIGHT);
                default -> {
                    if (rtl) p.setAlignment(ParagraphAlignment.RIGHT);
                }
            }
            return p;
        }

        private void inlineChildren(XWPFParagraph p, Element e, Fmt f) {
            for (Node n : e.childNodes()) inline(p, n, f);
        }

        private void inline(XWPFParagraph p, Node n, Fmt parent) {
            if (n instanceof TextNode t) {
                String text = t.getWholeText().replace(' ', ' ');
                if (text.isEmpty()) return;
                XWPFRun r = run(p, parent.size, parent.color);
                r.setBold(parent.bold);
                r.setItalic(parent.italic);
                if (parent.underline) r.setUnderline(UnderlinePatterns.SINGLE);
                if (parent.strike) r.setStrikeThrough(true);
                r.setText(text);
                return;
            }
            if (!(n instanceof Element e)) return;
            switch (e.tagName()) {
                case "br" -> p.createRun().addBreak();
                case "img" -> image(p, e);
                default -> {
                    Fmt f = parent.with(e);
                    if (isBlock(e)) {
                        // A block inside a list item or cell: start a new line in the same paragraph.
                        if (!p.getRuns().isEmpty()) p.createRun().addBreak();
                    }
                    inlineChildren(p, e, f);
                }
            }
        }

        private void image(XWPFParagraph p, Element img) {
            String src = img.attr("src");
            int comma = src.indexOf(',');
            if (!src.startsWith("data:image/") || comma < 0) return;
            try {
                byte[] bytes = Base64.getDecoder().decode(src.substring(comma + 1));
                BufferedImage bi = ImageIO.read(new ByteArrayInputStream(bytes));
                if (bi == null) return;
                double w = Math.min(450, bi.getWidth() * 0.75), h = w * bi.getHeight() / bi.getWidth();
                int type = src.startsWith("data:image/png") ? Document.PICTURE_TYPE_PNG : Document.PICTURE_TYPE_JPEG;
                p.createRun().addPicture(new ByteArrayInputStream(bytes), type, "image", Units.toEMU(w), Units.toEMU(h));
            } catch (Exception ignored) {
                // An image Word cannot take is left out.
            }
        }
    }

    /** Character formatting carried down the HTML tree. */
    private static final class Fmt {
        boolean bold, italic, underline, strike;
        double size = 11;
        String color;

        Fmt with(Element e) {
            Fmt f = new Fmt();
            f.bold = bold;
            f.italic = italic;
            f.underline = underline;
            f.strike = strike;
            f.size = size;
            f.color = color;
            switch (e.tagName()) {
                case "b", "strong" -> f.bold = true;
                case "i", "em" -> f.italic = true;
                case "u" -> f.underline = true;
                case "s", "strike" -> f.strike = true;
                case "font" -> {
                    if (e.hasAttr("color")) f.color = hex(e.attr("color"));
                    try {
                        f.size = FONT_SIZE[Math.max(1, Math.min(7, Integer.parseInt(e.attr("size").strip()))) - 1];
                    } catch (NumberFormatException ignored) {
                        // No size given.
                    }
                }
                default -> {
                }
            }
            String style = e.attr("style").toLowerCase(Locale.ROOT);
            if (style.contains("font-weight:bold") || style.matches(".*font-weight:[6-9]00.*")) f.bold = true;
            if (style.contains("font-style:italic")) f.italic = true;
            if (style.contains("underline")) f.underline = true;
            if (style.contains("line-through")) f.strike = true;
            Matcher m = COLOR.matcher(style.replace("background-color", "bg"));
            if (m.find()) f.color = m.group(1).toUpperCase(Locale.ROOT);
            m = RGB.matcher(style.replace("background-color", "bg"));
            if (m.find()) f.color = String.format("%02X%02X%02X", Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            m = SIZE_PT.matcher(style);
            if (m.find()) f.size = Double.parseDouble(m.group(1));
            m = SIZE_PX.matcher(style);
            if (m.find()) f.size = Double.parseDouble(m.group(1)) * 0.75;
            return f;
        }

        private static String hex(String c) {
            c = c.strip().replace("#", "");
            return c.matches("[0-9a-fA-F]{6}") ? c.toUpperCase(Locale.ROOT) : null;
        }
    }

    // --- helpers ------------------------------------------------------------------------------------

    static boolean isBlock(Element e) {
        return switch (e.tagName()) {
            case "p", "div", "h1", "h2", "h3", "ul", "ol", "table", "hr", "blockquote" -> true;
            default -> false;
        };
    }

    private static XWPFRun run(XWPFParagraph p, double size, String color) {
        XWPFRun r = p.createRun();
        r.setFontFamily(FONT);
        r.setFontSize(size);
        if (color != null) r.setColor(color);
        return r;
    }

    private static void line(XWPFTableCell cell, String text) {
        if (text == null || text.isBlank()) return;
        XWPFParagraph p = cell.addParagraph();
        p.setSpacingAfter(0);
        run(p, 8, "5A6275").setText(text);
    }

    private static void rtl(XWPFParagraph p) {
        CTPPr ppr = p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
        ppr.addNewBidi();
    }

    private static void width(XWPFTableCell cell, int twips) {
        CTTcPr pr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        CTTblWidth w = pr.addNewTcW();
        w.setType(STTblWidth.DXA);
        w.setW(BigInteger.valueOf(twips));
    }

    private static void noBorders(XWPFTable t) {
        t.setTopBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "FFFFFF");
        t.setBottomBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "FFFFFF");
        t.setLeftBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "FFFFFF");
        t.setRightBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "FFFFFF");
        t.setInsideHBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "FFFFFF");
        t.setInsideVBorder(XWPFTable.XWPFBorderType.NONE, 0, 0, "FFFFFF");
    }

    private static String joined(String sep, String... parts) {
        return Stream.of(parts).filter(p -> p != null && !p.isBlank()).collect(Collectors.joining(sep));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
