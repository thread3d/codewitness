package dev.codewitness.java;

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
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Detects Java projects and describes their build layout.
 *
 * <p>Only files are read here; nothing is executed. Detection is deliberately conservative: when the
 * build descriptor cannot be parsed the detector says so through
 * {@link ProjectInfo#attributes()} rather than guessing a layout that later phases would trust.
 */
public final class JavaProjectDetector {

    /** Attributes set by this detector. */
    public static final String ATTR_DESCRIPTOR = "descriptor";
    public static final String ATTR_DETECTION_WARNING = "detectionWarning";
    public static final String ATTR_ROOT_PACKAGES = "rootPackages";
    public static final String ATTR_HAS_TESTS = "hasTestSources";

    private static final Pattern GRADLE_INCLUDE =
            Pattern.compile("(?m)^\\s*include\\s*[\\(]?\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern GRADLE_ROOT_NAME =
            Pattern.compile("(?m)^\\s*rootProject\\.name\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern GRADLE_JAVA_VERSION =
            Pattern.compile("(?m)(?:sourceCompatibility|JavaLanguageVersion\\.of)\\s*[=\\(]?\\s*['\"]?(\\d+(?:\\.\\d+)?)");

    private JavaProjectDetector() {
    }

    /**
     * @return {@code true} when the directory looks like a Java project this adapter can handle.
     *
     * <p>The sources decide and the build descriptor is only the fallback, in this order:
     *
     * <ol>
     *   <li>a tree that holds Java source is this adapter's, wherever the build file is;</li>
     *   <li>a tree that holds Kotlin source and no Java source is not: Kotlin uses Gradle and Maven too, so
     *       {@code build.gradle.kts} and a {@code pom.xml} are exactly what a Kotlin project looks like, and
     *       the Kotlin adapter claims those trees itself. Claiming them here as well would make every Kotlin
     *       project a polyglot one that two adapters then both build, and the report would name a language
     *       the tree does not contain;</li>
     *   <li>a build descriptor with neither language's sources is claimed, which is what a Maven or Gradle
     *       aggregator looks like: its modules hold the sources and it holds the build.</li>
     * </ol>
     *
     * <p>Naming Kotlin here is the price of the two adapters not being able to see each other. The check is
     * the same one the Kotlin detector makes in the other direction - the source files, not the plugin
     * coordinates - so a tree with both languages' sources is claimed by both, which is the polyglot case
     * the engine already handles.
     */
    public static boolean looksLikeJavaProject(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return false;
        }
        if (Files.isDirectory(root.resolve("src/main/java"))
                || Files.isDirectory(root.resolve("src/test/java"))
                || hasSourceFile(root, ".java")) {
            return true;
        }
        if (Files.isDirectory(root.resolve("src/main/kotlin"))
                || Files.isDirectory(root.resolve("src/test/kotlin"))
                || hasSourceFile(root, ".kt")) {
            return false;
        }
        return Files.isRegularFile(root.resolve("pom.xml"))
                || Files.isRegularFile(root.resolve("build.gradle"))
                || Files.isRegularFile(root.resolve("build.gradle.kts"))
                || Files.isRegularFile(root.resolve("settings.gradle"))
                || Files.isRegularFile(root.resolve("settings.gradle.kts"));
    }

    /** Directories whose contents are build output, dependencies or sample projects, never the project. */
    private static final List<String> NOT_THE_PROJECT = List.of(
            "target", "build", "out", "bin", "obj", ".git", ".gradle", ".idea", "node_modules", "fixtures",
            "testdata");

    /**
     * @param root   the tree to search
     * @param suffix the file name suffix that decides the answer
     * @return {@code true} when the tree holds a file with that suffix, outside build output and dependencies
     */
    private static boolean hasSourceFile(Path root, String suffix) {
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
                            if (file.getFileName().toString().endsWith(suffix)) {
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

    /** Inspects the project and returns everything later phases need to know. */
    public static ProjectInfo inspect(ProjectRef project) {
        Path root = project.root();
        ProjectInfo.Builder builder = ProjectInfo.builder(project).language(LanguageId.JAVA);

        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            return inspectMaven(project, builder);
        }
        if (Files.isRegularFile(root.resolve("build.gradle"))
                || Files.isRegularFile(root.resolve("build.gradle.kts"))
                || Files.isRegularFile(root.resolve("settings.gradle"))
                || Files.isRegularFile(root.resolve("settings.gradle.kts"))) {
            return inspectGradle(project, builder);
        }
        // Plain source tree with no build descriptor: still inspectable, but it cannot be built.
        List<ModuleInfo> modules = new ArrayList<>();
        modules.add(new ModuleInfo(project.name(), root, "source-tree",
                existing(root.resolve("src/main/java")), existing(root.resolve("src/test/java")), null));
        builder.buildSystem(BuildSystemId.NONE)
                .addModule(modules.getFirst())
                .attribute(ATTR_DETECTION_WARNING,
                        "no pom.xml or Gradle build file found; the project can be analysed but not built")
                .attribute(ATTR_DESCRIPTOR, "none");
        detectTestFrameworks(root, builder, "");
        builder.attribute(ATTR_ROOT_PACKAGES, String.join(",", rootPackages(modules)));
        builder.attribute(ATTR_HAS_TESTS, String.valueOf(!modules.getFirst().testSourceDirs().isEmpty()));
        return builder.build();
    }

    private static ProjectInfo inspectMaven(ProjectRef project, ProjectInfo.Builder builder) {
        Path root = project.root();
        Path pom = root.resolve("pom.xml");
        builder.buildSystem(BuildSystemId.MAVEN).addDetectedDescriptor(pom).attribute(ATTR_DESCRIPTOR, "pom.xml");

        Document document;
        try {
            document = parseXml(pom);
        } catch (IOException | SAXException | ParserConfigurationException | RuntimeException e) {
            builder.attribute(ATTR_DETECTION_WARNING, "pom.xml could not be parsed: " + e.getMessage());
            builder.addModule(new ModuleInfo(project.name(), root, "jar",
                    existing(root.resolve("src/main/java")), existing(root.resolve("src/test/java")), null));
            detectWrapper(root, builder, "mvnw");
            return builder.build();
        }

        Element projectElement = document.getDocumentElement();
        String artifactId = text(projectElement, "artifactId");
        String packaging = text(projectElement, "packaging");
        String name = artifactId == null ? project.name() : artifactId;
        String javaVersion = mavenJavaVersion(projectElement);
        builder.javaVersion(javaVersion);

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

        String dependencyText = textOfChildren(projectElement, "dependencies");
        detectTestFrameworksFromText(dependencyText.toLowerCase(Locale.ROOT), builder);

        if (modulePaths.isEmpty()) {
            ModuleInfo module = new ModuleInfo(name, root, packaging == null ? "jar" : packaging,
                    existing(root.resolve("src/main/java")), existing(root.resolve("src/test/java")), javaVersion);
            builder.addModule(module);
        } else {
            for (Path modulePath : modulePaths) {
                if (!Files.isDirectory(modulePath)) {
                    builder.attribute(ATTR_DETECTION_WARNING, "declared module " + modulePath + " does not exist");
                    continue;
                }
                ModuleInfo module = readMavenModule(modulePath, javaVersion);
                builder.addModule(module);
                builder.addDetectedDescriptor(modulePath.resolve("pom.xml"));
            }
        }
        detectWrapper(root, builder, "mvnw");

        List<ModuleInfo> modules = builder.build().modules();
        builder.attribute(ATTR_HAS_TESTS, String.valueOf(modules.stream().anyMatch(ModuleInfo::hasTests)));
        return builder.build();
    }

    private static ModuleInfo readMavenModule(Path modulePath, String parentJavaVersion) {
        Path pom = modulePath.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return new ModuleInfo(modulePath.getFileName().toString(), modulePath, "jar",
                    existing(modulePath.resolve("src/main/java")), existing(modulePath.resolve("src/test/java")),
                    parentJavaVersion);
        }
        try {
            Element element = parseXml(pom).getDocumentElement();
            String artifactId = text(element, "artifactId");
            String packaging = text(element, "packaging");
            String javaVersion = mavenJavaVersion(element);
            return new ModuleInfo(
                    artifactId == null ? modulePath.getFileName().toString() : artifactId,
                    modulePath,
                    packaging == null ? "jar" : packaging,
                    existing(modulePath.resolve("src/main/java")),
                    existing(modulePath.resolve("src/test/java")),
                    javaVersion == null ? parentJavaVersion : javaVersion);
        } catch (Exception e) {
            return new ModuleInfo(modulePath.getFileName().toString(), modulePath, "jar",
                    existing(modulePath.resolve("src/main/java")), existing(modulePath.resolve("src/test/java")),
                    parentJavaVersion);
        }
    }

    private static ProjectInfo inspectGradle(ProjectRef project, ProjectInfo.Builder builder) {
        Path root = project.root();
        builder.buildSystem(BuildSystemId.GRADLE);
        String descriptor = Files.isRegularFile(root.resolve("build.gradle.kts")) ? "build.gradle.kts"
                : Files.isRegularFile(root.resolve("build.gradle")) ? "build.gradle" : "settings.gradle";
        builder.attribute(ATTR_DESCRIPTOR, descriptor);
        builder.addDetectedDescriptor(root.resolve(descriptor));

        List<String> settingsFiles = List.of("settings.gradle", "settings.gradle.kts");
        List<Path> modules = new ArrayList<>();
        String rootName = project.name();
        String javaVersion = null;
        for (String settingsName : settingsFiles) {
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
                String include = includeMatcher.group(1).replace(':', '/');
                Path modulePath = root.resolve(include).normalize();
                if (Files.isDirectory(modulePath)) {
                    modules.add(modulePath);
                    builder.addDetectedDescriptor(modulePath);
                }
            }
        }

        String buildText = "";
        for (String buildName : List.of("build.gradle", "build.gradle.kts")) {
            Path buildFile = root.resolve(buildName);
            if (Files.isRegularFile(buildFile)) {
                buildText = readString(buildFile);
                Matcher versionMatcher = GRADLE_JAVA_VERSION.matcher(buildText);
                if (versionMatcher.find()) {
                    javaVersion = versionMatcher.group(1);
                }
            }
        }
        builder.javaVersion(javaVersion);
        detectTestFrameworksFromText(buildText.toLowerCase(Locale.ROOT), builder);

        if (modules.isEmpty()) {
            builder.addModule(new ModuleInfo(rootName, root, "jar",
                    existing(root.resolve("src/main/java")), existing(root.resolve("src/test/java")), javaVersion));
        } else {
            for (Path modulePath : modules) {
                String moduleBuild = readString(modulePath.resolve(
                        Files.isRegularFile(modulePath.resolve("build.gradle.kts")) ? "build.gradle.kts" : "build.gradle"));
                detectTestFrameworksFromText(moduleBuild.toLowerCase(Locale.ROOT), builder);
                builder.addModule(new ModuleInfo(modulePath.getFileName().toString(), modulePath, "jar",
                        existing(modulePath.resolve("src/main/java")), existing(modulePath.resolve("src/test/java")),
                        javaVersion));
            }
        }
        detectWrapper(root, builder, "gradlew");
        List<ModuleInfo> moduleInfos = builder.build().modules();
        builder.attribute(ATTR_HAS_TESTS, String.valueOf(moduleInfos.stream().anyMatch(ModuleInfo::hasTests)));
        return builder.build();
    }

    private static void detectWrapper(Path root, ProjectInfo.Builder builder, String wrapperName) {
        Path wrapper = root.resolve(wrapperName);
        if (Files.isRegularFile(wrapper)) {
            builder.buildWrapper(wrapper);
        }
    }

    private static void detectTestFrameworks(Path root, ProjectInfo.Builder builder, String dependencyText) {
        StringBuilder text = new StringBuilder(dependencyText);
        for (String buildName : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
            Path file = root.resolve(buildName);
            if (Files.isRegularFile(file)) {
                text.append(readString(file).toLowerCase(Locale.ROOT));
            }
        }
        detectTestFrameworksFromText(text.toString(), builder);
    }

    private static void detectTestFrameworksFromText(String text, ProjectInfo.Builder builder) {
        if (text.contains("junit-jupiter") || text.contains("org.junit.jupiter") || text.contains("junit5")) {
            builder.addTestFramework("junit5");
        }
        if (text.contains("junit") && !text.contains("junit-jupiter")) {
            builder.addTestFramework("junit4");
        }
        if (text.contains("testng")) {
            builder.addTestFramework("testng");
        }
        if (text.contains("spock")) {
            builder.addTestFramework("spock");
        }
        if (text.contains("assertj")) {
            builder.addTestFramework("assertj");
        }
        if (text.contains("mockito")) {
            builder.addTestFramework("mockito");
        }
    }

    private static List<String> rootPackages(List<ModuleInfo> modules) {
        Set<String> packages = new LinkedHashSet<>();
        for (ModuleInfo module : modules) {
            for (Path sourceDir : module.sourceDirs()) {
                if (!Files.isDirectory(sourceDir)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(sourceDir, 8)) {
                    walk.filter(p -> p.toString().endsWith(".java")).limit(500).forEach(file -> {
                        String relative = sourceDir.relativize(file).toString().replace('\\', '/');
                        int slash = relative.indexOf('/');
                        if (slash > 0) {
                            packages.add(relative.substring(0, slash));
                        }
                    });
                } catch (IOException _) {
                    // package discovery is best effort
                }
            }
        }
        return List.copyOf(packages);
    }

    private static String mavenJavaVersion(Element project) {
        for (String tag : List.of("maven.compiler.release", "maven.compiler.target", "java.version")) {
            NodeList nodes = project.getElementsByTagName(tag);
            if (nodes.getLength() > 0) {
                String value = nodes.item(0).getTextContent().trim();
                if (!value.isEmpty() && !value.startsWith("$")) {
                    return value;
                }
            }
        }
        NodeList release = project.getElementsByTagName("release");
        if (release.getLength() > 0) {
            NodeList children = project.getElementsByTagName("source");
            if (children.getLength() > 0) {
                return children.item(0).getTextContent().trim();
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
     * Parses a tool report such as a JaCoCo or PIT XML file.
     *
     * <p>Those reports carry a DOCTYPE declaration. Rather than enabling DTD processing - which would
     * make the parser resolution-capable while reading a file produced by the project under
     * verification - the declaration is removed and the document is parsed with DTD support disabled.
     *
     * @param file report file
     * @return the parsed document
     * @throws IOException  when the file cannot be read
     * @throws SAXException when the content is not well formed XML
     * @throws ParserConfigurationException when the parser cannot be configured securely
     */
    public static Document parseReportXml(Path file) throws IOException, SAXException, ParserConfigurationException {
        return dev.codewitness.support.XmlDocuments.parse(file);
    }

    /** Parses XML with external entity resolution disabled: build files are untrusted input. */
    public static Document parseXml(Path file) throws IOException, SAXException, ParserConfigurationException {
        return dev.codewitness.support.XmlDocuments.parse(file);
    }

    private static List<Path> existing(Path directory) {
        return Files.isDirectory(directory) ? List.of(directory) : List.of();
    }

    private static String readString(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
