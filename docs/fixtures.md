# Fixture projects

A verification tool is only worth trusting if it can be shown to distinguish code that is wrong from
code that is right. Every fixture in `fixtures/` is deliberately broken in a specific way — or
deliberately correct — and the end-to-end tests assert that CodeWitness reaches the expected verdict.

The results below were produced by `tools/verify-all-fixtures.sh`, which runs
`./codewitness verify <fixture> --requirements <fixture>/requirements.md --max-duration 10m` for each
fixture and prints the outcome. `./codewitness` is the launcher in the repository root; the build does
not install a `codewitness` command on `PATH`.

| Fixture | Type of defect | Overall | Requirements | What it proves |
| --- | --- | --- | --- | --- |
| `withdrawal-ledger` | four defects: rolling window, discarded `BigDecimal`, negative amounts, race | `FAILED` | 4 `FAILED`, 1 `UNVERIFIED` | Several independent defects in one project, each mapped to its own requirement, each with a reproducible counterexample; and an honest `UNVERIFIED` for a requirement nobody can execute. |
| `python-withdrawal-ledger` | Python: rolling window applied per calendar day, negative amounts accepted | `FAILED` | 2 `FAILED`, 1 `VERIFIED`, 1 `UNVERIFIED` | The same evidence chain for a language the core knows nothing about: detection, syntax build, tests, scenarios, counterexamples and AST mutation, using only the standard library. |
| `rust-withdrawal-ledger` | Rust: rolling window applied per calendar day, negative amounts accepted | `FAILED` | 2 `FAILED`, 1 `VERIFIED`, 1 `UNVERIFIED` | The same evidence chain for a compiled language with a build step: Cargo detection and build, libtest parsing, scenario-generated integration tests, counterexamples and bounded source mutation, with no third-party crate. |
| `go-withdrawal-ledger` | Go: rolling window applied per calendar day, negative amounts accepted | `FAILED` | 2 `FAILED`, 1 `VERIFIED`, 1 `UNVERIFIED` | The same evidence chain for Go: module detection, `go build`, `go test -json` parsing, scenario-generated `_test.go` files in the package under test, counterexamples, built-in coverprofile coverage and bounded source mutation, with no third-party module. |
| `typescript-withdrawal-ledger` | TypeScript: rolling window applied per calendar day, negative amounts accepted | `FAILED` | 2 `FAILED`, 1 `VERIFIED`, 1 `UNVERIFIED` | The same evidence chain for a language executed by Node's type stripper: detection, syntax build, `node:test` with JUnit parsing, scenario-generated `node:test` modules, counterexamples, V8 coverage and bounded source mutation, with no third-party package. |
| `javascript-withdrawal-ledger` | JavaScript: rolling window applied per calendar day, negative amounts accepted | `FAILED` | 2 `FAILED`, 1 `VERIFIED`, 1 `UNVERIFIED` | The same evidence chain for plain JavaScript, with no compiler and no type check anywhere: detection, `node --check` syntax build, `node:test` with JUnit parsing, scenario-generated `node:test` modules, counterexamples, V8 coverage and bounded source mutation, with no third-party package. |
| `csharp-withdrawal-ledger` | C#/.NET: rolling window applied per calendar day, negative amounts accepted | `FAILED` | 2 `FAILED`, 1 `VERIFIED`, 1 `UNVERIFIED` | The same evidence chain for the .NET SDK: solution and project detection, `dotnet build` with Roslyn/MSBuild diagnostics, `dotnet test` with TRX parsing, scenario-generated xUnit facts written into the test project, counterexamples, coverlet cobertura coverage and bounded source mutation, with nothing installed beyond the SDK and the test host packages the project already references. |
| `kotlin-withdrawal-ledger` | Kotlin: rolling window applied per calendar day, negative amounts accepted | `FAILED` | 2 `FAILED`, 1 `VERIFIED`, 1 `UNVERIFIED` | The same evidence chain for Kotlin on the JVM: Gradle/Maven detection by Kotlin source rather than by the build descriptor it shares with Java, `kotlinc` diagnostics read from the compiler's own four shapes, JUnit XML reports, scenario-generated Kotlin test classes compiled by the project's own build, counterexamples, JaCoCo coverage over JVM bytecode and bounded source mutation, with nothing installed beyond the build tool and the Kotlin plugin it already declares. |
| `cpp-withdrawal-ledger` | C++: rolling window applied per calendar day, negative amounts accepted | `FAILED` | 2 `FAILED`, 1 `VERIFIED`, 1 `UNVERIFIED` | The same evidence chain for a compiled language whose build system is not a package manager: CMake detection and configure, `cmake --build`, CTest with JUnit parsing, scenario-generated self-contained C++ programs compiled with the flags the project's own build recorded, counterexamples, gcov coverage over a second instrumented build and bounded source mutation, with no package fetched at all. |
| `php-withdrawal-ledger` | PHP: rolling window applied per calendar day, negative amounts accepted | `FAILED` | 2 `FAILED`, 1 `VERIFIED`, 1 `UNVERIFIED` | The same evidence chain for a language whose test tool is a Composer dependency rather than part of the toolchain: detection from the Composer manifest and the PHPUnit configuration, a build that is the interpreter's own parse followed by `composer install`, PHPUnit with JUnit parsing, scenario-generated PHPUnit `TestCase` classes written into the tree's own test root, counterexamples with a real PHPUnit replay command, coverage reported as not measured when no Xdebug or PCOV driver is loaded, and bounded source mutation — with nothing installed beyond the interpreter and Composer. |
| `ruby-withdrawal-ledger` | Ruby: rolling window applied per calendar day, negative amounts accepted | `FAILED` | 2 `FAILED`, 1 `VERIFIED`, 1 `UNVERIFIED` | The same evidence chain for a language whose test tool is a Bundler dependency rather than part of the toolchain: detection from the `Gemfile` and the RSpec conventions, a build that is the interpreter's own parse followed by `bundle install`, RSpec with JSON parsing, scenario-generated RSpec specs written into the tree's own `spec/` root, counterexamples with a real `bundle exec rspec` replay command, coverage reported as not measured because the fixture does not load SimpleCov, and bounded source mutation — with nothing installed beyond the interpreter and Bundler. |
| `swift-withdrawal-ledger` | Swift: rolling window applied per calendar day, negative amounts accepted | `FAILED` | 2 `FAILED`, 1 `VERIFIED`, 1 `UNVERIFIED` | The same evidence chain for a compiled language whose build system and test runner both ship with the toolchain: detection from `Package.swift`, `swift build --build-tests` with swiftc diagnostics parsed from the compiler's own lines, `swift test` with XCTest results read from the runner's console, scenario-generated XCTest classes written into the tree's own test target, counterexamples with a real `swift test --filter` replay command, LLVM coverage from SwiftPM's own export, and bounded source mutation — with no package resolved and no network needed. |
| `verilog-withdrawal-ledger` | Verilog: an accepted withdrawal adds one cent back, and a withdrawal of exactly the balance is rejected | `FAILED` | 2 `FAILED`, 1 `VERIFIED`, 1 `UNVERIFIED` | The same evidence chain for a hardware description language with no package manager and no manifest: detection from `.v`/`.sv` sources, an Icarus `iverilog -g2012` build, one test case per testbench simulated with `vvp -N`, scenario-generated testbenches that instantiate the design under test as `dut`, counterexamples whose inputs are signal values, and bounded source mutation — with coverage and dependencies both declared not measured, because Icarus exports no coverage and HDL has no advisory ecosystem. |
| `vhdl-withdrawal-ledger` | VHDL: an accepted withdrawal adds one cent back, and a withdrawal of exactly the balance is rejected | `FAILED` | 2 `FAILED`, 1 `VERIFIED`, 1 `UNVERIFIED` | The same evidence chain for the other hardware description language, with no package manager and no manifest: detection from `.vhd`/`.vhdl` sources, a GHDL `ghdl -a` analysis into a work library and `ghdl -e` elaboration of the design entity, one test case per testbench simulated with `ghdl -r --assert-level=error`, scenario-generated testbench entities that declare a signal after each design port and connect it, counterexamples whose inputs are port signal values, and bounded source mutation — with coverage and dependencies both declared not measured, because GHDL's coverage export is not a form this adapter reads and HDL has no advisory ecosystem. |
| `polyglot-service` | six languages in one directory, plus a `mix.exs` for Elixir that nothing can verify | `PARTIALLY_VERIFIED` | 1 `VERIFIED`, 5 `UNVERIFIED` | One run verifies every language a tree contains: Maven, Python, Cargo, Go, TypeScript and JavaScript each build, test and analyse their own files in a single tree, a Python scenario discharges a requirement while five other languages read the same directory, dependencies come from more than one ecosystem, and the language no adapter understands is reported as present and not verified rather than left out. |
| `sql-withdrawal-ledger` | SQL: a balance floor the schema never declares is accepted, and the project's own script finds an overdraft | `FAILED` | 2 `FAILED`, 1 `VERIFIED`, 1 `UNVERIFIED` | The first fixture whose language's own specification is the thing under test: schema against test scripts, the declared DDL loaded into SQLite as the language's first failure, a probe built from the requirement's stated bound that fails because the constraint is missing, a probe meeting a check that is enforced and making its requirement `VERIFIED`, the project's own assertion script refuting a third requirement, and a fourth left `UNVERIFIED` — with mutation reporting 2 mutants generated and 2 killed, and no toolchain needed at all, so its end-to-end test never skips. |
| `pascal-withdrawal-ledger` | Pascal: an accepted withdrawal subtracts one cent too much, and a withdrawal of exactly the balance is accepted but leaves `-1` | `FAILED` | 2 `FAILED`, 1 `VERIFIED`, 1 `UNVERIFIED` | The same evidence chain for a compiled language whose build system and test framework both ship with the toolchain: detection from `.pas` and `.pp` sources, an `fpc` build, the project's fpcunit console runner read from its XML report, scenario-generated `program`s that link the project's own unit, counterexamples whose inputs are call arguments, and bounded source mutation — with coverage and dependencies both declared not measured, because base FPC exports no coverage and has no package manager. |
| `off-by-one-boundary` | boundary comparison (`>=` where `>` is required) | `FAILED` | 1 `FAILED`, 1 `VERIFIED` | Boundary enumeration finds the exact value (`amount=1000`) that the project's own tests never try. |
| `null-handling-defect` | missing null check (`username.trim()` before validation) | `FAILED` | 1 `FAILED`, 1 `VERIFIED` | A generated case can pass `null`; the NPE is captured as the counterexample's observed result. |
| `security-vulnerability` | hardcoded credential, SQL built by concatenation | `FAILED` | 2 `FAILED` | Static security rules produce counterexamples with file, line and rule id, without any test running. |
| `mutation-surviving` | correct code, weak tests (a boundary nobody asserts) | `VERIFIED` | 2 `VERIFIED (+2 survived mutants)` | Verdicts follow executed experiments; weak tests are surfaced as a test-strength limitation rather than silently lowering a verdict. |
| `misleading-tests` | correct code, a test with no assertion | `PARTIALLY_VERIFIED` | 1 `VERIFIED`, 1 `UNVERIFIED` | A test that cannot fail is reported as a finding, and a requirement described only in prose stays `UNVERIFIED`. |
| `correct-implementation` | none (control) | `VERIFIED` | 1 `VERIFIED` | `VERIFIED` is reachable, and means "every planned obligation was discharged by an executed experiment". |

