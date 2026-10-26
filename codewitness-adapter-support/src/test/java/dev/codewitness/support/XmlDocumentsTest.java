package dev.codewitness.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One hardened XML loader for every adapter. These tests are the security contract: a project's reports and
 * build files are hostile input, so nothing here may be able to read a file it names or expand an entity.
 */
class XmlDocumentsTest {

    @TempDir
    Path tmp;

    private Path write(String name, String content) throws Exception {
        Path file = tmp.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("a plain report parses")
    void plainDocumentParses() throws Exception {
        Path file = write("a.xml", "<testsuite><testcase name=\"a\"/></testsuite>");

        assertEquals("testsuite", XmlDocuments.parse(file).getDocumentElement().getTagName());
    }

    @Test
    @DisplayName("a byte order mark does not stop the document parsing")
    void byteOrderMarkIsTolerated() throws Exception {
        // Reports written on Windows routinely carry one, and a parser that rejects it reports a broken build
        // that is not broken.
        Path file = write("bom.xml", "\uFEFF<testsuite/>");

        assertEquals("testsuite", XmlDocuments.parse(file).getDocumentElement().getTagName());
    }

    @Test
    @DisplayName("a declared DOCTYPE is removed rather than failing the parse")
    void doctypeIsStripped() throws Exception {
        Path file = write("doctype.xml", "<!DOCTYPE testsuite SYSTEM \"x.dtd\"><testsuite/>");

        assertEquals("testsuite", XmlDocuments.parse(file).getDocumentElement().getTagName());
    }

    @Test
    @DisplayName("an external entity cannot be resolved, so no local file is read")
    void externalEntityIsNotResolved() throws Exception {
        Path secret = tmp.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET", StandardCharsets.UTF_8);
        Path file = write("xxe.xml", """
                <!DOCTYPE testsuite [<!ENTITY leak SYSTEM "%s">]>
                <testsuite>&leak;</testsuite>
                """.formatted(secret.toUri()));

        // Either the DOCTYPE is stripped (so the entity is never declared) or the parse is refused; what must
        // not happen is the file's contents appearing in the document.
        try {
            String text = XmlDocuments.parse(file).getDocumentElement().getTextContent();
            assertTrue(!text.contains("TOP-SECRET"), "an external entity was resolved: " + text);
        } catch (org.xml.sax.SAXException expected) {
            assertTrue(expected.getMessage() != null, "a rejected document still explains itself");
        }
    }

    @Test
    @DisplayName("the billion-laughs shape does not expand")
    void entityExpansionIsRefused() {
        // Ten levels of doubling is a billion characters if a parser expands it.
        String laughs = """
                <!DOCTYPE lolz [
                  <!ENTITY lol "lol">
                  <!ENTITY lol1 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
                  <!ENTITY lol2 "&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;&lol1;">
                  <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
                  <!ENTITY lol4 "&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;&lol3;">
                  <!ENTITY lol5 "&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;&lol4;">
                ]>
                <lolz>&lol5;</lolz>
                """;

        assertThrows(Exception.class, () -> XmlDocuments.parseString(laughs));
    }

    @Test
    @DisplayName("a document that is not well formed is a parse error, not a silent empty result")
    void malformedDocumentIsRejected() throws Exception {
        Path file = write("bad.xml", "<testsuite><testcase></testsuite>");

        assertThrows(org.xml.sax.SAXException.class, () -> XmlDocuments.parse(file));
    }
}
