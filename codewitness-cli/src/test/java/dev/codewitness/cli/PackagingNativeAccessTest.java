package dev.codewitness.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the shipped jar's native-access permission.
 *
 * <p>The bundled SQLite driver loads a native library through JNI. JDK 24 made that a restricted
 * operation (JEP 472), and a jar that has not been granted native access prints four lines of
 * {@code WARNING: A restricted method in java.lang.System has been called} on {@code stderr} before
 * any output — and on a later JDK the load is refused outright. The repository's own launcher passes
 * {@code --enable-native-access}, which is why a run through {@code ./codewitness} hides the problem:
 * every other entry point (a direct {@code java -jar}, an editor run configuration, a CI step, the
 * installed launcher whose whole body is {@code exec java -jar}) showed it.
 *
 * <p>The permission therefore belongs in the artifact: the shaded jar's manifest carries
 * {@code Enable-Native-Access: ALL-UNNAMED}, which a JDK 24+ JVM honours when the jar is started with
 * {@code -jar}. That is configuration, so nothing at runtime fails when it is dropped — a packaging
 * regression here is silent until someone reads the terminal — and both halves are asserted here.
 */
class PackagingNativeAccessTest {

    private static final String ATTRIBUTE = "Enable-Native-Access";

    /** The value JDK 24 accepts for the whole class path, which is what a shaded jar is. */
    private static final String UNNAMED_MODULES = "ALL-UNNAMED";

    @Test
    @DisplayName("the shaded jar's manifest grants native access, so java -jar does not warn")
    void shadedJarManifestGrantsNativeAccess() throws IOException {
        String transformer = manifestResourceTransformer(cliPom());

        assertNotNull(transformer,
                "codewitness-cli/pom.xml must configure shade's ManifestResourceTransformer; without it the "
                        + "jar has no Main-Class and no " + ATTRIBUTE + " attribute");

        Matcher entries = Pattern
                .compile("<" + ATTRIBUTE + ">\\s*([^<]*?)\\s*</" + ATTRIBUTE + ">")
                .matcher(transformer);

        assertTrue(entries.find(),
                "the shaded jar's manifest must declare <" + ATTRIBUTE + ">; without it every "
                        + "java -jar run of the tool prints four lines of JDK restricted-native-access warning "
                        + "on stderr before its own output, and a future JDK refuses the SQLite driver's load");
        assertTrue(UNNAMED_MODULES.equals(entries.group(1)),
                "<" + ATTRIBUTE + "> must be " + UNNAMED_MODULES + " — the jar is entirely an unnamed module, so "
                        + "naming anything else would grant the permission to nothing");
    }

    @Test
    @DisplayName("the launcher still grants native access, so an already-built jar runs quietly")
    void launcherGrantsNativeAccess() throws IOException {
        Path launcher = reactorRoot().resolve("codewitness");
        String script = Files.readString(launcher, StandardCharsets.UTF_8);

        assertTrue(script.contains("--enable-native-access=" + UNNAMED_MODULES),
                launcher + " must pass --enable-native-access=" + UNNAMED_MODULES + " to the JVM: it is the "
                        + "entry point the documentation names, and it also covers a jar built before the "
                        + "manifest carried the attribute");
    }

    /** The shade transformer block, or {@code null} when the POM does not configure one. */
    private static String manifestResourceTransformer(Path pom) throws IOException {
        String xml = Files.readString(pom, StandardCharsets.UTF_8);
        Matcher block = Pattern
                .compile("(?s)<transformer\\s+implementation=\"[^\"]*ManifestResourceTransformer\">(.*?)</transformer>")
                .matcher(xml);
        return block.find() ? block.group(1) : null;
    }

    private static Path cliPom() {
        return reactorRoot().resolve("codewitness-cli").resolve("pom.xml");
    }

    /** Walks up from the working directory, so the test does not depend on where Maven was started. */
    private static Path reactorRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("pom.xml");
            if (Files.isRegularFile(candidate) && isReactorRoot(candidate)) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("could not locate the reactor root above " + Path.of("").toAbsolutePath());
    }

    /**
     * The reactor root is the only POM that aggregates the modules, which distinguishes it from a module
     * POM that merely names {@code codewitness-parent} as its parent.
     */
    private static boolean isReactorRoot(Path pom) {
        try {
            return Files.readString(pom, StandardCharsets.UTF_8).contains("<module>codewitness-core</module>");
        } catch (IOException e) {
            return false;
        }
    }
}
