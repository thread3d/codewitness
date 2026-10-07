package dev.codewitness.kotlin.dependency;

import dev.codewitness.core.model.DependencyReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.PackageCoordinate;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.kotlin.KotlinTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two halves of the dependency inventory.
 *
 * <p>Maven is asked for its own resolved graph, and the parsing of that output is what these tests pin: the
 * coordinate is the second to last colon-separated element, a module banner attributes what follows to that
 * module, and build chatter that happens to contain colons is not a dependency. Gradle is read from its
 * manifests, which is the half that has to work on a machine with no Gradle at all.
 */
class KotlinDependencyAnalyzerTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("dependency:list output is read into coordinates, once each, in order")
    void readsMavenDependencyListOutput() {
        String output = """
                [INFO] Scanning for projects...
                [INFO]
                [INFO] --- dependency:3.8.1:list (default-cli) @ ledger ---
                [INFO]    org.jetbrains.kotlin:kotlin-stdlib:jar:2.2.20:runtime
                [INFO]    org.junit.jupiter:junit-jupiter:jar:5.10.2:test
                [INFO]
                [INFO] --- dependency:3.8.1:list (default-cli) @ app ---
                [INFO]    org.jetbrains.kotlin:kotlin-stdlib:jar:2.2.20:runtime
                [INFO]    org.jetbrains:annotations:jar:13.0:runtime -- module org.jetbrains.kotlin
                [INFO] Building kotlin-app 1.0
                [INFO] ------------------------------------------------------------------------
                """;

        List<String> dependencies = KotlinDependencyAnalyzer.parseMavenDependencies(output);
        Map<String, List<String>> byModule = KotlinDependencyAnalyzer.parseMavenByModule(output);

        assertEquals(List.of(
                "org.jetbrains.kotlin:kotlin-stdlib:jar:2.2.20:runtime",
                "org.junit.jupiter:junit-jupiter:jar:5.10.2:test",
                "org.jetbrains:annotations:jar:13.0:runtime"), dependencies,
                "the duplicate coordinate is reported once and the module note is not part of it");
        assertEquals(2, byModule.size(), byModule.toString());
        assertEquals(List.of(
                "org.jetbrains.kotlin:kotlin-stdlib:jar:2.2.20:runtime",
                "org.jetbrains:annotations:jar:13.0:runtime"), byModule.get("app"),
                "a coordinate is attributed to the module whose banner precedes it, and both modules "
                        + "resolve the standard library independently");
        assertEquals(2, byModule.get("ledger").size(), byModule.toString());
    }

    @Test
    @DisplayName("a resolved Maven coordinate is turned into an advisory coordinate with its version")
    void mavenCoordinatesCarryTheVersion() {
        List<PackageCoordinate> coordinates = PackageCoordinate.parseMaven(List.of(
                "org.jetbrains.kotlin:kotlin-stdlib:jar:2.2.20:runtime",
                "org.junit.jupiter:junit-jupiter:jar:5.10.2:test"));

        assertEquals(2, coordinates.size());
        assertEquals("Maven", coordinates.get(0).ecosystem());
        assertEquals("org.jetbrains.kotlin:kotlin-stdlib", coordinates.get(0).name());
        assertEquals("2.2.20", coordinates.get(0).version());
        assertTrue(coordinates.get(0).resolved());
    }

    @Test
    @DisplayName("a Gradle version catalog resolves alias and version.ref")
    void readsAGradleVersionCatalog() {
        Map<String, String> catalog = KotlinDependencyAnalyzer.versionCatalog("""
                [versions]
                kotlin = "2.2.20"
                junit = "5.10.2"

                [libraries]
                kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }
                junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version.ref = "junit" }
                kotlinx-coroutines = "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0" # a pinned version
                logback = { module = "ch.qos.logback:logback-classic" }

                [plugins]
                kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
                """);

        assertEquals("org.jetbrains.kotlin:kotlin-stdlib:2.2.20", catalog.get("kotlin-stdlib"));
        assertEquals("org.junit.jupiter:junit-jupiter:5.10.2", catalog.get("junit-jupiter"));
        assertEquals("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0", catalog.get("kotlinx-coroutines"),
                "a version written inline is read from the string shorthand");
        assertEquals("ch.qos.logback:logback-classic", catalog.get("logback"),
                "a library with no version is still a package, and is reported without one");
        assertFalse(catalog.containsKey("kotlin-jvm"), "a plugin is not a dependency");
    }

    @Test
    @DisplayName("build script declarations are read, with catalog aliases and without comments")
    void readsGradleBuildScripts() throws IOException {
        Map<String, String> catalog = KotlinDependencyAnalyzer.versionCatalog("""
                [versions]
                kotlin = "2.2.20"
                [libraries]
                kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }
                """);
        Path script = KotlinTestContexts.write(tmp.resolve("script"), "build.gradle.kts", """
                plugins { kotlin("jvm") version "2.2.20" }

                dependencies {
                    implementation(libs.kotlin.stdlib)
                    implementation("com.google.guava:guava:33.0.0-jre")
                    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
                    // implementation("commented.out:not-a-dependency:1.0")
                    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
                }
                """);

        List<PackageCoordinate> coordinates = KotlinDependencyAnalyzer.declaredGradleDependencies(
                Files.readString(script), catalog, Map.of());

        assertEquals(4, coordinates.size(), coordinates.toString());
        assertEquals("org.jetbrains.kotlin:kotlin-stdlib", coordinates.get(0).name());
        assertEquals("2.2.20", coordinates.get(0).version());
        assertEquals("com.google.guava:guava", coordinates.get(1).name());
        assertEquals("33.0.0-jre", coordinates.get(1).version());
        assertEquals("org.junit.jupiter:junit-jupiter", coordinates.get(2).name());
        assertFalse(coordinates.get(3).resolved(),
                "a declaration with no version is reported as a package whose version is unknown");
    }

    @Test
    @DisplayName("a lock file supplies the resolved version, and a dynamic version is not invented")
    void lockFileAndDynamicVersions() throws IOException {
        Map<String, String> lock = KotlinDependencyAnalyzer.gradleLockfile("""
                # This is a Gradle generated file for dependency locking.
                empty=productionRuntimeClasspath
                com.google.guava:guava:33.0.0-jre=compileClasspath,runtimeClasspath
                org.jetbrains.kotlin:kotlin-stdlib:2.2.20=compileClasspath
                """);
        assertEquals("33.0.0-jre", lock.get("com.google.guava:guava"));
        assertEquals("2.2.20", lock.get("org.jetbrains.kotlin:kotlin-stdlib"));

        Path script = KotlinTestContexts.write(tmp.resolve("dynamic"), "build.gradle.kts", """
                dependencies {
                    implementation("com.google.guava:guava:33.0.0-jre")
                    implementation("org.example:dynamic:+")
                    implementation("org.example:ranged:[1.0,2.0)")
                }
                """);
        List<PackageCoordinate> coordinates = KotlinDependencyAnalyzer.declaredGradleDependencies(
                Files.readString(script), Map.of(), lock);

        assertEquals("33.0.0-jre", coordinates.get(0).version());
        assertFalse(coordinates.get(1).resolved(),
                "a + version is not something an advisory service can be asked about");
        assertFalse(coordinates.get(2).resolved(), "nor is a range");
    }

    @Test
    @DisplayName("a Gradle project's inventory is read from its manifests without running anything")
    void gradleInventoryReadsManifestsOnly() throws IOException {
        Path root = KotlinTestContexts.project(tmp.resolve("gradle-project"));
        KotlinTestContexts.write(root, "build.gradle.kts", KotlinTestContexts.GRADLE_BUILD);
        KotlinTestContexts.write(root, "gradle/libs.versions.toml", """
                [versions]
                kotlin = "2.2.20"
                [libraries]
                stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }
                """);
        VerificationContext context = KotlinTestContexts.context(root, root.resolve(".codewitness"));

        DependencyReport report = new KotlinDependencyAnalyzer(
                new dev.codewitness.kotlin.KotlinBuildTools(context.runner())).analyseDependencies(context);

        assertTrue(report.analysed(), report.note());
        assertEquals("kotlin-dependencies", report.tool());
        assertTrue(report.note().contains("read from the Gradle build scripts"), report.note());
        assertTrue(report.note().contains("Whether any of these has a known advisory is recorded by the "
                + "advisory scan"), "the inventory must not claim to have checked anything: " + report.note());
        // An inventory made of typed coordinates displays each one as name@version; the Maven half, whose
        // tool already prints a coordinate, displays that instead.
        assertTrue(report.directDependencies().contains("org.jetbrains.kotlin:kotlin-stdlib@2.2.20"),
                report.directDependencies().toString());
        assertTrue(report.directDependencies().contains("org.junit.jupiter:junit-jupiter@5.10.2"),
                report.directDependencies().toString());
        assertEquals(2, report.coordinates().size(), report.coordinates().toString());
        assertTrue(report.coordinates().stream().allMatch(PackageCoordinate::resolved),
                "a catalog version is a resolved version, so the advisory scan can ask about it: "
                        + report.coordinates());
    }

    @Test
    @DisplayName("the analyzer claims Kotlin under either build system, and nothing else")
    void supportsOnlyKotlin() throws IOException {
        Path root = KotlinTestContexts.project(tmp.resolve("supported"));
        KotlinTestContexts.write(root, "build.gradle.kts", KotlinTestContexts.GRADLE_BUILD);
        VerificationContext context = KotlinTestContexts.context(root, root.resolve(".codewitness"));
        KotlinDependencyAnalyzer analyzer = new KotlinDependencyAnalyzer(
                new dev.codewitness.kotlin.KotlinBuildTools(context.runner()));

        assertTrue(analyzer.supports(context.projectInfo()));
        ProjectInfo javaProject = ProjectInfo.builder(context.projectInfo().project())
                .language(LanguageId.JAVA)
                .buildSystem(context.projectInfo().buildSystem())
                .build();
        assertFalse(analyzer.supports(javaProject), "a Java project is the Java adapter's to inventory");
    }
}
