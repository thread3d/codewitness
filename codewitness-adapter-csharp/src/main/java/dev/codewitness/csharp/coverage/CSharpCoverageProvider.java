package dev.codewitness.csharp.coverage;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.CoverageReport;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;
import dev.codewitness.csharp.CSharpBuildProvider;
import dev.codewitness.csharp.CSharpTestProvider;
import dev.codewitness.csharp.CSharpXml;
import dev.codewitness.csharp.DotnetTool;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Measures coverage by asking {@code dotnet test} to collect it.
 *
 * <p>The .NET SDK has no coverage of its own; coverage comes from a data collector, and the one every
 * .NET project already has if it has any is coverlet, which writes a cobertura report. The provider
 * therefore runs the project's own test command with {@code --collect:"XPlat Code Coverage"} and reads
 * the report coverlet wrote, rather than the console summary, which rounds and does not break the
 * figures down per class.
 *
 * <p>A project without the collector is not a failure: the run says coverage was not measured and names
 * the missing collector, because a project should not lose the rest of its verification over an optional
 * measurement. What it must never do is report zero coverage, which would read as a measurement.
 *
 * <p>Coverlet records lines and branches. Both are reported under the names the report model uses for
 * lines and branches; no figure is invented for anything the tool did not measure.
 */
public final class CSharpCoverageProvider implements VerificationTools.CoverageProvider {

    /** The file coverlet writes, whatever directory the test host chose below the results directory. */
    private static final String COBERTURA = "coverage.cobertura.xml";

