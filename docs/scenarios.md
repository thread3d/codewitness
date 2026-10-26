# Scenarios

CodeWitness cannot invent your API, so you declare how a requirement can be executed. CodeWitness
then supplies the mechanical part: boundary enumeration, seeded random search, execution, and
counterexamples you can replay.

```yaml
scenarios:
  - id: SC-ROLLING
    requirement: R-001
    description: Two withdrawals across midnight, one hour apart
    mode: sequence            # single | sequence | concurrent
    setup: "com.example.account.WithdrawalLedger ledger = new com.example.account.WithdrawalLedger();"
    steps:
      - call: "ledger.withdraw(java.time.Instant.ofEpochMilli(${t1}), ${a1})"
        inputs:
          - name: t1
            type: long
            domain: long
            values: ["85800000"]                 # deterministic boundary case (23:50)
            range: {min: 0, max: 172800000}      # seeded random search
          - name: a1
            type: java.math.BigDecimal
            domain: decimal
            values: ["700"]
            range: {min: 100, max: 900, scale: 2}
      - call: "ledger.withdraw(java.time.Instant.ofEpochMilli(${t2}), ${a2})"
        inputs:
          - name: t2
            type: long
            domain: long
            values: ["90000000"]                 # 01:00 the next day
          - name: a2
            type: java.math.BigDecimal
            domain: decimal
            values: ["700"]
    observation: "ledger.totalWithinLast24Hours(java.time.Instant.ofEpochMilli(${t2}))"
    invariant: "observation.compareTo(new java.math.BigDecimal(\"1000\")) <= 0"
    cases: 300
    seed: 42
```

`${name}` is substituted with a generated variable of the declared `type`, so write `${a1}` and
not `new java.math.BigDecimal(${a1})`. The fragments are written in the project's own language: for a
Python project they are Python statements and expressions, and `${name}` becomes a Python variable:

```yaml
language:
  adapter: python

scenarios:
  - id: SC-ROLLING
    requirement: R-001
    mode: sequence
    setup: |
      from ledger import WithdrawalLedger
      ledger = WithdrawalLedger()
    steps:
      - call: "ledger.withdraw(${t1}, ${a1})"
        inputs:
          - {name: t1, type: int, domain: integer, values: ["85800000"]}
          - {name: a1, type: decimal.Decimal, domain: decimal, values: ["700"], range: {min: 100, max: 900, scale: 2}}
    observation: "ledger.total_within_last_24_hours(${t1})"
    invariant: "observation <= decimal.Decimal(\"1000\")"
```

Boundary cases are enumerated in three passes: a baseline with every input at its first declared value, then
variation across the inputs in rounds so that every input moves as early as the budget allows, and finally pairs
once the budget is spare. A scenario with four inputs and six values each therefore varies all four, where an
enumeration that gave one input all of its values first would spend the cap before the later inputs moved at all.

The invariant is checked after every step. `mode: concurrent` releases eight threads simultaneously
against one shared subject and then checks the invariant. Every failing case reports its exact inputs
plus the seed and case index, which is what makes a counterexample reproducible. Python generated
tests are standard-library `unittest` cases, so they run with or without pytest.

For a Rust project the fragments are Rust statements, the generated test is a Cargo integration test
under `tests/`, and `${name}` becomes a Rust variable:

```yaml
language:
  adapter: rust

scenarios:
  - id: SC-ROLLING
    requirement: R-001
    mode: sequence
    setup: |
      let mut ledger = withdrawal_ledger::WithdrawalLedger::new();
    steps:
      - call: "ledger.withdraw(${t1}, ${a1});"
        inputs:
          - {name: t1, type: i64, domain: integer, values: ["85800000"]}
          - {name: a1, type: i64, domain: integer, values: ["70000"], range: {min: 10000, max: 90000}}
    observation: "ledger.total_within_last_24_hours(${t1})"
    invariant: "observation <= 100000"
```

