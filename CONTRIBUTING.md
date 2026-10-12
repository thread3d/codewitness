# Contributing to CodeWitness

Thanks for helping. This document covers how to build, test and extend the project, and the rules a
change has to respect.

## Prerequisites

- Java 25 or newer (the build targets release 25)
- Maven 3.9+ (or use `./mvnw`, which pins 3.9.16), on `PATH`, or `MAVEN_HOME`/`M2_HOME` set
- Python 3.9+ on `PATH` for the Python adapter and its end-to-end fixture (only the standard library
  is needed; pytest and coverage.py are used when present)
- Cargo on `PATH` for the Rust adapter and its end-to-end fixture (no third-party crate is needed;
  `cargo-llvm-cov` is used for coverage when present)
- Go 1.21 or newer on `PATH` for the Go adapter and its end-to-end fixture (no third-party module is
  needed; coverage comes from `go test -coverprofile`, so nothing extra is installed)
- Node 22.6 or newer on `PATH` for the TypeScript adapter and its end-to-end fixture (no package is
  needed; a project-local `tsc` is used for type checking when present)
- Node 18.17 or newer on `PATH` for the JavaScript adapter and its end-to-end fixture (no package is
  needed, and there is no compiler or type check; 18.17 is the first release whose JUnit reporter the
  test phase reads)
- The .NET SDK 8 or newer on `PATH` for the C# adapter and its end-to-end fixture (the fixture targets
  `net8.0` and needs only the test host packages the project already references, which must already be
  in the machine's NuGet cache — they are on any machine that has run `dotnet test` before — because
  the fixture's sandbox config sets `allow_network: false`)
- Maven and network access for the Kotlin adapter and its end-to-end fixture: the fixture is a Maven
  build with `kotlin-maven-plugin`, so `kotlinc`, the standard library and Surefire are fetched from a
  repository the way a dependency is. No Kotlin or Gradle installation is required
- CMake and CTest on `PATH` for the C and C++ adapter and its end-to-end fixture (no third-party
  package is needed; coverage comes from `gcov` over a second instrumented configure, so the adapter
  installs nothing of its own)
- PHP 8.1 or newer with Composer on `PATH` for the PHP adapter and its end-to-end fixture (the
  interpreter's own parser is the build, and PHPUnit is a Composer dependency the fixture declares, so
  the first run resolves it from Packagist; a project that declares none is reported as having no test
  runner rather than quietly passing)
- Ruby with Bundler on `PATH` for the Ruby adapter and its end-to-end fixture (the interpreter's own
  parser is the build, and RSpec is a bundle dependency the fixture declares, so the first run resolves
  it from RubyGems; a project that declares none is reported as having no test runner rather than
  quietly passing)
