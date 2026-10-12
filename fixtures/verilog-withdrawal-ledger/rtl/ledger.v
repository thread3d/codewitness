// Withdrawal ledger used by the CodeWitness Verilog fixture.
//
// The module is combinational: the balance and the requested amount are its inputs, and whether the
// withdrawal is accepted and the resulting balance are its outputs. It contains two deliberately
// introduced defects behind the requirements in `requirements.md`:
//
//  - R-001: an accepted withdrawal adds one cent back, so the balance is not reduced by exactly the
//    amount that was requested;
//  - R-003: the comparison is `<` rather than `<=`, so a withdrawal of exactly the balance is
//    rejected instead of emptying the account.
//
// R-002 (an over-limit withdrawal is rejected and changes nothing) is implemented correctly. A
// requirement that is genuinely satisfied should be verified, not merely reported, which is what the
// fixture is for.
//
// Money is held in integer cents. A design that carried a running total in real arithmetic would make
// an exact boundary unanswerable, and the fixture exists to show a real defect being found rather than
// a simulator artefact.
module ledger (
    input  wire [31:0] balance,
    input  wire [31:0] amount,
    output reg         accepted,
    output reg  [31:0] new_balance
);

    always @(*) begin
        // DEFECT (R-003): a withdrawal of exactly the balance fails this test and is rejected.
        if (amount < balance) begin
            accepted = 1'b1;
            // DEFECT (R-001): the accepted branch returns one cent too much.
            new_balance = balance - amount + 32'd1;
        end else begin
            // R-002: an over-limit withdrawal is rejected and leaves the balance unchanged.
            accepted = 1'b0;
            new_balance = balance;
        end
    end

endmodule
