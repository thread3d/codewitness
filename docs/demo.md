# Demo: a real run

This is a transcript of CodeWitness verifying a project that contains deliberately introduced defects,
with a note on what to look at in each part of the output. Everything below is copied from an actual
run on this repository; the only editing is removing the progress lines.

Every `codewitness` command shown here is the repository's `./codewitness` launcher, run from the
repository root. `./build.sh` builds the jar it needs; it does not install a `codewitness` command on
`PATH`.

## Prerequisites

```bash
./build.sh            # produces codewitness-cli/target/codewitness.jar
export PATH=/path/to/apache-maven-3.x/bin:$PATH   # CodeWitness invokes the project's build tool
./demo.sh             # or follow the steps below
```

The Java demo needs Maven (and a JDK) on `PATH`. The Python, Rust, Go, TypeScript, JavaScript, C#, Kotlin,
C/C++, PHP, Ruby, Swift, Verilog, VHDL and Pascal fixtures at the end need Python 3, Cargo, the `go` tool, a Node
runtime (22.6+ for TypeScript, 18.17+ for JavaScript), the .NET SDK (8+), Maven with network access the
first time, CMake with CTest, an interpreter with Composer, an interpreter with Bundler, a Swift
toolchain, Icarus Verilog (`iverilog` and `vvp`), GHDL (`ghdl`) and Free Pascal (`fpc`) respectively, and nothing
else: the Kotlin section is a Maven build, so `kotlinc` and the standard library arrive as dependencies,
the C and C++ section fetches no package at all, the PHP and Ruby sections' PHPUnit and RSpec arrive
from Packagist and RubyGems as dependencies rather than as part of the toolchain, the Swift section's
XCTest ships with the toolchain and the package declares no dependencies, so it runs offline, the
Verilog section's Icarus compiler and simulator come from the machine and compile and simulate a
combinational design with the network forbidden, the VHDL section's GHDL analyser, elaborator and
simulator come from the machine and run against the same kind of design offline, and the Pascal section's compiler brings its own
`fpcunit` test framework, so it needs no package manager and no network either.

## 1. What is in the project?

`./codewitness inspect` looks at the project without executing anything:

```text
CODEWITNESS  inspect
project          withdrawal-ledger (withdrawal-ledger-1k8v2c)
path             .../fixtures/withdrawal-ledger
adapter          java
language         java
build system     maven
build root       .../fixtures/withdrawal-ledger/.codewitness/runs/.../workspace
java version     25
build wrapper    none
test frameworks  junit5
capabilities     [BUILD, COVERAGE, DEPENDENCY_SCAN, GENERATED_TESTS, INSPECT, MUTATION,
                  PROPERTY_TESTING, SECURITY_SCAN, STATIC_ANALYSIS, TEST]
modules          1
  - withdrawal-ledger (jar) at ...
      main: .../src/main/java
      test: .../src/test/java
scenarios        4
```

`inspect` is the first thing to run when a verification result is surprising: it shows exactly what
CodeWitness thinks it is looking at, before anything is executed.

## 2. Verification

```bash
./codewitness verify fixtures/withdrawal-ledger \
    --requirements fixtures/withdrawal-ledger/requirements.md \
    --max-duration 10m
```

```text
Loaded 5 requirement(s) from fixtures/withdrawal-ledger/requirements.md
CodeWitness 0.1.1 verifying withdrawal-ledger

CODEWITNESS  verification report
run          CW-2026-10-06-CWZBF
project      withdrawal-ledger (maven, java)
revision     unknown (not a git checkout or git unavailable)
overall      FAILED
isolation    PROCESS

Requirements
  ✗ R-001    Rolling withdrawal limit     FAILED
      A counterexample reproduced by execution violates this requirement: Generated test
      dev.codewitness.generated.Cw_SC_ROLLING_OB_001_Test#case0000 violated R-001: 1400 (see CE-001)
  ✗ R-002    Fees are applied to the rec… FAILED
      A counterexample reproduced by execution violates this requirement: Generated test
      dev.codewitness.generated.Cw_SC_FEE_OB_002_Test#singleCase violated R-002: 100 (see CE-002)
  ✗ R-003    Negative amounts are reject… FAILED
      A counterexample reproduced by execution violates this requirement: Generated test
      dev.codewitness.generated.Cw_SC_NEGATIVE_OB_003_Test#case0000 violated R-003: 400 (see CE-003)
  ✗ R-004    Multiple concurrent withdra… FAILED
      A counterexample reproduced by execution violates this requirement: Generated test
      dev.codewitness.generated.Cw_SC_CONCURRENT_OB_004_ConcurrencyTest#concurrentSearch violated
      R-004: 1092 (see CE-005)
  ? R-005    Rounding of computed totals  UNVERIFIED
      CodeWitness found no way to execute this requirement: every planned obligation is still
      unverified. The requirement must not be treated as passing.

Counts
  ✓ VERIFIED             0
  ⚠ PARTIALLY_VERIFIED   0
  ✗ FAILED               4
  ? UNVERIFIED           1
  ⛔ BLOCKED              0

Tests
  3 executed, 3 passed, 0 failed, 0 skipped

Coverage
  line 56.3% (18/32), branch 28.6% (4/14)

Mutation testing
  14 mutants, 6 killed, 0 survived, 8 no coverage, score 100.0%

Static analysis
  3 finding(s): 1 high, 2 medium

Dependencies
  0 dependencies (inventory only: no vulnerability database was consulted, so this report does
  NOT show whether any dependency has known advisories)

Counterexamples
  CE-001  R-001  Generated test ...Cw_SC_ROLLING_OB_001_Test#case0000 violated R-001: 1400
      inputs:   {t1=85800000, a1=700, t2=90000000, a2=700}
      expected: observation.compareTo(new java.math.BigDecimal("1000")) <= 0
      actual:   1400
      reproduce: mvn -Dtest=Cw_SC_ROLLING_OB_001_Test test

Unknown / limitations
  - Execution isolation: PROCESS via local-process. child process with sanitised environment …
  - Test strength: 2 mutant(s) in the code covered by R-001 survived the test suite …

Elapsed 1m2s, generated tests 4, LLM calls 0

JSON report: .../report.json
HTML report: .../report.html
```

