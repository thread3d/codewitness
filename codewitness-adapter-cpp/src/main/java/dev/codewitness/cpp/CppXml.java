package dev.codewitness.cpp;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the XML documents the C and C++ toolchain produces and consumes.
 *
 * <p>Two of them matter: the JUnit report CTest writes, and the CMake file lists the detector reads as
 * text. The JDK's own parser is used rather than a dependency, with the hardening every other adapter in
 * this codebase applies: the document is treated as hostile input from the project under verification, so
 * DOCTYPE declarations are stripped and external entity resolution is disabled. A document that still
 * fails to parse is reported by the caller as unreadable rather than repaired.
 */
public final class CppXml {

    private CppXml() {
    }

    /**
     * Parses an XML file produced by the project under verification.
     *
     * @param file the document
     * @return the parsed document
     * @throws Exception when the file cannot be read or is not well formed
     */
    public static Document parse(Path file) throws Exception {
        String content = Files.readString(file, StandardCharsets.UTF_8);
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
     * @return every descendant element with the given tag name, in document order.
     *
     * <p>A JUnit document may or may not wrap its suites in a {@code testsuites} element and may or may not
     *     carry a namespace, so the query is by local name and over the whole subtree rather than by the
     *     document's top-level shape.
     */
    public static List<Element> descendants(Element parent, String tag) {
        List<Element> elements = new ArrayList<>();
        if (parent == null) {
            return elements;
        }
        collect(parent, tag, elements);
        return elements;
    }

    private static void collect(Element parent, String tag, List<Element> elements) {
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            if (tag == null || tag.equals(element.getTagName()) || tag.equals(element.getLocalName())) {
                elements.add(element);
            }
            collect(element, tag, elements);
        }
    }

    /** @return the first descendant with the given tag name, or {@code null}. */
    public static Element firstDescendant(Element parent, String tag) {
        List<Element> found = descendants(parent, tag);
        return found.isEmpty() ? null : found.getFirst();
    }

    /** @return the text of the first descendant with the given tag name, or {@code null}. */
    public static String text(Element parent, String tag) {
        Element element = firstDescendant(parent, tag);
        return element == null ? null : element.getTextContent();
    }
}
