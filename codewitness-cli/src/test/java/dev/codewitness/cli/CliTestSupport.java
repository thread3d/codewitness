package dev.codewitness.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs the CLI while capturing its streams so that output can be asserted on. */
final class CliTestSupport {

    private CliTestSupport() {
    }

    /** The observable result of one CLI invocation. */
    record Result(int exitCode, String stdout, String stderr) {

        boolean mentions(String text) {
            return stdout.contains(text) || stderr.contains(text);
        }
    }

    static Result run(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            int exitCode = Main.execute(args);
            return new Result(exitCode,
                    stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    /** Writes a UTF-8 file below {@code base}, creating parent directories as needed. */
    static Path write(Path base, String relative, String content) throws java.io.IOException {
        Path file = base.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /** @return the repository root, found by walking up from the working directory. */
    static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("fixtures")) && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        return Path.of("").toAbsolutePath();
    }
}
