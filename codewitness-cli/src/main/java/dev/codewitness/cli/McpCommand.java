package dev.codewitness.cli;

import picocli.CommandLine.Command;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

/**
 * {@code codewitness mcp} - serves the verification engine over the Model Context Protocol on stdio.
 *
 * <p>The protocol owns standard output. Every other message this process would print is redirected to
 * standard error first, so a stray progress line can never be mistaken for a JSON-RPC response. Tools
 * are listed by {@link McpTools} and handled against the same engine the CLI uses; the engine's progress
 * listener writes to standard error rather than stdout for the same reason.
 *
 * <p>The command blocks until the client closes stdin, which is the MCP stdio shutdown signal. It does
 * not install a shutdown hook: a client that wants the process gone closes the pipe or kills it.
 */
@Command(name = "mcp",
        description = "Serve CodeWitness tools over the Model Context Protocol on standard input/output.")
public final class McpCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        // Capture the real stdout for protocol frames, then make every ordinary print go to stderr. Code
        // that was written for a terminal keeps working, and the protocol stream stays clean.
        PrintStream protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true,
                StandardCharsets.UTF_8);
        PrintStream consoleOut = System.out;
        System.setOut(System.err);

        McpServer server = new McpServer(System.in, protocol, new McpTools());
        try {
            return server.serve();
        } finally {
            System.setOut(consoleOut);
            protocol.flush();
        }
    }
}
