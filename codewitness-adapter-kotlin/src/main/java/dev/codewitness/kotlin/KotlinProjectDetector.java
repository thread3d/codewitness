package dev.codewitness.kotlin;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ModuleInfo;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Detects Kotlin projects and describes their build layout.
 *
 * <p>Only files are read here; nothing is executed. The rule that decides whether this adapter claims a
 * tree is deliberately narrow, because Kotlin shares its build descriptors with Java: a Gradle build
 * script or a {@code pom.xml} is evidence of neither language on its own. What claims a tree is Kotlin
 * source - a {@code src/main/kotlin} or {@code src/test/kotlin} directory, a {@code .kt} file, or a build
 * descriptor that names the Kotlin plugin - and what claims the Java half of a mixed tree is Java source,
 * which the Java adapter looks for itself. A tree holding both is therefore polyglot and is verified by
 * both adapters, each over the sources written in its own language.
 *
 * <p>Detection is deliberately conservative: when the build descriptor cannot be parsed the detector says
 * so through {@link ProjectInfo#attributes()} rather than guessing a layout that later phases would trust.
 */
public final class KotlinProjectDetector {

    /** Attributes set by this detector. */
    public static final String ATTR_DESCRIPTOR = "descriptor";
    public static final String ATTR_DETECTION_WARNING = "detectionWarning";
    public static final String ATTR_HAS_TESTS = "hasTestSources";
    public static final String ATTR_KOTLIN_VERSION = "kotlinVersion";

    /** Source extensions this adapter treats as Kotlin, without the leading dot. */
    public static final Set<String> SOURCE_EXTENSIONS = Set.of("kt");

    /** Directories a Kotlin project conventionally keeps its sources in. */
    static final String MAIN_SOURCE_DIRECTORY = "src/main/kotlin";
    static final String TEST_SOURCE_DIRECTORY = "src/test/kotlin";

    private static final List<String> GRADLE_BUILD_FILES = List.of("build.gradle.kts", "build.gradle");
    private static final List<String> GRADLE_SETTINGS_FILES = List.of("settings.gradle.kts", "settings.gradle");

    /**
     * An {@code include} line in a Gradle settings file, with its whole argument list.
     *
     * <p>{@code include(":app", ":core")} declares two modules, so the line is captured and the quoted
     * names are read out of it rather than matching a single name at the start.
     */
    private static final Pattern GRADLE_INCLUDE = Pattern.compile(
            "(?m)^\\s*include\\s*[\\(]?\\s*(.+)$");
    private static final Pattern GRADLE_INCLUDED_NAME = Pattern.compile("['\"]([^'\"]+)['\"]");
    private static final Pattern GRADLE_ROOT_NAME = Pattern.compile(
            "(?m)^\\s*rootProject\\.name\\s*=\\s*['\"]([^'\"]+)['\"]");

    /**
     * A Kotlin plugin declaration in a Gradle build script: {@code kotlin("jvm") version "2.2.20"},
     * {@code id("org.jetbrains.kotlin.jvm") version "2.2.20"} or the legacy {@code apply plugin: 'kotlin'}.
     */
    private static final Pattern GRADLE_KOTLIN_PLUGIN = Pattern.compile(
            "kotlin\\s*\\(\\s*[\"'](?:jvm|android|multiplatform|js)[\"']\\s*\\)"
                    + "|org\\.jetbrains\\.kotlin\\.[a-z0-9.]+"
                    + "|apply\\s+plugin:\\s*[\"']kotlin[\"']");
    /**
     * The release a Gradle build pins, in either spelling: {@code kotlin("jvm") version "2.2.20"} or
     * {@code id("org.jetbrains.kotlin.jvm") version "2.2.20"}.
     */
    private static final Pattern GRADLE_KOTLIN_VERSION = Pattern.compile(
            "kotlin\\s*\\(\\s*[\"'][a-z0-9]+[\"']\\s*\\)\\s*version\\s*[\"']([^\"']+)[\"']"
                    + "|org\\.jetbrains\\.kotlin\\.[a-z0-9.]+[\"']\\s*\\)?\\s*version\\s*[\"']([^\"']+)[\"']");

    /**
     * What a POM says when it builds Kotlin: the plugin that does the compiling.
     *
     * <p>Deliberately not the word "kotlin". A Java project may name a Kotlin module or artifact - this
     * repository's own parent POM does, because one of its modules is this adapter - and detecting the word
     * would claim every such tree, report a language it does not contain, and run two adapters over one
     * project. What is evidence is the plugin or the compiler's own coordinates.
     */
    private static final List<String> MAVEN_KOTLIN_EVIDENCE = List.of("kotlin-maven-plugin", "org.jetbrains.kotlin");

    private KotlinProjectDetector() {
    }

    /** @return {@code true} when the directory looks like a Kotlin project this adapter can handle. */
    public static boolean looksLikeKotlinProject(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return false;
        }
        if (Files.isDirectory(root.resolve(MAIN_SOURCE_DIRECTORY))
                || Files.isDirectory(root.resolve(TEST_SOURCE_DIRECTORY))) {
            return true;
        }
        Path pom = root.resolve("pom.xml");
        if (Files.isRegularFile(pom) && mentionsKotlinPlugin(readString(pom))) {
            return true;
        }
        for (String name : GRADLE_BUILD_FILES) {
            Path build = root.resolve(name);
            if (Files.isRegularFile(build) && GRADLE_KOTLIN_PLUGIN.matcher(readString(build)).find()) {
                return true;
            }
        }
        // A Kotlin source file outside the conventional layout, or a settings file that names a Kotlin
        // module whose own build script is what declares the plugin.
        return hasKotlinSource(root);
    }

    /** Inspects the project and returns everything later phases need to know. */
    public static ProjectInfo inspect(ProjectRef project) {
        Path root = project.root();
        ProjectInfo.Builder builder = ProjectInfo.builder(project)
                .language(LanguageId.KOTLIN)
                .sourceExtensions(SOURCE_EXTENSIONS);

        Path pom = root.resolve("pom.xml");
        boolean maven = Files.isRegularFile(pom) && isKotlinMaven(pom, root);
        if (maven) {
            return inspectMaven(project, builder, pom);
        }
        if (hasGradleDescriptor(root) && isKotlinGradle(root)) {
            return inspectGradle(project, builder);
        }
        // Kotlin sources with no build descriptor this adapter recognises. Still inspectable, but there
        // is nothing here that could compile them.
        List<ModuleInfo> modules = new ArrayList<>();
        modules.add(new ModuleInfo(project.name(), root, "source-tree",
                existing(root.resolve(MAIN_SOURCE_DIRECTORY)), existing(root.resolve(TEST_SOURCE_DIRECTORY)),
                null));
        builder.buildSystem(BuildSystemId.NONE)
                .addModule(modules.getFirst())
                .attribute(ATTR_DESCRIPTOR, "none")
                .attribute(ATTR_DETECTION_WARNING, "no Kotlin Maven plugin or Kotlin Gradle plugin was found; "
                        + "the project can be analysed but not built")
                .attribute(ATTR_HAS_TESTS, String.valueOf(!modules.getFirst().testSourceDirs().isEmpty()));
        detectTestFrameworks(root, builder);
        return builder.build();
    }

    private static ProjectInfo inspectMaven(ProjectRef project, ProjectInfo.Builder builder, Path pom) {
        Path root = project.root();
        builder.buildSystem(BuildSystemId.MAVEN).addDetectedDescriptor(pom).attribute(ATTR_DESCRIPTOR, "pom.xml");

        Document document;
        try {
            document = parseXml(pom);
        } catch (IOException | SAXException | ParserConfigurationException | RuntimeException e) {
            builder.attribute(ATTR_DETECTION_WARNING, "pom.xml could not be parsed: " + e.getMessage());
            builder.addModule(new ModuleInfo(project.name(), root, "jar",
                    existing(root.resolve(MAIN_SOURCE_DIRECTORY)), existing(root.resolve(TEST_SOURCE_DIRECTORY)),
                    null));
            detectWrapper(root, builder);
            return builder.build();
        }

        Element projectElement = document.getDocumentElement();
        String artifactId = text(projectElement, "artifactId");
        String packaging = text(projectElement, "packaging");
        String name = artifactId == null ? project.name() : artifactId;
        String kotlinVersion = mavenKotlinVersion(projectElement);
        if (kotlinVersion != null) {
            builder.attribute(ATTR_KOTLIN_VERSION, kotlinVersion);
        }

        List<Path> modulePaths = new ArrayList<>();
        NodeList moduleNodes = projectElement.getElementsByTagName("module");
        for (int i = 0; i < moduleNodes.getLength(); i++) {
            Node node = moduleNodes.item(i);
            if (node.getParentNode() != null && "modules".equals(node.getParentNode().getNodeName())) {
                String value = node.getTextContent().trim();
                if (!value.isEmpty()) {
                    modulePaths.add(root.resolve(value).normalize());
                }
            }
        }

        detectTestFrameworksFromText(textOfChildren(projectElement, "dependencies").toLowerCase(Locale.ROOT),
                builder);

        if (modulePaths.isEmpty()) {
            builder.addModule(module(name, root, packaging, kotlinVersion));
        } else {
            for (Path modulePath : modulePaths) {
                if (!Files.isDirectory(modulePath)) {
                    builder.attribute(ATTR_DETECTION_WARNING, "declared module " + modulePath + " does not exist");
                    continue;
                }
                builder.addModule(readMavenModule(modulePath, kotlinVersion));
                builder.addDetectedDescriptor(modulePath.resolve("pom.xml"));
            }
        }
        detectWrapper(root, builder);

        List<ModuleInfo> modules = builder.build().modules();
        builder.attribute(ATTR_HAS_TESTS, String.valueOf(modules.stream().anyMatch(ModuleInfo::hasTests)));
        return builder.build();
    }

    private static ModuleInfo readMavenModule(Path modulePath, String parentKotlinVersion) {
        Path pom = modulePath.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return module(modulePath.getFileName().toString(), modulePath, null, parentKotlinVersion);
        }
        try {
            Element element = parseXml(pom).getDocumentElement();
            String artifactId = text(element, "artifactId");
            String kotlinVersion = mavenKotlinVersion(element);
            return module(artifactId == null ? modulePath.getFileName().toString() : artifactId, modulePath,
                    text(element, "packaging"), kotlinVersion == null ? parentKotlinVersion : kotlinVersion);
        } catch (Exception _) {
            return module(modulePath.getFileName().toString(), modulePath, null, parentKotlinVersion);
        }
    }

    private static ModuleInfo module(String name, Path path, String packaging, String kotlinVersion) {
        return new ModuleInfo(name, path, packaging == null ? "jar" : packaging,
                existing(path.resolve(MAIN_SOURCE_DIRECTORY)), existing(path.resolve(TEST_SOURCE_DIRECTORY)),
                kotlinVersion);
    }

    private static ProjectInfo inspectGradle(ProjectRef project, ProjectInfo.Builder builder) {
        Path root = project.root();
        builder.buildSystem(BuildSystemId.GRADLE);
        String descriptor = GRADLE_BUILD_FILES.stream().filter(name -> Files.isRegularFile(root.resolve(name)))
                .findFirst().orElse(GRADLE_SETTINGS_FILES.getFirst());
        builder.attribute(ATTR_DESCRIPTOR, descriptor).addDetectedDescriptor(root.resolve(descriptor));

        List<Path> modules = new ArrayList<>();
        String rootName = project.name();
        for (String settingsName : GRADLE_SETTINGS_FILES) {
            Path settings = root.resolve(settingsName);
            if (!Files.isRegularFile(settings)) {
                continue;
            }
            builder.addDetectedDescriptor(settings);
            String content = readString(settings);
            Matcher nameMatcher = GRADLE_ROOT_NAME.matcher(content);
            if (nameMatcher.find()) {
                rootName = nameMatcher.group(1);
            }
            Matcher includeMatcher = GRADLE_INCLUDE.matcher(content);
            while (includeMatcher.find()) {
                Matcher nameMatcherInLine = GRADLE_INCLUDED_NAME.matcher(includeMatcher.group(1));
                while (nameMatcherInLine.find()) {
                    // `include(":app")` names the module `app`; the colon is Gradle's separator, not the
                    // start of an absolute path. Kept as written, `root.resolve("/app")` would be `/app`,
                    // which exists on no machine, and every module would silently vanish into the
                    // single-module fallback.
                    String included = nameMatcherInLine.group(1).replace(':', '/');
                    while (included.startsWith("/")) {
                        included = included.substring(1);
                    }
                    Path modulePath = root.resolve(included).normalize();
                    if (Files.isDirectory(modulePath)) {
                        modules.add(modulePath);
                        builder.addDetectedDescriptor(modulePath);
                    }
                }
            }
        }

        String buildText = "";
        for (String buildName : GRADLE_BUILD_FILES) {
            Path buildFile = root.resolve(buildName);
            if (Files.isRegularFile(buildFile)) {
                buildText = readString(buildFile);
            }
        }
        Matcher versionMatcher = GRADLE_KOTLIN_VERSION.matcher(buildText);
        if (versionMatcher.find()) {
            String version = versionMatcher.group(1) != null ? versionMatcher.group(1) : versionMatcher.group(2);
            builder.attribute(ATTR_KOTLIN_VERSION, version);
        }
        detectTestFrameworksFromText(buildText.toLowerCase(Locale.ROOT), builder);

        if (modules.isEmpty()) {
            builder.addModule(new ModuleInfo(rootName, root, "jar",
                    existing(root.resolve(MAIN_SOURCE_DIRECTORY)), existing(root.resolve(TEST_SOURCE_DIRECTORY)),
                    null));
        } else {
            for (Path modulePath : modules) {
                String moduleBuild = readString(modulePath.resolve(
                        Files.isRegularFile(modulePath.resolve("build.gradle.kts")) ? "build.gradle.kts" : "build.gradle"));
                detectTestFrameworksFromText(moduleBuild.toLowerCase(Locale.ROOT), builder);
                builder.addModule(new ModuleInfo(modulePath.getFileName().toString(), modulePath, "jar",
                        existing(modulePath.resolve(MAIN_SOURCE_DIRECTORY)),
                        existing(modulePath.resolve(TEST_SOURCE_DIRECTORY)), null));
            }
        }
        detectWrapper(root, builder);

        List<ModuleInfo> moduleInfos = builder.build().modules();
        builder.attribute(ATTR_HAS_TESTS, String.valueOf(moduleInfos.stream().anyMatch(ModuleInfo::hasTests)));
        return builder.build();
    }

    /** @return every Kotlin source file under a source root, in a stable order. */
    public static List<Path> sourceFiles(Path sourceRoot) {
        List<Path> files = new ArrayList<>();
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".kt"))
                    .sorted()
                    .forEach(files::add);
        } catch (IOException _) {
            // an unreadable tree simply yields no sources
        }
        return List.copyOf(files);
    }

    /** @return {@code true} when the POM builds Kotlin, by plugin or by source directory. */
    static boolean isKotlinMaven(Path pom, Path root) {
        if (mentionsKotlinPlugin(readString(pom))) {
            return true;
        }
        return Files.isDirectory(root.resolve(MAIN_SOURCE_DIRECTORY))
                || Files.isDirectory(root.resolve(TEST_SOURCE_DIRECTORY));
    }

    /** @return {@code true} when a POM declares the Kotlin compiler or its build plugin. */
    private static boolean mentionsKotlinPlugin(String pom) {
        for (String evidence : MAVEN_KOTLIN_EVIDENCE) {
            if (pom.contains(evidence)) {
                return true;
            }
        }
        return false;
    }

    /** @return {@code true} when a Gradle build declares the Kotlin plugin. */
    static boolean isKotlinGradle(Path root) {
        for (String name : GRADLE_BUILD_FILES) {
            Path build = root.resolve(name);
            if (Files.isRegularFile(build) && GRADLE_KOTLIN_PLUGIN.matcher(readString(build)).find()) {
                return true;
            }
        }
        for (String name : GRADLE_SETTINGS_FILES) {
            Path settings = root.resolve(name);
            if (Files.isRegularFile(settings) && GRADLE_KOTLIN_PLUGIN.matcher(readString(settings)).find()) {
                return true;
            }
        }
        return Files.isDirectory(root.resolve(MAIN_SOURCE_DIRECTORY))
                || Files.isDirectory(root.resolve(TEST_SOURCE_DIRECTORY));
    }

    private static boolean hasGradleDescriptor(Path root) {
        for (String name : GRADLE_BUILD_FILES) {
            if (Files.isRegularFile(root.resolve(name))) {
                return true;
            }
        }
        for (String name : GRADLE_SETTINGS_FILES) {
            if (Files.isRegularFile(root.resolve(name))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param root the tree to search
     * @return {@code true} when the tree holds a Kotlin source file, outside build output and dependencies.
     *
     * <p>Every directory level is searched, because a Kotlin module of a multi-module build sits at whatever
     * depth its parent chose; build output, dependency trees and sample projects are pruned rather than
     * merely filtered, so a large {@code target/} does not cost a walk and a generated file inside one is not
     * mistaken for the project's own language.
     */
    private static boolean hasKotlinSource(Path root) {
        boolean[] found = {false};
        try {
            Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                    Integer.MAX_VALUE, new java.nio.file.SimpleFileVisitor<Path>() {
                        @Override
                        public java.nio.file.FileVisitResult preVisitDirectory(
                                Path directory, java.nio.file.attribute.BasicFileAttributes attributes) {
                            if (!directory.equals(root)
                                    && NOT_THE_PROJECT.contains(directory.getFileName().toString())) {
                                return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                            }
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFile(
                                Path file, java.nio.file.attribute.BasicFileAttributes attributes) {
                            if (file.getFileName().toString().endsWith(".kt")) {
                                found[0] = true;
                                return java.nio.file.FileVisitResult.TERMINATE;
                            }
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException _) {
            return false;
        }
        return found[0];
    }

    /** Directories whose contents are build output, dependencies or sample projects, never the project. */
    private static final List<String> NOT_THE_PROJECT = List.of(
            "target", "build", "out", "node_modules", ".git", ".gradle", ".idea", "fixtures", "testdata");

    private static void detectWrapper(Path root, ProjectInfo.Builder builder) {
        boolean gradle = hasGradleDescriptor(root);
        Path wrapper = root.resolve(gradle ? "gradlew" : "mvnw");
        if (Files.isRegularFile(wrapper)) {
            builder.buildWrapper(wrapper);
        }
    }

    private static void detectTestFrameworks(Path root, ProjectInfo.Builder builder) {
        StringBuilder text = new StringBuilder();
        for (String name : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
            Path file = root.resolve(name);
            if (Files.isRegularFile(file)) {
                text.append(readString(file).toLowerCase(Locale.ROOT));
            }
        }
        detectTestFrameworksFromText(text.toString(), builder);
    }

    private static void detectTestFrameworksFromText(String text, ProjectInfo.Builder builder) {
        if (text.contains("junit-jupiter") || text.contains("org.junit.jupiter") || text.contains("junit5")
                || text.contains("usejunitplatform")) {
            builder.addTestFramework("junit5");
        }
        if (text.contains("kotlin-test") || text.contains("kotlin(\"test\")") || text.contains("kotlin(\"test-junit5\")")) {
            builder.addTestFramework("kotlin-test");
        }
        if (text.contains("kotest")) {
            builder.addTestFramework("kotest");
        }
        if (text.contains("mockk")) {
            builder.addTestFramework("mockk");
        }
        if (text.contains("assertj")) {
            builder.addTestFramework("assertj");
        }
    }

    private static String mavenKotlinVersion(Element project) {
        for (String tag : List.of("kotlin.version", "kotlin.version.jvm")) {
            NodeList nodes = project.getElementsByTagName(tag);
            if (nodes.getLength() > 0) {
                String value = nodes.item(0).getTextContent().trim();
                if (!value.isEmpty() && !value.startsWith("$")) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getParentNode() == parent) {
                String value = node.getTextContent();
                return value == null ? null : value.trim();
            }
        }
        return null;
    }

    private static String textOfChildren(Element parent, String tag) {
        StringBuilder sb = new StringBuilder();
        NodeList nodes = parent.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            sb.append(nodes.item(i).getTextContent()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Parses a tool report such as a JaCoCo XML file.
     *
     * <p>Those reports carry a DOCTYPE declaration. Rather than enabling DTD processing - which would make
     * the parser resolution-capable while reading a file produced by the project under verification - the
     * declaration is removed and the document is parsed with DTD support disabled.
     */
    public static Document parseReportXml(Path file) throws IOException, SAXException, ParserConfigurationException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String withoutDoctype = content.replaceAll("(?s)<!DOCTYPE[^>]*>", "");
        DocumentBuilderFactory factory = secureFactory();
        return factory.newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new java.io.StringReader(withoutDoctype)));
    }

    /** Parses XML with external entity resolution disabled: build files are untrusted input. */
    public static Document parseXml(Path file) throws IOException, SAXException, ParserConfigurationException {
        DocumentBuilderFactory factory = secureFactory();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        try (var stream = Files.newInputStream(file)) {
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static DocumentBuilderFactory secureFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static List<Path> existing(Path directory) {
        return Files.isDirectory(directory) ? List.of(directory) : List.of();
    }

    /** @return the file's content, or the empty string when it cannot be read. */
    static String readString(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException _) {
            return "";
        }
    }
}
