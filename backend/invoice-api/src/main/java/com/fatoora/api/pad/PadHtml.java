package com.fatoora.api.pad;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cleans the editor's HTML down to the formatting the pad supports and returns it as XHTML for the PDF
 * renderer. Scripts, links, outside images and unknown styles are dropped; pasted images stay only as data URIs.
 */
public final class PadHtml {

    private static final Safelist SAFE = new Safelist()
            .addTags("p", "div", "br", "span", "b", "strong", "i", "em", "u", "s", "strike", "sub", "sup",
                    "h1", "h2", "h3", "ul", "ol", "li", "blockquote", "hr", "font",
                    "table", "thead", "tbody", "tr", "th", "td", "img")
            .addAttributes(":all", "style", "dir", "align")
            .addAttributes("font", "color", "size")
            .addAttributes("td", "colspan", "rowspan")
            .addAttributes("th", "colspan", "rowspan")
            .addAttributes("img", "src", "width", "height", "alt")
            .addProtocols("img", "src", "data");

    /** Allowed CSS properties and the shape their value must have. */
    private static final Map<String, Pattern> CSS = Map.of(
            "text-align", Pattern.compile("left|right|center|justify|start|end"),
            "direction", Pattern.compile("ltr|rtl"),
            "color", Pattern.compile("#[0-9a-f]{3,8}|rgba?\\([0-9.,\\s%]+\\)|[a-z]+"),
            "background-color", Pattern.compile("#[0-9a-f]{3,8}|rgba?\\([0-9.,\\s%]+\\)|[a-z]+"),
            "font-size", Pattern.compile("[0-9.]+(pt|px|em|%)|small|medium|large|x-large|xx-large|x-small"),
            "font-weight", Pattern.compile("bold|normal|[1-9]00"),
            "font-style", Pattern.compile("italic|normal"),
            "text-decoration", Pattern.compile("[a-z\\s-]+"),
            "text-decoration-line", Pattern.compile("[a-z\\s-]+"),
            "margin-left", Pattern.compile("[0-9.]+(pt|px|em)"));

    /** {@code <font size>} 1–7 as point sizes. */
    private static final String[] FONT_SIZE = {"7pt", "8pt", "9.5pt", "11pt", "13.5pt", "18pt", "24pt"};

    private PadHtml() {
    }

    /** The cleaned editor HTML, for storing and showing again in the editor. */
    public static String clean(String html) {
        return toDocument(html).body().html();
    }

    /** The cleaned HTML as well-formed XHTML for the letterhead PDF. */
    public static String xhtml(String html) {
        Document doc = toDocument(html);
        // <font> from the editor's colour and size buttons becomes a styled span the PDF renderer understands.
        for (Element f : doc.select("font")) {
            List<String> style = new ArrayList<>();
            if (f.hasAttr("color")) style.add("color:" + f.attr("color"));
            if (f.hasAttr("size")) {
                try {
                    int n = Math.max(1, Math.min(7, Integer.parseInt(f.attr("size").strip())));
                    style.add("font-size:" + FONT_SIZE[n - 1]);
                } catch (NumberFormatException ignored) {
                    // Unknown size: leave the text at the normal size.
                }
            }
            if (f.hasAttr("style")) style.add(f.attr("style"));
            f.tagName("span").clearAttributes();
            if (!style.isEmpty()) f.attr("style", String.join(";", style));
        }
        for (Element el : doc.select("[align]")) {
            String a = el.attr("align").toLowerCase(Locale.ROOT);
            el.removeAttr("align");
            if (a.matches("left|right|center|justify")) el.attr("style", ("text-align:" + a + ";" + el.attr("style")));
        }
        for (Element t : doc.select("table")) {
            t.attr("style", "width:100%;border-collapse:collapse;margin:4px 0 8px 0;" + t.attr("style"));
        }
        for (Element c : doc.select("td, th")) {
            c.attr("style", "border:1px solid #9aa3b5;padding:4px 6px;vertical-align:top;" + c.attr("style"));
        }
        for (Element img : doc.select("img")) {
            // Never wider than the writing area of the page.
            img.attr("style", "max-width:100%;" + img.attr("style"));
        }
        doc.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .escapeMode(Entities.EscapeMode.xhtml)
                .prettyPrint(false);
        return doc.body().html();
    }

    /** True when the document has no text, image, table or line. */
    public static boolean isEmpty(String html) {
        Document doc = toDocument(html);
        return doc.body().text().isBlank() && doc.select("img, table, hr").isEmpty();
    }

    private static Document toDocument(String html) {
        Document doc = new Cleaner(SAFE).clean(Jsoup.parseBodyFragment(html == null ? "" : html));
        for (Element el : doc.select("[style]")) {
            String style = style(el.attr("style"));
            if (style.isEmpty()) el.removeAttr("style");
            else el.attr("style", style);
        }
        for (Element el : doc.select("[dir]")) {
            if (!el.attr("dir").matches("ltr|rtl|auto")) el.removeAttr("dir");
        }
        return doc;
    }

    /** Keeps only the allowed declarations of an inline style. */
    static String style(String css) {
        List<String> kept = new ArrayList<>();
        for (String decl : css.split(";")) {
            int colon = decl.indexOf(':');
            if (colon < 0) continue;
            String prop = decl.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = decl.substring(colon + 1).strip().toLowerCase(Locale.ROOT);
            Pattern allowed = CSS.get(prop);
            if (allowed != null && allowed.matcher(value).matches()) kept.add(prop + ":" + value);
        }
        return String.join(";", kept);
    }
}