## Details

### `withdrawal-ledger`

The reference fixture: a ledger whose daily limit is applied per calendar day instead of over a
rolling 24-hour window, whose `applyFee` discards the result of `BigDecimal.add`, which accepts
negative withdrawals, and whose concurrent entry point is a check-then-act race.

```text
Requirements
  ✗ R-001  Rolling withdrawal limit            FAILED
  ✗ R-002  Fees are applied to the total       FAILED
  ✗ R-003  Negative amounts are rejected       FAILED
  ✗ R-004  Concurrent withdrawals              FAILED
  ? R-005  Rounding of computed totals         UNVERIFIED

Counterexamples
  CE-001  R-001  inputs: {t1=85800000, a1=700, t2=90000000, a2=700}  actual: 1400
  CE-002  R-002  inputs: {}                                          actual: 100
  CE-003  R-003  inputs: {amount=-100}                               actual: 400
  CE-005  R-004  inputs: {amount=273}  seed: 11, case: 0            actual: 1092
```

All three of the project's own tests pass. The counterexamples come from generated experiments —
boundary enumeration for the midnight crossing, a fixed two-step sequence for the fee, a seeded
random search for the negative amount, and eight threads released simultaneously for the race.

R-005 is the interesting one: it is `UNVERIFIED`, with the rationale "every planned obligation is
still unverified. The requirement must not be treated as passing."

### `python-withdrawal-ledger`

The Python counterpart of the reference fixture: a ledger whose limit is applied per calendar day
instead of over a rolling 24-hour window (R-001) and which accepts a negative withdrawal and reduces
the recorded total (R-003). The project's own tests pass, which is the point.

```text
Requirements
  ✗ R-001  Rolling withdrawal limit             FAILED
  ✓ R-002  Fees are applied to the total        VERIFIED
  ✗ R-003  Negative amounts are rejected        FAILED
  ? R-004  Rounding of computed totals          UNVERIFIED

Counterexamples
  CE-001  R-001  inputs: {t1=85800000, a1=700, t2=90000000, a2=700}  actual: 1400
  CE-002  R-001  inputs: {t1=133185906, a1=775.03, t2=7535, a2=886.94}  actual: 1661.97
  CE-003  R-003  inputs: {amount=-100}                               actual: 400

Mutation testing: 12 mutants, 5 killed, 7 survived (standard library AST mutator)
```

R-001 and R-003 fail through generated `unittest` scenarios; R-002 is discharged by an executed
generated test, which is the only path to `VERIFIED`; R-004 declares no scenario and stays
`UNVERIFIED`. The counterexamples carry the exact inputs and a replay command that runs the saved
generated module with the recorded seed and case.

The fixture needs an interpreter and nothing else: the adapter uses pytest when it can import it and
a standard-library runner otherwise, so no third-party package has to be installed for this result.

### `rust-withdrawal-ledger`

The Rust counterpart of the reference fixture: a Cargo library whose limit is applied per calendar day
instead of over a rolling 24-hour window (R-001) and which accepts a negative withdrawal and reduces
the recorded total (R-003). Amounts are integer minor units and instants are epoch milliseconds, so
the fixture needs no decimal crate. The project's own tests pass, which is the point.

```text
Requirements
  ✗ R-001  Rolling withdrawal limit              FAILED
  ✓ R-002  Fees are applied to the recorded total VERIFIED
  ✗ R-003  Negative amounts are rejected         FAILED
  ? R-004  Rounding of computed totals           UNVERIFIED

Counterexamples
  CE-001  R-001  inputs: {t1=85800000, a1=70000, t2=90000000, a2=70000}  actual: 140000
  CE-002  R-003  inputs: {amount=-10000}                                 actual: 40000

Mutation testing: 12 mutants, 5 killed, 7 survived (built-in source mutator)
```

R-001 and R-003 fail through generated Cargo integration tests; R-002 is discharged by an executed
generated test, which is the only path to `VERIFIED`; R-004 declares no scenario and stays
`UNVERIFIED`. Each counterexample carries the exact inputs and a replay command that runs the saved
generated test target with the recorded seed and case.

The fixture needs a Rust toolchain and nothing else: it has no dependencies, so it builds and tests
offline. Coverage is reported as "not measured" unless `cargo-llvm-cov` is installed, which is itself
the honest result the adapter is designed to produce.

### `go-withdrawal-ledger`

The Go counterpart of the reference fixture: a Go module whose limit is applied per calendar day
instead of over a rolling 24-hour window (R-001) and which accepts a negative withdrawal and reduces
the recorded total (R-003). Amounts are integer minor units and instants are epoch milliseconds, so
the fixture needs no decimal package. The project's own tests pass, which is the point.

```text
Requirements
  ✗ R-001  Rolling withdrawal limit               FAILED
  ✓ R-002  Fees are applied to the recorded total VERIFIED
  ✗ R-003  Negative amounts are rejected          FAILED
  ? R-004  Rounding of computed totals            UNVERIFIED
```

R-001 and R-003 fail through generated `_test.go` files placed in the package under test; R-002 is
discharged by an executed generated test, which is the only path to `VERIFIED`; R-004 declares no
scenario and stays `UNVERIFIED`. Each counterexample carries the exact inputs, the seed and case
index, and a replay command that runs the saved generated test with that seed and case.

The fixture needs the `go` tool and nothing else: it has no dependencies, so it builds and tests
offline. Coverage comes from the built-in `go test -coverprofile`, so it is available without
installing anything; the profile carries statements only, and branch coverage is reported as
unavailable rather than guessed.

### `typescript-withdrawal-ledger`