Rust generated tests use only the standard library, so they compile on any stable toolchain; a value
recorded in a counterexample must implement `Debug`.

For a Go module the fragments are Go statements, the generated test is a `_test.go` file written into
the package under test (so it uses only the standard library and needs no import), and `${name}`
becomes a Go variable. Go rejects unused local variables, so every generated input is blanked with
`_ = name`; the standard packages a fragment might use (`fmt`, `math`, `os`, `regexp`, `sort`,
`strconv`, `strings`, `sync`, `time`, `errors`) are imported and blank-referenced so an unused one
never breaks the build:

```yaml
language:
  adapter: go

scenarios:
  - id: SC-ROLLING
    requirement: R-001
    mode: sequence
    setup: |
      l := New()
    steps:
      - call: "l.Withdraw(${t1}, ${a1})"
        inputs:
          - {name: t1, type: int64, domain: integer, values: ["85800000"]}
          - {name: a1, type: int64, domain: integer, values: ["70000"], range: {min: 10000, max: 90000}}
    observation: "l.TotalWithinLast24Hours(${t1})"
    invariant: "observation <= 100000"
```

Go generated tests use only the standard library and call the package's exported API from within the
same package; a scenario fragment is responsible for using whatever it declares.

For a TypeScript project the fragments are TypeScript statements, the generated test is a `node:test`
module written beside the project's own tests, and inputs are referenced as `#{name}` rather than
`${name}`: `${...}` is template-literal interpolation in TypeScript, so `#{...}` is used instead and
cannot collide with it. An `import` line in `setup` is hoisted to the top of the module, because an
import cannot appear inside a function:

```yaml
language:
  adapter: typescript

scenarios:
  - id: SC-ROLLING
    requirement: R-001
    mode: sequence
    setup: |
      import { WithdrawalLedger } from "../src/ledger.ts";
      const ledger = new WithdrawalLedger();
    steps:
      - call: "ledger.withdraw(#{t1}, #{a1});"
        inputs:
          - {name: t1, type: bigint, domain: integer, values: ["85800000"]}
          - {name: a1, type: bigint, domain: integer, values: ["70000"], range: {min: 10000, max: 90000}}
    observation: "ledger.totalWithinLast24Hours(#{t1})"
    invariant: "observation <= 100000n"
```

TypeScript generated tests use only Node's standard library, so they run on Node 22.6 or later
without a build step; `#{name}` becomes a TypeScript variable of the declared `type`.

For a JavaScript project the fragments are plain JavaScript, the generated test is a `node:test`
module written beside the project's own tests, and inputs are referenced as `#{name}` for the same
reason: `${...}` is template-literal interpolation in JavaScript, so `#{...}` is used instead and
cannot collide with it. The generated file uses the `.test.mjs` extension, so it is unambiguously an
ES module whatever the project's `package.json` declares, and it carries no type annotations:

```yaml
language:
  adapter: javascript

scenarios:
  - id: SC-ROLLING
    requirement: R-001
    mode: sequence
    setup: |
      import { WithdrawalLedger } from "../src/ledger.js";
      const ledger = new WithdrawalLedger();
    steps:
      - call: "ledger.withdraw(#{t1}, #{a1});"
        inputs:
          - {name: t1, type: bigint, domain: integer, values: ["85800000"]}
          - {name: a1, type: bigint, domain: integer, values: ["70000"], range: {min: 10000, max: 90000}}
    observation: "ledger.totalWithinLast24Hours(#{t1})"
    invariant: "observation <= 100000n"
```

JavaScript generated tests use only Node's standard library, so they run on Node 18.17 or later
without a build step; `#{name}` becomes a JavaScript variable, and `type` only selects `bigint`
wrapping.

For a C# project the fragments are C# statements, `${name}` becomes a C# variable, and the generated
test is an xUnit class written into the tree's own test project — a C# test must live in a project
that compiles against the code under test, so there is nowhere else for it to go. The class is
declared in the namespace that project's own tests already use and the production namespaces are
imported, so a scenario writes `new WithdrawalLedger()` without spelling out where the type lives:

