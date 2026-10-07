package dev.codewitness.php;

import dev.codewitness.core.spi.VerificationContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The small PHP program CodeWitness runs inside a project.
 *
 * <p>PHP has no separate compilation step, but it does have a well defined first failure: a file the
 * interpreter itself cannot parse. Checking that needs no third-party package, only
 * {@code token_get_all(..., TOKEN_PARSE)}, so the check is a helper script written into the run
 * directory rather than a dependency the project would have to install. Writing it into the run
 * directory and not into the project is what makes a run repeatable and what keeps a verifier from
 * leaving files in somebody's working tree.
 *
 * <p>The script speaks a line protocol so that the Java side never has to read prose: one
 * {@code CWDIAG|} line per diagnostic and a final {@code CWSYNTAX|} summary.
 */
public final class PhpScripts {

    private PhpScripts() {
    }

    /** Writes the helper scripts into the run's artefact store and returns their paths. */
    public static Scripts write(VerificationContext context) {
        return new Scripts(writeScript(lintScript(context), LINT));
    }

    /**
     * Where {@link #write} puts the syntax checker.
     *
     * <p>Deriving the path without writing the script lets a caller name the command that will be run -
     * for evidence, say - without a side effect on the run directory.
     */
    public static Path lintScript(VerificationContext context) {
        return context.artifacts().artifactsDirectory().resolve("php").resolve("php-lint.php");
    }

    private static Path writeScript(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write helper script " + file, e);
        }
        return file;
    }

    /**
     * Checks the paths given as argv with the interpreter's own parser and prints one {@code CWDIAG|}
     * line per diagnostic.
     *
     * <p>The engine reports a failed build as {@code BLOCKED}, so the script must distinguish "the code
     * does not parse" from "the checker could not run": it exits non-zero only for parse errors and
     * prints a summary line the Java side records. A file it cannot read is a warning about the check,
     * not an error about the code. Any directory argument is walked, so the script works both when the
     * caller passes the files it collected and when it passes a tree.
     */
    static final String LINT = """
            <?php
            // CodeWitness PHP syntax check. Diagnostics go to stdout as
            //   CWDIAG|<SEVERITY>|<absolute path>|<line>|<message without newlines or pipes>
            // followed by one CWSYNTAX|files=<n>|errors=<n> summary. Exit status is 1 when at least one
            // file failed to parse, so a non-zero exit means "the project does not parse" and never
            // "the checker could not run".
            $excluded = ['vendor', 'node_modules', 'build', 'dist', '.git', '.codewitness', '.phpunit.cache',
                         '.phpunit.result.cache', 'cache', '.cache'];

            function cw_collect($argument, array $excluded) {
                $files = [];
                if (is_file($argument)) {
                    $files[] = $argument;
                    return $files;
                }
                if (!is_dir($argument)) {
                    return $files;
                }
                $stack = [$argument];
                while ($stack) {
                    $directory = array_pop($stack);
                    $entries = @scandir($directory);
                    if ($entries === false) {
                        continue;
                    }
                    foreach ($entries as $entry) {
                        if ($entry === '.' || $entry === '..') {
                            continue;
                        }
                        $path = $directory . DIRECTORY_SEPARATOR . $entry;
                        if (is_dir($path)) {
                            if (!in_array($entry, $excluded, true)) {
                                $stack[] = $path;
                            }
                        } elseif (is_file($path)) {
                            $extension = strtolower(pathinfo($path, PATHINFO_EXTENSION));
                            if ($extension === 'php' || $extension === 'phtml') {
                                $files[] = $path;
                            }
                        }
                    }
                }
                return $files;
            }

            function cw_diag($severity, $file, $line, $message) {
                $message = str_replace(["\\r", "\\n", "|"], [" ", " ", "/"], $message);
                echo 'CWDIAG|' . $severity . '|' . $file . '|' . $line . '|' . $message . PHP_EOL;
            }

            $files = [];
            foreach (array_slice($argv ?? [], 1) as $argument) {
                if ($argument === '') {
                    continue;
                }
                foreach (cw_collect($argument, $excluded) as $file) {
                    $files[] = $file;
                }
            }
            $files = array_values(array_unique($files));
            sort($files);

            $errors = 0;
            foreach ($files as $file) {
                $source = @file_get_contents($file);
                if ($source === false) {
                    // A file the checker cannot read says nothing about the project's code, so it is a
                    // warning and does not by itself fail the build.
                    cw_diag('WARNING', $file, 0, 'the file could not be read');
                    continue;
                }
                try {
                    token_get_all($source, TOKEN_PARSE);
                } catch (Throwable $error) {
                    $errors++;
                    cw_diag('ERROR', $file, $error->getLine(), $error->getMessage());
                }
            }
            if (!$files) {
                cw_diag('WARNING', '.', 0, 'no PHP source file was supplied');
            }
            echo 'CWSYNTAX|files=' . count($files) . '|errors=' . $errors . PHP_EOL;
            exit($errors > 0 ? 1 : 0);
            """;

    /**
     * Paths of the helper scripts for one run.
     *
     * @param lintScript syntax checker, run once by the build provider
     */
    public record Scripts(Path lintScript) {

        /** @return every script of this run, so that the caller can record them as artefacts. */
        public List<Path> all() {
            return List.of(lintScript);
        }
    }
}