The TypeScript counterpart of the reference fixture: a Node module whose limit is applied per calendar
day instead of over a rolling 24-hour window (R-001) and which accepts a negative withdrawal and
reduces the recorded total (R-003). Amounts are `bigint` minor units and instants are epoch
milliseconds, so the arithmetic is exact and the fixture needs no package. The project's own tests
pass, which is the point.

```text
Requirements
  ✗ R-001  Rolling withdrawal limit              FAILED
  ✓ R-002  Fees are applied to the recorded total VERIFIED
  ✗ R-003  Negative amounts are rejected         FAILED
  ? R-004  Rounding of computed totals           UNVERIFIED

Counterexamples
  CE-001  R-001  inputs: {t1=85800000, a1=70000, t2=90000000, a2=70000}  actual: 140000
  CE-002  R-003  inputs: {amount=-10000}                                 actual: 40000

Tests:            2 executed, 2 passed
Coverage:         line 97.3% (73/75), branch 88.9% (8/9)   (V8, via LCOV)
Mutation testing: 12 mutants, 5 killed, 7 survived         (built-in source mutator)
```

R-001 and R-003 fail through generated `node:test` modules; R-002 is discharged by an executed
generated test, which is the only path to `VERIFIED`; R-004 declares no scenario and stays
`UNVERIFIED`. Each counterexample carries the exact inputs and a replay command that runs the saved
generated test with the recorded seed and case.

The fixture needs Node 22.6 or later and nothing else: it has no packages, so it is syntax-checked,
tested, covered and mutated by the runtime it already has. Its scenario fragments reference inputs as
`#{name}` rather than `${name}`, because `${...}` is template-literal interpolation in TypeScript.

### `javascript-withdrawal-ledger`

The JavaScript counterpart of the reference fixture: a plain JavaScript module whose limit is applied
per calendar day instead of over a rolling 24-hour window (R-001) and which accepts a negative
withdrawal and reduces the recorded total (R-003). Amounts are `bigint` minor units and instants are
epoch milliseconds, so the arithmetic is exact and the fixture needs no package. The project's own
tests pass, which is the point.

```text
Requirements
  ✗ R-001  Rolling withdrawal limit              FAILED
  ✓ R-002  Fees are applied to the recorded total VERIFIED
  ✗ R-003  Negative amounts are rejected         FAILED
  ? R-004  Rounding of computed totals           UNVERIFIED

Tests:            2 executed, 2 passed
Coverage:         line 97.3% (73/75), branch 88.9% (8/9)   (V8, via LCOV)
Mutation testing: 12 mutants, 5 killed, 7 survived         (built-in source mutator)
```

R-001 and R-003 fail through generated `node:test` modules; R-002 is discharged by an executed
generated test, which is the only path to `VERIFIED`; R-004 declares no scenario and stays
`UNVERIFIED`. Each counterexample carries the exact inputs and a replay command that runs the saved
generated test with the recorded seed and case. Coverage and mutation are measured exactly as they are
for the TypeScript fixture, with no compiler anywhere in the pipeline.

The fixture needs Node 18.17 or later and nothing else: it has no packages, so it is syntax-checked,
tested, covered and mutated by the runtime it already has. Its `codewitness.yaml` names the built-in
mutator as `mutation.tool: javascript-text`. Its scenario fragments reference inputs as
`#{name}` rather than `${name}`, because `${...}` is template-literal interpolation in JavaScript, and
the generated test is written with the `.test.mjs` extension so it is unambiguously an ES module.

Unlike the TypeScript fixture, nothing here is type checked: the JavaScript adapter has no compiler,
and its build is a syntax check with the runtime's own parser. The two adapters are exclusive — a
project with a `tsconfig.json` or any TypeScript source belongs to the TypeScript adapter — so a
JavaScript run never implies a type check happened.

### `csharp-withdrawal-ledger`

The C# counterpart of the reference fixture: a .NET solution whose `Ledger` library applies its limit
per calendar day instead of over a rolling 24-hour window (R-001) and which accepts a negative
withdrawal and reduces the recorded total (R-003). Amounts are `long` minor units and instants are epoch
milliseconds, so the fixture needs no decimal type. The project's own two tests pass, which is the
point.

The fixture is three files of project definition — `WithdrawalLedger.sln`, `src/Ledger/Ledger.csproj`
and `tests/Ledger.Tests/Ledger.Tests.csproj` — because in .NET the unit of build and of testing is the
project, not the tree. The test project is recognised by what it references (`Microsoft.NET.Test.Sdk`,
`xunit` and `coverlet.collector`), not by where it sits; that is what tells the adapter where tests live
and where a generated test may be written.

```bash
./codewitness verify fixtures/csharp-withdrawal-ledger \
    --requirements fixtures/csharp-withdrawal-ledger/requirements.md
```

```text
Requirements
  ✗ R-001    Rolling withdrawal limit     FAILED
  ✓ R-002    Fees are applied to the rec… VERIFIED
  ✗ R-003    Negative amounts are reject… FAILED
  ? R-004    Rounding of computed totals  UNVERIFIED

Counterexamples
  CE-001  R-001  inputs: {t1=85800000, a1=70000, t2=90000000, a2=70000}  actual: 140000
  CE-002  R-003  inputs: {amount=-10000}                                 actual: 40000

Coverage:         line 89.5% (17/19), branch 50.0% (1/2)   (coverlet, cobertura)
Mutation testing: 12 mutants, 5 killed, 7 survived         (built-in source mutator)
```

R-001 and R-003 fail through generated xUnit classes written into `Ledger.Tests` — `CwScRollingOb001`
for the midnight crossing and `CwScNegativeOb003` for the accepted negative amount — and R-002 is
discharged by an executed generated test, which is the only path to `VERIFIED`; R-004 declares no
scenario and stays `UNVERIFIED`. Each counterexample carries the exact inputs and a replay command that
is an `env CODEWITNESS_SEED=… CODEWITNESS_CASE=… dotnet test --filter FullyQualifiedName~<class>`
invocation, with the seed and case index in the environment because a `--filter` cannot carry them.

The fixture needs the .NET 8 SDK and nothing else installed: it targets `net8.0` and references only the
test host packages an ordinary `dotnet test` project already has, and its `codewitness.yaml` sets
`sandbox.allow_network: false`, so those packages must already be in the machine's NuGet cache — which
they are on any machine that has run `dotnet test` before. NuGet has no switch that forbids the network
the way Cargo's `--offline` or Go's `GOPROXY=off` do, so what keeps this fixture offline is the populated
cache rather than the flag; the adapter still disables NuGet's own vulnerability audit on every command,
so nothing in a build goes looking for advisory data.

### `kotlin-withdrawal-ledger`

The Kotlin counterpart of the reference fixture: a Maven build with `kotlin-maven-plugin` whose
`WithdrawalLedger` applies its limit per calendar day instead of over a rolling 24-hour window (R-001)
and accepts a negative amount and reduces the recorded total (R-003). Money is a `BigDecimal`, as it is
in the Java fixture, because that is what a Kotlin service handling money would use — and because a
`JVM` class such as `java.math.BigDecimal` is spelled the same way in both languages, so the scenario
fragments that reference it read almost exactly like the Java ones.

The fixture is a Maven build rather than a Gradle one on purpose. Both are supported, but Maven needs no
Kotlin or Gradle installation at all: the compiler, the standard library and Surefire are resolved from a
repository the way a dependency is, so the project can be verified on any machine with Maven and a
network the first time. The `pom.xml` asks for the Kotlin compiler's in-process execution strategy, which
keeps the compiler inside the Maven JVM instead of leaving a daemon running for hours with file handles
inside the workspace the run is about to delete.

```bash
./codewitness verify fixtures/kotlin-withdrawal-ledger \
    --requirements fixtures/kotlin-withdrawal-ledger/requirements.md
```

```text
Requirements
  ✗ R-001    Rolling withdrawal limit     FAILED
  ✓ R-002    Fees are applied to the rec… VERIFIED
  ✗ R-003    Negative amounts are reject… FAILED
  ? R-004    Rounding of computed totals  UNVERIFIED

Counterexamples
  CE-001  R-001  inputs: {t1=85800000, a1=700, t2=90000000, a2=700}  actual: 1400
  CE-002  R-003  inputs: {amount=-100}                              actual: 400

Coverage:         line 100.0% (23/23), branch 80.0% (8/10)   (JaCoCo)
Mutation testing: 9 mutants, 7 killed, 2 survived          (built-in source mutator)
```