    @Override
    public String id() {
        return "dotnet-coverage";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.CSHARP);
    }

    @Override
    public CoverageReport measure(VerificationContext context) {
        List<Path> targets = CSharpTestProvider.testTargets(context);
        if (targets.isEmpty()) {
            return CoverageReport.notMeasured("dotnet-coverage",
                    "no project references a .NET test host, so there are no tests to measure coverage over");
        }
        Path root = context.projectRoot();
        List<Path> reports = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        int index = 0;
        for (Path target : targets) {
            index++;
            Path resultsDirectory = context.artifacts().artifactsDirectory().resolve("dotnet/coverage/" + index);
            try {
                // Coverage reports from an earlier attempt would be indistinguishable from fresh ones.
                deleteRecursively(resultsDirectory);
                Files.createDirectories(resultsDirectory);
            } catch (IOException e) {
                notes.add("cannot prepare the coverage directory " + resultsDirectory + ": " + e);
                continue;
            }
            List<String> command = commandFor(context, target, resultsDirectory);
            context.emit("coverage", "running dotnet test with the cobertura collector on "
                    + target.getFileName());

            ProcessResult result = context.runner().run(ProcessSpec.builder(command, root)
                    .environment(DotnetTool.environment(context))
                    .timeout(context.config().limits().timeout())
                    .maxOutputBytes(2_000_000)
                    .build());
            if (!result.started()) {
                notes.add("dotnet could not be started: " + result.failureReason());
                continue;
            }
            List<Path> found = coverageReports(resultsDirectory);
            if (found.isEmpty()) {
                notes.add("dotnet test on " + target.getFileName() + " produced no cobertura report ("
                        + result.describeOutcome() + "); the collector is only present when the test project "
                        + "references coverlet.collector. Last output: "
                        + DotnetTool.lastLines(result.combinedOutput(), 4));
                continue;
            }
            reports.addAll(found);
        }

        if (reports.isEmpty()) {
            return CoverageReport.notMeasured("dotnet-coverage",
                    "no coverage was collected: " + String.join("; ", notes));
        }
        return combine(reports, "coverlet cobertura from dotnet test; " + reports.size() + " report(s)");
    }

    /** @return the argv that runs one test project with the cobertura collector enabled. */
    public static List<String> commandFor(VerificationContext context, Path target, Path resultsDirectory) {
        List<String> command = new ArrayList<>();
        command.add(DotnetTool.dotnet(context.projectInfo(), context));
        command.add("test");
        command.add(CSharpBuildProvider.relativeArgument(context.projectRoot(), target));
        command.addAll(DotnetTool.commonArguments());
        command.add("--results-directory");
        command.add(resultsDirectory.toString());
        command.add("--collect");
        command.add("XPlat Code Coverage");
        return List.copyOf(command);
    }

    /** @return every cobertura report below a results directory, sorted. */
    static List<Path> coverageReports(Path resultsDirectory) {
        List<Path> reports = new ArrayList<>();
        if (!Files.isDirectory(resultsDirectory)) {
            return reports;
        }
        try (Stream<Path> walk = Files.walk(resultsDirectory)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(COBERTURA))
                    .sorted()
                    .forEach(reports::add);
        } catch (IOException _) {
            // an unreadable directory yields no reports, which the caller reports rather than guesses about
        }
        return List.copyOf(reports);
    }

    /** @return the combined measurement of every report, or a not-measured result when none is readable. */
    static CoverageReport combine(List<Path> reports, String note) {
        int lineCovered = 0;
        int lineTotal = 0;
        int branchCovered = 0;
        int branchTotal = 0;
        Map<String, Double> perClass = new LinkedHashMap<>();
        for (Path report : reports) {
            CoverageReport parsed = parse(report, note);
            if (!parsed.measured()) {
                continue;
            }
            lineCovered += parsed.lineCovered();
            lineTotal += parsed.lineTotal();
            branchCovered += parsed.branchCovered();
            branchTotal += parsed.branchTotal();
            parsed.perClassLineCoverage().forEach((name, coverage) -> perClass.merge(name, coverage, (a, b) -> b));
        }
        if (lineTotal == 0) {
            return CoverageReport.notMeasured("dotnet-coverage",
                    "the cobertura report(s) contained no instrumented line");
        }
        return new CoverageReport(true, "dotnet-coverage", note, lineCovered, lineTotal, branchCovered,
                branchTotal, perClass, reports);
    }

    /**
     * Parses one cobertura report.
     *
     * <p>The aggregate counts are read from the document's own attributes, which coverlet writes for
     * exactly this purpose. When a report omits them the {@code <line>} elements are counted instead, so
     * a report written by another cobertura producer is still measured rather than discarded.
     *
     * @param report the cobertura document
     * @param note   explanation carried into the report
     * @return the measured coverage, or a not-measured report when the document holds no line
     */
    static CoverageReport parse(Path report, String note) {
        Document document;
        try {
            document = CSharpXml.parse(report);
        } catch (Exception e) {
            return CoverageReport.notMeasured("dotnet-coverage",
                    "cannot read " + report.getFileName() + ": " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
        }
        Element root = document.getDocumentElement();
        if (root == null) {
            return CoverageReport.notMeasured("dotnet-coverage", report.getFileName() + " is empty");
        }
        Integer linesCovered = integer(CSharpXml.attribute(root, "lines-covered"));
        Integer linesValid = integer(CSharpXml.attribute(root, "lines-valid"));
        Integer branchesCovered = integer(CSharpXml.attribute(root, "branches-covered"));
        Integer branchesValid = integer(CSharpXml.attribute(root, "branches-valid"));

        Map<String, Double> perClass = new LinkedHashMap<>();
        int countedCovered = 0;
        int countedTotal = 0;
        // A cobertura document has one <packages> element; the per-class figures and the line elements
        // below it are read even when the document states its own totals, because the fallback has to be
        // there for the reports that do not.
        Element packages = CSharpXml.firstDescendant(root, "packages");
        for (Element packageElement : CSharpXml.children(packages, "package")) {
            for (Element classes : CSharpXml.children(packageElement, "classes")) {
                for (Element classElement : CSharpXml.children(classes, "class")) {
                    String name = CSharpXml.attribute(classElement, "name");
                    Double rate = rate(CSharpXml.attribute(classElement, "line-rate"));
                    if (name != null && rate != null) {
                        perClass.put(name, rate * 100.0);
                    }
                    for (Element methods : CSharpXml.children(classElement, "methods")) {
                        for (Element method : CSharpXml.children(methods, "method")) {
                            for (Element line : allLines(method)) {
                                if ("true".equalsIgnoreCase(CSharpXml.attribute(line, "branch"))) {
                                    continue;
                                }
                                countedTotal++;
                                Integer hits = integer(CSharpXml.attribute(line, "hits"));
                                if (hits != null && hits > 0) {
                                    countedCovered++;
                                }
                            }
                        }
                    }
                }
            }
        }

        int covered = linesCovered != null ? linesCovered : countedCovered;
        int total = linesValid != null ? linesValid : countedTotal;
        if (total == 0) {
            return CoverageReport.notMeasured("dotnet-coverage",
                    report.getFileName() + " contained no instrumented line");
        }
        return new CoverageReport(true, "dotnet-coverage", note, covered, total,
                branchesCovered == null ? 0 : branchesCovered, branchesValid == null ? 0 : branchesValid,
                perClass, List.of(report));
    }

    private static List<Element> allLines(Element methodContainer) {
        List<Element> lines = new ArrayList<>();
        for (Element linesElement : CSharpXml.children(methodContainer, "lines")) {
            lines.addAll(CSharpXml.children(linesElement, "line"));
        }
        return lines;
    }

    private static Double rate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private static Integer integer(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
