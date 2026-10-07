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
C/C++ and PHP fixtures at the end need Python 3, Cargo, the `go` tool, a Node runtime (22.6+ for
TypeScript, 18.17+ for JavaScript), the .NET SDK (8+), Maven with network access the first time, CMake
with CTest and an interpreter with Composer respectively, and nothing else: the Kotlin section is a
Maven build, so `kotlinc` and the standard library arrive as dependencies, the C and C++ section fetches
no package at all, and the PHP section's PHPUnit arrives from Packagist as a Composer dependency rather
than as part of the toolchain.

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
CodeWitness 0.1.0 verifying withdrawal-ledger

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