## 3. Reading the result

**Four defects, four counterexamples, one honest gap.**

| Requirement | Verdict | Why |
| --- | --- | --- |
| R-001 Rolling withdrawal limit | `FAILED` | The implementation resets the limit at midnight instead of using a rolling 24-hour window. Two withdrawals of 700 an hour apart total 1,400. |
| R-002 Fees are applied to the total | `FAILED` | `fee.add(...)` discards the result; `BigDecimal` is immutable, so the call is a silent no-op. |
| R-003 Negative amounts rejected | `FAILED` | A withdrawal of `-100` is accepted and reduces the recorded total to 400. |
| R-004 Concurrent withdrawals | `FAILED` | Eight threads released together pass the check before any of them writes, reaching 1,092 against a limit of 1,000. |
| R-005 Rounding of totals | `UNVERIFIED` | No scenario was declared, so CodeWitness cannot execute anything about it. |

Note what the run *also* shows: the project's own tests all pass (3/3). The counterexamples come from
generated experiments, not from the project's test suite. That is the entire point: a green test run
from the same process that produced the code is not evidence.

Note also that R-005 is `UNVERIFIED` rather than `FAILED` or `VERIFIED`. CodeWitness was asked
something it cannot answer, and it said so. The overall run is `FAILED` because of the four
counterexamples, and the exit code is `1`.

## 4. Reproducing a counterexample

The evidence is not a screenshot. The generated test is kept in the run's artifact directory, and
`reproduce` puts it back and runs the exact failing case:

```bash
./codewitness reproduce CW-2026-10-06-CWZBF --counterexample CE-005 --project fixtures/withdrawal-ledger --run
```

```text
Restored .../src/test/java/dev/codewitness/generated/Cw_SC_CONCURRENT_OB_004_ConcurrencyTest.java
$ .../mvn -Dtest=Cw_SC_CONCURRENT_OB_004_ConcurrencyTest -Dcodewitness.seed=11 -Dcodewitness.case=0 test

[ERROR] Cw_SC_CONCURRENT_OB_004_ConcurrencyTest.concurrentSearch -- Time elapsed: 0.026 s <<< FAILURE!
org.opentest4j.AssertionFailedError: CODEWITNESS_COUNTEREXAMPLE|case=0|seed=11|inputs={amount=273}|
expected=observation.compareTo(new java.math.BigDecimal("1000")) <= 0|actual=1092|
invariant=…|step=concurrent execution|requirement=R-004|obligation=OB-004

[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0
```

Same seed, same case index, same inputs, same failure. The generated test is ordinary JUnit; you can
read it, keep it (`report.keep_generated_tests: true`) or commit it as a regression test.

## 5. What the report files contain

`report.json` is the canonical record: run id, environment (Java version, build tool version, source
revision, configuration fingerprint, requirements hash, isolation level), the per-requirement verdicts
with their obligations and rationales, every evidence record with the command that produced it, the
counterexamples with their inputs, the test/mutation/coverage/static-analysis summaries, the phase
results, the budget actually consumed, and the limitations.

`report.html` is the same story for a human: an executive summary with the status counts, then the
requirements, the counterexamples with their exact inputs and reproduction commands, the test results,
the mutation section (including the explicit statement that a surviving mutant means *nobody checked*,
not that the requirement is violated), the findings, the environment, the limitations, and a
collapsible appendix listing every evidence record.

The two are generated from the same in-memory report, so they can never disagree.

## 6. Where the run data lives

```text
fixtures/withdrawal-ledger/.codewitness/
├── codewitness.db                 SQLite index: runs, requirements, evidence, findings, counterexamples
├── runs/CW-2026-10-06-CWZBF/
│   ├── report.json                canonical machine-readable report
│   ├── report.html                human report
│   ├── artifacts/
│   │   ├── generated-tests/…      copies of the generated tests, with the raw reports they produced
│   │   └── static-analysis/…
│   └── workspace/                 the isolated copy the run operated on
```

The project itself is untouched: generated tests are written into the copy, run there, and removed.
`./codewitness report` lists stored runs; `./codewitness clean` removes old ones.

## 7. Trying it on your own project

```bash
cd /path/to/my-project
/path/to/the/codewitness/checkout/codewitness init .   # the launcher is not installed on PATH
$EDITOR requirements.md                              # describe what the code must do
$EDITOR codewitness.yaml                            # declare how each requirement can be executed
/path/to/the/codewitness/checkout/codewitness verify . --requirements requirements.md
```

Start with one requirement and one scenario. If CodeWitness reports `UNVERIFIED`, read the rationale:
it always says why, and usually the answer is "you have not told me how to execute this yet".

## 8. The same thing for Python

The walkthrough above uses the Java fixture, but nothing about it is Java-specific. The Python fixture
is a ledger with two introduced defects, verified with nothing but a Python interpreter:

```bash
./codewitness verify fixtures/python-withdrawal-ledger \
    --requirements fixtures/python-withdrawal-ledger/requirements.md
```