R-001 and R-003 fail through generated Kotlin classes written into `src/test/kotlin` —
`dev.codewitness.generated.Cw_SC_ROLLING_OB_001_Test` for the midnight crossing and
`Cw_SC_NEGATIVE_OB_003_Test` for the accepted negative amount — and R-002 is discharged by an executed
generated test, which is the only path to `VERIFIED`; R-004 declares no scenario and stays `UNVERIFIED`.
Each counterexample carries the exact inputs and a replay command that is a real Maven invocation,
`mvn -Dtest=Cw_SC_ROLLING_OB_001_Test -Dcodewitness.seed=42 -Dcodewitness.case=0 test`, so the failing
case can be re-run by hand.

One Kotlin-specific detail is worth knowing when writing scenarios for it. A generated test is a Kotlin
class, so a step that is followed by the generated observation block is written as `kotlin.run { … }`
rather than a bare `{ … }`: in Kotlin a brace that follows a call is a trailing lambda argument to it, so
the bare block would be read as an extra argument to the scenario's own call. The generated file is also
where a `$` in an invariant or a step description is escaped, because Kotlin would otherwise read it as
the start of a string template.

### `cpp-withdrawal-ledger`

The C++ counterpart of the reference fixture: a CMake project with a `ledger` static library and a
`withdrawal_ledger_test` executable registered with CTest, whose `WithdrawalLedger` applies its limit
per calendar day instead of over a rolling 24-hour window (R-001) and accepts a negative amount and
reduces the recorded total (R-003). Money is `long long` minor units and instants are epoch
milliseconds, so the fixture needs no decimal library. The project's own test passes, which is the
point.

The fixture is a header and two source files plus one `CMakeLists.txt`:
`include/ledger/withdrawal_ledger.hpp`, `src/withdrawal_ledger.cpp`,
`tests/withdrawal_ledger_test.cpp` and a `CMakeLists.txt` that declares a `project()`, builds `ledger`
as a static library and registers
`add_test(NAME withdrawal_ledger_test COMMAND withdrawal_ledger_test)`. The test is registered under
the name of the file that implements it, which is the convention the requirement mapper reads, so the
CTest report and the requirement agree without either side guessing.

```bash
./codewitness verify fixtures/cpp-withdrawal-ledger \
    --requirements fixtures/cpp-withdrawal-ledger/requirements.md
```

```text
Requirements
  ✗ R-001    Rolling withdrawal limit     FAILED
  ✓ R-002    Fees are applied to the rec… VERIFIED
  ✗ R-003    Negative amounts are reject… FAILED
  ? R-004    Rounding of computed totals  UNVERIFIED

Counterexamples
  CwScRollingOb001   R-001  inputs: {t1=85800000, a1=70000, t2=90000000, a2=70000}
                            expected: observation <= 100000   actual: 140000
  CwScNegativeOb003  R-003  inputs: {amount=-10000}
                            expected: observation >= 50000    actual: 40000

Tests:            1 executed, 1 passed, 0 failed, 0 skipped    (CTest JUnit report)
Coverage:         line 92.7% (76/82), branch 41/72              (gcov)
Mutation testing: 8 mutants, 7 killed, 1 survived, score 87.5% (cpp-text)
Static analysis:  1 medium finding, CW-CPP-CORRECT-001 on the 86400000 constant
Dependencies:     inventory only, nothing resolvable, so the advisory scan consulted no source
```

The overall run is `FAILED` and the exit code is `1`.

R-001 fails through the generated program `CwScRollingOb001`, whose inputs are
`{t1=85800000, a1=70000, t2=90000000, a2=70000}` — 23:50 and 01:00, 70,000 minor units each — and whose
expected `observation <= 100000` is observed as `140000`. R-003 fails through `CwScNegativeOb003`,
whose input `{amount=-10000}` leaves the expected `observation >= 50000` at `40000`. R-002 is
discharged by an executed generated test, which is the only path to `VERIFIED`; R-004 declares no
scenario and stays `UNVERIFIED`. Each counterexample carries a replay command that runs the saved
generated program directly, with the seed and the case index in the environment:

```bash
env CODEWITNESS_SEED=42 CODEWITNESS_CASE=0 <run>/artifacts/generated-tests/CwScRollingOb001
```

What is C++-specific about it is the shape of a generated test, and it is a shape the other adapters do
not use. A generated test is one self-contained C++ translation unit with a `main`, written into the
run's artefact store and never into the tree — the Java and C# adapters write a generated test into the
tree's own test sources, and this one never edits the tree it is verifying. It is compiled by the
compiler the project's own build used, with the exact arguments that
build recorded in `compile_commands.json`, and linked against the `ledger` archive that build produced.
The file includes every header the project has, because a scenario fragment may name any of the
project's types and the generator cannot read a C++ declaration to know which; a project whose headers
cannot all be included together therefore gets a generated test that does not compile, which is
reported as a generated test that did not compile rather than as a defect in the code. A generated test
that does not compile leaves its requirement `UNVERIFIED`, never `BLOCKED`.

The fixture's own test is the one test CTest runs, so the test line is one executed and one passed,
parsed from the CTest JUnit report rather than from console output. Coverage is measured over the
project's own sources only — `src/withdrawal_ledger.cpp` and `tests/withdrawal_ledger_test.cpp` —
because `--coverage` also instruments the standard library headers the compiler read, and a line in a
header nobody in this project wrote is not a line this project can cover; the note says how many such
reports were excluded. Mutation produces eight mutants, of which seven are killed; the survivor is
`- -> +` at `src/withdrawal_ledger.cpp:46`, the window arithmetic `atMillis - entry.atMillis`, which no
test distinguishes from `atMillis + entry.atMillis` because the fixture's entries are all at or after
the instant asked about. Mutation needs a build and a test in one command, and CMake offers no single
invocation for both, so the mutant command composes the project's own two commands with the platform's
shell. Static analysis reads one rule table and reports a single medium finding, `CW-CPP-CORRECT-001`
on the `86400000` constant. The dependency inventory is an inventory only, and this fixture has nothing
resolvable in it, so the advisory scan reports that no source was consulted rather than reporting zero
vulnerabilities.

The fixture needs CMake and CTest and nothing else: it fetches no package, so it builds and tests
offline, and mutation and coverage use the compiler and `gcov` the machine already has. Only CMake is
driven: a C or C++ tree built by a hand-written `Makefile` or by Meson is recognised and reported as
present and not verified rather than built by a guess at its commands.

### `php-withdrawal-ledger`

The PHP counterpart of the reference fixture: a Composer project whose `WithdrawalLedger` applies its
limit per calendar day instead of over a rolling 24-hour window (R-001) and which accepts a negative
withdrawal and reduces the recorded total (R-003). Money is held in integer cents, so the arithmetic the
requirements ask about is exact and the fixture needs no decimal type. The project's own tests pass,
which is the point.

The fixture is a `composer.json` with a PSR-4 autoload prefix and PHPUnit declared in `require-dev`, a
`phpunit.xml` whose `bootstrap` is the Composer autoloader, `src/WithdrawalLedger.php`,
`tests/WithdrawalLedgerTest.php`, `requirements.md` and its own `codewitness.yaml`. The `bootstrap` is
not decoration: PHPUnit has no notion of a test tree it discovers by name alone, so the autoloader is
what makes a generated class findable, and the Composer manifest is what makes PHPUnit exist at all.

R-001 and R-003 fail through generated PHPUnit `TestCase` classes written into `tests/`; R-002 is
discharged by an executed generated test, which is the only path to `VERIFIED`; R-004 declares no
scenario and stays `UNVERIFIED`. Each counterexample carries the exact inputs and a replay command that
is a real PHPUnit invocation with the seed and the case index in the environment, because a `--filter`
cannot carry them.

The fixture's own suite is two tests, and both pass — that is the point of the fixture, and the reason a
passing suite written by the same process is not evidence. Verification runs those two and then generates
three PHPUnit classes, one per scenario. R-001 fails through
`CwScSC_ROLLINGTest#test_case0000` with the inputs `{t1=85800000, a1=70000, t2=90000000, a2=70000}` and an
observed total of 140,000 cents against a limit of 100,000, and R-003 through
`CwScSC_NEGATIVETest#test_case0000` with `{amount=-10000}` and an observed 40,000 where at least 50,000
was required. R-002 is discharged by `CwScSC_FEETest`, an executed generated test. Mutation produces eight
mutants, of which two are killed; the six survivors include `=== -> !==` at `src/WithdrawalLedger.php:45`,
the calendar-day comparison that no test in the fixture distinguishes from its negation. Static analysis
reports no finding, and the dependency inventory is 27 packages read from `composer.lock`.

