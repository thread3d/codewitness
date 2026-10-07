package dev.codewitness.kotlin;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Detection is the part that decides which adapter verifies what, so these tests pin both directions: a
 * Kotlin tree is claimed, and a tree that only looks like one - a Gradle build script, a POM, an empty
 * directory - is not.
 */
class KotlinProjectDetectorTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("a Maven project that compiles Kotlin is detected as a Kotlin Maven project")
    void detectsAMavenKotlinProject() throws IOException {
        Path root = KotlinTestContexts.project(tmp.resolve("maven"));
        KotlinTestContexts.write(root, "pom.xml", KotlinTestContexts.MAVEN_POM);

        ProjectInfo info = KotlinProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(LanguageId.KOTLIN, info.language());
        assertEquals(BuildSystemId.MAVEN, info.buildSystem());
        assertEquals(java.util.Set.of("kt"), info.sourceExtensions(),
                "the core needs to know which extensions are this language's source files");
        assertEquals("pom.xml", info.attributes().get(KotlinProjectDetector.ATTR_DESCRIPTOR));
        assertEquals("2.2.20", info.attributes().get(KotlinProjectDetector.ATTR_KOTLIN_VERSION),
                "the declared Kotlin version is read from the POM property");
        assertEquals(1, info.modules().size());
        assertEquals(java.util.List.of(root.resolve("src/main/kotlin")), info.modules().get(0).sourceDirs());
        assertEquals(java.util.List.of(root.resolve("src/test/kotlin")),
                info.modules().get(0).testSourceDirs());
        assertTrue(info.hasTests(), "a src/test/kotlin directory counts as tests");
    }

    @Test
    @DisplayName("a Gradle build with the Kotlin plugin is detected, with its modules")
    void detectsAGradleKotlinProject() throws IOException {
        Path root = KotlinTestContexts.project(tmp.resolve("gradle"));
        KotlinTestContexts.write(root, "build.gradle.kts", KotlinTestContexts.GRADLE_BUILD);
        KotlinTestContexts.write(root, "settings.gradle.kts", """
                rootProject.name = "kotlin-ledger"
                include(":app", ":core")
                """);
        Files.createDirectories(root.resolve("app/src/main/kotlin"));
        Files.createDirectories(root.resolve("core/src/main/kotlin"));

        ProjectInfo info = KotlinProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(BuildSystemId.GRADLE, info.buildSystem());
        assertEquals("build.gradle.kts", info.attributes().get(KotlinProjectDetector.ATTR_DESCRIPTOR));
        assertEquals("2.2.20", info.attributes().get(KotlinProjectDetector.ATTR_KOTLIN_VERSION));
        assertEquals(2, info.modules().size(), "each included module is a module");
        assertEquals(java.util.List.of("app", "core"),
                info.modules().stream().map(module -> module.name()).toList());
        assertTrue(info.testFrameworks().contains("junit5"),
                "the Junit 5 declaration in the build script is recognised: " + info.testFrameworks());
    }

    @Test
    @DisplayName("a Kotlin source tree with no build descriptor is inspectable but flagged as unbuildable")
    void aPlainSourceTreeIsDetectedWithAWarning() throws IOException {
        Path root = KotlinTestContexts.project(tmp.resolve("plain"));

        ProjectInfo info = KotlinProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(LanguageId.KOTLIN, info.language());
        assertEquals(BuildSystemId.NONE, info.buildSystem());
        assertEquals("none", info.attributes().get(KotlinProjectDetector.ATTR_DESCRIPTOR));
        String warning = info.attributes().get(KotlinProjectDetector.ATTR_DETECTION_WARNING);
        assertNotNull(warning, "a project with no build file must carry a detection warning");
        assertTrue(warning.contains("no Kotlin Maven plugin or Kotlin Gradle plugin"), warning);
    }

    @Test
    @DisplayName("a Java project is not claimed, even though it uses the same build tools")
    void aJavaTreeIsNotClaimed() throws IOException {
        Path root = tmp.resolve("java");
        Files.createDirectories(root.resolve("src/main/java/com/example"));
        KotlinTestContexts.write(root, "pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>java-example</artifactId>
                </project>
                """);
        KotlinTestContexts.write(root, "src/main/java/com/example/Ledger.java", "package com.example; class Ledger {}");

        assertFalse(KotlinProjectDetector.looksLikeKotlinProject(root),
                "a Java project with a POM holds no Kotlin evidence and is the Java adapter's to verify");
    }

    @Test
    @DisplayName("a Gradle build script that never mentions Kotlin is not claimed")
    void aJavaGradleTreeIsNotClaimed() throws IOException {
        Path root = tmp.resolve("java-gradle");
        Files.createDirectories(root.resolve("src/main/java"));
        KotlinTestContexts.write(root, "build.gradle", """
                plugins { id 'java' }
                repositories { mavenCentral() }
                """);

        assertFalse(KotlinProjectDetector.looksLikeKotlinProject(root),
                "the Kotlin plugin is what makes a Gradle build a Kotlin one");
    }

    @Test
    @DisplayName("an empty directory and a missing directory are not Kotlin projects")
    void nothingElseIsClaimed() throws IOException {
        Path empty = Files.createDirectories(tmp.resolve("empty"));
        assertFalse(KotlinProjectDetector.looksLikeKotlinProject(empty));
        assertFalse(KotlinProjectDetector.looksLikeKotlinProject(null));
        assertFalse(KotlinProjectDetector.looksLikeKotlinProject(tmp.resolve("does-not-exist")));
    }

    @Test
    @DisplayName("Kotlin files and a Kotlin plugin are both evidence, a Kotlin source root most of all")
    void detectionEvidenceIsDocumented() throws IOException {
        Path bySourceRoot = tmp.resolve("by-root");
        Files.createDirectories(bySourceRoot.resolve("src/main/kotlin"));
        assertTrue(KotlinProjectDetector.looksLikeKotlinProject(bySourceRoot));

        Path byFile = tmp.resolve("by-file");
        Files.createDirectories(byFile.resolve("app"));
        KotlinTestContexts.write(byFile, "app/Main.kt", "fun main() {}\n");
        assertTrue(KotlinProjectDetector.looksLikeKotlinProject(byFile));

        Path byPlugin = tmp.resolve("by-plugin");
        KotlinTestContexts.write(byPlugin, "build.gradle.kts", KotlinTestContexts.GRADLE_BUILD);
        assertTrue(KotlinProjectDetector.looksLikeKotlinProject(byPlugin));
    }

    @Test
    @DisplayName("a POM that merely mentions Kotlin is not evidence that the project builds Kotlin")
    void theWordKotlinIsNotEvidence() throws IOException {
        Path root = tmp.resolve("mentions");
        Files.createDirectories(root.resolve("src/main/java/example"));
        KotlinTestContexts.write(root, "pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>java-service</artifactId>
                  <modules>
                    <module>kotlin-helper</module>
                  </modules>
                  <dependencies>
                    <dependency>
                      <artifactId>kotlin-helper</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);
        KotlinTestContexts.write(root, "src/main/java/example/Ledger.java", "package example; class Ledger {}\n");

        assertFalse(KotlinProjectDetector.looksLikeKotlinProject(root),
                "a Java project may name a Kotlin module - this repository's own parent POM names this "
                        + "adapter - and claiming it would report a language the tree does not contain");
    }

    @Test
    @DisplayName("a build output directory full of Kotlin is not evidence of a Kotlin project")
    void buildOutputIsNotEvidence() throws IOException {
        Path root = tmp.resolve("build-only");
        KotlinTestContexts.write(root, "build/generated/Ledger.kt", "class Ledger\n");

        assertFalse(KotlinProjectDetector.looksLikeKotlinProject(root),
                "a generated file in build output says nothing about the project's own language");
    }

    @Test
    @DisplayName("source files are found under a root, in a stable order, and only .kt files")
    void sourceFilesAreFoundInOrder() throws IOException {
        Path root = KotlinTestContexts.project(tmp.resolve("sources"));
        KotlinTestContexts.write(root, "src/main/kotlin/b/B.kt", "class B\n");
        KotlinTestContexts.write(root, "src/main/kotlin/a/A.kt", "class A\n");
        KotlinTestContexts.write(root, "src/main/kotlin/build.gradle.kts", "// not a source file\n");

        java.util.List<Path> files = KotlinProjectDetector.sourceFiles(root.resolve("src/main/kotlin"));

        assertEquals(2, files.size(), "the build script is not a Kotlin source file: " + files);
        assertTrue(files.get(0).toString().endsWith("a/A.kt"), files.toString());
        assertTrue(files.get(1).toString().endsWith("b/B.kt"), files.toString());
    }

    @Test
    @DisplayName("a Kotlin project with a wrapper reports the wrapper so the pinned build tool is used")
    void theProjectWrapperIsRecorded() throws IOException {
        Path root = KotlinTestContexts.project(tmp.resolve("wrapper"));
        KotlinTestContexts.write(root, "pom.xml", KotlinTestContexts.MAVEN_POM);
        KotlinTestContexts.write(root, "mvnw", "#!/bin/sh\n");

        ProjectInfo info = KotlinProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(root.resolve("mvnw"), info.buildWrapper().orElseThrow());
    }

    @Test
    @DisplayName("an unparsable POM is reported rather than guessed at")
    void aMalformedPomIsReported() throws IOException {
        Path root = KotlinTestContexts.project(tmp.resolve("broken"));
        KotlinTestContexts.write(root, "pom.xml", "<project><artifactId>broken</artifactId>");

        ProjectInfo info = KotlinProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(BuildSystemId.MAVEN, info.buildSystem());
        String warning = info.attributes().get(KotlinProjectDetector.ATTR_DETECTION_WARNING);
        assertNotNull(warning, "an unparsable pom must be reported instead of guessed");
        assertTrue(warning.contains("pom.xml could not be parsed"), warning);
        assertEquals(1, info.modules().size(), "a usable fallback module is still reported");
    }
}