```text
Requirements
  ✗ R-001    Rolling withdrawal limit     FAILED
  ✓ R-002    Fees are applied to the rec… VERIFIED
  ✗ R-003    Negative amounts are reject… FAILED
  ? R-004    Rounding of computed totals  UNVERIFIED

Counterexamples
  CE-001  R-001  inputs: {t1=85800000, a1=700, t2=90000000, a2=700}  actual: 1400
  CE-003  R-003  inputs: {amount=-100}                               actual: 400
```

The scenario fragments are Python, the generated tests are `unittest` cases, and the counterexample's
replay command runs the saved generated module with the recorded seed and case. R-002 reaches
`VERIFIED` through an executed generated test; R-004 declares no scenario and stays `UNVERIFIED`.
Mutation runs through the standard library `ast` module, so no mutation framework has to be installed.

## 9. The same thing for Rust

The Rust fixture is the same ledger again, this time as a Cargo library with amounts in integer minor
units. It builds offline because it has no dependencies:

```bash
./codewitness verify fixtures/rust-withdrawal-ledger \
    --requirements fixtures/rust-withdrawal-ledger/requirements.md
```

```text
Requirements
  ✗ R-001    Rolling withdrawal limit      FAILED
  ✓ R-002    Fees are applied to the rec…  VERIFIED
  ✗ R-003    Negative amounts are reject…  FAILED
  ? R-004    Rounding of computed totals   UNVERIFIED

Counterexamples
  CE-001  R-001  inputs: {t1=85800000, a1=70000, t2=90000000, a2=70000}  actual: 140000
  CE-002  R-003  inputs: {amount=-10000}                                 actual: 40000

Mutation testing
  12 mutants, 5 killed, 7 survived, 0 no coverage, score 41.7%
```

The scenario fragments are Rust, the generated tests are standard-library Cargo integration tests
under `tests/`, and the replay command is a `cargo test --test <generated target>` invocation with the
recorded seed and case. Coverage is reported as "not measured" because `cargo-llvm-cov` is not
installed in this environment — which is the honest result, not a silent zero. Mutation runs through
the adapter's built-in source mutator, so `cargo-mutants` does not have to be installed either.

## 10. The same thing for Go

The Go fixture is the same ledger again, this time as a Go module with amounts in integer minor units.
It builds offline because it has no dependencies:

```bash
./codewitness verify fixtures/go-withdrawal-ledger \
    --requirements fixtures/go-withdrawal-ledger/requirements.md
```

```text
Requirements
  ✗ R-001    Rolling withdrawal limit      FAILED
  ✓ R-002    Fees are applied to the rec…  VERIFIED
  ✗ R-003    Negative amounts are reject…  FAILED
  ? R-004    Rounding of computed totals   UNVERIFIED

Counterexamples
  CE-001  R-001  inputs: {t1=85800000, a1=70000, t2=90000000, a2=70000}  actual: 140000
  CE-002  R-003  inputs: {amount=-10000}                                 actual: 40000
```

The scenario fragments are Go, the generated tests are ordinary `_test.go` files placed in the
package under test, and the replay command is a `go test -run <generated test>` invocation with the
recorded seed and case. Existing tests are read from `go test -json` with `-count=1`, coverage comes
from the built-in `go test -coverprofile`, and mutation runs through the adapter's built-in source
mutator, so nothing has to be installed beyond the `go` tool.

## 11. The same thing for TypeScript

The TypeScript fixture is the same ledger again, this time as a Node module with amounts as `bigint`
minor units. It runs offline because it has no packages:

```bash
./codewitness verify fixtures/typescript-withdrawal-ledger \
    --requirements fixtures/typescript-withdrawal-ledger/requirements.md
```

```text
Requirements
  ✗ R-001    Rolling withdrawal limit      FAILED
  ✓ R-002    Fees are applied to the rec…  VERIFIED
  ✗ R-003    Negative amounts are reject…  FAILED
  ? R-004    Rounding of computed totals   UNVERIFIED

Counterexamples
  CE-001  R-001  inputs: {t1=85800000, a1=70000, t2=90000000, a2=70000}  actual: 140000
  CE-002  R-003  inputs: {amount=-10000}                                 actual: 40000

Tests
  2 executed, 2 passed, 0 failed, 0 skipped

Coverage
  line 97.3% (73/75), branch 88.9% (8/9)

Mutation testing
  12 mutants, 5 killed, 7 survived, 0 no coverage, score 41.7%
```

The scenario fragments are TypeScript (inputs are referenced as `#{name}`), the generated tests are
`node:test` modules, and the replay command is a `node --test <saved generated test>` invocation with
the recorded seed and case. Coverage comes from Node's own V8 instrumentation, read from its LCOV
export. Mutation runs through the adapter's built-in source mutator, so no mutation framework has to
be installed. Node 22.6 or later is required; no package and no build step is.

## 12. The same thing for JavaScript

The JavaScript fixture is the same ledger again, this time as a plain JavaScript module with amounts
as `bigint` minor units and no type annotations anywhere. It runs offline because it has no packages,
and nothing is type checked, because JavaScript has no compiler: the build is a syntax check with the
runtime's own parser.

```bash
./codewitness verify fixtures/javascript-withdrawal-ledger \
    --requirements fixtures/javascript-withdrawal-ledger/requirements.md
```

```text
Requirements
  ✗ R-001    Rolling withdrawal limit      FAILED
  ✓ R-002    Fees are applied to the rec…  VERIFIED
  ✗ R-003    Negative amounts are reject…  FAILED
  ? R-004    Rounding of computed totals   UNVERIFIED

Tests
  2 executed, 2 passed, 0 failed, 0 skipped
```

R-001 fails with two withdrawals either side of midnight and R-003 fails because a negative amount is
accepted; both counterexamples carry the exact `bigint` inputs and a replayable `node --test`
command.

