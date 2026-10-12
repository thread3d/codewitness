// The testbench a developer would have written alongside the ledger.
//
// It passes, which is the point: CodeWitness must not treat a passing testbench written by the same
// process as independent verification, and must find the defects through the declared scenarios
// instead. It checks only the one requirement the design satisfies - R-002 - and never the boundary
// where the defects live.
`timescale 1ns/1ps

module ledger_tb;

    reg  [31:0] balance;
    reg  [31:0] amount;
    wire        accepted;
    wire [31:0] new_balance;

    ledger dut (
        .balance(balance),
        .amount(amount),
        .accepted(accepted),
        .new_balance(new_balance)
    );

    initial begin
        // R-002: a withdrawal larger than the balance is rejected and changes nothing.
        balance = 32'd100000;
        amount  = 32'd200000;
        #1;

        if (accepted !== 1'b0) begin
            $display("FAIL: an over-limit withdrawal was accepted");
            $fatal;
        end
        if (new_balance !== 32'd100000) begin
            $display("FAIL: a rejected withdrawal changed the balance");
            $fatal;
        end

        $display("PASS: an over-limit withdrawal is rejected and leaves the balance unchanged");
        $finish;
    end

endmodule