Coverage needs an instrumenting driver, which PHPUnit does not supply itself: with neither Xdebug nor
PCOV loaded in the interpreter that runs the tests, the phase reports the figure as not measured and names
the missing driver rather than reporting zero, because a zero is a measurement and a missing driver is
not. The CI job loads Xdebug so that the coverage phase is exercised there rather than silently absent.

```bash
./codewitness verify fixtures/php-withdrawal-ledger \
    --requirements fixtures/php-withdrawal-ledger/requirements.md
```

The verdict shape is fixed by the fixture's two declared defects and its three scenarios. The counts a
run prints — executed tests, the coverage figure when a driver is loaded, killed and surviving mutants —
are that run's own, and are recorded by the end-to-end suite rather than transcribed here.

Two things about the PHP fixture are worth knowing before reading its report. The first is that PHPUnit
is a Composer dependency, not part of the toolchain: the build phase has to run `composer install`
before a test can run at all, which is why the fixture's `codewitness.yaml` sets
`sandbox.allow_network: true` and why a CI job warms the toolchain first — the run is then measuring the
adapter, not a download. The second is that coverage is reported as not measured unless Xdebug or PCOV
is loaded for the interpreter that runs the tests; PHP has no instrumentation of its own, and a missing
driver is an unknown figure rather than a zero.

The fixture needs an interpreter and Composer and nothing else installed, and its `composer.json`
declares `php: >=8.1`, which is the version floor the adapter reads and records — and the lowest PHPUnit
11 supports. Beyond that platform requirement it declares one dependency, PHPUnit, in `require-dev`, and
its committed `composer.lock` is Composer's own resolution record for it: committed on purpose, because a
lock file is what makes an install reproducible and because only a lock file carries the resolved
versions an advisory can be a statement about. The ledger itself is plain PHP with no runtime dependency,
so everything the fixture asks about is in `src/WithdrawalLedger.php` and the only package Composer
installs is the test tool.

### `ruby-withdrawal-ledger`

The Ruby counterpart of the PHP fixture: a Bundler project whose `WithdrawalLedger` applies its limit per
calendar day instead of over a rolling 24-hour window (R-001) and which accepts a negative withdrawal and
reduces the recorded total (R-003). Money is held in integer cents, so the arithmetic the requirements ask
about is exact and the fixture needs no decimal type. The project's own tests pass, which is the point.

The fixture is a `Gemfile` declaring RSpec and a committed `Gemfile.lock`, a `.rspec`,
`lib/withdrawal_ledger.rb`, `spec/withdrawal_ledger_spec.rb`, `spec/spec_helper.rb`, `requirements.md` and
its own `codewitness.yaml`. The `spec_helper` is not decoration: RSpec discovers a spec by its file name,
and the `require 'spec_helper'` a generated spec opens with is what puts the ledger on the load path for
it, exactly as the Composer autoloader does for a generated PHPUnit `TestCase`. The `Gemfile.lock` is
committed on purpose, because a lock file is what makes an install reproducible and because only a lock
file carries the resolved versions an advisory can be a statement about.

R-001 and R-003 fail through generated RSpec specs written into `spec/generated/`; R-002 is discharged by an
executed generated test, which is the only path to `VERIFIED`; R-004 declares no scenario and stays
`UNVERIFIED`. Each counterexample carries the exact inputs and a replay command that is a real
`bundle exec rspec` invocation with the seed and the case index in the environment, because RSpec has no
per-case seed flag to pin a case with.

The fixture's own suite is two RSpec examples, and both pass — that is the point of the fixture, and the
reason a passing suite written by the same process is not evidence. Verification runs those two and then
generates three spec files, one per scenario. R-001 fails through the generated boundary case
`cw_case0000` in `spec/generated/cw_sc_SC_ROLLING_spec.rb`, carrying
`{t1=85800000, a1=70000, t2=90000000, a2=70000}` and an observed total of 140,000 cents against a limit of
100,000; R-003 fails with `{amount=-10000}` and an observed 40,000 where at least 50,000 was required.
R-002 is discharged by an executed generated test. Mutation produces eight mutants, of which one is killed
and seven survive; the survivors include `== -> !=` at `lib/withdrawal_ledger.rb:35`, the calendar-day
comparison that no test in the fixture distinguishes from its negation. Static analysis and the security
scan report no finding, and the dependency inventory is six gems read from `Gemfile.lock`.

Coverage is reported as not measured here: the fixture neither declares SimpleCov nor loads it, so the run
has no result set to read, and the phase names that rather than reporting zero, because a zero is a
measurement and a missing report is not.

```bash
./codewitness verify fixtures/ruby-withdrawal-ledger \
    --requirements fixtures/ruby-withdrawal-ledger/requirements.md
```

The verdict shape is fixed by the fixture's two declared defects and its three scenarios. The counts a
run prints — executed tests, the coverage figure when a project loads SimpleCov, killed and surviving
mutants — are that run's own, and are recorded by the end-to-end suite rather than transcribed here.

Two things about the Ruby fixture are worth knowing before reading its report. The first is that RSpec is
a bundle dependency, not part of the toolchain: the build phase has to run `bundle install` before a test
can run at all, which is why the fixture's `codewitness.yaml` sets `sandbox.allow_network: true` and why a
CI job warms its bundle first — the run is then measuring the adapter, not a download. The second is that
coverage is not measured at all unless the project itself loads SimpleCov; this fixture does not, and a
missing result set is an unknown figure rather than a zero.

The fixture needs an interpreter and Bundler and nothing else installed. Ruby and Bundler come from the
machine; RSpec and its five supporting gems are resolved from RubyGems into the run's own bundle on the
first run, and the committed `Gemfile.lock` is Bundler's own resolution record for them. The ledger itself
is plain Ruby with no runtime dependency, so everything the fixture asks about is in
`lib/withdrawal_ledger.rb` and the only gems Bundler installs are the test tool and its dependencies.

### `swift-withdrawal-ledger`

The Swift counterpart of the Ruby fixture: a Swift package whose `WithdrawalLedger` applies its limit
per calendar day instead of over a rolling 24-hour window (R-001) and which accepts a negative
withdrawal and reduces the recorded total (R-003). Money is held in integer cents and instants are
epoch milliseconds, so the arithmetic the requirements ask about is exact. The package's own two tests
pass, which is the point.

The fixture is a `Package.swift` (tools version 5.9), `Sources/WithdrawalLedger/WithdrawalLedger.swift`,
`Tests/WithdrawalLedgerTests/WithdrawalLedgerTests.swift`, `requirements.md` and its own
`codewitness.yaml`. It declares no external dependencies, and XCTest ships with the toolchain, so the
sandbox forbids the network and the whole run — build, tests, coverage and mutation — compiles and runs
offline with nothing installed by hand.

```text
Requirements
  ✗ R-001    Rolling withdrawal limit     FAILED
  ✓ R-002    Fees are applied to the rec… VERIFIED
  ✗ R-003    Negative amounts are reject… FAILED
  ? R-004    Rounding of computed totals  UNVERIFIED

Counterexamples
  CE-001  R-001  inputs: {t1=85800000, a1=70000, t2=90000000, a2=70000}  actual: 140000
  CE-005  R-003  inputs: {amount=-10000}                                 actual: 40000

Coverage:         line 98.1% (52/53), branch 0.0% (0/0)          (llvm-cov, SwiftPM export)
Mutation testing: 11 mutants, 3 killed, 8 survived, score 27.3%  (swift-text)
```

R-001 and R-003 fail through generated XCTest classes written into `WithdrawalLedgerTests` —
`CwScSC_ROLLINGTests` for the midnight crossing and `CwScSC_NEGATIVETests` for the accepted negative
amount — and R-002 is discharged by an executed generated test, which is the only path to `VERIFIED`;
R-004 declares no scenario and stays `UNVERIFIED`. Each counterexample carries the exact inputs and a
replay command that is an `env CODEWITNESS_SEED=… CODEWITNESS_CASE=… swift test --filter
<Module.Class>/<method>` invocation, with the seed and the case index in the environment because
`swift test` has no seed option that pins a search case.

The generated-test shape is Swift-specific in two ways worth knowing. First, XCTest discovers a test
method only when its name begins with `test` and selects a replay only from that discovered set, so the
generated methods are `testCwCase0000`, `testCwSearch` and `testCwSingleCase` rather than the bare
`cw_…` spelling the other adapters use. Second, a test target cannot import itself, so the generated
class imports the package's library target (`@testable import WithdrawalLedger`) while the result is
reported under the test target's module (`WithdrawalLedgerTests.CwScSC_ROLLINGTests`) — the two names
are different facts and the detector records both.

