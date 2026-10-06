# CodeWitness architecture

This document explains how the system is put together, and — more importantly — why the verification
rules are what they are. The rules exist to stop a verification tool from becoming an oracle.

## 1. The central abstraction: an evidence chain

Everything in CodeWitness exists to make this chain machine-checkable:

```
CLAIM                    a requirement, in natural language, written by a human
   ↓
OBLIGATION               a concrete experiment that would demonstrate the claim
   ↓
EXPERIMENT               an executed tool run: a build, a test, a fuzzer, an analyzer
   ↓
OBSERVATION              what the tool actually printed or returned
   ↓
EVIDENCE                 the observation, stored with its command, inputs and confidence
   ↓
CONCLUSION               VERIFIED / PARTIALLY_VERIFIED / FAILED / UNVERIFIED / BLOCKED
```

Each arrow is a place where a verification tool usually starts lying. CodeWitness closes them:

- An obligation that no experiment can discharge stays visible as `unknownAspects` rather than being
  quietly dropped.
- An experiment that could not run produces an `INCONCLUSIVE` record with the failure reason, not a
  pass.
- An observation is stored with the exact command that produced it.
- Evidence carries a **confidence**, and only deterministic evidence can move a verdict.
- The conclusion is computed by one small class, `VerdictCalculator`, from the evidence — never by the
  thing that produced the evidence.

## 2. Layers

```
┌──────────────────────────────────────────────────────────────┐
│ CLI (picocli)         verify · inspect · test · report · …    │
├──────────────────────────────────────────────────────────────┤
│ Verification engine   pipeline, budget, phase records         │
├──────────────────────────────────────────────────────────────┤
│ Requirement / evidence model   requirements, obligations,     │
│                                evidence ledger, verdicts      │
├──────────────────────────────────────────────────────────────┤
│ Verification strategies   existing tests · static analysis ·  │
│                           generated tests · mutation · LLM    │
├──────────────────────────────────────────────────────────────┤
│ Language adapter API   LanguageAdapter · VerificationTools    │
├────────────────┬───────────────────────┬─────────────────────┤
│ Java adapter   │ (Python, TypeScript,  │                     │
│                │  Rust … in future)    │                     │
├────────────────┴───────────────────────┴─────────────────────┤
│ External tools: javac · Maven · Gradle · JUnit · PIT · JaCoCo │
└──────────────────────────────────────────────────────────────┘
```

Enforced by module boundaries, not by convention:

| Module | May depend on | Contains |
| --- | --- | --- |
| `codewitness-core` | SnakeYAML | the whole model, the engine, the SPIs |
| `codewitness-adapter-java` | core | Java, Maven, Gradle, JUnit, PIT, JaCoCo |
| `codewitness-storage` | core | SQLite persistence |
| `codewitness-report` | core, Jackson | JSON and HTML rendering |
| `codewitness-cli` | all of the above | argument parsing and wiring |

The core has no reference to Java, Maven or JUnit. That is checked by looking at the imports: the
words do not appear outside `codewitness-adapter-java`.

## 3. The pipeline

`VerificationEngine.verify(...)` runs these phases, each bounded and each recorded as a
`PhaseRecord`:

1. **inspect** — language, build system, modules, source and test directories, test frameworks,
   build wrapper, Java version (`JavaProjectDetector`).
2. **workspace** — the project is copied into the run directory, excluding build output, VCS metadata
   and previous run data, with executable bits restored on `mvnw`/`gradlew`.
3. **build** — `test-compile`/`testClasses` through the project's own build tool. Diagnostics are
   parsed from the tool's output. A failure ends verification here, as `BLOCKED`.
4. **tests** — stale reports are deleted, the suite is run, and results are parsed from the raw XML
   reports, not from console output.
5. **static analysis** — compiler warnings (`-Xlint:all`) plus the heuristic rule table.
6. **security** — the same rule table, filtered to security rules, reported separately.
7. **dependencies** — inventory only (see the limitations in the README).
8. **coverage** — JaCoCo report parsing, with the agent attached automatically when needed.
9. **mutation** — PIT, with stale reports deleted first.
10. **obligation planning** — scenarios declared by the user, properties derived from requirement
    text, or an explicit "nothing can be attempted here" obligation.
