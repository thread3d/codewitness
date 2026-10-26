# Findings in CI

Findings are advisory: a heuristic match never fails a requirement, so the report has to carry them to
where a reviewer is. Three things make that work.

**SARIF.** `--sarif <file>` (or `report.sarif: true`, which writes `report.sarif` beside the other
reports) emits SARIF 2.1.0: one rule per rule id, one result per finding and per counterexample, the
finding fingerprint as a partial fingerprint, and a single invocation whose `executionSuccessful`
reflects the run status, so a truncated scan cannot be uploaded as a clean one. A counterexample is
always `error`, because it is proof rather than advice, and each result keeps its original severity in
`properties.codeWitnessSeverity` because SARIF's three levels cannot express six.

**Baseline.** `--baseline <report.json>` (or `findings.baseline`) takes a previous run's canonical report
and reports only the findings it did not contain — the question a pull request actually asks. Nothing is
deleted and nothing is remembered between runs: the file the caller names is the only memory, it is
matched on the finding's fingerprint, and the number of findings it accounted for is stated in the run's
limitations. A baseline that cannot be read makes the run report everything and says why, because silently
falling back to "no baseline" would present known findings as new.

**Suppression.** A rule that fires on correct code cannot be argued with in a pull request. Suppression
is the narrowing answer:

```yaml
findings:
  baseline: .codewitness/runs/CW-2026-10-25-0QYX6/report.json   # optional
  suppress:                                                     # optional
    - rule: CW-CONC-001
      reason: immutable model classes built through a builder
    - rule: CW-SEC-004
      path: src/main/java/dev/example/Sandbox.java              # this file only
      reason: the sandbox is the component that executes commands, by design
```

A suppression entry needs a rule id — an entry without one would cover every finding — and the run states
how many findings were suppressed, by which rules and for what reason. The rules stay installed:
suppression is a claim about these findings in this project, not a finding that the rule is wrong.
