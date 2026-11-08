package com.fatoora.api.pad;

import com.fatoora.api.organization.Organization;
import com.fatoora.api.pdf.SkylineLetterhead;
import org.apache.poi.util.Units;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.xmlbeans.XmlException;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTAnchor;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTInline;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDrawing;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcBorders;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.Base64;

/**
 * The SKYLINE letterhead in Word: the whole-page backdrop (stripes, ornament, skyline, footer band) floats behind
 * the text from the page header, so it repeats on every page, and the company details are ordinary header and
 * footer text laid over it. Mirrors {@link SkylineLetterhead}.
 */
final class SkylineWord {

    private static final String NAVY = SkylineLetterhead.NAVY.substring(1), BLUE = SkylineLetterhead.BLUE.substring(1);
    private static final String TEXT = "3C4A5E";
    /** Twips per millimetre. */
    private static final double MM = 56.6929;
    /** Content width: A4 minus 15 mm each side. */
    private static final int WIDTH = (int) Math.round(180 * MM);

    private SkylineWord() {
    }

    static void apply(XWPFDocument doc, Organization org) {
        page(doc);
        header(doc, org);
        footer(doc, org);
        refLine(doc);
    }

    private static void page(XWPFDocument doc) {
        CTSectPr sect = doc.getDocument().getBody().isSetSectPr() ? doc.getDocument().getBody().getSectPr()
                : doc.getDocument().getBody().addNewSectPr();
        CTPageSz size = sect.isSetPgSz() ? sect.getPgSz() : sect.addNewPgSz();
        size.setW(BigInteger.valueOf(11906));
        size.setH(BigInteger.valueOf(16838));
        CTPageMar mar = sect.isSetPgMar() ? sect.getPgMar() : sect.addNewPgMar();
        mar.setTop(twips(41));
        mar.setBottom(twips(46));
        mar.setLeft(twips(15));
        mar.setRight(twips(15));
        mar.setHeader(twips(8));
        mar.setFooter(twips(6));
    }

    private static void header(XWPFDocument doc, Organization org) {
        XWPFHeader h = doc.createHeader(HeaderFooterType.DEFAULT);
        XWPFTable t = h.createTable(1, 3);
        PadWord.noBorders(t);
        t.setWidth(String.valueOf(WIDTH));
        int side = (int) Math.round(WIDTH * 0.38), mid = WIDTH - 2 * side;
        PadWord.width(t.getRow(0).getCell(0), side);
        PadWord.width(t.getRow(0).getCell(1), mid);
        PadWord.width(t.getRow(0).getCell(2), side);

        // Left: the Arabic name (or the English one) with the slogan under it.
        XWPFTableCell left = t.getRow(0).getCell(0);
        XWPFParagraph nameP = left.getParagraphs().get(0);
        tight(nameP);
        nameP.setSpacingBefore(60);
        boolean ar = !PadWord.nz(org.getLegalNameAr()).isBlank();
        XWPFRun name = PadWord.run(nameP, 15, NAVY);
        name.setBold(true);
        name.setText(ar ? org.getLegalNameAr() : PadWord.nz(org.getLegalNameEn()));
        if (ar) arabic(name, true);
        String tag = PadWord.nz(org.getTagline()).isBlank() ? (ar ? org.getLegalNameEn() : null) : org.getTagline();
        if (tag != null && !tag.isBlank()) {
            for (String line : tag.strip().split("\\R")) {
                XWPFParagraph p = left.addParagraph();
                tight(p);
                XWPFRun r = PadWord.run(p, 9.5, BLUE);
                r.setBold(true);
                r.setText(line.strip());
            }
        }

        // Middle: the logo.
        XWPFParagraph logoP = t.getRow(0).getCell(1).getParagraphs().get(0);
        logoP.setAlignment(ParagraphAlignment.CENTER);
        tight(logoP);
        logo(logoP, org);

        // Right: phone, email and website, each with its icon.
        XWPFTableCell right = t.getRow(0).getCell(2);
        boolean first = true;
        String[][] contacts = {{org.getPhone(), "icon-phone.png"}, {org.getEmail(), "icon-mail.png"}, {org.getWebsite(), "icon-web.png"}};
        for (String[] c : contacts) {
            if (c[0] == null || c[0].isBlank()) continue;
            XWPFParagraph p = first ? right.getParagraphs().get(0) : right.addParagraph();
            first = false;
            p.setAlignment(ParagraphAlignment.RIGHT);
            tight(p);
            p.setSpacingBefore(40);
            p.setSpacingAfter(60);
            XWPFRun r = PadWord.run(p, 8.5, NAVY);
            r.setBold(true);
            r.setText(c[0].strip() + "  ");
            icon(p, c[1], 10, 8.2);
        }

        backdrop(h.createParagraph(), org);
    }

