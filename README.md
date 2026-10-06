# CodeWitness

**Independent verification engine for AI-generated software.**

CodeWitness does not generate code. It answers one question:

> *"What evidence do we have that this code satisfies its requirements and behaves correctly?"*

It answers it by compiling the project, running its tests, analysing it, generating additional
adversarial tests, executing them, searching for counterexamples, and then reporting what was
observed — with the evidence attached. A language model's opinion is recorded as a suggestion and
never counted as proof.

```
CLAIM  ──▶  OBLIGATION  ──▶  EXPERIMENT  ──▶  OBSERVATION  ──▶  EVIDENCE  ──▶  CONCLUSION
```

---

## Quick start

```bash
# 1. Build (needs Java 25+ and Maven)
./build.sh

# 2. See what CodeWitness finds in a project that contains deliberate defects
./demo.sh
```

The demo verifies `fixtures/withdrawal-ledger`, a small Java project with four introduced defects
behind five requirements. At the time of writing it produces:

```text
Requirements
  ✗ R-001    Rolling withdrawal limit     FAILED
  ✗ R-002    Fees are applied to the rec… FAILED
  ✗ R-003    Negative amounts are reject… FAILED
  ✗ R-004    Multiple concurrent withdra… FAILED
  ? R-005    Rounding of computed totals  UNVERIFIED

Counterexamples
  CE-001  R-001  inputs: {t1=85800000, a1=700, t2=90000000, a2=700}  actual: 1400
```

R-001 fails because the ledger applies the limit per calendar day: two withdrawals of 700 an hour
apart, across midnight, total 1,400 in a rolling 24-hour window. R-005 has no way to be executed, so
it is `UNVERIFIED` — which is the honest answer, not a failure.

Verify any Java project:

```bash
codewitness verify ./my-project --requirements requirements.md
```

---

## What CodeWitness is not

- It is not a code generator.
- It is not an oracle: it never says "this code is correct". It says what was observed and what
  remains unknown.
- It never converts "the tests pass" into "the requirement holds" unless the tests it *executed*
  actually exercised that requirement.
- It never treats a language model's opinion as evidence.
- It does not modify source code. A failing requirement produces evidence and a reproduction
  command, not a patch.

---

## The five verdicts

Every requirement ends up in exactly one state:

| Status | Meaning |
| --- | --- |
| `VERIFIED` | Every planned obligation was discharged by an executed experiment, and nothing contradicted the requirement. |
| `PARTIALLY_VERIFIED` | Some obligations were discharged; significant cases remain unverified. |
| `FAILED` | An executed experiment (or a deterministic analyzer) reproduced a violation. The evidence contains the exact input. |
| `UNVERIFIED` | CodeWitness could not establish sufficient evidence either way. This is a legitimate and common result. |
| `BLOCKED` | Verification could not proceed because of an infrastructure or project problem: a failed build, a missing build tool, an exhausted budget. **A failed build is reported as `BLOCKED`, never as `FAILED`.** |

An overall run status is derived from the individual verdicts, and it is deliberately conservative: a
single counterexample makes the whole run `FAILED`; blocked verification never becomes a pass.

---

## Command line

```bash
codewitness verify <project>      # the full pipeline, producing report.html and report.json
codewitness inspect <project>     # detection only: language, build system, modules, sources, tests
codewitness test <project>        # compile and run the existing test suite only
codewitness report <run-id>       # print a stored run, or list recent runs
codewitness reproduce <run-id>    # print (or with --run, execute) the command that replays a counterexample
codewitness clean <project>       # delete stored run data
codewitness init <project>        # write starter codewitness.yaml and requirements.md
codewitness version
```

Useful `verify` flags: `--requirements <file>`, `--config <file>`, `--output <dir>`,
`--max-duration 10m`, `--max-generated-tests 100`, `--in-place`,
`--no-tests`, `--no-mutation`, `--no-generated-tests`, `--no-static-analysis`, `--no-security`,
`--llm`, `--json`, `--quiet`.

Exit codes are part of the interface:

| Code | Meaning |
| --- | --- |
| `0` | The run completed and no requirement has a counterexample. |
| `1` | At least one requirement is `FAILED`. |
| `2` | Verification was `BLOCKED` (build failure, missing tool). |
| `3` | Usage error or an internal failure. |

---

## Requirements

Requirements are plain text with stable identifiers. Markdown:

```markdown
# R-001 Rolling withdrawal limit

A customer may withdraw at most 1,000 during any rolling 24-hour period.
A withdrawal that would exceed the limit must be rejected.
The system must behave correctly when multiple withdrawal requests occur concurrently.
```

or YAML, which additionally lets you declare structured properties, source paths and test classes:

```yaml
requirements:
  - id: R-001
    title: Rolling withdrawal limit
    description: A customer may withdraw at most 1,000 during any rolling 24-hour period.
    properties:
      - type: upper_bound
        value: 1000
      - type: concurrency
    paths:
      - src/main/java/com/example/account/WithdrawalLedger.java
    tests:
      - com.example.account.WithdrawalLedgerTest
```

The natural language text is authoritative. Anything structured that CodeWitness derives from it —
by heuristic or by language model — is labelled as an *interpretation* in every report.

---

## Scenarios: how a requirement gets executed

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

`${name}` is substituted with a generated Java variable of the declared `type`, so write `${a1}` and
not `new java.math.BigDecimal(${a1})`. The invariant is checked after every step. `mode: concurrent`
releases eight threads simultaneously against one shared subject and then checks the invariant. Every
failing case reports its exact inputs plus the seed and case index, which is what makes a
counterexample reproducible:

```bash
mvn -Dtest=Cw_SC_ROLLING_OB_001_Test -Dcodewitness.seed=42 -Dcodewitness.case=37 test
```

See [`docs/writing-scenarios.md`](docs/writing-scenarios.md) for the full reference.

---

## Configuration

`codewitness.yaml` at the project root; every value has a conservative default.

```yaml
project:
  name: payment-service

language:
  adapter: auto          # auto | java

verification:
  timeout: 15m           # per build or test command
  max_duration: 15m      # whole run
  max_test_generation_iterations: 5
  max_generated_tests: 500
  max_mutation_time: 10m

mutation:      { enabled: true, tool: pitest }
property_testing: { enabled: true, max_scenarios: 50, concurrent: true }
static_analysis:  { enabled: true, analyzers: [javac, heuristics] }
security:      { enabled: true }
tests:         { enabled: true }

llm:
  enabled: false         # advisory only, off by default
  provider: none         # none | openai-compatible
  api_key_env: CODEWITNESS_LLM_API_KEY
  max_calls: 0

report:  { html: true, json: true }
sandbox:
  mode: local-process    # local-process | docker
  allow_network: false
```

The language model is optional, disabled by default, and never decides anything. When it is enabled,
its output is stored as `LLM_ANALYSIS` evidence with `ADVISORY` confidence,
`generatedByLlm = true` and `executedIndependently = false`, and it is reported as an unexecuted
suggestion. Source code and project files are treated as untrusted data and fenced accordingly, so
an "ignore all previous instructions" comment inside a source file stays data.

---

## Reports

Every run produces both:

- `report.json` — the canonical machine-readable representation, with run id, environment, status,
  per-requirement verdicts, obligations, evidence, counterexamples, test/mutation/coverage/
  static-analysis summaries, phases, budget usage and limitations;
- `report.html` — a self-contained human report that starts with an executive summary and then shows
  the evidence, including an appendix listing every evidence record with the command that produced it.

Run data lives in `<project>/.codewitness/runs/<run-id>/`: the report files, the raw artifacts
(generated test sources, test and coverage reports) and an isolated copy of the project. Metadata is
also indexed in `<project>/.codewitness/codewitness.db` (SQLite).

---

## Reproducibility

A report is only useful if it can be reproduced, so every run records: the project identifier, the
source revision, the CodeWitness version, the Java version, the build tool and its version, the
verification configuration fingerprint, every tool version, the language model identifier if any, the
requirements hash, the isolation level, timestamps and every command that was executed. Evidence
records carry their command and inputs, and counterexamples carry a replayable command.

---

## Security

CodeWitness executes project code, so it treats the project as hostile:

- all execution goes through one sandbox abstraction, with a sanitised environment (credentials and
  proxy variables removed), an isolated working directory, capped output and hard timeouts;
- the project is copied into the run workspace by default, so generated tests and build output never
  touch your working tree;
- `sandbox.mode: docker` runs everything in a container with no network and dropped capabilities;
- the local-process sandbox is a **resource-safety** boundary, not a security boundary, and every
  report says so instead of overstating it.

See [`docs/security.md`](docs/security.md).

---

## Project layout

```
codewitness/
├── codewitness-core/          language-independent verification core
│   ├── model/                 project, build, test, coverage, mutation, finding models
│   ├── evidence/              the evidence model and ledger
│   ├── requirements/          requirement parsing, properties, obligations
│   ├── result/                verdicts, counterexamples, report, budget, run record
│   ├── spi/                   LanguageAdapter, VerificationTools, registries, context
│   ├── engine/                the pipeline, verdict calculator, strategies
│   ├── exec/                  sandbox and process execution
│   ├── io/                    isolated workspace and artifact store
│   ├── config/                codewitness.yaml
│   └── llm/                   advisory provider abstraction and prompt fencing
├── codewitness-adapter-java/  Java 17+ projects (module targets 25), Maven and Gradle, JUnit 5, PIT, JaCoCo
├── codewitness-storage/       SQLite persistence
├── codewitness-report/        canonical JSON and human HTML reports
├── codewitness-cli/           command line interface
└── fixtures/                  deliberately defective projects used to test all of this
```