Coverage comes from SwiftPM's own LLVM export: the suite is re-run with
`swift test --enable-code-coverage`, `swift test --show-codecov-path` names the `codecov` JSON, and the
totals are summed over the project's own Swift files only (the ledger at 97.1% line coverage and the
project's own test file at 100%), so a checked-out dependency, SwiftPM's generated test runner or
XCTest itself is not counted. The toolchain instruments no branch regions for this package, so branch
coverage is reported as not measured rather than as zero.

Mutation produces eleven mutants of which three are killed; the survivors include `== -> !=` at
`Sources/WithdrawalLedger/WithdrawalLedger.swift:36`, the calendar-day comparison that no test in the
fixture distinguishes from its negation, and the `+ -> -` flips in the total arithmetic. Static analysis
and the security scan report no finding, and the dependency inventory is an inventory of zero read from
SwiftPM's resolved graph, so the advisory scan consulted no source rather than reporting a clean one.

The fixture needs a Swift toolchain and nothing else: it has no packages, so it builds and tests
offline, and coverage and mutation use the compiler and `llvm-cov` the toolchain already carries. Only
Swift Package Manager is driven: a tree with `.swift` files but no `Package.swift` — a CocoaPods
project, say — is recognised and reported as present and not verified rather than built by a guess at
its commands.

### `verilog-withdrawal-ledger`

The Verilog counterpart of the Swift fixture: a combinational `ledger` module whose `balance` and
`amount` inputs are integer cents and whose `accepted` and `new_balance` outputs carry the result. It
holds the same two kinds of defect. R-001 is a one-cent drift — an accepted withdrawal computes
`new_balance = balance - amount + 32'd1`, so it returns one cent too much — and R-003 is a boundary
defect: the comparison is `<` rather than `<=`, so a withdrawal of exactly the balance is rejected
instead of emptying the account. R-002, the rejected branch, is implemented correctly. The project's own
testbench `tb/ledger_tb.v` passes, which is the point: it checks only R-002 and never the boundary where
the defects live.

The fixture is `rtl/ledger.v`, `tb/ledger_tb.v`, `requirements.md` and its own `codewitness.yaml`.
Verilog has no package manager and no manifest, so there is nothing to declare and nothing to resolve;
the whole run is driven by Icarus Verilog, and the sandbox forbids the network because a combinational
design fetches nothing.

```text
Requirements
  ✗ R-001  An accepted withdrawal reduces the balance by exactly the amount  FAILED
  ✓ R-002  An over-limit withdrawal is rejected                              VERIFIED
  ✗ R-003  A withdrawal of exactly the balance is accepted                   FAILED
  ? R-004  The balance never goes negative                                   UNVERIFIED

Counterexamples
  R-001  inputs: {balance=100000, amount=1}       expected: accepted == 1 && new_balance == balance - amount
  R-003  inputs: {balance=100000, amount=100000}  expected: accepted == 1 && new_balance == 0

Coverage:         not measured (Icarus Verilog exports no coverage data)
Mutation testing: 3 mutants, 0 killed, 3 survived  (verilog-text)
```

R-001 and R-003 fail through generated testbench modules written under `generated-tests/`, one per
scenario, each instantiating the design under test as `dut` and driving the declared inputs; R-002 is
discharged by an executed generated test, which is the only path to `VERIFIED`; R-004 declares no
scenario and stays `UNVERIFIED`.

The generated-test shape is Verilog-specific in three ways worth knowing. First, Verilog has no
case-level test protocol, so the run reports **one test case per testbench**, under the testbench file's
own name, rather than one case per generated input combination. Second, the generated module declares
the scenario's inputs at the design's own port widths — or, when a name is not a port, the scenario's
declared Verilog type, falling back to `reg [63:0]` —
assigns `observation` to a 64-bit register after a `#1` delay so a combinational design has settled,
and checks `invariant` as a plain Verilog boolean expression. Third, a failing case prints the shared
`CODEWITNESS_COUNTEREXAMPLE|…` line and the testbench calls `$fatal` once at the end, so the simulation
exits non-zero and the failing case is recorded, rather than a simulation that runs to completion.

A Verilog test is two processes, compile then simulate, so there is no single command to replay: the
counterexample's reproduction command compiles the kept generated testbench together with the project
and runs it with `vvp -N`. Mutation runs through the shared `TextMutator` with a `VerilogLexis` and one
shell pipeline per mutant that compiles every testbench and stops at the first failure. It generates
three mutants, kills none and reports three survivors, because the fixture's own testbench exercises
only the rejected branch: the defects the scenarios find are exactly the ones no test the project ships
distinguishes from correct code.

Coverage is reported as not measured, because Icarus Verilog exports no coverage data and the adapter
does not drive Verilator. Dependencies are reported as not analysed, because Verilog has no package
manager and there is no advisory ecosystem for HDL, so the phase has no inventory to read and consults
no source rather than reporting a clean result it cannot support.

The fixture needs Icarus Verilog 12.0 or newer and nothing else: `iverilog` compiles and `vvp`
simulates, both come from the machine, and `-g2012` and the `$fatal` exit-code behaviour the adapter
relies on are present in 12.0. The end-to-end test skips itself when either executable is absent, and CI
installs `iverilog` (which brings `vvp`) and asserts both, so the Verilog path is exercised rather than
silently skipped.

### `vhdl-withdrawal-ledger`

The VHDL counterpart of the same fixture: a combinational `ledger` entity whose `balance` and `amount`
inputs are `unsigned(31 downto 0)` integer cents and whose `accepted` and `new_balance` outputs carry the
result. It holds the same two kinds of defect. R-001 is a one-cent drift — an accepted withdrawal computes
`new_balance <= balance - amount + 1`, so it returns one cent too much — and R-003 is a boundary defect:
the comparison is `amount < balance` rather than `amount <= balance`, so a withdrawal of exactly the
balance is rejected instead of emptying the account. R-002, the rejected branch, is implemented
correctly. The project's own testbench `tb/ledger_tb.vhd` passes, which is the point: it checks only R-002
and never the boundary where the defects live.

The fixture is `rtl/ledger.vhd`, `tb/ledger_tb.vhd`, `requirements.md` and its own `codewitness.yaml`.
VHDL has no package manager and no manifest, so there is nothing to declare and nothing to resolve; the
whole run is driven by GHDL, and the sandbox forbids the network because a combinational design fetches
nothing.

```text
Requirements
  ✗ R-001  An accepted withdrawal reduces the balance by exactly the amount  FAILED
  ✓ R-002  An over-limit withdrawal is rejected                              VERIFIED
  ✗ R-003  A withdrawal of exactly the balance is accepted                   FAILED
  ? R-004  The balance never goes negative                                   UNVERIFIED

Counterexamples
  R-001  inputs: {balance=100000, amount=1}       expected: accepted = '1' and observation = to_integer(balance) - to_integer(amount)
  R-003  inputs: {balance=100000, amount=100000}  expected: accepted = '1' and observation = 0

Coverage:         not measured (GHDL's coverage export is not a form this adapter reads)
Mutation testing: 3 mutants, 1 killed, 2 survived  (vhdl-text)
```

R-001 and R-003 fail through generated testbench entities written under `generated-tests/`, one per
scenario, each declaring a signal after the design's ports and instantiating the design; R-002 is
discharged by an executed generated test, which is the only path to `VERIFIED`; R-004 declares no
scenario and stays `UNVERIFIED`.

The generated-test shape is VHDL-specific in three ways worth knowing. First, VHDL has no case-level test
protocol, so the run reports **one test case per testbench**, under the testbench file's own name, rather
than one case per generated input combination. Second, a component instance's ports cannot be selected by
name — VHDL has no `dut.balance` — so the generated testbench declares a signal named after each port and
connects it, and a scenario fragment references the port signals directly; because VHDL is strongly typed,
each integer boundary value is converted into the port's own subtype with `to_unsigned`, `to_signed` or a
std_logic conditional, and `observation` is assigned to an `integer` variable after a `1 ns` wait so a
combinational design has settled. Third, a failing case prints the shared `CODEWITNESS_COUNTEREXAMPLE|…`
line and sets a flag, and a single `assert … severity failure` runs at the end, so every case runs and the
simulation still exits non-zero rather than stopping at the first failure.

