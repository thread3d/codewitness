package dev.codewitness.verilog.analysis;

import dev.codewitness.core.model.Finding;
import dev.codewitness.core.model.FindingCategory;
import dev.codewitness.core.spi.VerificationContext;
import dev.codewitness.verilog.VerilogTestContexts;

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

/** The Verilog rule table. */
class VerilogHeuristicAnalyzerTest {

    private static Set<String> ruleIds(List<Finding> findings) {
        return findings.stream().map(Finding::ruleId).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("a blocking assignment in a clocked block is reported")
    void blockingInClockedBlock(@TempDir Path root, @TempDir Path run) throws IOException {
        VerilogTestContexts.write(root, "rtl/core.v", """
                module core (input wire clk, input wire d, output reg q);
                  always @(posedge clk) begin
                    q = d;
                  end
                endmodule
                """);
        VerificationContext context = VerilogTestContexts.context(root, run);
        List<Finding> findings = new VerilogHeuristicAnalyzer().analyze(context);
        assertTrue(ruleIds(findings).contains("CW-VLOG-BUG-001"), findings.toString());
    }

    @Test
    @DisplayName("a nonblocking assignment in a combinational block is reported")
    void nonblockingInCombinational(@TempDir Path root, @TempDir Path run) throws IOException {
        VerilogTestContexts.write(root, "rtl/core.v", """
                module core (input wire a, input wire b, output reg y);
                  always @(*) begin
                    y <= a & b;
                  end
                endmodule
                """);
        VerificationContext context = VerilogTestContexts.context(root, run);
        assertTrue(ruleIds(new VerilogHeuristicAnalyzer().analyze(context)).contains("CW-VLOG-BUG-002"));
    }

    @Test
    @DisplayName("a case without a default is reported")
    void caseWithoutDefault(@TempDir Path root, @TempDir Path run) throws IOException {
        VerilogTestContexts.write(root, "rtl/core.v", """
                module core (input wire [1:0] s, output reg y);
                  always @(*) begin
                    case (s)
                      2'b00: y = 1'b0;
                      2'b01: y = 1'b1;
                    endcase
                  end
                endmodule
                """);
        VerificationContext context = VerilogTestContexts.context(root, run);
        assertTrue(ruleIds(new VerilogHeuristicAnalyzer().analyze(context)).contains("CW-VLOG-CORRECT-001"));
    }

    @Test
    @DisplayName("a zero-delay forever loop is reported")
    void foreverLoop(@TempDir Path root, @TempDir Path run) throws IOException {
        VerilogTestContexts.write(root, "rtl/core.v", """
                module core (input wire a, output reg y);
                  initial begin
                    forever y = ~y;
                  end
                endmodule
                """);
        VerificationContext context = VerilogTestContexts.context(root, run);
        assertTrue(ruleIds(new VerilogHeuristicAnalyzer().analyze(context)).contains("CW-VLOG-BUG-004"));
    }

    @Test
    @DisplayName("$system is a security finding and is kept out of the code findings")
    void systemTaskIsSecurity(@TempDir Path root, @TempDir Path run) throws IOException {
        VerilogTestContexts.write(root, "rtl/core.v", """
                module core (input wire a, output reg y);
                  initial begin
                    $system("rm -rf /");
                    y = a;
                  end
                endmodule
                """);
        VerificationContext context = VerilogTestContexts.context(root, run);
        VerilogHeuristicAnalyzer analyzer = new VerilogHeuristicAnalyzer();

        List<Finding> code = analyzer.analyze(context);
        assertTrue(code.stream().allMatch(finding -> finding.category() != FindingCategory.SECURITY),
                "the static view must not carry the security rules: " + code);

        List<Finding> security = analyzer.analyzeSecurity(context);
        assertTrue(ruleIds(security).contains("CW-VLOG-SEC-001"), security.toString());
    }

    @Test
    @DisplayName("a testbench file is not analysed as design logic")
    void testbenchIsSkipped(@TempDir Path root, @TempDir Path run) throws IOException {
        VerilogTestContexts.write(root, "tb/thing_tb.v", """
                module thing_tb;
                  initial begin
                    forever #5;
                  end
                endmodule
                """);
        VerificationContext context = VerilogTestContexts.context(root, run);
        assertFalse(ruleIds(new VerilogHeuristicAnalyzer().analyze(context)).contains("CW-VLOG-BUG-004"),
                "a testbench's forever loop is how a clock is made");
    }

    @Test
    @DisplayName("a clean design reports nothing")
    void cleanDesign(@TempDir Path root, @TempDir Path run) throws IOException {
        VerilogTestContexts.write(root, "rtl/core.v", """
                module core (input wire clk, input wire d, output reg q);
                  always @(posedge clk) begin
                    q <= d;
                  end
                endmodule
                """);
        VerificationContext context = VerilogTestContexts.context(root, run);
        assertTrue(new VerilogHeuristicAnalyzer().analyze(context).isEmpty(),
                "a conventional design must not produce findings");
    }
}
