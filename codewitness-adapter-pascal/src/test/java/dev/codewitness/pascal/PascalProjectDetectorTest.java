package dev.codewitness.pascal;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;
import dev.codewitness.core.model.ProjectRef;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Detection reads the sources, not a manifest, and never compiles anything. */
class PascalProjectDetectorTest {

    @TempDir
    Path root;

    private Path write(String relative, String content) throws IOException {
        return PascalTestContexts.write(root, relative, content);
    }

    @Test
    @DisplayName("a unit, a program and a test program are told apart by what they declare")
    void distinguishesUnitsProgramsAndTests() throws IOException {
        write("src/ledger.pas", """
                unit ledger;
                {$mode objfpc}{$H+}
                interface
                uses SysUtils;
                function Withdraw(balance, amount: Integer): Integer;
                implementation
                end.
                """);
        write("src/ledgerapp.lpr", """
                program ledgerapp;
                {$mode objfpc}{$H+}
                uses ledger;
                begin
                end.
                """);
        write("tests/ledgertests.pas", """
                unit ledgertests;
                {$mode objfpc}{$H+}
                interface
                uses fpcunit, testregistry;
                type
                  TWithdrawTest = class(TTestCase)
                  end;
                implementation
                initialization
                  RegisterTest(TWithdrawTest);
                end.
                """);

        List<PascalModule> modules = PascalProjectDetector.modules(root);

        assertEquals(3, modules.size());
        assertEquals(List.of("ledgerapp"), PascalProjectDetector.programs(root).stream()
                .map(PascalModule::name).toList());
        assertEquals(List.of("ledger"), PascalProjectDetector.units(root).stream()
                .map(PascalModule::name).toList());
        assertEquals(List.of("ledgertests"), PascalProjectDetector.tests(root).stream()
                .map(PascalModule::name).toList());
        assertTrue(PascalProjectDetector.looksLikePascalProject(root));
    }

    @Test
    @DisplayName("a uses clause lists the unit's dependencies, whichever section it appears in")
    void readsDependencies() throws IOException {
        write("ledger.pas", """
                unit ledger;
                interface
                uses SysUtils, Math;
                implementation
                uses ledgererrors in 'ledgererrors.pas';
                end.
                """);

        PascalModule module = PascalProjectDetector.modules(root).getFirst();

        assertEquals(List.of("SysUtils", "Math", "ledgererrors"), module.dependencies());
        assertTrue(module.uses("math"), "a uses clause is matched case-insensitively");
    }

    @Test
    @DisplayName("a program or uses clause inside a comment is not seen")
    void ignoresCommentsAndStrings() throws IOException {
        write("notes.pas", """
                unit notes;
                interface
                // program notreallyaprogram;
                { uses GhostUnit; }
                (* uses AnotherGhost in 'x.pas'; *)
                const Greeting = 'uses QuotedGhost;';
                implementation
                end.
                """);

        PascalModule module = PascalProjectDetector.modules(root).getFirst();

        assertTrue(module.dependencies().isEmpty(), module.dependencies().toString());
        assertTrue(PascalProjectDetector.programs(root).isEmpty());
    }

    @Test
    @DisplayName("a Delphi and Lazarus program suffix makes an executable even without the keyword")
    void recognisesProgramSuffixes() throws IOException {
        write("app.dpr", "begin\nend.\n");
        write("lazarus.lpr", "begin\nend.\n");

        assertEquals(2, PascalProjectDetector.programs(root).size());
    }

    @Test
    @DisplayName("build output and dependency trees are not mistaken for the project's own source")
    void excludesBuildOutput() throws IOException {
        write("units/ledger.ppu", "not source");
        write("node_modules/some-package/thirdparty.pas", "unit thirdparty; interface implementation end.");
        write("build/generated.pas", "unit generated; interface implementation end.");
        write("src/ledger.pas", "unit ledger; interface implementation end.");

        assertEquals(List.of("ledger"), PascalProjectDetector.modules(root).stream()
                .map(PascalModule::name).toList());
    }

    @Test
    @DisplayName("a lib or units directory that holds real sources is read, not skipped as build output")
    void readsSourceDirectoriesNamedLikeOutputDirectories() throws IOException {
        write("lib/ledger.pas", "unit ledger; interface implementation end.");
        write("units/interest.pas", "unit interest; interface implementation end.");

        assertEquals(List.of("interest", "ledger"), PascalProjectDetector.modules(root).stream()
                .map(PascalModule::name).sorted().toList());
    }

    @Test
    @DisplayName("inspection describes the language, the compiler and the fpcunit framework")
    void describesTheProject() throws IOException {
        write("src/ledger.pas", "unit ledger; interface implementation end.");
        write("tests/ledgertests.pas", """
                unit ledgertests;
                interface
                uses fpcunit;
                implementation
                end.
                """);

        ProjectInfo info = PascalProjectDetector.inspect(ProjectRef.of(root));

        assertEquals(LanguageId.PASCAL, info.language());
        assertEquals(BuildSystemId.FPC, info.buildSystem());
        assertTrue(info.testFrameworks().contains("fpcunit"));
        assertEquals("true", info.attributes().get(PascalProjectDetector.ATTR_HAS_TESTS));
        assertEquals(1, info.modules().size());
        assertTrue(info.sourceExtensions().contains("lpr"));
    }

    @Test
    @DisplayName("a tree with no Pascal file at all is not claimed")
    void doesNotClaimOtherLanguages() throws IOException {
        write("README.md", "no pascal here\n");
        write("src/Main.java", "class Main {}\n");

        assertTrue(!PascalProjectDetector.looksLikePascalProject(root));
    }
}
