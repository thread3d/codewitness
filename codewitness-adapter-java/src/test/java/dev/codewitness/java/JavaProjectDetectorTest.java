package dev.codewitness.java;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static dev.codewitness.java.TestContexts.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the layout detection rules: what a project is made of, which build system drives it and what
 * is reported when the build descriptor cannot be trusted.
 */
class JavaProjectDetectorTest {

    @TempDir
    Path tmp;

    private static final String SINGLE_MODULE_POM = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>ledger</artifactId>
              <version>1.0.0</version>
              <properties>
                <maven.compiler.release>17</maven.compiler.release>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <scope>test</scope>
                </dependency>
              </dependencies>
            </project>
            """;

    @Test
    void detectsSingleModuleMavenProject() throws IOException {
        Path root = tmp.resolve("single");
        write(root, "pom.xml", SINGLE_MODULE_POM);
        Files.createDirectories(root.resolve("src/main/java"));
        Files.createDirectories(root.resolve("src/test/java"));
        Path wrapper = write(root, "mvnw", "#!/bin/sh\n");

        ProjectInfo info = JavaProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(LanguageId.JAVA, info.language());
        assertEquals(BuildSystemId.MAVEN, info.buildSystem());
        assertEquals("pom.xml", info.attributes().get(JavaProjectDetector.ATTR_DESCRIPTOR));
        assertFalse(info.multiModule(), "a single module project must not be reported as multi module");
        assertEquals(1, info.modules().size());

        ModuleInfo module = info.modules().get(0);
        assertEquals("ledger", module.name(), "the Maven artifactId names the module");
        assertEquals("jar", module.packaging());
        assertEquals(List.of(root.resolve("src/main/java")), module.sourceDirs());
        assertEquals(List.of(root.resolve("src/test/java")), module.testSourceDirs());
        assertEquals(Optional.of("17"), info.javaVersion());
        assertTrue(info.testFrameworks().contains("junit5"),
                "a junit-jupiter dependency means JUnit 5: " + info.testFrameworks());
        assertTrue(info.hasTests());
        assertEquals(wrapper, info.buildWrapper().orElseThrow(),
                "mvnw in the project root is the build wrapper");
        assertEquals("true", info.attributes().get(JavaProjectDetector.ATTR_HAS_TESTS));
        assertEquals("pom.xml", info.attributes().get(JavaProjectDetector.ATTR_DESCRIPTOR));
    }

    @Test
    void detectsEveryModuleOfAMultiModuleMavenProject() throws IOException {
        Path root = tmp.resolve("multi");
        write(root, "pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <properties>
                    <maven.compiler.release>17</maven.compiler.release>
                  </properties>
                  <modules>
                    <module>core</module>
                    <module>app</module>
                  </modules>
                </project>
                """);
        write(root, "core/pom.xml", modulePom("core"));
        write(root, "app/pom.xml", modulePom("app"));
        Files.createDirectories(root.resolve("core/src/main/java"));
        Files.createDirectories(root.resolve("app/src/test/java"));

        ProjectInfo info = JavaProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(BuildSystemId.MAVEN, info.buildSystem());
        assertTrue(info.multiModule());
        assertEquals(List.of("core", "app"),
                info.modules().stream().map(ModuleInfo::name).toList(),
                "every declared module must be reported, in declaration order");
        assertEquals(Optional.of("17"), info.javaVersion(), "modules inherit the parent Java version");
        assertTrue(info.hasTests(), "only app has test sources, but that is enough");
        assertTrue(info.detectedDescriptors().size() >= 3,
                "the parent pom and both module poms are detection inputs: " + info.detectedDescriptors());
    }

    @Test
    void reportsDeclaredModulesThatDoNotExist() throws IOException {
        Path root = tmp.resolve("missing-module");
        write(root, "pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>parent</artifactId>
                  <modules><module>gone</module></modules>
                </project>
                """);

        ProjectInfo info = JavaProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(0, info.modules().size(), "the missing module must not be reported as a module");
        assertNotNull(info.attributes().get(JavaProjectDetector.ATTR_DETECTION_WARNING));
        assertTrue(info.attributes().get(JavaProjectDetector.ATTR_DETECTION_WARNING).contains("gone"));
    }

    @Test
    void detectsGradleProjectAndItsModules() throws IOException {
        Path root = tmp.resolve("gradle");
        write(root, "settings.gradle", """
                rootProject.name = 'shop'
                include 'app'
                include 'lib'
                """);
        write(root, "build.gradle", """
                plugins { id 'java' }
                sourceCompatibility = '17'
                dependencies {
                    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
                }
                """);
        Files.createDirectories(root.resolve("app"));
        Files.createDirectories(root.resolve("lib"));
        Files.createDirectories(root.resolve("app/src/main/java"));

        ProjectInfo info = JavaProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(BuildSystemId.GRADLE, info.buildSystem());
        assertEquals("build.gradle", info.attributes().get(JavaProjectDetector.ATTR_DESCRIPTOR));
        assertEquals(Optional.of("17"), info.javaVersion());
        assertEquals(List.of("app", "lib"), info.modules().stream().map(ModuleInfo::name).toList());
        assertTrue(info.testFrameworks().contains("junit5"));
        assertFalse(info.modules().get(0).sourceDirs().isEmpty());
        assertTrue(info.modules().get(1).sourceDirs().isEmpty());
    }

    @Test
    void detectsJava25ProjectsInBothBuildSystems() throws IOException {
        Path mavenRoot = tmp.resolve("maven25");
        write(mavenRoot, "pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>modern</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>25</maven.compiler.release>
                  </properties>
                </project>
                """);
        Files.createDirectories(mavenRoot.resolve("src/main/java"));

        assertEquals(Optional.of("25"), JavaProjectDetector.inspect(ProjectRef.of(mavenRoot)).javaVersion(),
                "a two-digit Maven release must survive detection");

        // Gradle expresses the same thing differently, and through the other supported spelling.
        Path gradleRoot = tmp.resolve("gradle25");
        write(gradleRoot, "build.gradle", """
                plugins { id 'java' }
                java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
                """);
        Files.createDirectories(gradleRoot.resolve("src/main/java"));

        ProjectInfo gradle = JavaProjectDetector.inspect(ProjectRef.of(gradleRoot));
        assertEquals(Optional.of("25"), gradle.javaVersion(),
                "JavaLanguageVersion.of(25) must survive detection");
        assertEquals("25", gradle.modules().get(0).javaVersion(),
                "the module inherits the detected Java version");
    }

    @Test
    void detectsAPlainSourceTreeWithoutABuildDescriptor() throws IOException {
        Path root = tmp.resolve("plain");
        Files.createDirectories(root.resolve("src/main/java/com/example"));

        ProjectInfo info = JavaProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(LanguageId.JAVA, info.language());
        assertEquals(BuildSystemId.NONE, info.buildSystem());
        assertEquals(1, info.modules().size());
        assertEquals(List.of(root.resolve("src/main/java")), info.modules().get(0).sourceDirs());
        String warning = info.attributes().get(JavaProjectDetector.ATTR_DETECTION_WARNING);
        assertNotNull(warning, "a project with no build file must carry a detection warning");
        assertTrue(warning.contains("no pom.xml or Gradle build file"), warning);
        assertEquals("none", info.attributes().get(JavaProjectDetector.ATTR_DESCRIPTOR));
        assertFalse(info.hasTests());
        assertTrue(JavaProjectDetector.looksLikeJavaProject(root));
    }

    @Test
    void malformedPomDoesNotThrowAndRecordsAWarning() throws IOException {
        Path root = tmp.resolve("broken");
        write(root, "pom.xml", "<project><artifactId>broken</artifactId>");
        Files.createDirectories(root.resolve("src/main/java"));

        ProjectInfo info = JavaProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(BuildSystemId.MAVEN, info.buildSystem());
        assertEquals(1, info.modules().size(), "a usable fallback module is still reported");
        String warning = info.attributes().get(JavaProjectDetector.ATTR_DETECTION_WARNING);
        assertNotNull(warning, "an unparsable pom must be reported instead of guessed");
        assertTrue(warning.contains("pom.xml could not be parsed"), warning);
        assertFalse(info.modules().get(0).sourceDirs().isEmpty(),
                "the conventional source directory is still detected");
    }

    @Test
    @DisplayName("a tree whose only sources are Kotlin is the Kotlin adapter's, not this one's")
    void aKotlinTreeIsNotClaimed() throws IOException {
        Path root = tmp.resolve("kotlin");
        write(root, "pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>kotlin-service</artifactId>
                  <build>
                    <sourceDirectory>src/main/kotlin</sourceDirectory>
                    <plugins>
                      <plugin>
                        <groupId>org.jetbrains.kotlin</groupId>
                        <artifactId>kotlin-maven-plugin</artifactId>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);
        write(root, "src/main/kotlin/example/Ledger.kt", "package example\n\nclass Ledger\n");

        assertFalse(JavaProjectDetector.looksLikeJavaProject(root),
                "Kotlin projects use Maven and Gradle too, so a descriptor is not Java evidence: claiming "
                        + "them here would make every Kotlin project polyglot and would name a language the "
                        + "tree does not contain");
    }

    @Test
    @DisplayName("a tree with both languages' sources is claimed, because that is genuinely polyglot")
    void aMixedTreeIsClaimed() throws IOException {
        Path root = tmp.resolve("mixed");
        write(root, "pom.xml", "<project><artifactId>mixed</artifactId></project>");
        write(root, "src/main/kotlin/example/Kt.kt", "package example\n");
        write(root, "src/main/java/example/Java.java", "package example; class Java {}\n");

        assertTrue(JavaProjectDetector.looksLikeJavaProject(root),
                "the Java half is still this adapter's to verify");
    }

    @Test
    @DisplayName("an aggregator with a build descriptor and no sources of either language is claimed")
    void anAggregatorIsClaimed() throws IOException {
        Path root = tmp.resolve("aggregator");
        write(root, "pom.xml", "<project><artifactId>aggregator</artifactId></project>");
        Files.createDirectories(root.resolve("module-a/src/main/java/example"));

        assertTrue(JavaProjectDetector.looksLikeJavaProject(root),
                "the modules hold the sources and the root holds the build");
    }

    @Test
    @DisplayName("a Java source file outside the conventional layout is found at any depth")
    void javaSourcesAreFoundWhereverTheyAre() throws IOException {
        Path root = tmp.resolve("deep");
        write(root, "app/service/src/com/example/Ledger.java", "package com.example; class Ledger {}\n");

        assertTrue(JavaProjectDetector.looksLikeJavaProject(root));
    }

    @Test
    void aDirectoryThatIsNotAJavaProjectIsNotDetected() throws IOException {
        Path root = tmp.resolve("empty");
        Files.createDirectories(root);
        assertFalse(JavaProjectDetector.looksLikeJavaProject(root));
    }

    @Test
    void detectsJunit4WhenJupiterIsNotDeclared() throws IOException {
        Path root = tmp.resolve("junit4");
        write(root, "pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>legacy</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>junit</groupId>
                      <artifactId>junit</artifactId>
                      <version>4.13.2</version>
                      <scope>test</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);

        ProjectInfo info = JavaProjectDetector.inspect(ProjectRef.of(root));

        assertTrue(info.testFrameworks().contains("junit4"), info.testFrameworks().toString());
        assertFalse(info.testFrameworks().contains("junit5"), info.testFrameworks().toString());
    }

    @Test
    void reportXmlWithADoctypeDeclarationIsAccepted() throws Exception {
        // Regression: strict parsing used to reject the DOCTYPE every JaCoCo and PIT report carries,
        // which silently turned measured coverage into "0/0" and mutations into "none".
        Path report = write(tmp, "jacoco.xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                <report name="sample">
                  <counter type="LINE" missed="1" covered="3"/>
                </report>
                """);

        Document document = JavaProjectDetector.parseReportXml(report);

        assertEquals("report", document.getDocumentElement().getTagName());
        assertEquals("sample", document.getDocumentElement().getAttribute("name"));
    }

    @Test
    void buildDescriptorsRejectDoctypeDeclarations() throws IOException {
        Path pom = write(tmp, "doctype-pom.xml",
                "<!DOCTYPE project [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\n<project>&xxe;</project>");

        assertThrows(SAXException.class, () -> JavaProjectDetector.parseXml(pom),
                "a build file must not be able to pull in external entities");
    }

    private static String modulePom(String artifactId) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>%s</artifactId>
                </project>
                """.formatted(artifactId);
    }
}