A VHDL test is analysis then simulation in one executable, so there is no single command to replay: the
counterexample's reproduction command analyses the kept generated testbench together with the project in
dependency order and runs it with `ghdl -r … --assert-level=error`. `--assert-level=error` is essential
rather than tidy, because without it GHDL prints an assertion error and still exits zero. Mutation runs
through the shared `TextMutator` with a `VhdlLexis` and one shell pipeline per mutant that analyses every
source and simulates every testbench, stopping at the first failure. It generates three mutants, kills one
and reports two survivors: the killed mutant is the comparison swap `< -> >`, which the fixture's own
testbench detects because it checks the rejected branch, and the two arithmetic flips `- -> +` and
`+ -> -` survive because the testbench never exercises the accepted branch where they live. The defects the
scenarios find are exactly the ones no test the project ships distinguishes from correct code.

Coverage is reported as not measured, because GHDL's coverage export is not a form this adapter reads and
the adapter claims no coverage capability. Dependencies are reported as not analysed, because VHDL has no
package manager and there is no advisory ecosystem for HDL, so the phase has no inventory to read and
consults no source rather than reporting a clean result it cannot support.

The fixture needs GHDL and nothing else: `ghdl` analyses, elaborates and simulates as one executable, the
`--std=08` revision and the `--assert-level=error` exit-code behaviour the adapter relies on are the
toolchain's own documented options, and the tree declares no dependencies. The end-to-end test skips
itself when `ghdl` is absent, and CI installs GHDL and asserts it, so the VHDL path is exercised rather
than silently skipped.

### `sql-withdrawal-ledger`

A SQL schema with one deliberate omission: the `ledger` table declares `amount NUMERIC NOT NULL CHECK
(amount <= 1000)` but no check on `balance`, so nothing refuses a balance below zero. Two of the four
requirements state a bound, and the difference between them is the whole point of the fixture.

```text
Requirements
  ✗ R-001  The balance must be at least 0         FAILED
  ✓ R-002  The amount must not exceed 1000        VERIFIED
  ✗ R-003  A withdrawal beyond the balance …      FAILED
  ? R-004  Statements are recorded per ledger id  UNVERIFIED

Counterexamples
  CE-001  R-001  the statement was expected to be rejected and it succeeded:
                 INSERT INTO ledger (id, amount, balance) VALUES (2, 1000, -1)
  CE-002  R-003  the assertion query returned 1 row(s): {id=1, balance=-50}
```

R-001 states a floor the schema does not enforce. The generated probe is built from the **requirement's**
bound, not from the schema's constraints - a row exactly at the bound must be accepted and a row one step
outside it must be rejected - so it inserts a balance of `-1`, finds the database accepts it, and reports
the counterexample. A probe built only from the schema's own checks could never see a check that is not
there, and that distinction is what this fixture pins down.

R-002 states a bound the schema does enforce, and the same kind of probe passes: the row at `1000` is
accepted, the row at `1001` is rejected by the declared check, and the requirement is `VERIFIED` - the one
status that requires an executed experiment. R-003 is refuted by the project's own assertion script
(`tests/overdraft_test.sql`), whose final `SELECT` returns the overdraft row it should not find. R-004
declares nothing executable and stays `UNVERIFIED`.

The two scripts also give mutation something to work with. `tests/amount_test.sql` inserts exactly `1000`
(must succeed), `1001` (must be rejected) and `NULL` (must be rejected), which is what kills both of the
schema's mutants:

```text
Mutation testing
  2 mutants, 2 killed, 0 survived, 0 no coverage, score 100.0%
```

The mutants are the boundary of the declared check moved one step (`amount <= 1000` becoming
`amount < 1000`, which only the `1000` insert detects) and the check removed altogether
(`CHECK (amount <= 1000)` becoming `CHECK (1)`, which only the `1001` insert detects). A suite that
ignored the boundary would report them as survivors instead - the adapter reports a score only when the
scripts earned it.

The fixture needs no toolchain at all: the schema is loaded into SQLite through the JDBC driver already in
the build, so unlike the compiler and runtime fixtures this end-to-end test never skips itself, which is
what keeps SQL covered in every CI job.

### `pascal-withdrawal-ledger`

The Pascal counterpart of the Verilog fixture: an integer-cents unit whose `Withdraw` returns
`balance - amount - 1` whenever it accepts, so R-001 sees a one-cent drift on every accepted withdrawal
and R-003 sees a withdrawal of exactly the balance accepted but leaving `-1` instead of `0`. R-002, the
over-limit rejection, is implemented correctly. The project's own fpcunit suite is four tests of which
two fail, which is the point: the requirement verdicts are settled by generated experiments, not by that
suite.

The fixture is `src/ledger.pas` (the defective unit), `tests/ledgertests.pas` (four fpcunit tests),
`tests/testrunner.pas` (the `consoletestrunner` entry point that links them), `requirements.md` and its
own `codewitness.yaml`. Detection reads the Pascal source suffixes — `.pas` and `.pp` units, `.lpr` and
`.dpr` programs, and `.p` old sources — and Pascal has no package manager and no manifest, so there is
nothing to declare and nothing to resolve; the whole run is driven by Free Pascal, and the sandbox forbids
the network because `fpcunit` ships with the compiler and a generated program fetches nothing.

```text
Requirements
  ✗ R-001  An accepted withdrawal reduces the balance by exactly the amount  FAILED
  ✓ R-002  A withdrawal larger than the balance is rejected                  VERIFIED
  ✗ R-003  A withdrawal of exactly the balance is accepted                   FAILED
  ? R-004  The balance never goes negative                                   UNVERIFIED

Counterexamples
  R-001  inputs: {balance=100000, amount=1}       expected: not ledger.Accepted(balance, amount) or (observation = balance - amount)
  R-003  inputs: {balance=100000, amount=100000}  expected: ledger.Accepted(balance, amount) and (observation = 0)

Tests:            4 executed, 2 passed, 2 failed        (the project's own fpcunit suite)
Mutation testing: 3 mutants, 3 killed, 0 survived       (pascal-text)
Static analysis:  no findings
Dependencies:     not analysed (base FPC has no package manager)
```

The overall run is `FAILED` and the exit code is `1`.

R-001 fails through the generated program `cw_sc_sc_debit_ob_001`, whose boundary cases drive
`{balance=100000, amount=1}` and `{balance=100000, amount=99999}` and observe `balance - amount - 1` where
the requirement asks for `balance - amount`; R-003 fails through `cw_sc_sc_exact_ob_003` with
`{balance=100000, amount=100000}`, which the unit accepts but leaves at `-1` where the requirement asks for
`0`. R-002 is discharged by the executed program `cw_sc_sc_reject_ob_002`, which is the only path to
`VERIFIED`; R-004 declares no scenario and stays `UNVERIFIED`.

The generated-test shape is Pascal-specific in three ways worth knowing. First, a generated test is a
`program`, not a class in a framework, named `cw_sc_<scenario>_<obligation>` with both ids sanitised to
lower case — `cw_sc_sc_debit_ob_001` for `SC-DEBIT` and `OB-001` — and written under `generated-tests/` in
the run workspace. Second, it `uses` the project unit named in the scenario's fragments plus `SysUtils`,
and the compiler links the project's own units into it, so the scenario's call is a direct call into the
unit under test. Third, the program declares only the declared inputs and `observation` — there is no
scratch-variable support, so a scenario that needs an intermediate value must assign it to `observation`
or call a project function — and a failing case prints the shared `CODEWITNESS_COUNTEREXAMPLE|…` line with
`WriteLn` while the program calls `Halt(1)` once at the end, so it exits non-zero and the failing case is
recorded rather than the program running to completion.

Coverage is reported as not measured, because base Free Pascal exports no coverage data and the adapter
does not drive an instrumenting build. Dependencies are reported as not analysed, because FPC has no
package manager and a Pascal unit has no manifest to inventory, so the phase has no lock file to read and
consults no source rather than reporting a clean result it cannot support.

Mutation runs through the shared `TextMutator` with a `PascalLexis` and one command per mutant that
compiles and runs the fixture's own suite. It generates three mutants, kills all three and reports no
survivor. Static analysis and the security scan report no finding.