```yaml
language:
  adapter: csharp

scenarios:
  - id: SC-ROLLING
    requirement: R-001
    mode: sequence
    setup: |
      var l = new WithdrawalLedger();
    steps:
      - call: "l.Withdraw(${t1}, ${a1})"
        inputs:
          - {name: t1, type: long, domain: integer, values: ["85800000"]}
          - {name: a1, type: long, domain: integer, values: ["70000"], range: {min: 10000, max: 90000}}
      - call: "l.Withdraw(${t2}, ${a2})"
        inputs:
          - {name: t2, type: long, domain: integer, values: ["90000000"]}
          - {name: a2, type: long, domain: integer, values: ["70000"]}
    observation: "l.TotalWithinLast24Hours(${t2})"
    invariant: "observation <= 100000"
```

The generated class is run with `dotnet test --filter FullyQualifiedName~<class>` and removed
afterwards unless `report.keep_generated_tests` is set, with a copy always kept in the run's artefact
store; a failing case reaches the engine as a `CODEWITNESS_COUNTEREXAMPLE|…` line carried in the TRX
failure message, so the counterexample carries data rather than a stack trace.

```bash
mvn -Dtest=Cw_SC_ROLLING_OB_001_Test -Dcodewitness.seed=42 -Dcodewitness.case=37 test
```

For a Verilog project the fragments are Verilog statements, `${name}` becomes the signal `name`, and
the generated test is an ordinary testbench module named `cw_sc_<scenario>_<obligation>` that
instantiates the design under test as `dut` and connects the scenario's inputs to it, declaring each
one at the design's own port width (or `reg [63:0]` when the name is not a port). The generator
enumerates the declared boundary values and adds a seeded `$urandom_range` search when a scenario
declares a range, advances time with `#1` before sampling the observation — a combinational design
settles in a delta cycle — and checks the invariant once per case:

```yaml
language:
  adapter: verilog

scenarios:
  - id: SC-DEBIT
    requirement: R-001
    mode: single
    setup: ""
    steps:
      - call: ";"
        inputs:
          - {name: balance, type: integer, domain: integer, values: ["100000"]}
          - {name: amount, type: integer, domain: integer, values: ["1", "99999"], range: {min: 1, max: 99999}}
    observation: "dut.new_balance"
    invariant: "dut.accepted == 1 && observation == balance - amount"
    cases: 1
    seed: 1
```

A violated invariant prints the shared `CODEWITNESS_COUNTEREXAMPLE|…` line and the simulation calls
`$fatal` once at the end, so it exits non-zero; because a Verilog test is a compile and then a
simulation rather than one command, the replay is the two-step `iverilog` then `vvp` recipe.

For a VHDL design the fragments are VHDL statements, `${name}` becomes the signal `name`, and the
generated test is an ordinary testbench entity named `cw_sc_<scenario>_<obligation>` that declares a
signal named after each of the design's ports and connects it to an instance of the design. Two VHDL
facts shape the file. A component instance's ports cannot be selected by name — there is no `dut.balance`
— so a fragment references the port signals directly (`balance`, `accepted`, `new_balance`). And the
language is strongly typed, so an integer boundary value is converted into the port's own subtype with
`to_unsigned`, `to_signed` or a std_logic conditional before it is assigned, and the observation is
assigned to an `integer` variable named `observation`. The generator enumerates the declared boundary
values and adds a seeded search when a scenario declares a non-negative range, waits `1 ns` before
sampling the observation — a combinational design settles in a delta cycle — and checks the invariant
once per case:

```yaml
language:
  adapter: vhdl

scenarios:
  - id: SC-DEBIT
    requirement: R-001
    mode: single
    setup: ""
    steps:
      - call: "null;"
        inputs:
          - {name: balance, type: integer, domain: integer, values: ["100000"]}
          - {name: amount, type: integer, domain: integer, values: ["1", "99999"], range: {min: 1, max: 99999}}
    observation: "to_integer(new_balance)"
    invariant: "accepted = '1' and observation = to_integer(balance) - to_integer(amount)"
    cases: 1
    seed: 1
```