    private static void footer(XWPFDocument doc, Organization org) {
        XWPFFooter f = doc.createFooter(HeaderFooterType.DEFAULT);
        XWPFTable t = f.createTable(1, 3);
        PadWord.noBorders(t);
        t.setWidth(String.valueOf(WIDTH));
        int pin = twips(7).intValue(), ids = twips(58).intValue();
        PadWord.width(t.getRow(0).getCell(0), pin);
        PadWord.width(t.getRow(0).getCell(1), WIDTH - pin - ids);
        PadWord.width(t.getRow(0).getCell(2), ids);

        XWPFParagraph pinP = t.getRow(0).getCell(0).getParagraphs().get(0);
        tight(pinP);
        icon(pinP, "icon-pin.png", 9.5, 12.2);

        XWPFTableCell addr = t.getRow(0).getCell(1);
        String address = PadWord.joined("، ", org.getStreet(), org.getDistrict(), org.getCity());
        XWPFParagraph a1 = addr.getParagraphs().get(0);
        tight(a1);
        if (!address.isEmpty()) arabic(PadWord.run(a1, 8.5, TEXT), false).setText(address);
        String city = PadWord.nz(org.getCity());
        boolean arabicCity = city.codePoints().anyMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.ARABIC);
        XWPFParagraph a2 = addr.addParagraph();
        tight(a2);
        PadWord.run(a2, 8.5, TEXT).setText(PadWord.joined(" · ",
                org.getBuildingNumber() == null ? null : "Bldg " + org.getBuildingNumber(),
                org.getPostalCode() == null ? null : "Postal Code " + org.getPostalCode(),
                city.isBlank() || arabicCity ? "KSA" : city + ", KSA"));

