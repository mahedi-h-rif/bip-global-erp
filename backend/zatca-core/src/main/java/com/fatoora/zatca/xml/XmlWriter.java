package com.fatoora.zatca.xml;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Minimal streaming writer that emits compact XML (no indentation or whitespace text nodes).
 * Compact output matters: ZATCA's invoice hash is computed after removing the signature, QR and
 * extension elements, and with no surrounding whitespace the remaining tree is byte-identical
 * before and after signing.
 */
public final class XmlWriter {

    private final StringBuilder out = new StringBuilder(4096);
    private final Deque<String> open = new ArrayDeque<>();
    private boolean startTagOpen;

    public XmlWriter declaration() {
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        return this;
    }

    public XmlWriter start(String name) {
        closeStartTag();
        out.append('<').append(name);
        open.push(name);
        startTagOpen = true;
        return this;
    }

    public XmlWriter attr(String name, String value) {
        if (!startTagOpen) throw new IllegalStateException("Attributes must follow start()");
        out.append(' ').append(name).append("=\"").append(escape(value, true)).append('"');
        return this;
    }

    public XmlWriter text(String value) {
        closeStartTag();
        out.append(escape(value, false));
        return this;
    }

    /** Appends pre-built, already well-formed XML. */
    public XmlWriter raw(String xml) {
        closeStartTag();
        out.append(xml);
        return this;
    }

    public XmlWriter end() {
        String name = open.pop();
        if (startTagOpen) {
            out.append("/>");
            startTagOpen = false;
        } else {
            out.append("</").append(name).append('>');
        }
        return this;
    }

    /** {@code <name>value</name>}; skipped entirely when value is null or blank. */
    public XmlWriter element(String name, String value) {
        if (value == null || value.isBlank()) return this;
        return start(name).text(value).end();
    }

    public XmlWriter element(String name, String value, String attrName, String attrValue) {
        if (value == null || value.isBlank()) return this;
        return start(name).attr(attrName, attrValue).text(value).end();
    }

    @Override
    public String toString() {
        if (!open.isEmpty()) throw new IllegalStateException("Unclosed elements: " + open);
        return out.toString();
    }

    private void closeStartTag() {
        if (startTagOpen) {
            out.append('>');
            startTagOpen = false;
        }
    }

    public static String escape(String value, boolean attribute) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append(attribute ? "&quot;" : "\"");
                case '\r' -> sb.append("&#xD;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