The scenario fragments are plain JavaScript (inputs are referenced as `#{name}`), the generated tests
are `node:test` modules written with the `.test.mjs` extension so they are always ES modules, and the
replay command is a `node --test <saved generated test>` invocation with the recorded seed and case.
Coverage comes from Node's own V8 instrumentation, read from its LCOV export. Mutation runs through
the adapter's built-in source mutator, so no mutation framework has to be installed. Node 18.17 or
later is required for the JUnit reporter the test phase reads; no package and no build step is.

## 13. The same thing for C#

The C# fixture is the same ledger again, this time as a .NET solution with amounts in `long` minor
units. Its test project references the test host every `dotnet test` project references, so there is
nothing to install beyond the SDK:

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

Coverage
  line 89.5% (17/19), branch 50.0% (1/2)

Mutation testing
  12 mutants, 5 killed, 7 survived, 0 no coverage, score 41.7%
```

The scenario fragments are C#, the generated tests are xUnit classes written into the tree's own test
project — `CwScRollingOb001` and `CwScNegativeOb003` — and the replay command is a
`dotnet test --filter FullyQualifiedName~<class>` invocation with the recorded seed and case in the
environment, because a `--filter` cannot carry them. Existing tests are read from the TRX report the
runner writes, coverage comes from coverlet's cobertura report collected by
`dotnet test --collect:"XPlat Code Coverage"`, and mutation runs through the adapter's own source
mutator, so no mutation framework has to be installed. The fixture targets `net8.0`, needs the .NET 8
SDK, and needs no network: its sandbox config sets `allow_network: false`, and the test host packages it
restores must already be in the machine's NuGet cache — which they are on any machine that has run
`dotnet test` before.

## 14. The same thing for Kotlin

The Kotlin fixture is the same ledger once more, this time with `BigDecimal` money and a Maven build with
`kotlin-maven-plugin`. It needs Maven and nothing else installed — no Kotlin compiler and no Gradle
distribution, because Maven fetches the plugin and the standard library the way it fetches any dependency:

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

Coverage
  line 100.0% (23/23), branch 80.0% (8/10)

Mutation testing
  9 mutants, 7 killed, 2 survived, 0 no coverage, score 77.8%
```

The scenario fragments are Kotlin, the generated tests are Kotlin JUnit 5 classes written into
`src/test/kotlin` — `Cw_SC_ROLLING_OB_001_Test` and `Cw_SC_NEGATIVE_OB_003_Test` — and the replay command
is a real Maven invocation, `mvn -Dtest=Cw_SC_ROLLING_OB_001_Test -Dcodewitness.seed=42
-Dcodewitness.case=0 test`, with the seed and case as system properties that Surefire forwards. The
compiler diagnostics the build phase reads are the Kotlin compiler's own
(`[ERROR] file:///path/File.kt:4:23 message`), coverage comes from JaCoCo over the same JVM bytecode a
Java project produces, and mutation runs through the adapter's own source mutator, whose lexis knows that
`===` is not `==` and that `..` is not a malformed number.

Kotlin is where the adapters have to be careful about what claims a tree. It shares Gradle and Maven with
Java, so neither build descriptor is evidence of either language on its own: the Kotlin adapter claims a
tree with Kotlin sources or a Kotlin plugin, and the Java adapter claims one with Java sources. A tree
holding both is polyglot and is verified by both, each over its own files.

## 15. The same thing for C and C++

The C and C++ fixture is the same ledger once more, this time as a CMake project with a `ledger` static
library and a `withdrawal_ledger_test` executable registered with CTest. It needs CMake and CTest and
nothing else installed — no package is fetched — and the money is `long long` minor units:

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

Tests
  1 executed, 1 passed, 0 failed, 0 skipped

Coverage
  line 92.7% (76/82), branch 41/72

Mutation testing
  8 mutants, 7 killed, 1 survived, score 87.5%
```

The scenario fragments are C++, the generated tests are one self-contained C++ program per scenario,
compiled by the compiler the project's own build used with the arguments that build recorded in
`compile_commands.json` and linked against the library it produced, and the replay command runs the
saved program directly with the seed and case in the environment:
`env CODEWITNESS_SEED=42 CODEWITNESS_CASE=0 <run>/artifacts/generated-tests/CwScRollingOb001`. The
generated file is never added to the project's build, and the executable is kept in the run's artefact
store, which is what makes the replay work after the run. Existing tests are read from the JUnit report
`ctest --output-junit` writes, coverage comes from `gcov` over a second configure that adds
`--coverage` to `CMAKE_C_FLAGS` and `CMAKE_CXX_FLAGS`, and mutation runs through the adapter's own text
mutator, whose profile composes `cmake --build` and `ctest` with the platform shell because CMake has no
single invocation that builds and tests in one command. Mutation kills seven of its eight mutants; the
survivor is `- -> +` in the window arithmetic, which no test distinguishes from `+` because every entry
in the fixture is at or after the instant asked about. Static analysis reports one medium finding,
`CW-CPP-CORRECT-001` on the `86400000` constant, and the dependency phase reports an inventory with
nothing resolvable rather than reporting zero vulnerabilities.

Coverage counts the project's own sources only. The instrumented build also compiles the standard
library headers, and a line in a header nobody in this project wrote is not a line this project can
cover, so those reports are excluded from the figure and the note says how many were. A generated test
that does not compile leaves its requirement `UNVERIFIED`, never `BLOCKED`, and a C or C++ project whose
headers cannot all be included together gets exactly that — it is a stated limit, not a hidden one.

Only CMake is driven, which is the one thing about C and C++ support worth knowing before pointing it at
a tree: a `CMakeLists.txt` declaring a `project()` and at least one C or C++ source is claimed and
verified, while a tree built by a hand-written `Makefile` or by Meson is recognised and reported as
present and not verified. Nothing is guessed at a build this adapter does not drive.

## 16. The same thing for PHP

The PHP fixture is the same ledger once more, this time as a Composer project with PHPUnit. It needs an
interpreter and Composer and nothing else installed, but it is the one fixture here whose test tool is a
project dependency rather than a tool of the machine: PHPUnit comes from Packagist, so the build phase
has to run `composer install` before a test can run at all, which is why the fixture's sandbox
configuration allows network access and why the CI job warms the toolchain first. The run is then
measuring the adapter, not a download.

```bash
./codewitness verify fixtures/php-withdrawal-ledger \
    --requirements fixtures/php-withdrawal-ledger/requirements.md