The verification core contains no reference to Java, Maven or JUnit: language support is entirely in
adapters. Adding a language means adding a module that implements
`dev.codewitness.core.spi.LanguageAdapter` and registering its providers — not editing the engine.

---

## Building and testing

```bash
./build.sh                      # package everything
mvn test                        # unit and integration tests
mvn -Pe2e test                  # plus the end-to-end tests that verify the fixture projects
mvn -pl codewitness-core test   # one module
```

`mvn -Pe2e test` requires a Maven executable on `PATH` (`MAVEN_HOME` and `M2_HOME` are honoured) and
network access the first time, because it builds real projects.

---

## Honest status of this MVP

Implemented and exercised end to end:

- detection of Java 17+ projects, including Java 25, using Maven or Gradle, with multi-module layouts;
- compilation and existing-test execution through the project's own build tool, with results parsed
  from raw Surefire/Failsafe/Gradle XML reports;
- static analysis: compiler diagnostics (`-Xlint:all`) plus an auditable table of deterministic
  heuristics, and a separate security rule set;
- coverage through JaCoCo (agent attached automatically when no report exists);
- mutation testing through PIT, including surviving-mutant reporting;
- requirement parsing (Markdown and YAML), stable ids, heuristic property extraction, obligation
  planning;
- generated adversarial tests from declared scenarios: boundary enumeration, seeded random search
  and concurrent execution;
- counterexample extraction from structured failure messages, with replayable reproduction commands;
- requirement → evidence mapping, conservative verdict computation;
- SQLite persistence, canonical JSON and self-contained HTML reports, console reporting;
- a CLI with `verify`, `inspect`, `test`, `report`, `reproduce`, `clean`, `init`, `version`.

Implemented but limited, and reported as such:

- **Dependency analysis** lists resolved dependencies but does not consult a vulnerability database,
  so it never claims a project has no vulnerable dependencies.
- **Gradle** support (detection, commands, report parsing) is implemented and unit tested, but the
  end-to-end demonstration in this repository was exercised with Maven only; no Gradle
  distribution was available in the environment it was built in.
- **Mutation testing** needs the project to give PIT a test-framework plugin (the exact snippet is
  printed when it is missing). CodeWitness does not modify the project to add it.
- **Heuristic analysis** is a small, explicitly documented rule table with a known false-positive
  profile; findings are `MEDIUM`/`LOW` confidence and a clean run is weak evidence. One rule in
  particular, `CW-CONC-001`, fires on any class that reads a mutable private field in a condition and
  writes it in the same file without synchronisation, even when the object is never shared. It is
  advisory: it never changes a verdict.
- **Language model support** is an advisory provider abstraction with one HTTP implementation. It is
  off by default, and no automatic conversion of suggestions into executable tests is implemented.
- **Concurrency** verification depends on the declared scenario; CodeWitness does not infer
  invariants, and a passing concurrent test is evidence, not proof.
- A step that throws is a failure by default, because an unexpected exception is a failure. To say
  "rejecting by throwing is correct", wrap the call in a `try`/`catch` inside the step fragment; see
  [`docs/writing-scenarios.md`](docs/writing-scenarios.md).

Not implemented (deliberately out of MVP scope): formal verification, symbolic execution,
distributed or cloud execution, IDE or VCS integration, autonomous repair.

Where CodeWitness cannot verify something, it says `UNVERIFIED` and explains why. That is a feature.

---

## Licence

Apache License 2.0 — see [`LICENSE`](LICENSE).

Copyright 2026 thread3d. You may use, modify and redistribute this work, including
commercially, provided you keep the copyright and licence notices and state significant changes;
the licence also grants an express patent licence and comes without warranty.

That choice was made for a tool that gets wired into build pipelines: the patent grant matters more
here than the brevity of MIT, and it is compatible with everything CodeWitness depends on (SnakeYAML,
Jackson, picocli, sqlite-jdbc and slf4j under Apache-2.0/MIT; JUnit under the EPL-2.0, test scope only).

## Documentation

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — the layered architecture, the evidence chain and why the
  verdict rules are what they are.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — how to build, test, add a fixture, add a language adapter.
- [`docs/verification-model.md`](docs/verification-model.md) — requirements, obligations, evidence
  and verdicts in detail, including worked examples.
- [`docs/writing-scenarios.md`](docs/writing-scenarios.md) — the scenario reference.
- [`docs/security.md`](docs/security.md) — the execution model and its limits.
- [`docs/fixtures.md`](docs/fixtures.md) — the deliberately defective projects and what each proves.
- [`docs/demo.md`](docs/demo.md) — a walkthrough of a real run, with the observed output.
