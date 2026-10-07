package dev.codewitness.cpp;

import dev.codewitness.core.config.Scenario;
import dev.codewitness.core.config.VerificationConfig;
import dev.codewitness.core.evidence.EvidenceLedger;
import dev.codewitness.core.exec.ExecutionSandbox;
import dev.codewitness.core.exec.LocalProcessSandbox;
import dev.codewitness.core.io.ArtifactStore;
import dev.codewitness.core.io.WorkspaceManager;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;
import dev.codewitness.core.result.BudgetUsage;
import dev.codewitness.core.spi.VerificationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds a real {@link VerificationContext} around a temporary CMake project.
 *
 * <p>The detector, the parsers, the analyzers, the generator and the command builders under test only
 * read source files and configuration, so the context is complete as soon as it has a project, a
 * workspace, an artefact store, a ledger, a budget and a sandbox. Nothing executes project code in these
 * tests; the sandbox is present because the context requires one, and it is the real one so that a test
 * that does need to run a command runs it the way a verification run would.
 */
public final class CppTestContexts {

    /** The smallest CMake project that declares a library, for tests that only need one to exist. */
    public static final String LIBRARY_CMAKE = """
            cmake_minimum_required(VERSION 3.20)
            project(ledger VERSION 1.0.0 LANGUAGES CXX)

            set(CMAKE_CXX_STANDARD 17)

            add_library(ledger STATIC src/ledger.cpp)
            target_include_directories(ledger PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
            """;

    /** A CMake project that also builds and registers a test. */
    public static final String TESTING_CMAKE = """
            cmake_minimum_required(VERSION 3.20)
            project(ledger VERSION 1.0.0 LANGUAGES CXX)

            set(CMAKE_CXX_STANDARD 17)

            add_library(ledger STATIC src/ledger.cpp)
            target_include_directories(ledger PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)

            add_executable(withdrawal_ledger_test tests/withdrawal_ledger_test.cpp)
            target_link_libraries(withdrawal_ledger_test PRIVATE ledger)

            enable_testing()
            add_test(NAME withdrawal_ledger_test COMMAND withdrawal_ledger_test)
            """;

    private CppTestContexts() {
    }

    /** Writes a UTF-8 file below {@code base}, creating parent directories as needed. */
    public static Path write(Path base, String relative, String content) throws IOException {
        Path file = base.resolve(relative);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    /** @return project information detected from a real directory. */
    public static ProjectInfo projectInfo(Path root) {
        return CppProjectDetector.inspect(ProjectRef.of(root));
    }

    /** A context with the default configuration and no scenarios. */
    public static VerificationContext context(Path root, Path runDirectory) {
        return context(root, runDirectory, List.of());
    }

    /** A context with the given scenarios declared in code. */
    public static VerificationContext context(Path root, Path runDirectory, List<Scenario> scenarios) {
        ProjectRef projectRef = ProjectRef.of(root);
        WorkspaceManager.Workspace workspace =
                WorkspaceManager.prepare(projectRef, runDirectory, WorkspaceManager.Mode.IN_PLACE);
        ExecutionSandbox sandbox = new LocalProcessSandbox(List.of(root, runDirectory));
        VerificationConfig defaults = VerificationConfig.defaults();
        VerificationConfig config = new VerificationConfig(
                defaults.project(), defaults.language(), defaults.limits(), defaults.tests(),
                defaults.mutation(), defaults.propertyTesting(), defaults.staticAnalysis(),
                defaults.security(), defaults.llm(), defaults.report(), defaults.sandbox(), scenarios);
        return VerificationContext.builder()
                .runId("CW-2026-01-01-TEST0")
                .projectRef(projectRef)
                .projectInfo(projectInfo(root))
                .config(config)
                .workspace(workspace)
                .artifacts(new ArtifactStore(runDirectory))
                .budget(new BudgetUsage.Tracker(config.toBudget()))
                .sandbox(sandbox)
                .evidenceLedger(new EvidenceLedger("CW-2026-01-01-TEST0"))
                .build();
    }

    /** A fixture-shaped CMake project with a library and one registered test. */
    public static VerificationContext fixture(Path project, Path run) throws IOException {
        write(project, "CMakeLists.txt", TESTING_CMAKE);
        write(project, "include/ledger/withdrawal_ledger.hpp", """
                #ifndef LEDGER_WITHDRAWAL_LEDGER_HPP
                #define LEDGER_WITHDRAWAL_LEDGER_HPP
                namespace ledger {
                class WithdrawalLedger {
                public:
                    long long totalWithinLast24Hours(long long atMillis) const;
                };
                }  // namespace ledger
                #endif
                """);
        write(project, "src/withdrawal_ledger.cpp", """
                #include "ledger/withdrawal_ledger.hpp"
                namespace ledger {
                long long WithdrawalLedger::totalWithinLast24Hours(long long) const { return 0; }
                }  // namespace ledger
                """);
        write(project, "tests/withdrawal_ledger_test.cpp", """
                #include "ledger/withdrawal_ledger.hpp"
                int main() { return 0; }
                """);
        return context(project, run);
    }
}