```

```text
Requirements
  ✗ R-001    Rolling withdrawal limit     FAILED
  ✓ R-002    Fees are applied to the rec… VERIFIED
  ✗ R-003    Negative amounts are reject… FAILED
  ? R-004    Rounding of computed totals  UNVERIFIED

Counterexamples
  CwScSC_ROLLINGTest   R-001  inputs: {t1=85800000, a1=70000, t2=90000000, a2=70000}
                              expected: $observation <= 100000   actual: 140000
  CwScSC_NEGATIVETest  R-003  inputs: {amount=-10000}
                              expected: $observation >= 50000    actual: 40000

Tests
  2 executed, 2 passed, 0 failed, 0 skipped  (parsed from the PHPUnit JUnit XML report)

Coverage
  coverage not measured (no coverage driver is loaded in the PHP interpreter)

Mutation testing
  8 mutants, 2 killed, 6 survived, score 25.0%
```

The scenario fragments are PHP. `${name}` becomes the PHP variable `$name`, `setup` is PHP statements run
once per case, `observation` is a PHP expression assigned to `$observation`, and `invariant` is the PHP
boolean expression that must hold — in the fixture, `$observation <= 100000`. The generated tests are
PHPUnit `TestCase` classes written into the project's own test root, because a PHP project's autoloader
and PHPUnit configuration are what make a class runnable; the replay command is a real
`php vendor/bin/phpunit --filter 'CwScSC_ROLLINGTest'` invocation with the recorded seed and case in the
environment, because a `--filter` cannot carry them.

Existing tests are read from the JUnit report `--log-junit` writes. PHPUnit gives a test class's name
twice — in PHP's own backslash spelling and in JUnit's dotted one — and the parser reads the dotted
`classname`, which is the identifier the requirement mapper derives from the file's `namespace`
declaration. Coverage comes from PHPUnit's Clover report when Xdebug or PCOV is loaded for the
interpreter and is reported as not measured, with the missing driver named, when neither is: PHP has no
instrumentation of its own, and a missing driver is an unknown figure rather than a zero. Mutation runs
through the adapter's own text mutator, whose lexis knows that `.` is string concatenation and not a
number separator and that `===` is not `==`.

The fixture's two defects are the rolling-window bug behind R-001 and the accepted negative amount
behind R-003, its fee scenario discharges R-002 through an executed generated test, and R-004 declares no
scenario, so it stays `UNVERIFIED`. PHPUnit is also the thing to know before pointing this at a real
project: a project that declares no PHPUnit and has none on the machine is reported as having no test
runner rather than as passing, because PHP has no standard-library test framework to fall back on.

## 17. The same thing for Ruby

The Ruby fixture is the same ledger once more, this time as a Bundler project with RSpec. It needs an
interpreter and Bundler and nothing else installed, and it shares PHP's structural peculiarity: RSpec is
a project dependency rather than a tool of the machine, so the build phase has to run `bundle install`
before a test can run at all. That is why the fixture's sandbox configuration allows network access and
why the CI job warms the bundle first; the run is then measuring the adapter, not a download.

```bash
./codewitness verify fixtures/ruby-withdrawal-ledger \
    --requirements fixtures/ruby-withdrawal-ledger/requirements.md
```

```text
Requirements
  ✗ R-001    Rolling withdrawal limit     FAILED
  ✓ R-002    Fees are applied to the rec… VERIFIED
  ✗ R-003    Negative amounts are reject… FAILED
  ? R-004    Rounding of computed totals  UNVERIFIED

Counterexamples
  spec.generated.cw_sc_SC_ROLLING_spec   R-001  inputs: {t1=85800000, a1=70000, t2=90000000, a2=70000}
                                               expected: observation <= 100000   actual: 140000
  spec.generated.cw_sc_SC_NEGATIVE_spec  R-003  inputs: {amount=-10000}
                                               expected: observation >= 50000    actual: 40000

Tests
  2 executed, 2 passed, 0 failed, 0 skipped  (parsed from the RSpec JSON report)

Coverage
  coverage not measured (SimpleCov wrote no coverage/.resultset.json)

Mutation testing
  8 mutants, 1 killed, 7 survived, score 12.5%