The fixture needs Free Pascal and nothing else: `fpc` 3.2.2 is the version this result was measured with,
and `fpcunit`, `testregistry` and `consoletestrunner` ship with the compiler, so the build, the tests and
every generated program compile and run offline with no package manager involved. The adapter resolves
`fpc` from the project's own tools directory, then the `FPC` environment variable (an absolute path to the
compiler), then the conventional install locations and `PATH`. A relocated or vendored Free Pascal that
keeps its `fpc.cfg` outside the prefix it was configured for needs `PPC_CONFIG_PATH` pointing at that
`etc` directory; the adapter finds it beside the compiler and passes it to every child process, so a
normal system install needs nothing. The end-to-end test `PascalEndToEndVerificationTest` is tagged `e2e`
and skips itself when `fpc` is absent, via `assumePascalAvailable()`, and CI asserts the compiler is
present so the Pascal path is exercised rather than silently skipped.

### `polyglot-service`

One directory holding six languages' projects and a `mix.exs`: a Maven project, a Python package with a
`poetry.lock`, a Cargo crate, a Go module, a TypeScript ledger and a plain-JavaScript tool. Nothing
arranges the sharing - each adapter finds its own files by extension and ignores the rest - which is the
property the fixture exists to demonstrate. JavaScript and TypeScript own disjoint extensions
(`js`/`mjs`/`cjs` against `ts`/`tsx`/`mts`/`cts`), so both are verified rather than one stepping aside
for the other.

One scenario is declared, for the Python half. The other five requirements have no scenario and stay
`UNVERIFIED`, which is the honest answer for a requirement nobody has said how to execute. That one
scenario is what proves the thing the per-language fixtures cannot: a generated test executed against the
right language while six of them share a tree.

The `mix.exs` is not decoration either. It is the case that running more adapters cannot fix - a language
present in the tree that nothing understands - and the report names it, with the file that gave it away,
instead of computing verdicts as though the code were not there.

```text
project      polyglot-service (polyglot, polyglot)
  language   java via java (maven, 1 module(s), build succeeded)
  language   python via python (python, 1 module(s), build succeeded)
  language   rust via rust (cargo, 1 module(s), build succeeded)
  language   go via go (go, 1 module(s), build succeeded)
  language   javascript via javascript (node, 1 module(s), build succeeded)
  language   typescript via typescript (node, 1 module(s), build succeeded)
  NOT VERIFIED elixir - present (mix.exs), but no adapter in this run can verify it

Requirements
  ? R-001  The Java ledger totals a list of amounts      UNVERIFIED
  ✓ R-002  The Python ledger totals a list of amounts    VERIFIED
  ? R-003  The Rust ledger totals a list of amounts      UNVERIFIED
  ? R-004  The Go ledger totals a list of amounts        UNVERIFIED
  ? R-005  The TypeScript ledger totals a list of amounts UNVERIFIED
  ? R-006  The JavaScript tool formats minor units       UNVERIFIED

Tests
  8 executed, 8 passed, 0 failed, 0 skipped (Python 2, Rust 2, Go 2, JavaScript 1, TypeScript 1)
```

### `off-by-one-boundary`

```text
R-001  A withdrawal of exactly the daily limit is accepted    FAILED
       CE-001 inputs: {amount=1000}  actual: 0
R-002  A withdrawal strictly below the daily limit            VERIFIED
```

The implementation guards with `>= 0` where `> 0` is required, so a withdrawal of exactly 1,000 is
rejected. The project's tests probe 999.99 and 1,000.01, which is exactly the gap boundary
enumeration is designed to close.

### `null-handling-defect`

```text
R-001  A null username is rejected without throwing   FAILED
       CE-001 inputs: {username=null}
       actual: java.lang.NullPointerException: Cannot invoke "String.trim()" because "username" is null
R-002  Non-blank usernames are registered             VERIFIED
```

### `security-vulnerability`

Requirements are declared in `requirements.yaml` with `properties: [{type: security}]` and explicit
`paths`, so CodeWitness plans a security obligation for each:

```text
R-001  Database credentials are not embedded in source   FAILED
       CE-001  CW-SEC-001  CRITICAL  src/main/java/com/example/security/DatabaseConfig.java:19
R-002  SQL injection                                      FAILED
       CE-002  CW-SEC-002  HIGH      src/main/java/com/example/security/AccountRepository.java:47
```

No test runs: a deterministic analyzer demonstrating the defect is as good as a failing test, and the
obligation is marked `FAILED` by the analyzer rather than being overwritten by the strategies that
have nothing to execute.

### `mutation-surviving`

The implementation is correct and both requirements are `VERIFIED` by their scenarios. The project's
own suite is weak: it calls `describe(BigDecimal.ZERO)` without asserting anything, so PIT reports
`generated=8, killed=6, survived=2` and the run's limitations say:

```text
Test strength: 2 mutant(s) in the code covered by R-001 survived the test suite
(FeeSchedule#describe:50 -> changed conditional boundary). Nobody checked those behaviours:
surviving mutants are not a violation of the requirement, but they are a reason not to trust
unexecuted assumptions about it.
```

This is the deliberate design choice described in `docs/verification-model.md`: test strength is
evidence about the *tests*, not about the requirement, so it is reported loudly without silently
overriding a verdict that execution supports.

### `misleading-tests`

`GradeCalculator` is correct. The project's tests are not: one `@Test` method calls the code under
test and asserts nothing, and another asserts that a string has a non-negative length.

```text
R-001  Letter grades follow the documented boundaries   VERIFIED
R-002  The boundaries match the institution's policy    UNVERIFIED  (no scenario can express it)
findings: CW-TEST-001 MEDIUM src/test/java/com/example/grade/GradeCalculatorTest.java:27
```

A test that cannot fail is worse than no test, because it looks like evidence — so CodeWitness
reports it as a finding.

### `correct-implementation`

The control. A rolling 24-hour limit implemented with an explicit list of accepted timestamps,
`BigDecimal.compareTo`, and a closed `[t-24h, t]` window; one requirement, one `sequence` scenario
with boundary values and a 200-case random search.

```text
R-001  Rolling 24-hour withdrawal limit   VERIFIED
  OB-001 PROPERTY  VERIFIED  13 generated test case(s) executed and all passed
```

## Running them yourself

```bash
./build.sh
export PATH=/path/to/apache-maven/bin:$PATH
./tools/verify-all-fixtures.sh
```

Each fixture is a standalone project and can also be verified one at a time, from the repository root
with the `./codewitness` launcher:

```bash
./codewitness verify fixtures/withdrawal-ledger --requirements fixtures/withdrawal-ledger/requirements.md
./codewitness verify fixtures/python-withdrawal-ledger --requirements fixtures/python-withdrawal-ledger/requirements.md
./codewitness verify fixtures/rust-withdrawal-ledger --requirements fixtures/rust-withdrawal-ledger/requirements.md
./codewitness verify fixtures/go-withdrawal-ledger --requirements fixtures/go-withdrawal-ledger/requirements.md
./codewitness verify fixtures/typescript-withdrawal-ledger --requirements fixtures/typescript-withdrawal-ledger/requirements.md
./codewitness verify fixtures/javascript-withdrawal-ledger --requirements fixtures/javascript-withdrawal-ledger/requirements.md
./codewitness verify fixtures/csharp-withdrawal-ledger --requirements fixtures/csharp-withdrawal-ledger/requirements.md
./codewitness verify fixtures/kotlin-withdrawal-ledger --requirements fixtures/kotlin-withdrawal-ledger/requirements.md
./codewitness verify fixtures/cpp-withdrawal-ledger --requirements fixtures/cpp-withdrawal-ledger/requirements.md
./codewitness verify fixtures/php-withdrawal-ledger --requirements fixtures/php-withdrawal-ledger/requirements.md
./codewitness verify fixtures/ruby-withdrawal-ledger --requirements fixtures/ruby-withdrawal-ledger/requirements.md
./codewitness verify fixtures/swift-withdrawal-ledger --requirements fixtures/swift-withdrawal-ledger/requirements.md
./codewitness verify fixtures/verilog-withdrawal-ledger --requirements fixtures/verilog-withdrawal-ledger/requirements.md
./codewitness verify fixtures/vhdl-withdrawal-ledger --requirements fixtures/vhdl-withdrawal-ledger/requirements.md
./codewitness verify fixtures/pascal-withdrawal-ledger --requirements fixtures/pascal-withdrawal-ledger/requirements.md
./codewitness verify fixtures/security-vulnerability --requirements fixtures/security-vulnerability/requirements.yaml
```

Run data is written under `<fixture>/.codewitness/` and is ignored by git; remove it with
`./codewitness clean <fixture> --all`.

## Adding a fixture

See [CONTRIBUTING.md](../CONTRIBUTING.md): one defect, stated in a comment; requirements that
describe the intended behaviour; scenarios that can expose the defect; and an entry in the table
above plus an assertion in the end-to-end suite.
