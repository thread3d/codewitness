package dev.codewitness.pascal.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.pascal.PascalTestContexts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each rule fires on the shape it names and stays quiet on a clean unit. */
class PascalHeuristicAnalyzerTest {

    @TempDir
    Path root;

    @TempDir
    Path run;

    private VerificationContext context() {
        return PascalTestContexts.context(root, run);
    }

    private static Set<String> ruleIds(List<Finding> findings) {
        return findings.stream().map(Finding::ruleId).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("memory allocated and never released is reported once")
    void reportsLeaks() throws IOException {
        PascalTestContexts.write(root, "leaky.pas", """
                unit leaky;
                interface
                procedure Fill;
                implementation
                procedure Fill;
                var p: PByte;
                begin
                  GetMem(p, 16);
                end;
                end.
                """);

        Set<String> rules = ruleIds(new PascalHeuristicAnalyzer().analyze(context()));

        assertTrue(rules.contains("CW-PAS-BUG-001"), rules.toString());
    }

    @Test
    @DisplayName("a file opened and never closed is reported")
    void reportsOpenFiles() throws IOException {
        PascalTestContexts.write(root, "files.pas", """
                unit files;
                interface
                implementation
                var f: Text;
                procedure Read;
                begin
                  AssignFile(f, 'data.txt');
                  Reset(f);
                end;
                end.
                """);

        assertTrue(ruleIds(new PascalHeuristicAnalyzer().analyze(context())).contains("CW-PAS-BUG-002"));
    }

    @Test
    @DisplayName("an empty except, a with and a goto each have their own rule")
    void reportsStructuralSmells() throws IOException {
        PascalTestContexts.write(root, "smelly.pas", """
                unit smelly;
                interface
                uses SysUtils;
                implementation
                procedure P;
                begin
                  with TObject.Create do
                    ;
                  try
                    raise Exception.Create('x');
                  except end;
                  goto done;
                  done:
                end;
                end.
                """);

        Set<String> rules = ruleIds(new PascalHeuristicAnalyzer().analyze(context()));

        assertTrue(rules.contains("CW-PAS-CORRECT-001"), rules.toString());
        assertTrue(rules.contains("CW-PAS-MAINT-001"), rules.toString());
        assertTrue(rules.contains("CW-PAS-STYLE-001"), rules.toString());
    }

    @Test
    @DisplayName("a SQL statement assembled by concatenation is a security finding, never ordinary analysis")
    void reportsSqlInjection() throws IOException {
        PascalTestContexts.write(root, "db.pas", """
                unit db;
                interface
                uses SysUtils;
                implementation
                procedure Find(id: Integer);
                begin
                  Query.SQL.Add('SELECT * FROM ledger WHERE id = ' + IntToStr(id));
                  Query.Open;
                end;
                end.
                """);

        PascalHeuristicAnalyzer analyzer = new PascalHeuristicAnalyzer();
        List<Finding> security = analyzer.analyzeSecurity(context());
        List<Finding> ordinary = analyzer.analyze(context());

        assertTrue(ruleIds(security).contains("CW-PAS-SEC-001"), ruleIds(security).toString());
        assertTrue(ordinary.stream().noneMatch(finding -> finding.category() == FindingCategory.SECURITY));
    }

    @Test
    @DisplayName("a credential written as a literal is a security finding")
    void reportsHardcodedSecrets() throws IOException {
        PascalTestContexts.write(root, "config.pas", """
                unit config;
                interface
                const DatabasePassword = 'hunter2';
                implementation
                end.
                """);

        assertTrue(ruleIds(new PascalHeuristicAnalyzer().analyzeSecurity(context())).contains("CW-PAS-SEC-002"));
    }

    @Test
    @DisplayName("a clean unit produces no findings at all")
    void cleanUnitIsQuiet() throws IOException {
        PascalTestContexts.write(root, "ledger.pas", """
                unit ledger;
                interface
                uses SysUtils;
                function Withdraw(balance, amount: Integer): Integer;
                implementation
                function Withdraw(balance, amount: Integer): Integer;
                begin
                  if amount > balance then
                    raise ERangeError.Create('insufficient funds');
                  Result := balance - amount;
                end;
                end.
                """);

        PascalHeuristicAnalyzer analyzer = new PascalHeuristicAnalyzer();

        assertTrue(analyzer.analyze(context()).isEmpty(), analyzer.analyze(context()).toString());
        assertTrue(analyzer.analyzeSecurity(context()).isEmpty());
    }

    @Test
    @DisplayName("a test unit's own code is not analysed as the code under test")
    void skipsTestUnits() throws IOException {
        PascalTestContexts.write(root, "tests/ledgertests.pas", """
                unit ledgertests;
                interface
                uses fpcunit;
                implementation
                procedure P;
                begin
                  goto done;
                  done:
                end;
                end.
                """);

        assertTrue(new PascalHeuristicAnalyzer().analyze(context()).isEmpty());
    }

    @Test
    @DisplayName("the analyzer's id marks its findings as advisory rather than deterministic")
    void findingsAreAdvisory() throws IOException {
        PascalTestContexts.write(root, "leaky.pas", """
                unit leaky;
                interface
                implementation
                var p: PByte;
                procedure Fill;
                begin
                  New(p);
                end;
                end.
                """);

        List<Finding> findings = new PascalHeuristicAnalyzer().analyze(context());

        assertFalse(findings.isEmpty());
        assertFalse(findings.getFirst().fromDeterministicTool(),
                "a heuristic finding must not be able to refute a requirement on its own");
    }
}
