package dev.codewitness.vhdl;

import dev.codewitness.core.exec.ProcessResult;
import dev.codewitness.core.exec.ProcessSpec;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.TestCaseResult;
import dev.codewitness.core.model.TestResult;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.core.spi.VerificationTools;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs a VHDL project's existing testbenches with GHDL.
 *
 * <p>A VHDL test has no runner: a testbench is a top-level entity that instantiates the design, drives
 * it and stops the simulation, and the only way to run it is to analyse the tree into a work library
 * and simulate that entity. That is exactly what this provider does, once per testbench, and it
 * reports one test case per testbench because there is no case-level protocol to read.
 *
 * <p>Analysis is shared: the whole tree is analysed once and every testbench is simulated out of the
 * same library. A tree that does not analyse is an ERROR for each testbench rather than a failing
 * test, because the analysis is a statement about the build, and VHDL's analysis of one tree also
 * elaborates the design a testbench instantiates. A simulation that exits non-zero, stops on an
 * assertion or prints a counterexample is a failure; a simulation that runs to completion is a pass.
 *
 * <p>There is deliberately no single test command. A VHDL test is inherently several processes -
 * analyse, then simulate - so {@link #testCommand} returns nothing rather than naming a command that
 * cannot have produced the result, and {@link #replayCommand} returns the recipe a human would type.
 */
public final class VhdlTestProvider implements VerificationTools.TestRunner {

    @Override
    public String id() {
        return "vhdl-test";
    }

    @Override
    public boolean supports(ProjectInfo project) {
        return project.language().equals(LanguageId.VHDL);
    }

    @Override
    public TestResult runTests(VerificationContext context) {
        List<VhdlDesignUnit> testbenches = VhdlProjectDetector.testbenches(context.projectRoot());
        if (testbenches.isEmpty()) {
            return TestResult.notExecuted("no testbench entity was found; a VHDL test is a top-level entity "
                    + "that drives the design and stops the simulation, and without one there is nothing to simulate");
        }
        List<Path> sources = VhdlProjectDetector.sourceFiles(context.projectRoot());
        String ghdl = VhdlTool.ghdl(context.projectInfo(), context);
        Path workDirectory = VhdlBuildProvider.workLibrary(context, "tests");

        VhdlAnalyzer.Analysis analysis = VhdlAnalyzer.analyze(context, ghdl, workDirectory, sources);
        List<TestCaseResult> cases = new ArrayList<>();
        List<Path> reports = new ArrayList<>();
        Duration total = analysis.duration();
        if (!analysis.success()) {
            for (VhdlDesignUnit testbench : testbenches) {
                String suite = VhdlSourceParser.stem(testbench.file().getFileName().toString());
                cases.add(VhdlTestReportParser.analysisFailure(suite, testbench.name(), analysis.representative()));
                reports.add(context.artifacts().write("vhdl/tests/" + safeName(testbench.name()) + "-analysis.log",
                        analysis.failureOutput()));
            }
            return TestResult.from(cases, total, reports,
                    "the design did not analyse, so no testbench could be simulated");
        }

        for (VhdlDesignUnit testbench : testbenches) {
            String suite = VhdlSourceParser.stem(testbench.file().getFileName().toString());
            String name = testbench.name();
            context.emit("tests", "simulating testbench " + name);
            ProcessResult simulated = context.runner().run(ProcessSpec.builder(
                            VhdlBuildProvider.runCommand(ghdl, workDirectory, name), context.projectRoot())
                    .environment(VhdlTool.environment(context))
                    .timeout(context.config().limits().timeout())
                    .maxOutputBytes(2_000_000)
                    .build());
            total = total.plus(simulated.duration());
            reports.add(context.artifacts().write(
                    "vhdl/tests/" + safeName(name) + "-simulation.log", simulated.combinedOutput()));
            cases.add(VhdlTestReportParser.fromSimulation(suite, name, simulated));
        }

        if (cases.isEmpty()) {
            return TestResult.notExecuted("no testbench could be analysed or simulated");
        }
        String note = "the tree was analysed with ghdl and each testbench simulated with ghdl -r; a testbench is "
                + "reported as one case because VHDL gives a command line caller no case-level protocol";
        return TestResult.from(cases, total, reports, note);
    }

    @Override
    public List<String> testCommand(VerificationContext context) {
        // VHDL has no single test invocation: the phase analyses the tree and then simulates each
        // testbench. Naming only the analysis or only the simulation would describe an invocation that
        // cannot have produced the result, so the evidence carries no command and the note says why.
        return List.of();
    }

    @Override
    public List<String> replayCommand(VerificationContext context, TestCaseResult testCase) {
        if (testCase == null) {
            return List.of();
        }
        List<Path> sources = VhdlProjectDetector.sourceFiles(context.projectRoot());
        if (sources.isEmpty()) {
            return List.of();
        }
        // A test case's suite is the testbench file's own name and its name is the entity, which is what
        // ghdl -r needs; when only the suite is known, the entity is taken from the same convention.
        String root = testCase.name() == null || testCase.name().isBlank() ? testCase.suite() : testCase.name();
        return VhdlTestCommand.replay(context, sources, List.of(), root);
    }

    private static String safeName(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
