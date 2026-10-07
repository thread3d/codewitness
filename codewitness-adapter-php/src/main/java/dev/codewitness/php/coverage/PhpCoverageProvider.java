package dev.codewitness.php.coverage;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.php.PhpInterpreter;
import dev.codewitness.php.PhpProjectDetector;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Measures PHP coverage with PHPUnit's Clover XML report.
 *
 * <p>Coverage in PHP needs one of two interpreter extensions - Xdebug or PCOV - and neither is part of a
 * default PHP installation. The interpreter probe records which of them answered
 * {@code extension_loaded}, and this provider runs PHPUnit with {@code --coverage-clover} only when one
 * did. When neither did, the report says coverage was <em>not measured</em> and names the missing driver;
 * it never reports zero, because a zero is indistinguishable from a project whose tests execute nothing.
 *
 * <p>The loaded extension is necessary but not sufficient: Xdebug 3 only collects coverage when it runs in
 * {@code coverage} mode, and that is a setting of the interpreter rather than of the extension list. A run
 * that PHPUnit refuses for that reason produces no report, and the failure is reported with PHPUnit's own
 * output rather than being turned into a percentage.
 *
 * <p>Only the project's own files are counted. A Clover report can name any file the run loaded, and the
 * ones that matter here are the ones this project wrote: a file under {@code vendor/}, a path outside the
 * project, or a stale entry for a file that no longer exists is left out, because a dependency's coverage
 * says nothing about the code under verification.
 */
public final class PhpCoverageProvider implements VerificationTools.CoverageProvider {

    /**
     * The coverage tool as it is named in the report.
     *
     * <p>PHPUnit is only the writer; the measurement itself is Clover's line/statement model, and naming
     * the format is more useful to a reader than naming the runner that happened to be configured.
     */
    private static final String TOOL = "clover";

    /**
     * Turns a malformed report into an exception for the caller instead of a line on the console.
     *
     * <p>A warning is kept: a document that parses with a warning is still a document a measurement can be
     * read from, and refusing it would replace evidence with a note.
     */
    private static final ErrorHandler REPORT_ERRORS = new ErrorHandler() {
        @Override
        public void warning(SAXParseException exception) {
            // a warning does not stop the document from being read
        }

        @Override
        public void error(SAXParseException exception) throws SAXParseException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXParseException {
            throw exception;
        }
    };