- A Swift toolchain on `PATH` for the Swift adapter and its end-to-end fixture (the fixture is a Swift
  package with no external dependencies, and XCTest ships with the toolchain, so nothing has to be
  resolved and the fixture's sandbox forbids the network)
- Icarus Verilog 12.0 or newer on `PATH` for the Verilog and SystemVerilog adapter and its end-to-end
  fixture (`iverilog` compiles and `vvp` simulates; the fixture is a combinational design with no
  manifest and no dependencies, so nothing has to be resolved and the fixture's sandbox forbids the
  network)
- Network access for the first build (dependencies) and for the end-to-end tests, except the C#
  fixture, the C/C++ fixture, the Swift fixture and the Verilog fixture, which need none, and the PHP
  and Ruby fixtures, which need it until their `vendor/` directory or bundle exists, because PHPUnit and
  RSpec are package-manager dependencies rather than part of the toolchain

## Build and test

```bash
./build.sh                          # package everything; produces codewitness-cli/target/codewitness.jar
./codewitness version               # runs the built jar

mvn test                            # unit and integration tests for every module
mvn -pl codewitness-core test       # one module
mvn -Pe2e test                      # additionally runs the end-to-end fixture tests (slower, needs Maven on PATH)
mvn -Pe2e -pl codewitness-cli test  # just the end-to-end tests
```

The default `mvn test` deliberately excludes the `e2e` group so that a normal build stays fast and
does not depend on the network. Anything that builds or runs a real project belongs in the `e2e`
group.

Before opening a pull request: `mvn -Pe2e test` must pass, and `./demo.sh` must still show the
expected findings.

## Ground rules for changes

These are not style preferences; they are the product's contract. A change that breaks one of them
will be rejected regardless of how useful it otherwise is.

1. **Only `VerdictCalculator` may assign a verdict from evidence**, and it may only do so from
   evidence with the right confidence. Nothing else in the system may decide that a requirement is
   verified.
2. **A failed build is `BLOCKED`, never `FAILED`** (and never `VERIFIED`). Infrastructure problems and
   code defects must remain distinguishable in every output.
3. **Insufficient evidence is `UNVERIFIED`**, and the reason must appear in the report. Do not
   introduce a default that quietly turns "we could not check" into "it passed".
4. **Language model output is advisory.** It is stored with `generatedByLlm = true`, confidence
   `ADVISORY`, and `executedIndependently = false` until it has actually been executed. It may never
   create or discharge an obligation by itself.
5. **Deterministic tools produce the evidence.** A tool that cannot run produces an explanatory note,
   not an optimistic default.
6. **Never execute project code outside the sandbox.** All process execution goes through
   `ExecutionSandbox`; nothing else may call `ProcessBuilder` or `Runtime.exec`.
7. **Every conclusion must be traceable to evidence ids**, and every evidence record must carry the
   command and inputs that produced it.
8. **The natural language requirement stays authoritative.** Anything derived from it is labelled an
   interpretation.

## Adding a fixture project

Fixtures are how we prove the tool actually distinguishes good code from bad. A fixture is a small,
self-contained project under `fixtures/<name>/` containing a known defect (or a known-correct
implementation), plus `requirements.md` and `codewitness.yaml`.

To add one:

1. Copy the structure of `fixtures/withdrawal-ledger` (Java, Maven, JUnit 5),
   `fixtures/python-withdrawal-ledger` (Python, `pyproject.toml`, `unittest`/pytest),
   `fixtures/rust-withdrawal-ledger` (Rust, `Cargo.toml`, libtest),
   `fixtures/go-withdrawal-ledger` (Go, `go.mod`, `go test`),
   `fixtures/typescript-withdrawal-ledger` (TypeScript, `tsconfig.json`, `node:test`),
   `fixtures/javascript-withdrawal-ledger` (JavaScript, `package.json`, `node:test`),
   `fixtures/csharp-withdrawal-ledger` (C#, `WithdrawalLedger.sln`, `dotnet test`),
   `fixtures/kotlin-withdrawal-ledger` (Kotlin, `pom.xml` with `kotlin-maven-plugin`, `kotlin.test`/
   JUnit 5), `fixtures/cpp-withdrawal-ledger` (C++, `CMakeLists.txt`, `ctest`),
   `fixtures/php-withdrawal-ledger` (PHP, `composer.json`, `phpunit.xml`, PHPUnit),
   `fixtures/ruby-withdrawal-ledger` (Ruby, `Gemfile`, RSpec),
   `fixtures/swift-withdrawal-ledger` (Swift, `Package.swift`, XCTest) or
   `fixtures/verilog-withdrawal-ledger` (Verilog, no manifest, Icarus Verilog `iverilog`/`vvp`). Keep
   at least one Java fixture on an older release (currently `correct-implementation` on Java 17) so the
   adapter's "Java 17+" claim stays exercised against real bytecode.
2. Introduce **one** defect, and state it in a comment at the top of the class — the comment is part
   of the fixture's contract.
3. Write requirements that describe the intended behaviour, not the defect.
4. Declare scenarios that can expose the defect, and leave at least one requirement deliberately
   without a scenario when you want to exercise the `UNVERIFIED` path.
5. Document it in `docs/fixtures.md`: the defect, the requirement, the expected verdict and what the
   fixture proves.
6. Add an assertion for it to the end-to-end test suite (`codewitness-cli` `e2e` group) if the
   behaviour is stable enough to assert.
7. Remove the `.codewitness` directory before committing — run data never belongs in the repository.

## Adding a language adapter

1. Create a module `codewitness-adapter-<language>` depending on `codewitness-core` only.
2. Implement `dev.codewitness.core.spi.LanguageAdapter`: `id()`, `supports()`, `inspect()`,
   `build()`, `runTests()`, `analyze()` and `capabilities()`. Detection belongs in its own
   inspectable class, like `JavaProjectDetector`.
3. Implement the tool providers you need from `VerificationTools` and register them where the CLI
   wires everything (`JavaAdapterBundle`, `PythonAdapterBundle`, `RustAdapterBundle`,
   `GoAdapterBundle`, `TypeScriptAdapterBundle`, `JavaScriptAdapterBundle`, `CSharpAdapterBundle`,
   `KotlinAdapterBundle`, `CppAdapterBundle`, `PhpAdapterBundle`, `RubyAdapterBundle`,
   `SwiftAdapterBundle` and
   `VerilogAdapterBundle` are the worked
   examples). Two languages can share a
   build tool — Kotlin and Java both use Gradle and Maven — so a provider has to select on the
   project's *language* as well as on its build system, or both adapters answer for one tree. The
   reverse case is the C and C++ adapter's: one build system, CMake, covers two languages, so one
   adapter claims both and one `LanguageId` (`cpp`) carries them. PHP is the case where the test runner
   is not a tool of the machine at all: PHPUnit is a Composer dependency, so a `TestRunner` must resolve
   it after the build phase has installed it and say so when there is none, rather than reporting a
   suite that could not have existed at inspection time. Ruby is the same case with a different package
   manager: RSpec arrives from RubyGems through `bundle install`, so a `TestRunner` must resolve it after
   the build too and report "no test runner" rather than a suite of zero when there is none. Swift is
   the opposite case: XCTest ships with the toolchain, so its `TestRunner` resolves nothing beyond the
   `swift` driver the build already drove. Verilog is the case where a test is two processes rather than
   one: `iverilog` compiles and `vvp` simulates, so the adapter reports one case per testbench instead
   of a case-level protocol and its replay recipe is the compile-then-simulate pair. If your
   tests are invoked in a way the core cannot guess, override the optional replay-command methods so a
   counterexample carries a command that actually runs.
4. Answer the advisory hook on the `DependencyAnalyzer` your adapter registers. Declare
   `ecosystems()` — the advisory ecosystems your coordinates are filed under, spelled as the advisory
   service spells them — and, for each, `dependencyPathHint(coordinate)` and, where a reliable command
   exists, `dependencyPathCommand(context, coordinate)`. This is how an advisory against your language
   arrives with a reproduction recipe: the engine knows no ecosystem and no tool, and asks the analyzer
   that claims the advisory's ecosystem. `dev.codewitness.support.dependency.DependencyPathHints` holds
   the shipped hints and the one executable Maven recipe; reuse one or phrase your own. If your provider
   is a runner-aware wrapper that delegates the inventory to another class, forward these three methods
   explicitly — the registry holds the wrapper, so an inherited default would answer "no ecosystem" and
   the recipe would silently lose its hint.
5. Add `src/main/resources/META-INF/services/dev.codewitness.core.spi.LanguageAdapter` containing the
   adapter class name. Every shipped adapter carries one, Verilog's included: it is the entry the
   service loader reads and the file the shade plugin merges into the CLI jar, so without it the
   adapter's classes are on the classpath and still never run.
6. Do not add anything language specific to `codewitness-core`. If you believe the core must change,
   say so in the pull request instead of changing it — an engine that grows a language branch is an
   engine that can no longer claim to be language independent. Declaring the file extensions your
   language uses as source (`ProjectInfo.sourceExtensions`) is the intended mechanism for the few
   places the core must find files.

## Adding a verification tool

Implement one of `VerificationTools.Compiler`, `TestRunner`, `StaticAnalyzer`, `CoverageProvider`,
`MutationTester`, `SecurityAnalyzer`, `DependencyAnalyzer`, `TestGenerator` or
`GeneratedTestExecutor`, and register it. Two rules:

- a tool that cannot run returns a result object explaining why — it never throws for a tool failure
  and never reports success it did not observe;
- raw tool output is parsed from the tool's own report files where the tool has them, not from
  console output, so that the recorded observation is the tool's, not our reading of its log.

## Adding a verification strategy

Implement `VerificationStrategy`. You get the `VerificationContext`, the requirement, and the
obligations to attempt. Return `StrategyOutcome` with evidence, counterexamples and per-obligation
outcomes. You cannot assign a verdict; the engine does that from your evidence. Prefer
`UNVERIFIED` over optimism.

## Code style

- Java 25, records for value types, immutable models with builders where a type has many optional
  parts.
- Javadoc on every public type and on every non-obvious method, explaining **why** where the reason
  matters. Comments that restate the code are noise; comments that explain a decision are the
  documentation.
- No new runtime dependencies without discussing it in the pull request first. The core in particular
  is close to dependency free on purpose: a verification tool that pulls in a large dependency tree
  is harder to trust and to audit.
- No `System.out` inside libraries; progress and output belong to the CLI. Use the `ProgressListener`
  from the context.
- Prefer explicit types at API boundaries. `var` is fine for locals where the type is obvious.

## Test expectations

- Every new behaviour needs a test, and every fixed bug needs a regression test that would have
  caught it (the DOCTYPE parsing bug and the workspace-rebasing bug both have one).
- Tests must be deterministic: no network, no dependency on the developer's home directory, no long
  sleeps. Use `@TempDir` for anything on disk.
- If production code is wrong and you cannot fix it in this change, commit the failing test with
  `@Disabled` and a reason, and report it. A disabled test with an explanation is information; a
  deleted test is not.

## Commit and review checklist

- [ ] `mvn -Pe2e test` passes.
- [ ] `./demo.sh` still finds the four defects in `fixtures/withdrawal-ledger`, and
      `./codewitness verify fixtures/python-withdrawal-ledger` still reports R-001 and R-003 as `FAILED`
      and R-002 as `VERIFIED`, and `./codewitness verify fixtures/rust-withdrawal-ledger`,
      `fixtures/go-withdrawal-ledger`, `fixtures/typescript-withdrawal-ledger`,
      `fixtures/javascript-withdrawal-ledger`, `fixtures/csharp-withdrawal-ledger`,
      `fixtures/kotlin-withdrawal-ledger`, `fixtures/cpp-withdrawal-ledger`,
      `fixtures/php-withdrawal-ledger`, `fixtures/ruby-withdrawal-ledger`,
      `fixtures/swift-withdrawal-ledger` and `fixtures/verilog-withdrawal-ledger` still report R-001 and
      R-003 as `FAILED`, R-002 as `VERIFIED` and R-004 as `UNVERIFIED`.
- [ ] No `.codewitness` run data, build output or generated test sources are committed.
- [ ] The README's "honest status" section still describes what the tool actually does.
- [ ] Any new limitation is stated in the README and, where it affects a run, in the report's
      `limitations` list.