        XWPFTableCell idCell = t.getRow(0).getCell(2);
        CTTcPr pr = idCell.getCTTc().isSetTcPr() ? idCell.getCTTc().getTcPr() : idCell.getCTTc().addNewTcPr();
        CTTcBorders b = pr.isSetTcBorders() ? pr.getTcBorders() : pr.addNewTcBorders();
        CTBorder l = b.addNewLeft();
        l.setVal(STBorder.SINGLE);
        l.setSz(BigInteger.valueOf(6));
        l.setColor("9FB6CC");
        idLine(idCell.getParagraphs().get(0), "VAT", org.getVatNumber());
        idLine(idCell.addParagraph(), "CR", org.getOtherId());
    }

    private static void idLine(XWPFParagraph p, String label, String value) {
        p.setAlignment(ParagraphAlignment.RIGHT);
        tight(p);
        XWPFRun l = PadWord.run(p, 8.5, NAVY);
        l.setBold(true);
        l.setText(label + " ");
        PadWord.run(p, 8.5, TEXT).setText(PadWord.nz(value));
    }

    /** "Ref. No. ____ الرقم    Date ____ التاريخ" at the top of the letter, to fill in by hand. */
    private static void refLine(XWPFDocument doc) {
        XWPFTable t = doc.createTable(1, 7);
        PadWord.noBorders(t);
        t.setWidth(String.valueOf(WIDTH));
        String[] text = {"Ref. No.", "", "الرقم", "", "Date", "", "التاريخ"};
        int[] w = {14, 52, 14, 20, 12, 54, 14};
        for (int i = 0; i < 7; i++) {
            XWPFTableCell c = t.getRow(0).getCell(i);
            PadWord.width(c, twips(w[i]).intValue());
            XWPFParagraph p = c.getParagraphs().get(0);
            tight(p);
            if (i == 2 || i == 6) p.setAlignment(ParagraphAlignment.RIGHT);
            if (!text[i].isEmpty()) {
                XWPFRun r = PadWord.run(p, 9, "4B5668");
                r.setText(text[i]);
                if (i == 2 || i == 6) arabic(r, false);
            }
            if (i == 1 || i == 5) {
                CTTcPr pr = c.getCTTc().isSetTcPr() ? c.getCTTc().getTcPr() : c.getCTTc().addNewTcPr();
                CTBorder bottom = (pr.isSetTcBorders() ? pr.getTcBorders() : pr.addNewTcBorders()).addNewBottom();
                bottom.setVal(STBorder.DOTTED);
                bottom.setSz(BigInteger.valueOf(6));
                bottom.setColor("9AA3B5");
            }
        }
        doc.createParagraph();
    }

    /** The A4 backdrop, then the faint logo watermark, as pictures behind the text, placed on the page. */
    private static void backdrop(XWPFParagraph p, Organization org) {
        tight(p);
        try (InputStream in = asset("page-skyline.png")) {
            behind(p, in.readAllBytes(), "Letterhead backdrop", 0, 0, 210, 297, 0);
        } catch (Exception e) {
            // No backdrop rather than no pad.
        }
        SkylineLetterhead.Watermark wm = SkylineLetterhead.watermarkImage(org);
        if (wm != null)
            behind(p, wm.png(), "Logo watermark", (210 - wm.widthMm()) / 2, 148 - wm.heightMm() / 2,
                    wm.widthMm(), wm.heightMm(), 1);
    }

    /** A PNG floating behind the text at ({@code x}, {@code y}) mm from the page's top-left corner. */
    private static void behind(XWPFParagraph p, byte[] png, String name, double x, double y, double w, double h, int z) {
        XWPFRun r = p.createRun();
        long cx = emu(w), cy = emu(h);
        try {
            r.addPicture(new ByteArrayInputStream(png), Document.PICTURE_TYPE_PNG, name, (int) cx, (int) cy);
        } catch (Exception e) {
            return;
        }
        CTDrawing drawing = r.getCTR().getDrawingArray(0);
        CTInline inline = drawing.getInlineArray(0);
        try {
            // An xml-fragment carries the anchor's attributes and children without a nested <wp:anchor>.
            CTAnchor anchor = CTAnchor.Factory.parse("""
                    <xml-fragment xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
                        distT="0" distB="0" distL="0" distR="0" simplePos="0" relativeHeight="%d"
                        behindDoc="1" locked="1" layoutInCell="1" allowOverlap="1">
                      <wp:simplePos x="0" y="0"/>
                      <wp:positionH relativeFrom="page"><wp:posOffset>%d</wp:posOffset></wp:positionH>
                      <wp:positionV relativeFrom="page"><wp:posOffset>%d</wp:posOffset></wp:positionV>
                      <wp:extent cx="%d" cy="%d"/>
                      <wp:effectExtent l="0" t="0" r="0" b="0"/>
                      <wp:wrapNone/>
                      <wp:docPr id="%d" name="%s"/>
                      <wp:cNvGraphicFramePr/>
                    </xml-fragment>""".formatted(z, emu(x), emu(y), cx, cy, inline.getDocPr().getId(), name));
            anchor.setGraphic(inline.getGraphic());
            drawing.setAnchorArray(new CTAnchor[]{anchor});
            drawing.removeInline(0);
        } catch (XmlException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long emu(double mm) {
        return Math.round(mm * 36000);
    }

    private static void logo(XWPFParagraph p, Organization org) {
        if (org.getLogo() == null) return;
        byte[] bytes = Base64.getDecoder().decode(org.getLogo());
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) return;
            double h = 70, w = h * img.getWidth() / img.getHeight();
            if (w > 140) {
                h = h * 140 / w;
                w = 140;
            }
            int type = "image/png".equals(org.getLogoContentType()) ? Document.PICTURE_TYPE_PNG : Document.PICTURE_TYPE_JPEG;
            p.createRun().addPicture(new ByteArrayInputStream(bytes), type, "logo", Units.toEMU(w), Units.toEMU(h));
        } catch (Exception e) {
            // No logo rather than no pad.
        }
    }

    private static void icon(XWPFParagraph p, String name, double wPt, double hPt) {
        try (InputStream in = asset(name)) {
            p.createRun().addPicture(in, Document.PICTURE_TYPE_PNG, name, Units.toEMU(wPt), Units.toEMU(hPt));
        } catch (Exception e) {
            // An icon is decoration.
        }
    }

    /** Marks a run as right-to-left (Arabic), so Word shapes and orders it correctly. */
    private static XWPFRun arabic(XWPFRun r, boolean bold) {
        CTRPr pr = r.getCTR().isSetRPr() ? r.getCTR().getRPr() : r.getCTR().addNewRPr();
        pr.addNewRtl();
        if (bold) pr.addNewBCs();
        // Word sizes right-to-left text by the complex-script size, which is not set by setFontSize.
        Double size = r.getFontSizeAsDouble();
        // One point up: Arabic glyphs run small next to Latin ones at the same size.
        if (size != null) pr.addNewSzCs().setVal(BigInteger.valueOf(Math.round((size + 1) * 2)));
        return r;
    }

    private static void tight(XWPFParagraph p) {
        p.setSpacingAfter(0);
        p.setSpacingBefore(0);
    }

    private static BigInteger twips(double mm) {
        return BigInteger.valueOf(Math.round(mm * MM));
    }

    private static InputStream asset(String name) {
        InputStream in = SkylineWord.class.getResourceAsStream("/letterhead/" + name);
        if (in == null) throw new UncheckedIOException(new IOException("Missing letterhead asset " + name));
        return in;
    }
}
