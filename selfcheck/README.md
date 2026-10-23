# Self-verification: CodeWitness checked by CodeWitness

This directory makes CodeWitness verify its own source tree. The requirements in
[`requirements.yaml`](requirements.yaml) restate the project's own documented contract — the "Ground
rules for changes" in `CONTRIBUTING.md`, the verdict table and exit codes in `README.md` — as claims a
machine can check. The scenarios in [`codewitness.yaml`](codewitness.yaml) then execute the code path
each rule is about, against `VerdictCalculator`, `Evidence`, `RequirementParser` and `DurationParser`.

## Running it

```bash
# from the repository root, with Java 25 and Maven available
./codewitness verify . \
    --requirements selfcheck/requirements.yaml \
    --config       selfcheck/codewitness.yaml \
    --no-mutation --max-duration 30m
```

`verify` exits `1` if a requirement FAILED, `2` if it was BLOCKED and `0` otherwise. The machine
report is written to `.codewitness/runs/<run-id>/report.json` and the human report to `report.html`.

## What it checks

| Requirement | Rule | How it is executed |
| --- | --- | --- |
| CWR-001 | A failed build is `BLOCKED`, never `FAILED` | `VerdictCalculator.compute` with a build failure **and** a counterexample: the verdict must still be `BLOCKED` |
| CWR-002 | Insufficient evidence is `UNVERIFIED` | `compute` with no obligations, evidence or counterexamples |
| CWR-003 | An executed counterexample fails the requirement | `compute` with a recorded counterexample |
| CWR-004 | Language-model output is advisory | `compute` with `LLM_ANALYSIS`/`ADVISORY` evidence only; the verdict must not be `VERIFIED` and the evidence must not qualify as proof |
| CWR-005 | Requirement identifiers are stable | `RequirementParser.parseMarkdown` on a heading; the id comes from the heading |
| CWR-006 | Durations parse with documented suffixes | `DurationParser.parse("10m", …)` and an unparseable value falling back |
| CWR-007 | CLI exit codes are part of the interface | **No scenario** — its evidence is in the opt-in `e2e` suite, so the honest verdict is `UNVERIFIED` |

CWR-007 is deliberate. A requirement CodeWitness cannot execute must stay `UNVERIFIED` with a reason,
not pass by default; leaving one claim unexecuted keeps that behaviour in the self-check.

## Result

Run `CW-2026-10-07-SP7DE` (isolated-copy mode, `--no-mutation`), after both defects below were fixed:

```
CWR-001  VERIFIED    CWR-005  VERIFIED
CWR-002  VERIFIED    CWR-006  VERIFIED
CWR-003  VERIFIED    CWR-007  UNVERIFIED
CWR-004  VERIFIED

711 existing tests executed, 711 passed, 0 failed
coverage line 70.1% (11215/15991), branch 59.0% (4980/8438)
overall status PARTIALLY_VERIFIED (6 verified, 0 failed, 1 unverified)
verify exit code 0
```

## Findings

The first self-check pointed CodeWitness at its own tree in its default isolated-copy mode and the
build failed. That is not a failure of the check: the tool reported every requirement `BLOCKED`
(never `FAILED`), which is CWR-001 holding under a real defect. It then found a second defect that
kept coverage from being measured. Both are fixed.

1. **`WorkspaceManager` excluded source packages named like build output (fixed).**
   The copy step matched excluded directory *names* at any depth, so it skipped
   `codewitness-adapter-java/src/main/java/dev/codewitness/java/build/` — a real package — and the copied
   project could not compile. The fix only skips an excluded name outside a `src` tree; the path is
   checked relative to the copied project, so a project that merely lives under a directory called
   `src` is unaffected. Regression test:
   `WorkspaceManagerTest.sourcePackagesNamedLikeBuildOutputAreCopied`.

2. **Coverage was never measured for this project (fixed).**
   The root `pom.xml` hard-coded surefire's `<argLine>--enable-native-access=ALL-UNNAMED</argLine>`,
   which overrode the `argLine` property JaCoCo's `prepare-agent` sets. The agent was therefore never
   attached, no `jacoco.exec` was produced and there was no XML report — and CodeWitness correctly
   recorded `coverage not measured` rather than reporting coverage it had not observed. The POM now
   uses late replacement (`<argLine>@{argLine} --enable-native-access=ALL-UNNAMED</argLine>`) with an
   empty `<argLine>` default property. Regression test:
   `SurefireArgLineConfigurationTest`.

