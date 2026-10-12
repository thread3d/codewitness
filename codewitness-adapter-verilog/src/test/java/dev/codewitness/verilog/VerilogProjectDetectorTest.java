package dev.codewitness.verilog;

import dev.codewitness.core.model.BuildSystemId;
import dev.codewitness.core.model.LanguageId;
import dev.codewitness.core.model.ProjectInfo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Detection of Verilog designs, testbenches and their layout. */
class VerilogProjectDetectorTest {

    @Test
    @DisplayName("a design and its testbench are told apart, and the top module is the design")
    void detectsDesignAndTestbench(@TempDir Path root) throws IOException {
        VerilogTestContexts.write(root, "rtl/ledger.v", """
                module ledger (
                    input  wire [31:0] balance,
                    input  wire [31:0] amount,
                    output reg  [31:0] new_balance
                );
                  always @(*) new_balance = balance - amount;
                endmodule
                """);
        VerilogTestContexts.write(root, "tb/ledger_tb.v", """
                `timescale 1ns/1ps
                module ledger_tb;
                  reg [31:0] balance;
                  ledger dut (.balance(balance));
                  initial begin
                    $dumpvars;
                    #1 $finish;
                  end
                endmodule
                """);

        ProjectInfo info = VerilogProjectDetector.inspect(dev.codewitness.core.model.ProjectRef.of(root));

        assertEquals(LanguageId.VERILOG, info.language());
        assertEquals(BuildSystemId.ICARUS, info.buildSystem());
        assertEquals("ledger", info.attributes().get(VerilogProjectDetector.ATTR_TOP_MODULE));
        assertEquals("ledger_tb", info.attributes().get(VerilogProjectDetector.ATTR_TESTBENCHES));
        assertEquals("true", info.attributes().get(VerilogProjectDetector.ATTR_HAS_TESTS));
        assertTrue(info.sourceExtensions().contains("v"), info.sourceExtensions().toString());
        assertTrue(info.sourceExtensions().contains("sv"), info.sourceExtensions().toString());

        List<VerilogModule> testbenches = VerilogProjectDetector.testbenches(root);
        assertEquals(1, testbenches.size());
        assertEquals("ledger_tb", testbenches.getFirst().name());
        assertTrue(testbenches.getFirst().testbench());
        assertEquals("ledger", VerilogProjectDetector.topModule(root).name());
    }

    @Test
    @DisplayName("a module with ports and an initial block is still a design, not a testbench")
    void portsMakeADesign(@TempDir Path root) throws IOException {
        VerilogTestContexts.write(root, "rtl/core.v", """
                module core (input wire clk, output reg q);
                  initial q = 1'b0;
                  always @(posedge clk) q <= ~q;
                endmodule
                """);

        List<VerilogModule> modules = VerilogProjectDetector.modules(root);
        assertEquals(1, modules.size());
        assertFalse(modules.getFirst().testbench(), "a module with ports is the design");
        assertEquals(2, modules.getFirst().ports().size());
        assertTrue(modules.getFirst().inputs().getFirst().isInput());
    }

    @Test
    @DisplayName("a file named *_tb is a testbench even when its module has ports")
    void namingConvention(@TempDir Path root) throws IOException {
        VerilogTestContexts.write(root, "tb/thing_tb.v", """
                module thing_tb (input wire clk);
                  initial $finish;
                endmodule
                """);
        assertTrue(VerilogProjectDetector.testbenches(root).stream()
                .anyMatch(module -> module.name().equals("thing_tb")));
    }

    @Test
    @DisplayName("SystemVerilog sources are sources, and a header comment does not hide a module")
    void systemVerilogAndComments(@TempDir Path root) throws IOException {
        VerilogTestContexts.write(root, "rtl/pack.sv", """
                // module not_a_module (input wire x);
                /* module also_not (input wire y); */
                module pack #(parameter int W = 8) (
                    input  logic       clk,
                    input  logic [W-1:0] d,
                    output logic [W-1:0] q
                );
                  always_ff @(posedge clk) q <= d;
                endmodule
                """);

        List<VerilogModule> modules = VerilogProjectDetector.modules(root);
        assertEquals(1, modules.size());
        assertEquals("pack", modules.getFirst().name());
        assertEquals(3, modules.getFirst().ports().size());
    }

    @Test
    @DisplayName("non-ANSI headers name ports the body declares")
    void nonAnsiHeader(@TempDir Path root) throws IOException {
        VerilogTestContexts.write(root, "rtl/legacy.v", """
                module legacy (a, b, y);
                  input a;
                  input b;
                  output y;
                  assign y = a & b;
                endmodule
                """);
        List<VerilogModule.Port> ports = VerilogProjectDetector.modules(root).getFirst().ports();
        assertEquals(3, ports.size());
        assertEquals("unknown", ports.getFirst().direction());
    }

    @Test
    @DisplayName("a build directory is not searched for sources")
    void excludesBuildOutput(@TempDir Path root) throws IOException {
        VerilogTestContexts.write(root, "rtl/ledger.v", "module ledger (input a); endmodule\n");
        VerilogTestContexts.write(root, "obj_dir/generated.v", "module generated (input a); endmodule\n");
        VerilogTestContexts.write(root, ".git/committed.v", "module committed (input a); endmodule\n");

        List<Path> sources = VerilogProjectDetector.sourceFiles(root);
        assertEquals(1, sources.size());
        assertEquals("ledger.v", sources.getFirst().getFileName().toString());
    }

    @Test
    @DisplayName("a file list is reported as a detected descriptor")
    void detectsFileList(@TempDir Path root) throws IOException {
        VerilogTestContexts.write(root, "rtl/ledger.v", "module ledger (input a); endmodule\n");
        VerilogTestContexts.write(root, "ledger.f", "rtl/ledger.v\n");

        ProjectInfo info = VerilogProjectDetector.inspect(dev.codewitness.core.model.ProjectRef.of(root));
        assertTrue(info.detectedDescriptors().stream()
                .anyMatch(path -> path.getFileName().toString().equals("ledger.f")));
        assertEquals("ledger.f", info.attributes().get(VerilogProjectDetector.ATTR_FILE_LISTS));
    }
}
