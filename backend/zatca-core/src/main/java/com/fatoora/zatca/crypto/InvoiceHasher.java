package com.fatoora.zatca.crypto;

import com.fatoora.zatca.xml.Ns;
import org.apache.xml.security.c14n.Canonicalizer;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes the ZATCA invoice hash:
 * remove {@code ext:UBLExtensions}, {@code cac:Signature} and the QR {@code cac:AdditionalDocumentReference},
 * canonicalise with C14N 1.1, SHA-256, base64.
 * <p>
 * This mirrors the transforms declared in the signature's {@code invoiceSignedData} reference.
 */
public final class InvoiceHasher {

    static {
        org.apache.xml.security.Init.init();
    }

    private InvoiceHasher() {}

    public static String hash(String invoiceXml) {
        return Hashing.base64Sha256(canonicalForm(invoiceXml));
    }

    /** The exact bytes that are hashed; exposed for diagnostics. */
    public static byte[] canonicalForm(String invoiceXml) {
        try {
            Document doc = parse(invoiceXml);
            Element root = doc.getDocumentElement();
            List<Node> remove = new ArrayList<>();
            for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element e)) continue;
                if (is(e, Ns.EXT, "UBLExtensions") || is(e, Ns.CAC, "Signature") || isQrReference(e)) remove.add(e);
            }
            remove.forEach(root::removeChild);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Canonicalizer.getInstance(Canonicalizer.ALGO_ID_C14N11_OMIT_COMMENTS).canonicalizeSubtree(doc, out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot canonicalise invoice XML", e);
        }
    }

    private static boolean isQrReference(Element e) {
        if (!is(e, Ns.CAC, "AdditionalDocumentReference")) return false;
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && is(c, Ns.CBC, "ID")) return "QR".equals(c.getTextContent().trim());
        }
        return false;
    }

    private static boolean is(Element e, String ns, String localName) {
        return ns.equals(e.getNamespaceURI()) && localName.equals(e.getLocalName());
    }

    static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new InputSource(new StringReader(xml)));
    }
}