11. **strategies** — per requirement: existing tests, static analysis, generated tests, mutation,
    advisory LLM analysis. Each returns evidence, counterexamples and per-obligation outcomes.
12. **verdicts** — `VerdictCalculator` computes one status per requirement from the evidence.
13. **report** — canonical JSON, self-contained HTML, console summary, SQLite index.

Every phase runs inside a budget: wall clock, generated tests, LLM calls, mutation time. When a limit
is reached the run is marked `ABORTED` and everything not yet verified stays unverified.

## 4. The verdict rules, and why

`VerdictCalculator` is deliberately the least clever class in the codebase. Its rules:

| Situation | Verdict | Why |
| --- | --- | --- |
| The project did not build | `BLOCKED` | A broken build says nothing about the code's behaviour. Reporting it as `FAILED` would be the single most misleading thing a verification tool could do. |
| An executed experiment reproduced a violation | `FAILED` | The counterexample is stored with its exact inputs. |
| Deterministic evidence refutes the requirement | `FAILED` | A static analyzer demonstrating the defect is as good as a failing test. |
| Every obligation discharged by executed experiments, nothing contradicted | `VERIFIED` | This is the only path to `VERIFIED`, and it requires execution. |
| Some obligations discharged | `PARTIALLY_VERIFIED` | Partial evidence is reported as partial. |
| Nothing executable, or nothing discharged | `UNVERIFIED` | Insufficient evidence never becomes a pass. |

Two properties of evidence make this work:

- **Confidence.** `DETERMINISTIC` evidence comes from executing a tool; `ADVISORY` evidence is a
  language model's opinion, or a human's. Only deterministic evidence can decide a verdict.
- **Provenance.** Evidence generated by a model counts only if it was independently executed. A
  suggestion that was never run is `SUGGESTION`, not `COUNTEREXAMPLE`.

### What deliberately does *not* change a verdict

- **Passing existing tests, on their own.** A test suite written by the same process that wrote the
  code is not independent verification. A failing *mapped* test produces a counterexample; a passing
  mapped test produces supporting evidence with `HIGH` (not deterministic) confidence, which is not
  enough for `VERIFIED`.
- **A high mutation score.** A surviving mutant means *nobody checked that behaviour*. It is not a
  violation of a requirement (the mutation may be equivalent, or the behaviour may be unclaimed), and
  letting it block verdicts would let test strength silently overrule executed evidence. Surviving
  mutants are reported as prominent evidence and as an explicit run limitation instead.
- **A clean static analysis run.** It only covers the rules the analyzers know. It is recorded as
  weak evidence and it does not make a requirement verified.
- **A language model's opinion.** Ever.

## 5. Evidence model

`Evidence` records: id (`EV-001`), run id, type, status (`SUPPORTS`/`REFUTES`/`INCONCLUSIVE`),
confidence, description, source tool, timestamp, the exact command, the inputs, the output, the
result, arbitrary metadata, artifact paths, the requirements and obligations it belongs to, whether a
language model produced it, whether it was executed independently, and a reproduction command.

Types: `PROJECT_INSPECTION`, `COMPILER`, `TEST`, `GENERATED_TEST`, `PROPERTY_TEST`, `FUZZ_TEST`,
`STATIC_ANALYSIS`, `COVERAGE`, `MUTATION_TEST`, `SECURITY_SCAN`, `DEPENDENCY_SCAN`, `COUNTEREXAMPLE`,
`REQUIREMENT_INTERPRETATION`, `LLM_ANALYSIS`, `INFRASTRUCTURE`, `ENVIRONMENT`.

Counterexamples are first class: `Counterexample` carries the ordered inputs, expected vs observed
result, the invariant that was violated, the test that observed it, the evidence id, the random seed,
the case index, and a `ReproductionRecipe` (command plus artifact path). Generated tests report
failures through a single structured line, `CODEWITNESS_COUNTEREXAMPLE|case=…|seed=…|inputs={…}|…`,
which the engine parses — that is why a counterexample contains data rather than just a stack trace.

