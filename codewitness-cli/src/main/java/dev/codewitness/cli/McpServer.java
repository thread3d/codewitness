package dev.codewitness.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codewitness.core.engine.VerificationEngine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * The MCP stdio transport: newline-delimited JSON-RPC 2.0 in, the same out.
 *
 * <p>This is deliberately a small protocol loop rather than a general JSON-RPC server. MCP over stdio
 * sends one UTF-8 JSON object per line, so the loop reads lines, dispatches the handful of methods this
 * server advertises, and writes one compact object per response. Standard output belongs to the
 * protocol; diagnostics go to standard error.
 *
 * <p>Tool failures are returned as MCP tool results with {@code isError: true}, because the model can
 * read those and decide what to do. Protocol failures - unparseable input, an unknown method - are
 * JSON-RPC errors, because there is no tool result to attach them to. An unexpected exception inside a
 * tool is a fault in CodeWitness, so it is also written to the self-issue journal before the model sees
 * it.
 */
final class McpServer {

    /** Latest protocol revision this server implements. */
    static final String PROTOCOL_VERSION = "2025-06-18";

    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS =
            Set.of("2024-11-05", "2025-03-26", PROTOCOL_VERSION);

    private final ObjectMapper mapper = new ObjectMapper();
    private final BufferedReader input;
    private final PrintStream output;
    private final McpTools tools;

    McpServer(InputStream input, PrintStream output, McpTools tools) {
        this.input = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        this.output = output;
        this.tools = tools;
    }

    /** @return process exit code: 0 when the client closed stdin, 1 when stdin itself failed */
    int serve() {
        String line;
        try {
            while ((line = input.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                handle(line);
            }
        } catch (IOException e) {
            System.err.println("codewitness mcp: stdin failed: " + e.getMessage());
            return 1;
        }
        return 0;
    }

    private void handle(String line) {
        JsonNode request;
        try {
            request = mapper.readTree(line);
        } catch (IOException e) {
            writeError(null, -32700, "Parse error: " + e.getMessage());
            return;
        }
        if (request == null || !request.isObject()) {
            writeError(null, -32600, "Invalid request: expected a JSON object");
            return;
        }

        JsonNode id = request.get("id");
        boolean isRequest = id != null && !id.isNull();
        JsonNode methodNode = request.get("method");
        if (methodNode == null || !methodNode.isTextual()) {
            if (isRequest) {
                writeError(id, -32600, "Invalid request: method must be a string");
            }
            return;
        }
        String method = methodNode.asText();
        JsonNode params = request.path("params");

        // A message without an id is a notification. MCP clients send initialized (and cancellations)
        // this way; no response is expected or allowed.
        if (!isRequest) {
            return;
        }

        switch (method) {
            case "initialize" -> writeResult(id, initialize(params));
            case "ping" -> writeResult(id, mapper.createObjectNode());
            case "tools/list" -> {
                ObjectNode result = mapper.createObjectNode();
                result.set("tools", tools.list());
                writeResult(id, result);
            }
            case "tools/call" -> callTool(id, params);
            default -> writeError(id, -32601, "Method not found: " + method);
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText("");
        String version = SUPPORTED_PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSION;
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", version);
        result.putObject("capabilities").putObject("tools");
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "codewitness");
        serverInfo.put("version", VerificationEngine.VERSION);
        result.put("instructions",
                "CodeWitness verifies a project against requirements and reports the evidence it observed. "
                        + "A tool result with exitCode 1 means at least one requirement failed; 2 means the "
                        + "run was blocked (for example a failed build); 3 means the tool could not run. "
                        + "Those are verification results, not MCP tool errors. Use tools/list to see the "
                        + "available tools and their arguments.");
        return result;
    }

    private void callTool(JsonNode id, JsonNode params) {
        String name = params.path("name").asText("");
        if (name.isBlank()) {
            writeResult(id, McpTools.error("tools/call requires a tool name"));
            return;
        }
        JsonNode arguments = params.path("arguments");
        if (arguments.isMissingNode() || arguments.isNull()) {
            arguments = mapper.createObjectNode();
        }
        if (!arguments.isObject()) {
            writeResult(id, McpTools.error("tools/call arguments must be an object"));
            return;
        }
        try {
            writeResult(id, tools.call(name, arguments));
        } catch (IllegalArgumentException | UncheckedIOException e) {
            // Bad arguments, a missing directory or an unreadable file are statements about the request,
            // not defects in CodeWitness. Return them as a tool error without filling the issue journal.
            writeResult(id, McpTools.error(e.getMessage() == null ? e.toString() : e.getMessage()));
        } catch (McpTools.McpToolFailure e) {
            if (!e.recorded()) {
                SelfIssues.recordInternalFailure("MCP tool " + name, e);
            }
            writeResult(id, McpTools.error(e.getMessage()));
        } catch (Throwable e) {
            SelfIssues.recordInternalFailure("MCP tool " + name, e);
            String pointer = SelfIssues.pointer();
            String text = "Internal error in " + name + ": " + e
                    + (pointer == null ? "" : "\n" + pointer)
                    + "\nSee the self-issue journal for the stack trace.";
            System.err.println("codewitness mcp: tool " + name + " failed: " + e);
            e.printStackTrace(System.err);
            writeResult(id, McpTools.error(text));
        }
    }

    private void writeResult(JsonNode id, JsonNode result) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        write(response);
    }

    private void writeError(JsonNode id, int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id == null) {
            response.putNull("id");
        } else {
            response.set("id", id);
        }
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        write(response);
    }

    private void write(ObjectNode response) {
        try {
            output.print(mapper.writeValueAsString(response));
            output.print('\n');
            output.flush();
        } catch (IOException e) {
            System.err.println("codewitness mcp: cannot write protocol response: " + e.getMessage());
        }
    }
}