```

The scenario fragments are Ruby. `${name}` becomes a bare Ruby local variable, `setup` is Ruby
statements run once per case, `observation` is a Ruby expression assigned to the local `observation`,
and `invariant` is the Ruby boolean expression that must hold — in the fixture, `observation <= 100000`.
The generated tests are RSpec spec files written into the project's own test root as
`<test root>/generated/cw_sc_<ScenarioId>_spec.rb`, because RSpec discovers a spec by its file name and
the `require 'spec_helper'` each generated file opens with is what loads the project's own
configuration; there is nowhere else RSpec would find one. Each case is one `it` example —
`cw_case0000` for the declared values, `cw_single_case` for a scenario with no inputs,
`cw_random_search` when the generator searched under a seed — and the replay command is a real
`bundle exec rspec <generated spec>` invocation with the recorded seed and case in the environment,
because RSpec has no per-case seed flag to carry them.

Existing tests are read from the JSON report RSpec's own formatter writes. A spec is reported by the path
it was invoked under, so the parser records the suite as that path with the separators turned into dots
and the extension dropped — `spec/ledger_spec.rb` becomes `spec.ledger_spec` — which is exactly the
identifier the requirement mapper derives from the file for the same reason. Coverage comes from
SimpleCov's `coverage/.resultset.json`, which only exists when the project loads SimpleCov, and is
reported as not measured with the reason when it does not: the fixture deliberately does not load it, so
the phase has to say so rather than report zero. SimpleCov's default report carries no branch data, so
branch coverage is reported as unavailable rather than as an empty figure. Mutation runs through the same
shared `TextMutator` as the other source-language adapters with a `RubyLexis` that knows what a text
mutator gets wrong in Ruby: `#` and `=begin`/`=end` comments, single- and double-quoted strings,
`%w`/`%q`/`%r` literals, heredocs, `?a` character literals, and a `/` that divides rather than opening a
regular expression. The survivor is `== -> !=` in the calendar-day comparison, which no test in the
fixture distinguishes from its negation.

The fixture's two defects are the rolling-window bug behind R-001 and the accepted negative amount
behind R-003, its fee scenario discharges R-002 through an executed generated test, and R-004 declares no
scenario, so it stays `UNVERIFIED`. RSpec is also the thing to know before pointing this at a real
project: a project that declares no RSpec and has none on the machine is reported as having no test
runner rather than as passing. Ruby does ship minitest as a default gem, but the adapter deliberately
does not fall back to it — a suite is written against one runner, and running a second, weaker one
against files it was not written for produces results that describe nothing.

## 18. The same thing for Swift

The Swift fixture is the same ledger once more, this time as a Swift package with XCTest. It needs only
a Swift toolchain: XCTest is part of the distribution and the package declares no dependencies, so the
sandbox forbids the network and the run compiles, tests, measures coverage and mutates without fetching
anything.

```bash
./codewitness verify fixtures/swift-withdrawal-ledger \
    --requirements fixtures/swift-withdrawal-ledger/requirements.md
```

```text
Requirements
  ✗ R-001    Rolling withdrawal limit     FAILED
  ✓ R-002    Fees are applied to the rec… VERIFIED
  ✗ R-003    Negative amounts are reject… FAILED
  ? R-004    Rounding of computed totals  UNVERIFIED

Counterexamples
  WithdrawalLedgerTests.CwScSC_ROLLINGTests   R-001  inputs: {t1=85800000, a1=70000, t2=90000000, a2=70000}
                                                     expected: observation <= 100000   actual: 140000
  WithdrawalLedgerTests.CwScSC_NEGATIVETests  R-003  inputs: {amount=-10000}
                                                     expected: observation >= 50000    actual: 40000

Tests
  2 executed, 2 passed, 0 failed, 0 skipped  (parsed from the swift test console output)

Coverage
  line 98.1% (52/53), branch 0.0% (0/0)  (llvm-cov, SwiftPM export)

Mutation testing
  11 mutants, 3 killed, 8 survived, score 27.3%
```

The scenario fragments are Swift. `${name}` becomes a local `name`, `setup` is Swift statements run once
per case, `observation` is a Swift expression assigned to `let observation`, and `invariant` is the
Swift boolean expression that must hold — in the fixture, `observation <= 100000`. The generated tests
are ordinary XCTest classes written into the project's own test target as
`<test root>/Generated/CwSc<ScenarioId>Tests.swift`, because SwiftPM compiles only the Swift files that
live inside a declared target's directory. Each case is one `test`-prefixed method — `testCwCase0000`
for the boundary and declared values, `testCwSingleCase` for a scenario with no inputs, `testCwSearch`
when the generator searched under a seed — and the replay command is a real
`swift test --filter <Module.Class>/<method>` invocation with the recorded seed and case in the
environment, because `swift test` has no seed option that pins a search case.

Two Swift details are worth knowing before writing scenarios. XCTest discovers a method only when its
name begins with `test`, so a generated class whose methods were named `cw_…` would compile and then run
zero cases; the generated names carry the prefix for that reason. And a test target cannot import
itself, so the generated class imports the package's library target while the result is reported under
the test target's module; the detector records the two names separately.

Existing tests are read from XCTest's console output, because XCTest gives a command-line caller no
structured report: each case is opened and closed by a fixed `Test Case '-[Module.Class method]'` line,
and a failure's message is the compiler-style error line printed before the close, which is where the
generated test's `CODEWITNESS_COUNTEREXAMPLE|…` line lands. Coverage comes from SwiftPM's own LLVM
export — `swift test --enable-code-coverage` then `swift test --show-codecov-path` — and is summed over
the project's own Swift files only; this toolchain instruments no branch regions for the package, so
branch coverage is reported as not measured rather than as zero. Mutation runs through the shared
`TextMutator` with a `SwiftLexis` that knows Swift's comments, string forms and identity operators; the
survivors include `== -> !=` in the calendar-day comparison, which no test in the fixture distinguishes
from its negation.

The fixture's two defects are the rolling-window bug behind R-001 and the accepted negative amount
behind R-003, its fee scenario discharges R-002 through an executed generated test, and R-004 declares no
scenario, so it stays `UNVERIFIED`. The limit to know before pointing this at a real project is that the
adapter drives Swift Package Manager and nothing else: a tree with `.swift` files but no `Package.swift`
is recognised and reported as present and not verified, and a suite written with Swift Testing rather
than XCTest is reported as having no readable case rather than quietly passing.

