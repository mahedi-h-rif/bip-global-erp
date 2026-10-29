package com.fatoora.zatca.xml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.Optional;

/** Reads values back out of a (for example, ZATCA-cleared) UBL invoice. */
public final class UblReader {

    private UblReader() {}

    /** The QR payload embedded in the invoice, if any. Cleared invoices carry ZATCA's own QR. */
    public static Optional<String> qrCode(String xml) {
        return attachment(xml, "QR");
    }

    public static Optional<String> attachment(String xml, String id) {
        Document doc = parse(xml);
        NodeList refs = doc.getElementsByTagNameNS(Ns.CAC, "AdditionalDocumentReference");
        for (int i = 0; i < refs.getLength(); i++) {
            Element ref = (Element) refs.item(i);
            Element idEl = firstChild(ref, Ns.CBC, "ID");
            if (idEl == null || !id.equals(idEl.getTextContent().trim())) continue;
            NodeList bins = ref.getElementsByTagNameNS(Ns.CBC, "EmbeddedDocumentBinaryObject");
            if (bins.getLength() > 0) return Optional.of(bins.item(0).getTextContent().trim());
        }
        return Optional.empty();
    }

    private static Element firstChild(Element parent, String ns, String local) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName())) return e;
        }
        return null;
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid invoice XML", e);
        }
    }
}
