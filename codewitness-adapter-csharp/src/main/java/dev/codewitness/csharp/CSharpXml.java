package dev.codewitness.csharp;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the XML documents the .NET toolchain produces and consumes.
 *
 * <p>Three of them matter: the project and solution files the detector reads, the TRX report
 * {@code dotnet test} writes, and the cobertura report coverlet writes. The JDK's own parser is used
 * rather than a dependency, with the hardening every other adapter in this codebase applies: the
 * document is treated as hostile input from the project under verification, so DOCTYPE declarations
 * are stripped and external entity resolution is disabled. A document that still fails to parse is
 * reported by the caller as unreadable rather than repaired.
 */
public final class CSharpXml {

    private CSharpXml() {
    }

    /**
     * Parses an XML file produced by the project under verification.
     *
     * @param file the document
     * @return the parsed document
     * @throws IOException when the file cannot be read
     * @throws javax.xml.parsers.ParserConfigurationException when the parser cannot be configured
     * @throws org.xml.sax.SAXException when the document is not well formed
     */
    public static Document parse(Path file) throws Exception {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        // The .NET SDK writes its TRX reports with a UTF-8 byte order mark. Read as UTF-8 that mark is a
        // U+FEFF at the start of the text, which a parser rejects with "content is not allowed in
        // prolog" - a failure that reads like a corrupt report and is really one character.
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
            content = content.substring(1);
        }
        // Security: strip any DOCTYPE before parsing as well as disallowing it, because a document that
        // declares internal entities is exactly the billion-laughs shape this guard exists for.
        String withoutDoctype = content.replaceAll("(?s)<!DOCTYPE[^>]*>", "");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(withoutDoctype)));
    }

    /** @return the attribute's value, or {@code null} when it is absent or empty. */
    public static String attribute(Element element, String name) {
        if (element == null) {
            return null;
        }
        String value = element.getAttribute(name);
        return value == null || value.isEmpty() ? null : value;
    }

    /** @return every direct child element with the given tag name, in document order. */
    public static List<Element> children(Element parent, String tag) {
        List<Element> elements = new ArrayList<>();
        if (parent == null) {
            return elements;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && (tag == null || tag.equals(element.getTagName())
                    || tag.equals(element.getLocalName()))) {
                elements.add(element);
            }
        }
        return elements;
    }

    /**
     * @return the first descendant with the given tag name, or {@code null}.
     *
     * <p>Used for the TRX report, whose elements are nested a few levels deep and whose namespace
     * prefixes are not stable enough to match on the qualified name alone.
     */
    public static Element firstDescendant(Element parent, String tag) {
        if (parent == null) {
            return null;
        }
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() > 0 && nodes.item(0) instanceof Element element) {
            return element;
        }
        NodeList anyNs = parent.getElementsByTagNameNS("*", tag);
        return anyNs.getLength() > 0 && anyNs.item(0) instanceof Element element ? element : null;
    }

    /** @return the text of the first descendant with the given tag name, or {@code null}. */
    public static String text(Element parent, String tag) {
        Element element = firstDescendant(parent, tag);
        return element == null ? null : element.getTextContent();
    }
}