## 19. The same thing for Verilog

The Verilog fixture is the same ledger once more, this time as a combinational module with a testbench.
It needs only Icarus Verilog: the design has no manifest and no dependencies, so the sandbox forbids the
network and the run compiles, simulates and mutates without fetching anything.

```bash
./codewitness verify fixtures/verilog-withdrawal-ledger \
    --requirements fixtures/verilog-withdrawal-ledger/requirements.md
```

```text
Requirements
  ✗ R-001  An accepted withdrawal reduces the balance by exactly the amount  FAILED
  ✓ R-002  An over-limit withdrawal is rejected                              VERIFIED
  ✗ R-003  A withdrawal of exactly the balance is accepted                   FAILED
  ? R-004  The balance never goes negative                                   UNVERIFIED

Counterexamples
  cw_sc_SC_DEBIT_OB_001  R-001  inputs: {balance=100000, amount=1}
                                expected: accepted == 1 && new_balance == balance - amount
                                actual: 100000
  cw_sc_SC_EXACT_OB_001  R-003  inputs: {balance=100000, amount=100000}
                                expected: accepted == 1 && new_balance == 0
                                actual: 100000

Tests
  1 executed, 1 passed, 0 failed, 0 skipped  (ledger_tb, the project's own testbench)

Coverage
  coverage not measured (Icarus Verilog exports no coverage data)

Mutation testing
  3 mutants, 0 killed, 3 survived, score 0.0%
```

The run exits 1, because a requirement failed. R-002 is discharged by an executed generated testbench,
which is the only path to `VERIFIED`; R-004 declares no scenario, so it stays `UNVERIFIED` for the
honest reason rather than counting a passing build as evidence. Dependencies are not analysed: Verilog
has no package manager and there is no advisory ecosystem for HDL, so the phase has no inventory to read
and consults no source.

The scenario fragments are Verilog. `${name}` becomes the bare signal `name`, `setup` is Verilog
statements run once per case, and each step's `call` is a Verilog statement — the fixture's ledger is
combinational, so a step's `call` is the empty statement `;` and the declared inputs are the whole
invocation. The generator instantiates the design under test as `dut`, drives the inputs at the design's
own port widths, assigns `observation` to a 64-bit register after a `#1` delay so the combinational
logic has settled, and checks `invariant` as a plain Verilog boolean expression — in the fixture,
`dut.accepted == 1 && observation == balance - amount`. Boundary values come from `values` and
`range.min`/`range.max`, and `sequence` and `concurrent` modes add a seeded `$urandom_range` search when
the scenario declares a non-negative range. A failing case prints the same
`CODEWITNESS_COUNTEREXAMPLE|…` line every other adapter uses, and the testbench calls `$fatal` once at
the end, so the simulation exits non-zero and the engine records a failure.

Existing tests are testbenches. Verilog has no case-level test protocol, so each testbench is reported as
a single test case under the file's own name, which is why the fixture's own `ledger_tb` is one executed
test — it passes because it checks only the rejected branch, which is the point of the fixture. A
Verilog test is two processes, `iverilog` to compile and `vvp` to simulate, so there is no single test
command and a counterexample's reproduction is the compile-then-simulate pair. The limit to know before
pointing this at a real project is that the adapter drives Icarus and nothing else: `.v` and `.sv` files
are the same language to `iverilog -g2012`, a tree with sources but no testbench is still built and
analysed with its requirements left `UNVERIFIED`, and a design that does not compile is reported as
`BLOCKED` rather than as incorrect code.

## 20. The same thing for VHDL

The VHDL fixture is the same ledger once more, this time as a combinational entity with a testbench. It
needs only GHDL: `ghdl` analyses, elaborates and simulates as one executable, the design has no manifest
and no dependencies, and the sandbox forbids the network, so the run analyses, elaborates, simulates and
mutates without fetching anything.

```bash
./codewitness verify fixtures/vhdl-withdrawal-ledger \
    --requirements fixtures/vhdl-withdrawal-ledger/requirements.md
```

```text
Requirements
  ✗ R-001  An accepted withdrawal reduces the balance by exactly the amount  FAILED
  ✓ R-002  An over-limit withdrawal is rejected                              VERIFIED
  ✗ R-003  A withdrawal of exactly the balance is accepted                   FAILED
  ? R-004  The balance never goes negative                                   UNVERIFIED

Counterexamples
  cw_sc_sc_debit_ob_001  R-001  inputs: {balance=100000, amount=1}
                                expected: accepted = '1' and observation = to_integer(balance) - to_integer(amount)
                                actual: 100000
  cw_sc_sc_exact_ob_003  R-003  inputs: {balance=100000, amount=100000}
                                expected: accepted = '1' and observation = 0
                                actual: 100000

Tests
  1 executed, 1 passed, 0 failed, 0 skipped  (ledger_tb, the project's own testbench)

Coverage
  coverage not measured (no coverage provider was registered for any language)

Mutation testing
  3 mutants, 1 killed, 2 survived, 0 no coverage, score 33.3%
```

The run exits 1, because a requirement failed. R-002 is discharged by an executed generated testbench,
which is the only path to `VERIFIED`; R-004 declares no scenario, so it stays `UNVERIFIED` for the honest
reason rather than counting a passing build as evidence. Coverage is not measured either: GHDL's coverage
export is not a form this adapter reads, so the adapter claims no coverage capability rather than
reporting a zero it cannot support. Dependencies are not analysed: VHDL has no package manager and there
is no advisory ecosystem for HDL, so the phase has no inventory to read and consults no source.