    @Override
    public String id() {
        return "php-coverage";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.PHP);
    }

    @Override
    public CoverageReport measure(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        String driver = driver(project);
        if (driver == null) {
            return CoverageReport.notMeasured(TOOL,
                    "no coverage driver is loaded in the PHP interpreter (" + PhpInterpreter.php(project, context)
                            + "): the inspection probe reported neither Xdebug nor PCOV as loaded, and PHPUnit "
                            + "cannot measure coverage without one, so coverage was not measured rather than "
                            + "reported as zero");
        }
        List<String> command = coverageCommand(context);
        if (command.isEmpty()) {
            return CoverageReport.notMeasured(TOOL, "PHPUnit was not found, so the Clover report this "
                    + "measurement reads could not be produced; PHP has no standard-library test runner");
        }
        Path report = reportPath(context);
        try {
            Files.createDirectories(report.getParent());
            Files.deleteIfExists(report);
        } catch (IOException e) {
            return CoverageReport.notMeasured(TOOL, "cannot prepare the Clover report directory: " + e);
        }
        context.emit("coverage", "measuring coverage with " + driver + " through PHPUnit");

        ProcessResult result = context.runner().run(ProcessSpec.builder(command, context.projectRoot())
                .environment(PhpInterpreter.environment(project, context.projectRoot(),
                        context.artifacts().artifactsDirectory()))
                .timeout(context.config().limits().timeout())
                .maxOutputBytes(1_000_000)
                .build());
        if (result.timedOut()) {
            // A killed run may have written a partial report for the tests that finished; a percentage
            // describing an unfinished test run is not a measurement.
            return CoverageReport.notMeasured(TOOL, "PHPUnit was killed after the "
                    + context.config().limits().timeout().toSeconds() + "s timeout, so a partial Clover report "
                    + "is not a measurement; last output: " + result.tail(5));
        }
        if (!Files.isRegularFile(report)) {
            return CoverageReport.notMeasured(TOOL, "PHPUnit produced no Clover report ("
                    + result.describeOutcome() + "); last output: " + result.tail(5));
        }
        return parse(report, context.projectRoot(),
                "Clover XML written by PHPUnit under the " + driver + " driver; " + result.describeOutcome());
    }

    /**
     * @return the coverage driver the probe observed, or {@code null} when there is none
     *
     * <p>Anything the attribute holds that is not {@code xdebug} or {@code pcov} is read as "no driver": an
     * unknown value is not evidence that a driver can measure anything.
     */
    static String driver(ProjectInfo project) {
        String recorded = project.attributes().get(PhpInterpreter.ATTR_COVERAGE_DRIVER);
        if (recorded == null || recorded.isBlank()) {
            return null;
        }
        String normalised = recorded.strip().toLowerCase(Locale.ROOT);
        return normalised.equals("xdebug") || normalised.equals("pcov") ? normalised : null;
    }

    /**
     * @return the argv that writes this run's Clover report, or an empty list when PHPUnit is unavailable
     *
     * <p>This is deliberately not the test provider's command with an option appended: that one also asks
     * for a JUnit report, and a coverage run that had to write a second report into a directory the test
     * phase prepared would make the measurement depend on the order the phases ran in.
     */
    static List<String> coverageCommand(VerificationContext context) {
        ProjectInfo project = context.projectInfo();
        List<String> phpunit = PhpInterpreter.phpunit(project, context);
        if (phpunit.isEmpty()) {
            return List.of();
        }
        List<String> command = new ArrayList<>(phpunit);
        command.add("--coverage-clover");
        command.add(reportPath(context).toString());
        command.add("--do-not-cache-result");
        command.add("--colors=never");
        Path configuration = configurationFile(context.projectRoot());
        if (configuration != null) {
            command.add("--configuration");
            command.add(configuration.toString());
        } else {
            for (Path directory : project.testSourceDirs()) {
                command.add(directory.toString());
            }
        }
        return List.copyOf(command);
    }

    /** @return where this run's Clover report is written, inside the run's artefact store. */
    static Path reportPath(VerificationContext context) {
        return context.artifacts().artifactsDirectory().resolve("php").resolve("coverage").resolve("clover.xml");
    }

    /**
     * Parses a Clover report into a coverage summary.
     *
     * @param report      the Clover XML file
     * @param projectRoot the project whose files may be counted
     * @param note        explanation carried into the report
     * @return the summary, or "not measured" when the report could not be read or named no file of this
     *     project
     */
    public static CoverageReport parse(Path report, Path projectRoot, String note) {
        Document document;
        try {
            document = parseXml(report);
        } catch (Exception e) {
            return CoverageReport.notMeasured(TOOL, "the Clover report " + report.getFileName()
                    + " could not be read (" + e.getClass().getSimpleName() + "): " + e.getMessage());
        }
        int statementsCovered = 0;
        int statementsTotal = 0;
        int branchesCovered = 0;
        int branchesTotal = 0;
        Map<String, Double> perFile = new LinkedHashMap<>();
        int countedFiles = 0;

        NodeList files = document.getElementsByTagName("file");
        for (int i = 0; i < files.getLength(); i++) {
            Element file = (Element) files.item(i);
            Path path = projectFile(projectRoot, file.getAttribute("name"));
            if (path == null) {
                continue;
            }
            countedFiles++;
            int covered = 0;
            int total = 0;
            NodeList lines = file.getElementsByTagName("line");
            for (int j = 0; j < lines.getLength(); j++) {
                Element line = (Element) lines.item(j);
                String type = line.getAttribute("type");
                int count = integer(line, "count");
                if ("stmt".equals(type)) {
                    total++;
                    statementsTotal++;
                    if (count > 0) {
                        covered++;
                        statementsCovered++;
                    }
                } else if ("cond".equals(type)) {
                    branchesTotal++;
                    if (count > 0) {
                        branchesCovered++;
                    }
                }
                // A "method" line names the method that starts there, not a statement: counting it would
                // inflate the denominator with something no test can execute.
            }
            perFile.put(relativeName(projectRoot, path),
                    total == 0 ? 0.0 : 100.0 * covered / total);
        }
        if (countedFiles == 0) {
            return CoverageReport.notMeasured(TOOL, "the Clover report " + report.getFileName()
                    + " names no file of this project (only files under vendor/, outside the project, or "
                    + "already deleted), so nothing was measured about the code under verification");
        }
        return new CoverageReport(true, TOOL, note, statementsCovered, statementsTotal,
                branchesCovered, branchesTotal, perFile, List.of(report));
    }

    /**
     * @return the report's file when it is a file this project wrote, otherwise {@code null}
     *
     * <p>A file counts when it exists, lies inside the project root and is not in the vendor tree or a
     * cache. The existence check is what excludes a stale entry from an earlier build, and the root check
     * uses real paths because a Clover report names the path the interpreter opened, which on a machine
     * with a symlinked checkout is not the path the run was started from.
     */
    private static Path projectFile(Path projectRoot, String name) {
        if (projectRoot == null || name == null || name.isBlank()) {
            return null;
        }
        Path path;
        try {
            path = Path.of(name);
        } catch (InvalidPathException _) {
            return null;
        }
        if (!path.isAbsolute() || !Files.isRegularFile(path)) {
            return null;
        }
        if (!realPath(path).startsWith(realPath(projectRoot))) {
            return null;
        }
        if (PhpProjectDetector.isExcludedSource(projectRoot, path)) {
            return null;
        }
        return path;
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException _) {
            return path.toAbsolutePath().normalize();
        }
    }

    /** @return the report's name for a file, relative to the project root where that is possible. */
    private static String relativeName(Path projectRoot, Path path) {
        try {
            return realPath(projectRoot).relativize(realPath(path)).toString();
        } catch (IllegalArgumentException _) {
            return path.getFileName().toString();
        }
    }

    private static Path configurationFile(Path projectRoot) {
        Path configuration = projectRoot.resolve("phpunit.xml");
        if (Files.isRegularFile(configuration)) {
            return configuration;
        }
        Path fallback = projectRoot.resolve("phpunit.xml.dist");
        return Files.isRegularFile(fallback) ? fallback : null;
    }

    private static Document parseXml(Path file) throws Exception {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        // Security: strip any DOCTYPE and disable external entity resolution, because the report is
        // produced from a project CodeWitness treats as hostile.
        String withoutDoctype = content.replaceAll("(?s)<!DOCTYPE[^>]*>", "");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder builder = factory.newDocumentBuilder();
        // A report this provider refuses is reported to the caller, not printed to the console: a library
        // writes to the report, and the default handler would put "[Fatal Error]" on the stderr of a run
        // whose result already says what happened.
        builder.setErrorHandler(REPORT_ERRORS);
        return builder.parse(new InputSource(new StringReader(withoutDoctype)));
    }

    private static int integer(Element element, String attribute) {
        try {
            return Integer.parseInt(element.getAttribute(attribute).strip());
        } catch (NumberFormatException _) {
            return 0;
        }
    }
}
