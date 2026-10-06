# Contributing to CodeWitness

Thanks for helping. This document covers how to build, test and extend the project, and the rules a
change has to respect.

## Prerequisites

- Java 25 or newer (the build targets release 25)
- Maven 3.9+ (or use `./mvnw`, which pins 3.9.16), on `PATH`, or `MAVEN_HOME`/`M2_HOME` set
- Python 3.9+ on `PATH` for the Python adapter and its end-to-end fixture (only the standard library
  is needed; pytest and coverage.py are used when present)
- Network access for the first build (dependencies) and for the end-to-end tests

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

1. Copy the structure of `fixtures/withdrawal-ledger` (Java, Maven, JUnit 5) or
   `fixtures/python-withdrawal-ledger` (Python, `pyproject.toml`, `unittest`/pytest). Keep at least
   one Java fixture on an older release (currently `correct-implementation` on Java 17) so the
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
   wires everything (`JavaAdapterBundle` and `PythonAdapterBundle` are the worked examples). If your
   tests are invoked in a way the core cannot guess, override the optional replay-command methods so a
   counterexample carries a command that actually runs.
4. Add `src/main/resources/META-INF/services/dev.codewitness.core.spi.LanguageAdapter` containing the
   adapter class name.
5. Do not add anything language specific to `codewitness-core`. If you believe the core must change,
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
      `codewitness verify fixtures/python-withdrawal-ledger` still reports R-001 and R-003 as `FAILED`
      and R-002 as `VERIFIED`.
- [ ] No `.codewitness` run data, build output or generated test sources are committed.
- [ ] The README's "honest status" section still describes what the tool actually does.
- [ ] Any new limitation is stated in the README and, where it affects a run, in the report's
      `limitations` list.