## 6. Requirements, obligations and the mapping problem

The natural language requirement is authoritative; everything structured is an interpretation, and
reports say so. Three sources of obligations, in decreasing order of strength:

1. a scenario declared by the user — executable by construction, attributed to the user;
2. properties derived from the requirement text — used by the generators to choose boundaries;
3. obligations suggested by a language model — advisory, never executable by themselves.

When a requirement has a declared scenario, the text-derived obligations are *not* added. They would
otherwise be permanent "not executed" entries that keep an otherwise fully verified requirement out
of `VERIFIED`, which would be a lie in the other direction.

Mapping evidence to requirements is deliberately mechanical, so that a reader can check it:

- a source or test file belongs to a requirement when the requirement's id appears in it, or when the
  requirement declares `paths:`/`tests:` explicitly;
- a mutation class name is mapped to its source file path;
- anything unmapped stays unmapped and the report says so. CodeWitness never silently attributes a
  passing test to a requirement it does not mention.

## 7. Extension points

**Another language.** Implement `LanguageAdapter` (`supports`, `inspect`, `build`, `runTests`,
`analyze`, `capabilities`) in a new module, add a `META-INF/services` entry, and register any tool
providers. The engine needs no change: it selects an adapter by asking each one whether it supports
the project, and never switches on a language string.

**Another tool.** Implement one of the interfaces in `VerificationTools` (`Compiler`, `TestRunner`,
`StaticAnalyzer`, `CoverageProvider`, `MutationTester`, `SecurityAnalyzer`, `DependencyAnalyzer`,
`TestGenerator`, `GeneratedTestExecutor`) and register it in the composition root
(`JavaAdapterBundle` is the example). Registration is data, not code paths.

**Another verification strategy.** Implement `VerificationStrategy`. It receives the context, the
requirement and its obligations, and returns evidence plus per-obligation outcomes. It cannot assign
a verdict; only the engine can.

## 8. Isolation and reproducibility

All execution inside a run goes through one `ExecutionSandbox`. `LocalProcessSandbox` sanitises the
environment (credentials and proxy variables are dropped), refuses working directories outside the
run, caps output and enforces hard timeouts. `DockerSandbox` runs the same commands in a container
with no network and dropped capabilities. The isolation level actually used is recorded in every
report, and the local sandbox is explicitly documented as a resource-safety boundary rather than a
security boundary.

A run records everything needed to reproduce it: project identifier, source revision, CodeWitness
version, Java version, build tool and version, configuration fingerprint, tool versions, language
model identifier, requirements hash, isolation level, timestamps, and every command. Run data is kept
under `<project>/.codewitness/runs/<run-id>/`, with generated sources and raw reports copied into the
artifact store so a counterexample can be reviewed and replayed after the run.

## 9. Storage

SQLite (`<project>/.codewitness/codewitness.db`) holds `projects`, `verification_runs`,
`requirements`, `obligations`, `evidence`, `test_results`, `test_cases`, `findings`,
`counterexamples` and `tool_runs`, with the canonical JSON document stored verbatim on the run row so
that `codewitness report <run-id>` reproduces exactly what was written. Generated sources and raw
reports live in the artifact store on disk, not in the database.

## 10. Testing strategy

- **Unit tests** pin the verdict rules, the parsers, the configuration, the sandbox, the workspace
  copier, the analyzers' rule table and the generated-test rendering (including compiling a generated
  test to prove it is valid Java).
- **Integration tests** drive the adapter against small projects and parse synthetic tool reports,
  including the DOCTYPE-carrying JaCoCo and PIT formats.
- **End-to-end tests** (`-Pe2e`) run `codewitness verify` against the fixture projects and assert the
  verdicts, the counterexample contents and the exit codes.
- **Fixtures** are projects with known defects — off-by-one boundaries, null handling, concurrency,
  security, weak tests, and a requirement that cannot be verified at all — used to prove that
  CodeWitness distinguishes them.
