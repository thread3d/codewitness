package dev.codewitness.support;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * One hardened way to read an XML file a project produced.
 *
 * <p>Test reports, coverage reports and build files are XML written by the project under verification — that
 * is, by the code CodeWitness treats as hostile. Nine copies of this hardening existed across seven adapters
 * and they were not identical: they enabled the same features in different orders, and only some of them
 * stripped a {@code DOCTYPE} before parsing. A defence against billion-laughs and external-entity expansion
 * that has to be fixed in nine places is a defence with nine chances to be missed, which is why this is one
 * class rather than nine.
 *
 * <p>The hardening is deliberately belt and braces, because the two halves cover different parser paths:
 *
 * <ul>
 *   <li>{@code disallow-doctype-decl} makes a document that declares any DOCTYPE a parse error;</li>
 *   <li>the declared DOCTYPE is also removed from the text first, so a report that carries one - some test
 *       frameworks write {@code <!DOCTYPE testsuite>} out of habit - still parses instead of failing as
 *       though the tool had emitted something broken;</li>
 *   <li>external general/parameter entities and XInclude are off, and the external DTD and schema access is
 *       emptied, so nothing outside the file is ever fetched even if a future parser ignores the DOCTYPE
 *       flag.</li>
 * </ul>
 *
 * <p>The cost is that the whole document is held as a string. These are test and coverage reports, and the
 * callers that streamed them were reading them into memory a few lines later anyway.
 */
public final class XmlDocuments {

    private XmlDocuments() {
    }

    /**
     * Parses an XML file with external entity and DOCTYPE processing disabled.
     *
     * @param file the document
     * @return the parsed document
     * @throws IOException                   when the file cannot be read
     * @throws SAXException                  when it is not well formed
     * @throws ParserConfigurationException when the parser cannot be configured securely
     */
    public static Document parse(Path file) throws IOException, SAXException, ParserConfigurationException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
            content = content.substring(1);
        }
        return parseString(content);
    }

    /**
     * Parses XML held in memory with the same hardening as {@link #parse}.
     *
     * @param content the document text
     * @return the parsed document
     * @throws IOException                   when parsing from the in-memory reader fails
     * @throws SAXException                  when it is not well formed
     * @throws ParserConfigurationException when the parser cannot be configured securely
     */
    public static Document parseString(String content)
            throws IOException, SAXException, ParserConfigurationException {
        String withoutDoctype = content.replaceAll("(?s)<!DOCTYPE[^>]*>", "");
        return hardenedFactory().newDocumentBuilder().parse(new InputSource(new StringReader(withoutDoctype)));
    }

    /**
     * @return a factory configured so that no document can reach outside itself
     * @throws ParserConfigurationException when the parser does not support a required feature
     */
    public static DocumentBuilderFactory hardenedFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }
}