The scenario fragments are VHDL. `${name}` becomes the bare signal `name`, `setup` is VHDL statements run
once per case, and each step's `call` is a VHDL statement — the fixture's ledger is combinational, so the
call is the null statement `null;` and the declared inputs are the whole invocation. The generator
declares a signal named after each of the design's ports and connects it to an instance of the design,
because a component instance's ports cannot be selected by name: a fragment writes `balance`, `accepted`
and `new_balance`, never `dut.balance`, and the language is strongly typed, so each integer boundary
value is converted into the port's own subtype with `to_unsigned`, `to_signed` or a std_logic
conditional. It assigns `observation` to an `integer` variable after a `1 ns` wait so the combinational
logic has settled, and checks `invariant` as a plain VHDL boolean expression — in the fixture,
`accepted = '1' and observation = to_integer(balance) - to_integer(amount)`. Boundary values come from
`values` and `range.min`/`range.max`, and `sequence` and `concurrent` modes add a seeded search when the
scenario declares a non-negative range. A failing case prints the same `CODEWITNESS_COUNTEREXAMPLE|…`
line every other adapter uses and sets a flag; a single assertion at failure severity runs at the end, so
every case runs and the simulation still exits non-zero, and the engine records a failure.

Existing tests are testbenches. VHDL has no case-level test protocol, so each testbench is reported as a
single test case under the file's own name, which is why the fixture's own `ledger_tb` is one executed
test — it passes because it checks only the rejected branch, which is the point of the fixture. A VHDL
test is several processes in one executable, `ghdl -a` to analyse and `ghdl -r` to simulate, with
`--assert-level=error` so a failing assertion actually exits non-zero rather than being printed and
ignored. There is no single test command, so a counterexample's reproduction is the analyse-then-simulate
recipe. The console above reports three mutants, one killed and two survivors: the fixture's own testbench
detects the comparison swap because it checks the rejected branch, and the two arithmetic mutants survive
because it never exercises the accepted branch where they live. The limit to know before
pointing this at a real project is that the adapter drives GHDL and nothing else: `.vhd` and `.vhdl` are
the same language to `ghdl --std=08`, a tree with sources but no testbench is still analysed and elaborated
with its requirements left `UNVERIFIED`, and a design that does not analyse is reported as `BLOCKED`
rather than as incorrect code.

## 21. The same thing for Pascal

The Pascal fixture is the same ledger once more, this time as an fpcunit project whose defective unit
returns one cent too few from every accepted withdrawal. It needs only Free Pascal: `fpcunit`,
`testregistry` and `consoletestrunner` are part of the compiler's distribution, so the sandbox forbids
the network and the run builds, tests and mutates without fetching anything.

```bash
./codewitness verify fixtures/pascal-withdrawal-ledger \
    --requirements fixtures/pascal-withdrawal-ledger/requirements.md
```

```text
Requirements
  ✗ R-001  An accepted withdrawal reduces the balance by exactly the amount  FAILED
  ✓ R-002  A withdrawal larger than the balance is rejected                  VERIFIED
  ✗ R-003  A withdrawal of exactly the balance is accepted                   FAILED
  ? R-004  The balance never goes negative                                   UNVERIFIED

Counterexamples
  R-001  inputs: {balance=100000, amount=1}       expected: not ledger.Accepted(balance, amount) or (observation = balance - amount)
  R-003  inputs: {balance=100000, amount=100000}  expected: ledger.Accepted(balance, amount) and (observation = 0)

Tests
  4 executed, 2 passed, 2 failed, 0 skipped  (the project's own fpcunit console runner)

Coverage
  coverage not measured (base Free Pascal exports no coverage data)

Mutation testing
  3 mutants, 3 killed, 0 survived, score 100.0%
```

The run exits 1, because two requirements failed. R-001 and R-003 fail through generated Pascal programs
— `cw_sc_sc_debit_ob_001` and `cw_sc_sc_exact_ob_003` — each a `program` that `uses` the project's own
unit and is compiled and run with `fpc`; R-002 is discharged by an executed generated program, which is
the only path to `VERIFIED`; R-004 declares no scenario, so it stays `UNVERIFIED` for the honest reason
rather than counting a passing build as evidence. Dependencies are not analysed: base FPC has no package
manager, so the phase has no inventory to read and consults no source.

The scenario fragments are Pascal. `${name}` becomes the declared input `name`, `setup` and each step's
`call` are Pascal statements run once per case, `observation` is a Pascal expression assigned to a
variable whose type the generator derives from the declared inputs (default `Integer`), and `invariant`
is the boolean Pascal expression that must hold — in the fixture,
`ledger.Accepted(balance, amount) or (observation = balance)`. A generated test is a self-contained
`program` rather than a class in a framework, named `cw_sc_<scenario>_<obligation>` in lower case
(`cw_sc_sc_reject_ob_002` for `SC-REJECT` and `OB-002`), written under `generated-tests/` in the run
workspace, and compiled by `fpc` against the project's own units. It declares only the declared inputs
and `observation`, so a scenario that needs an intermediate value has to assign it to `observation` or
call a project function directly; a failing case prints the same `CODEWITNESS_COUNTEREXAMPLE|…` line
every other adapter uses and the program calls `Halt(1)`, so the process exits non-zero and the engine
records a failure.

The limit to know before pointing this at a real project is that the adapter drives Free Pascal and
nothing else: a tree with `.pas` and `.pp` sources is detected, built and tested through `fpc` and its own
fpcunit console runner, and coverage and dependency analysis are declared not measured because base FPC
exports no coverage data and has no package manager — a stated limit rather than a guess at either.
