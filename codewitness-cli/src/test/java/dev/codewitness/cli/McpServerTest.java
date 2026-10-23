package dev.codewitness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.codewitness.core.diagnostics.SelfIssueCategory;
import dev.codewitness.core.diagnostics.SelfIssueJournal;
import dev.codewitness.core.diagnostics.SelfIssueRecord;
import dev.codewitness.core.diagnostics.SelfIssueSeverity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP stdio surface: JSON-RPC framing, discovery and the tools themselves.
 *
 * <p>The server is driven directly rather than through a spawned process so the test can assert both
 * halves of the protocol contract: exactly one response per request, none for a notification, and
 * nothing on the protocol stream that is not JSON.
 */
class McpServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    @Test
    @DisplayName("initialize negotiates a protocol version and tools/list returns the seven tools")
    void initializeAndListTools() {
        List<JsonNode> responses = run(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                        + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                        + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");

        assertEquals(2, responses.size(), "a notification must not produce a response");
        JsonNode initialized = responses.get(0).path("result");
        assertEquals("2025-06-18", initialized.path("protocolVersion").asText());
        assertEquals("codewitness", initialized.path("serverInfo").path("name").asText());
        assertTrue(initialized.path("capabilities").has("tools"));

        JsonNode tools = responses.get(1).path("result").path("tools");
        assertTrue(tools.isArray(), "tools/list must wrap the array in a tools field: " + tools);
        assertEquals(7, tools.size());
        List<String> names = new ArrayList<>();
        tools.forEach(tool -> names.add(tool.path("name").asText()));
        assertTrue(names.containsAll(List.of("version", "inspect", "test", "verify", "report",
                "issues", "reproduce")), names.toString());
        tools.forEach(tool -> assertTrue(tool.path("inputSchema").isObject(),
                tool.path("name").asText() + " needs an input schema"));
    }

    @Test
    @DisplayName("a tool call returns text and structured content")
    void versionToolReturnsText() {
        List<JsonNode> responses = run(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"version\",\"arguments\":{}}}");

        JsonNode result = responses.getFirst().path("result");
        assertFalse(result.path("isError").asBoolean());
        assertTrue(result.path("content").isArray());
        assertTrue(result.path("content").get(0).path("text").asText().contains("codewitness"));
    }

    @Test
    @DisplayName("an unknown method and a malformed frame are protocol errors")
    void protocolErrors() {
        List<JsonNode> responses = run(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"nope\"}",
                "{not json}");

        assertEquals(-32601, responses.get(0).path("error").path("code").asInt());
        assertEquals(-32700, responses.get(1).path("error").path("code").asInt());
    }

    @Test
    @DisplayName("an unknown or invalid tool is a tool error, not a protocol failure")
    void toolErrors() {
        List<JsonNode> responses = run(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"no-such-tool\",\"arguments\":{}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"verify\",\"arguments\":{}}}");

        JsonNode unknown = responses.get(0).path("result");
        assertTrue(unknown.path("isError").asBoolean());
        assertTrue(unknown.path("content").get(0).path("text").asText().contains("Unknown tool"));

        JsonNode missing = responses.get(1).path("result");
        assertTrue(missing.path("isError").asBoolean());
        assertTrue(missing.path("content").get(0).path("text").asText().contains("project"));
    }

    @Test
    @DisplayName("a project no adapter supports is a tool error with the registered adapters")
    void inspectWithoutAnAdapterIsAnError() {
        List<JsonNode> responses = run(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"inspect\",\"arguments\":{\"project\":\"" + json(tmp) + "\"}}}");

        JsonNode result = responses.getFirst().path("result");
        assertTrue(result.path("isError").asBoolean());
        assertTrue(result.path("content").get(0).path("text").asText()
                .contains("No language adapter supports"));
    }

    @Test
    @DisplayName("the issues tool reads the journal the CLI writes")
    void issuesToolReadsTheJournal() {
        SelfIssueJournal journal = SelfIssueJournal.open(
                SelfIssueJournal.defaultDirectory(tmp));
        RuntimeException error = new IllegalStateException("phase broke");
        error.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("dev.codewitness.core.engine.VerificationEngine", "runPhase",
                        "VerificationEngine.java", 1268)
        });
        SelfIssueRecord issue = SelfIssueRecord.create(SelfIssueCategory.PHASE_FAILURE,
                SelfIssueSeverity.HIGH, "phase broke", error, Map.of());
        journal.record(issue);

        List<JsonNode> responses = run(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                        + "{\"name\":\"issues\",\"arguments\":{\"project\":\"" + json(tmp) + "\"}}}");

        JsonNode result = responses.getFirst().path("result");
        assertFalse(result.path("isError").asBoolean());
        String text = result.path("content").get(0).path("text").asText();
        assertTrue(text.contains(issue.id()), text);
        assertTrue(text.contains("phase broke"), text);
        assertEquals(1, result.path("structuredContent").path("open").asInt());
    }

    /**
     * Runs the server over a fixed input and returns the parsed protocol responses.
     *
     * <p>Every line of output must parse as JSON. That assertion is the reason this helper does not
     * print the raw stream: a stray diagnostic on stdout is a protocol violation, not cosmetic.
     */
    private static List<JsonNode> run(String... input) {
        String payload = String.join("\n", input) + "\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        McpServer server = new McpServer(
                new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new McpTools());
        assertEquals(0, server.serve(), "a client that closes stdin ends the server normally");

        List<JsonNode> responses = new ArrayList<>();
        for (String line : output.toString(StandardCharsets.UTF_8).lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            try {
                responses.add(MAPPER.readTree(line));
            } catch (Exception e) {
                throw new AssertionError("non-JSON output on the MCP stream: " + line, e);
            }
        }
        return responses;
    }

    private static String json(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