A violated invariant prints the shared `CODEWITNESS_COUNTEREXAMPLE|…` line and sets a flag; a single
`assert … severity failure` runs at the end, so every case runs and the simulation still exits non-zero;
because a VHDL test is an analyse and then a simulation rather than one command, the replay is the
analyse-then-simulate recipe.

For a Pascal project the fragments are Pascal statements, `${name}` becomes the declared input `name`,
and the generated test is an ordinary `program` named `cw_sc_<scenario>_<obligation>` that links the
project's units by name. The generator declares each input at the Pascal type derived from its declared
type, declares `observation` at the widest type any input implies, enumerates the declared boundary
values, and for a `sequence` scenario adds a seeded `Random` search when every input declares an integer
range. A step's `call` is a statement, so it may assign to `observation` directly, and the invariant is
the boolean expression checked once per case:

```yaml
language:
  adapter: pascal

scenarios:
  - id: SC-REJECT
    requirement: R-002
    mode: single
    setup: ""
    steps:
      - call: "observation := ledger.Withdraw(balance, amount);"
        inputs:
          - {name: balance, type: integer, domain: integer, values: ["100000"]}
          - {name: amount, type: integer, domain: integer, values: ["100001", "200000"]}
    observation: "observation"
    invariant: "ledger.Accepted(balance, amount) or (observation = balance)"
    cases: 1
    seed: 1
```

A violated invariant prints the shared `CODEWITNESS_COUNTEREXAMPLE|…` line and calls `Halt(1)`, so the
program exits non-zero; because a Pascal test is a compile and then a run rather than one command, the
replay is the two-step `fpc` then executable recipe. The generator declares only the scenario's inputs
and `observation` — it has no scratch variables — so a scenario assigns to `observation` or calls
project code directly.

### Process scenarios: asserting on a program's own interface

Every mode above calls code from *inside* the project's test framework. None of them can observe what a caller of
the built program sees — its exit code, its diagnostics — because those are properties of a process. `mode: process`
closes that gap: it runs a command and asserts on what it returned.

```yaml
scenarios:
  - id: SC-EXIT
    requirement: R-007
    mode: process
    command:                      # argv, not a shell string
      - java
      - -jar
      - ${project_root}/codewitness-cli/target/codewitness.jar
      - verify
      - .
      - --requirements
      - does-not-exist.md
    expect:
      exit_code: 3
      stderr_contains: ["codewitness: requirements file not found"]
    timeout_seconds: 120
```

- **`command`** is a list of arguments. There is no shell, so there are no quoting rules to get wrong and no place
  for a value to become a second command. `${project_root}` is substituted with the original project tree — needed
  because the isolated copy excludes build output, so an artefact the run just built is not in the copy; using it is
  recorded in the run's limitations.
- **`expect`** is declarative rather than an expression language: `exit_code`, `stdout_contains`, `stderr_contains`.
  A free-form expression evaluated inside CodeWitness would be a second scenario language with different semantics;
  the other modes avoid that by evaluating their invariant in the project's own environment.
- The command runs through the same sandbox as every other process — allow-listed environment, hard timeout, capped
  output — and with the isolated copy as its working directory.
- A violated expectation produces a counterexample carrying the argv, the observed exit code and the tails of both
  streams. A process that never started is `UNVERIFIED`, never a failure: a missing binary is not a defect in the
  code under verification.

This mode exists because CodeWitness's own exit-code contract was the one ground rule its self-check could not
demonstrate. It is demonstrated now: `selfcheck/codewitness.yaml` runs the built jar and asserts exit `3` on a usage
error, and every requirement in `selfcheck/requirements.yaml` is `VERIFIED` by execution.

See [`docs/writing-scenarios.md`](writing-scenarios.md) for the full reference.