Both findings are instances of the same principle the project documents: infrastructure problems and
code defects stay distinguishable, and a tool that cannot run something says so instead of guessing.

## Dependency advisories

`security.vulnerabilities` is enabled in [`codewitness.yaml`](codewitness.yaml), so the self-check asks
OSV which of CodeWitness's own resolved dependencies carry a known advisory. This is the one part of a
run that talks to a third party, which is why it is off by default and why the report always names the
service and the time it was asked.

It is also the check that was missing: the report used to end its dependencies section with
"N dependencies, 0 known vulnerabilities" whether or not anything had been looked for, which reads as
a clean bill of health. It now says "not checked" unless an advisory source was actually consulted, and
the raw responses are kept under `artifacts/vulnerabilities/` so a finding can be traced back.

Under the contract's `fail_on: high`, an advisory at that severity or above **fails** the requirements
whose own module resolves the vulnerable artifact. The attribution is deliberately narrow — the
counterexample names the module that resolves the artifact — so a requirement in one module is never
refuted by a dependency that only another module pulls in, and an advisory whose severity the source did
not state refutes nothing.

Turning this check on the project itself found something, exactly as it should:

1. With Jackson at **2.17.1** the scan reported 15 advisories (8 high, 7 medium) in `jackson-core` and
   `jackson-databind`. The self-check went from green to `FAILED` — six failed requirements, exit `1` —
   because CodeWitness's requirements sit in `codewitness-core`, which resolves Jackson. That was the
   contract working, not a regression: the project shipped dependencies with high severity advisories,
   and the report said so in a verdict rather than in a footnote.
2. Jackson was therefore upgraded. **`2.21.7` is the earliest release with no advisory affecting the
   four Jackson artifacts this project uses**, checked against OSV; 2.18.8, 2.19.4 and 2.20.2 still
   carried them.

The self-check is green again, and this time the zero is a measurement rather than an absence:

```
18 dependencies, 0 advisories (source osv, checked <run time>)
overall PARTIALLY_VERIFIED (6 verified, 1 unverified), verify exit code 0
```

This is a moving target. The dependencies are clean as of the run recorded in the report, against what
OSV had recorded at that moment; a later advisory can turn this red again without anything in the
repository changing. That is the honest reading of an empty result, and the report says so next to the
count.

## Known false positives in the report

The static-analysis section reports 10 findings (1 high, 9 medium). They are heuristic and cannot
change any verdict: a finding only refutes a requirement when a deterministic tool produced it
(`Finding.fromDeterministicTool`), and every rule in the heuristic table identifies itself as
`*-heuristics`. A rule match is recorded as advisory evidence with a note instead.

- the `HIGH` finding is `ProcessBuilder` use in `LocalProcessSandbox` — that class *is* the sandbox
  boundary, and the README documents this rule's false-positive profile;
- the `CW-CONC-001` medium findings fire on value types and per-run accumulators whose fields are
  never shared: the rule sees a private mutable field, an `if` and an assignment in the same class
  and calls it a check-then-act race, without asking whether two threads can reach it. The README
  calls this out as advisory by design.

Two rules that used to dominate this list report nothing here now. Both were fixed rather than
suppressed, and each has regression tests in `HeuristicSourceAnalyzerTest`:

- `CW-TEST-001` reported 31 tests as assertion-free, and all 31 assert. The method body was delimited
  by the first `}` on its own line, which sits inside the fixture text block those tests embed, so
  every assertion after it was invisible. Bodies are now delimited by matching braces on a
  literal-blanked view — the same technique the C++ and C# analyzers already used — and the finding
  points at the method rather than at its `@Test` annotation. An `assert` inside a fixture string no
  longer counts as an assertion of the test around it, while a thrown `AssertionError` does.
- `CW-NUM-001` reported two discarded `BigDecimal.add` calls that were `List.add` on an
  `ArrayList<String>` named `values`. A receiver now has to be declared `BigDecimal` in the same
  file (or be `new BigDecimal` / `BigDecimal.ZERO`) instead of merely reading like a quantity.

